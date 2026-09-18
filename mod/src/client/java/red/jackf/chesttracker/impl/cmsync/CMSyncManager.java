package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.memory.EnderChestKeys;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.key.ManualMode;
import red.jackf.chesttracker.impl.memory.key.OverrideInfo;
import red.jackf.jackfredlib.client.api.gps.Coordinate;
import red.jackf.chesttracker.api.memory.Memory;

import java.time.Instant;
import java.util.*;

/**
 * Bidirectional sync with explicit async queue — never blocks the client thread.
 *
 * <p>Client thread does ONLY: guards + fast copy of ItemStacks (plus detached Memory
 * clones and override reads). All Gson/codec/HTTP/hash runs on {@link CMSyncQueue}.
 * Merge back happens via {@code client.execute()} (main thread).
 *
 * <p>Fidelity: pushes carry the full native {@code Memory} record (items with all NBT/
 * components, container name/block) plus user overrides inside {@code raw}, alongside
 * the normalized {@code {id,count}} view used for search, hashes and cross-version
 * fallback. Same-MC-version peers restore everything; other versions still get
 * names+counts. See protocol/normalization.md.
 *
 * <p>Merge rules (per container): same-UUID identical echo is skipped (protects live
 * entity tracking and kills churn); otherwise last-observation-wins via updatedAt;
 * entity references are always stripped on receive (shared view is positional —
 * entity tracking re-livens on next open).
 */
public class CMSyncManager {
    public static final CMSyncManager INSTANCE = new CMSyncManager();
    public static final String MOD_VERSION = "cmsync.6";
    private static final Logger LOGGER = ChestTracker.getLogger("CMSync");
    // Only an established bank reading completely empty is held (hub-wipe safety,
    // needs 10+ previously pushed containers). Everything else pushes through;
    // the server snapshots + logs mass deletes as backstop.
    private static final int EMPTY_HOLD_MIN_BANK = 10;

    @Nullable private String activeBankId = null;
    @Nullable private String lastPushHash = null;
    private int lastPushedCount = -1;
    private boolean failing = false;
    private long lastAttemptMs = 0;
    // Deterministic server rejections (e.g. HTTP 422): retrying every 5s is pure
    // spam — the same body will fail the same way. Report once, back off quietly.
    @Nullable private String deterministicNote = null;
    private long deterministicUntilMs = 0;
    private static final long DETERMINISTIC_COOLDOWN_MS = 5 * 60 * 1000L;
    // consecutive transport failures back off quietly (5s doubling to 60s) so one
    // hiccup doesn't turn into a full-push-every-5s storm against a slow server
    private int consecutiveConnFails = 0;
    private long quietUntilMs = 0;
    // last snapshot's key set, for delete propagation (broken/emptied containers).
    // Null = needs a baseline (fresh session or filter toggle), never a mass delete.
    @Nullable private Map<String, Set<String>> lastSnapshotKeys = null;
    @Nullable private Boolean lastSyncEnder = null;
    private boolean emptyHoldNotified = false;
    @Nullable private String lastWarnedDeletes = null;

    @Nullable private Instant lastSuccess = null;
    @Nullable private String lastResult = null;
    @Nullable private String lastDetail = null;

    private CMSyncManager() {
    }

    public void setup() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public boolean isActiveFor(String bankId) {
        if (activeBankId == null || !activeBankId.equals(bankId)) return false;
        return CMSyncSettings.load(bankId).isActive();
    }

    public void markActivated(String bankId, String serverId) {
        resetSession();
        this.activeBankId = bankId;
        CMSyncLog.log("session", "activated bank=" + bankId + " server=" + serverId + " mod=" + MOD_VERSION);
    }

    public void deactivate() {
        CMSyncLog.log("session", "deactivated bank=" + activeBankId);
        resetSession();
    }

    public Optional<Instant> getLastSuccess() {
        return Optional.ofNullable(lastSuccess);
    }

    public Optional<String> getLastResult() {
        return Optional.ofNullable(lastResult);
    }

    /** Free-text detail of the last push attempt (HTTP code, duration, sizes). */
    public Optional<String> getLastDetail() {
        return Optional.ofNullable(lastDetail);
    }

    /**
     * Server id as sent on the wire (handshake/push/pull/wipe): lowercased.
     * See the tick() comment — client libraries disagree on capitalisation per
     * MC version, the server compares literally.
     */
    public static String canonicalWireId(String serverId) {
        return serverId == null ? "" : serverId.toLowerCase(java.util.Locale.ROOT);
    }

    /** Teammate uuids -> last seen names for ender chest profiles (sidecar cache + live player). */
    public Map<UUID, String> getOwnerNames(String bankId) {
        Map<UUID, String> out = new HashMap<>();
        for (var e : CMSyncSettings.load(bankId).ownerNames.entrySet()) {
            try {
                out.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    @Nullable
    public String ownerName(String bankId, UUID uuid) {
        String known = getOwnerNames(bankId).get(uuid);
        if (known != null) return known;
        var player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(uuid)) return player.getName().getString();
        return null;
    }

    /**
     * A newer server generation (wipe) clears this bank's locals so stale data can
     * never resurrect — including for players who were offline during the wipe.
     * Runs on the client thread (pull merge) or already-client command/menu threads.
     *
     * @return true if a wipe was applied (caller should skip normal merging)
     */
    public boolean applyServerGeneration(String bankId, int generation) {
        if (generation < 0) return false;
        CMSyncSettings s = CMSyncSettings.load(bankId);
        if (generation <= s.generation) return false;
        s.generation = generation;
        s.save(bankId);
        CMSyncLog.log("wipe", "bank=" + bankId + " applied server generation " + generation + ", locals cleared");
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            if (!bank.getId().equals(bankId)) return;
            for (Identifier k : new ArrayList<>(bank.getKeys())) bank.removeKey(k);
            MemoryBankAccessImpl.INSTANCE.save();
        });
        this.lastPushHash = null;
        this.lastSnapshotKeys = null;
        this.lastPushedCount = -1;
        this.emptyHoldNotified = false;
        this.lastWarnedDeletes = null;
        this.lastResult = "wiped to generation " + generation;
        return true;
    }

    private void resetSession() {
        this.activeBankId = null;
        this.lastPushHash = null;
        this.lastPushedCount = -1;
        this.failing = false;
        this.lastAttemptMs = 0;
        this.deterministicNote = null;
        this.deterministicUntilMs = 0;
        this.consecutiveConnFails = 0;
        this.quietUntilMs = 0;
        this.lastSnapshotKeys = null;
        this.lastSyncEnder = null;
        this.emptyHoldNotified = false;
        this.lastWarnedDeletes = null;
        this.lastSuccess = null;
        this.lastResult = null;
        this.lastDetail = null;
    }

    /** Client thread: fast guards + fast copy only. */
    private void tick(Minecraft client) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty() || client.player == null || client.level == null) {
            if (activeBankId != null) resetSession();
            return;
        }
        MemoryBankImpl bank = bankOpt.get();
        CMSyncSettings settings = CMSyncSettings.load(bank.getId());
        if (!settings.isActive()) {
            if (activeBankId != null) resetSession();
            return;
        }
        Coordinate coord = Coordinate.getCurrent().orElse(null);
        if (coord == null) return;
        if (settings.boundServerId != null && !settings.boundServerId.equals(coord.id())) return;

        if (!bank.getId().equals(activeBankId)) {
            resetSession();
            this.activeBankId = bank.getId();
            sendChat(client, Component.literal("CMSync resumed"), ChatFormatting.GREEN);
        }

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < Math.max(2, settings.intervalSeconds) * 1000L) return;
        // deterministic rejection cooling down: stay quiet, don't burn the lane
        if (now < deterministicUntilMs) return;

        final boolean syncEnder = settings.syncEnderChest;
        final boolean syncContainerNames = settings.syncContainerNames;
        final boolean chatNotifications = settings.chatNotifications;

        // ---- cheap delete peek (key strings only, no copies): broken/emptied
        // containers bypass the transport backoff below so ghosts vanish promptly
        // instead of waiting out a cooldown. Never fires on a fresh baseline, an
        // empty bank (hub-wipe safety), or a filter-toggle tick.
        List<String[]> deletedPairs = new ArrayList<>();
        Map<String, Set<String>> curKeys = new HashMap<>();
        for (Map.Entry<Identifier, MemoryKeyImpl> e : bank.getMemories().entrySet()) {
            if (EnderChestKeys.isHiddenLegacyKey(e.getKey())) continue;
            if (!syncEnder && EnderChestKeys.isSyncableEnderKey(e.getKey())) continue;
            Set<String> set = new HashSet<>();
            for (Map.Entry<BlockPos, Memory> m : e.getValue().getMemories().entrySet()) {
                // must mirror the copy loop's entity skip exactly, or phantom deletes appear
                if (m.getValue().entityId() != null) continue;
                set.add(ItemNormalizer.posToString(m.getKey()));
            }
            curKeys.put(e.getKey().toString(), set);
        }
        if (lastSnapshotKeys != null && Objects.equals(lastSyncEnder, syncEnder)) {
            for (var e : lastSnapshotKeys.entrySet()) {
                Set<String> cur = curKeys.getOrDefault(e.getKey(), Set.of());
                for (String pos : e.getValue()) {
                    if (!cur.contains(pos)) deletedPairs.add(new String[]{e.getKey(), pos});
                }
            }
        }
        // backing off after transport failures — unless deletes are pending
        boolean quietBypass = now < quietUntilMs && !deletedPairs.isEmpty();
        if (now < quietUntilMs && deletedPairs.isEmpty()) return;
        // queue lane: if network busy, skip this tick (coalesce) — keeps FPS smooth
        if (!CMSyncQueue.tryClaim()) return;
        lastAttemptMs = now;
        lastSnapshotKeys = curKeys;
        lastSyncEnder = syncEnder;
        final String deleteStamp = Instant.now().toString();

        // ---- fast copy on client thread (no Gson, no codec, no HTTP) ----
        final String bankId = bank.getId();
        final String url = settings.url;
        final String token = settings.token;
        final String playerUuid = client.player.getUUID().toString();
        final String playerName = client.player.getName().getString();
        // Wire id, lowercased: 26.x reports e.g. multiplayer/Fabriccraft_net while
        // 1.21.11 reports multiplayer/fabriccraft_net and the server compares
        // literally — sending it raw ate ACCESS_DENIED every cycle on 26.x.
        // Local bank binding keeps the exact spelling; only the wire is normalised.
        // (No UI override needed: every spelling maps to the same id here.)
        final String serverId = canonicalWireId(coord.id());
        final String serverName = coord.userFriendlyName();
        final String mcVersion = gameVersion();
        final DynamicOps<JsonElement> ops =
                client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        // player position lets the server withhold far-away containers (range gate)
        final BlockPos playerPos = client.player.blockPosition();
        final String dim = client.level.dimension().identifier().toString();

        List<RawEntry> snapshot = new ArrayList<>();
        try {
            for (Map.Entry<Identifier, MemoryKeyImpl> e : bank.getMemories().entrySet()) {
                if (!syncEnder && EnderChestKeys.isSyncableEnderKey(e.getKey())) continue;
                String key = e.getKey().toString();
                MemoryKeyImpl keyImpl = e.getValue();
                for (Map.Entry<BlockPos, Memory> m : keyImpl.getMemories().entrySet()) {
                    Memory mem = m.getValue();
                    // entity-held containers (minecarts/boats): position is session-local,
                    // skip sending (documented limitation)
                    if (mem.entityId() != null) continue;
                    List<ItemStack> copies = new ArrayList<>(mem.items().size());
                    for (ItemStack s : mem.items()) {
                        if (!s.isEmpty()) copies.add(s.copy());
                    }
                    // detached clone: safe to encode off-thread (bank only mutates here)
                    Memory clone = new Memory(copies, mem.savedName(), mem.otherPositions(),
                            mem.container(), nzL(mem.loadedTimestamp(), Memory.UNKNOWN_LOADED_TIMESTAMP),
                            nzL(mem.inGameTimestamp(), Memory.UNKNOWN_WORLD_TIMESTAMP),
                            nzT(mem.realTimestamp()), null, null);
                    OverrideInfo ov = keyImpl.overrides().get(m.getKey());
                    snapshot.add(new RawEntry(key, ItemNormalizer.posToString(m.getKey()),
                            Instant.now().toString(), copies, clone,
                            ov != null ? ov.getCustomName() : null,
                            ov != null ? ov.getManualMode().name() : ManualMode.DEFAULT.name(),
                            ov != null));
                }
            }
        } catch (RuntimeException ex) {
            CMSyncQueue.release();
            LOGGER.warn("cmsync snapshot copy failed", ex);
            CMSyncLog.log("cycle", "snapshot copy FAILED: " + CMSyncLog.trunc(ex.getMessage(), 160));
            return;
        }
        CMSyncLog.log("cycle", "bank=" + bankId + " server=" + serverId + " snapshot=" + snapshot.size()
                + " deletes=" + deletedPairs.size() + (quietBypass ? " quiet-bypass" : ""));

        // ---- everything heavy runs on queue lane ----
        CMSyncQueue.executor().execute(() -> {
            try {
                runSyncJob(client, bankId, url, token, playerUuid, playerName,
                        serverId, serverName, mcVersion, ops, snapshot, playerPos, dim,
                        deletedPairs, deleteStamp, syncContainerNames, chatNotifications);
            } finally {
                CMSyncQueue.release();
            }
        });
    }

    private record RawEntry(String key, String pos, String updatedAt,
                            List<ItemStack> stacks, Memory detached,
                            @Nullable String ovName, String ovMode, boolean hasOv) {
    }

    /** Background lane: encode NBT + hash + push + pull (blocking). Merge on client thread. */
    private void runSyncJob(Minecraft client, String bankId, String url, String token,
                            String playerUuid, String playerName, String serverId, String serverName,
                            String mcVersion, DynamicOps<JsonElement> ops, List<RawEntry> snapshot,
                            BlockPos playerPos, String dim,
                            List<String[]> deletedPairs, String deleteStamp,
                            boolean syncContainerNames, boolean chatNotifications) {
        List<JsonObject> changes = new ArrayList<>(snapshot.size());
        List<JsonObject> hashProj = new ArrayList<>(snapshot.size());
        for (RawEntry r : snapshot) {
            List<JsonObject> norm = ItemNormalizer.toNormList(r.stacks());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (JsonObject n : norm) arr.add(n);
            // full-fidelity blob: native Memory record (NBT/components/name/block) + override.
            // Lives under `raw`, which the server stores opaquely — no server change needed.
            JsonObject raw = new JsonObject();
            raw.addProperty("v", 2);
            raw.addProperty("mc", mcVersion);
            try {
                Memory.CODEC.encodeStart(ops, r.detached()).result().ifPresent(j -> raw.add("memory", j));
            } catch (RuntimeException e) {
                LOGGER.debug("cmsync: NBT encode failed for {} {}, sending names+counts only", r.key(), r.pos());
            }
            if (r.hasOv()) {
                try {
                    ManualMode mm = ManualMode.valueOf(r.ovMode());
                    OverrideInfo.CODEC.encodeStart(ops, new OverrideInfo(mm, r.ovName()))
                            .result().ifPresent(j -> raw.add("override", j));
                } catch (IllegalArgumentException ignored) {
                }
            }
            JsonObject ch = new JsonObject();
            ch.addProperty("key", r.key());
            ch.addProperty("pos", r.pos());
            ch.addProperty("deleted", false);
            ch.addProperty("updatedAt", r.updatedAt());
            ch.addProperty("updatedBy", playerUuid);
            ch.addProperty("mcVersion", mcVersion);
            ch.add("items", arr);
            ch.add("raw", raw);
            changes.add(ch);
            hashProj.add(ItemNormalizer.projection(r.key(), r.pos(), false, norm, r.ovName(), r.ovMode()));
        }
        final int upsertCount = changes.size();

        // append propagated deletes (broken/emptied since last snapshot)
        for (String[] del : deletedPairs) {
            JsonObject ch = new JsonObject();
            ch.addProperty("key", del[0]);
            ch.addProperty("pos", del[1]);
            ch.addProperty("deleted", true);
            ch.addProperty("updatedAt", deleteStamp);
            ch.addProperty("updatedBy", playerUuid);
            ch.addProperty("mcVersion", mcVersion);
            ch.add("items", new com.google.gson.JsonArray());
            changes.add(ch);
            hashProj.add(ItemNormalizer.projection(del[0], del[1], true, List.of(), null,
                    ManualMode.DEFAULT.name()));
        }
        final String fullHash = ItemNormalizer.hashProjections(hashProj);
        final boolean emptyLocal = changes.isEmpty();
        final String prevHash = this.lastPushHash;
        final boolean dirty = this.failing || !fullHash.equals(prevHash);

        // hub-wipe hold: an established bank reading completely empty is never pushed —
        // pull-only, so the team refills the view instead of a wipe propagating.
        // (Tiny banks always propagate; a real fresh start uses /cmsync wipealldata.)
        if (upsertCount == 0 && !deletedPairs.isEmpty() && lastPushedCount >= EMPTY_HOLD_MIN_BANK) {
            client.execute(() -> {
                if (!bankId.equals(activeBankId)) return;
                lastResult = "held empty bank";
                if (!emptyHoldNotified) {
                    emptyHoldNotified = true;
                    sendChat(client, Component.literal("CMSync holding: bank reads empty but had "
                            + lastPushedCount + " containers — pull-only, nothing deleted. "
                            + "To truly start over, use /cmsync wipealldata."), ChatFormatting.YELLOW);
                }
            });
            doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion, playerPos, dim, syncContainerNames, chatNotifications);
            CMSyncLog.log("hold", "bank=" + bankId + " held empty bank (" + lastPushedCount + " before), pull-only");
            return;
        }
        emptyHoldNotified = false;

        // everything else (including mass breaks) goes straight through with one warning
        // per unique delete set; the server snapshots + logs mass deletes as backstop
        if (!deletedPairs.isEmpty() && !fullHash.equals(lastWarnedDeletes)) {
            lastWarnedDeletes = fullHash;
            final int fGone = deletedPairs.size();
            client.execute(() -> {
                if (!bankId.equals(activeBankId)) return;
                sendChat(client, Component.literal("CMSync syncing " + fGone
                        + " removed container(s) to the team."), ChatFormatting.YELLOW);
            });
        }

        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                playerUuid, playerName, serverId, serverName, mcVersion, MOD_VERSION);

        try {
            if (dirty && !emptyLocal) {
                CMSyncHttp.PushOutcome push = CMSyncHttp.push(url, token, ident, prevHash, fullHash, changes).join();
                CMSyncLog.log("push", "bank=" + bankId + " result=" + push.result()
                        + " http=" + push.statusCode() + " ms=" + push.tookMs()
                        + " upserts=" + upsertCount + " deletes=" + deletedPairs.size()
                        + (push.note().isEmpty() ? "" : " note=" + CMSyncLog.trunc(push.note(), 160)));
                if (push.tookMs() > 10_000) {
                    LOGGER.warn("cmsync slow push: {} containers + {} deletes took {}ms (HTTP {})",
                            upsertCount, deletedPairs.size(), push.tookMs(), push.statusCode());
                }
                // deterministic rejections would fail identically on every retry —
                // handle separately (one message + cooldown) instead of the flap loop
                if (push.result() == CMSyncHttp.Result.VALIDATION_ERROR
                        || push.result() == CMSyncHttp.Result.NOT_A_CMSYNC_SERVER) {
                    final String note = push.note().isEmpty() ? push.result().name() : push.note();
                    client.execute(() -> handleDeterministic(client, bankId, note));
                    doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion, playerPos, dim, syncContainerNames, chatNotifications);
                    return;
                }
                client.execute(() -> handlePushResult(client, bankId, push.result(),
                        push.result() == CMSyncHttp.Result.SYNCED ? fullHash : prevHash,
                        false, push.note(), upsertCount, push.statusCode(), push.tookMs()));
                if (push.result() == CMSyncHttp.Result.QUARANTINED) {
                    doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion, playerPos, dim, syncContainerNames, chatNotifications);
                    return;
                }
            } else if (emptyLocal) {
                // hub-wipe protection: never push empties, but keep pulling
                client.execute(() -> {
                    if (bankId.equals(activeBankId)) lastResult = "empty-local, pull-only";
                });
            }
            doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion, playerPos, dim, syncContainerNames, chatNotifications);
        } catch (RuntimeException ex) {
            LOGGER.error("cmsync job failed", ex);
            client.execute(() -> handlePushResult(client, bankId,
                    CMSyncHttp.Result.CONNECTION_FAILED, prevHash, false, "", lastPushedCount, -1, 0));
        }
    }

private void doPullBlocking(Minecraft client, String bankId, String url, String token,
                            String playerUuid, String serverId, String playerName,
                            String serverName, String mcVersion, BlockPos playerPos, String dim,
                            boolean syncContainerNames, boolean chatNotifications) {
        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                playerUuid, playerName, serverId, serverName, mcVersion, MOD_VERSION);
        CMSyncHttp.PullOutcome pull;
        try {
            pull = CMSyncHttp.pull(url, token, serverId, playerUuid,
                    playerPos.getX(), playerPos.getY(), playerPos.getZ(), dim).join();
        } catch (RuntimeException ex) {
            CMSyncLog.log("pull", "bank=" + bankId + " THREW: " + CMSyncLog.trunc(ex.getMessage(), 200));
            client.execute(() -> handlePullError(client, bankId, CMSyncLog.trunc(ex.getMessage(), 160)));
            return;
        }
        final CMSyncHttp.PullOutcome f = pull;
        client.execute(() -> {
            if (!bankId.equals(activeBankId)) return;
            if (f.result() == CMSyncHttp.Result.SYNCED) {
                // wipe first: merging pulled data into about-to-be-cleared locals is pointless
                if (!applyServerGeneration(bankId, f.generation()))
                    applyPull(client, bankId, f.changes(), f.tombstones(), playerUuid, mcVersion, syncContainerNames);
                if (!f.owners().isEmpty()) {
                    CMSyncSettings s = CMSyncSettings.load(bankId);
                    boolean changed = false;
                    for (var e : f.owners().entrySet()) {
                        if (!e.getValue().equals(s.ownerNames.get(e.getKey()))) {
                            s.ownerNames.put(e.getKey(), e.getValue());
                            changed = true;
                        }
                    }
                    if (changed) s.save(bankId);
                }
                consecutiveConnFails = 0;
                quietUntilMs = 0;
                lastSuccess = Instant.now();
                lastResult = "synced";
                CMSyncLog.log("pull", "bank=" + bankId + " SYNCED changes=" + f.changes().size()
                        + " tombs=" + f.tombstones().size() + " containers=" + f.containers()
                        + " gen=" + f.generation() + " owners=" + f.owners().size());
                if (failing) {
                    failing = false;
                    sendChat(client, Component.literal("CMSync re-established"), ChatFormatting.GREEN);
                }
                if (chatNotifications) {
                    sendChat(client, Component.literal("Sync complete"), ChatFormatting.GREEN);
                }
            } else {
                lastResult = f.result().name();
                CMSyncLog.log("pull", "bank=" + bankId + " result=" + f.result()
                        + (f.note().isEmpty() ? "" : " note=" + CMSyncLog.trunc(f.note(), 160)));
                if (f.result() == CMSyncHttp.Result.CONNECTION_FAILED) {
                    consecutiveConnFails++;
                    quietUntilMs = System.currentTimeMillis()
                            + Math.min(60_000L, 5_000L << Math.min(consecutiveConnFails - 1, 3));
                }
                if (!failing) {
                    failing = true;
                    String msg = "CMSync pull failed: " + f.result()
                            + (f.note().isEmpty() ? "" : " — " + CMSyncLog.trunc(f.note(), 120));
                    sendChat(client, Component.literal(msg), ChatFormatting.RED);
                }
            }
        });
    }

    private void applyPull(Minecraft client, String bankId, List<JsonObject> changes,
                           List<JsonObject> tombstones, String myUuid, String myMc,
                           boolean syncContainerNames) {
        Optional<MemoryBankImpl> opt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (opt.isEmpty() || !opt.get().getId().equals(bankId) || client.level == null) return;
        MemoryBankImpl bank = opt.get();
        long loadedTime = bank.getMetadata().getLoadedTime();
        long gameTime = client.level.getGameTime();
        DynamicOps<JsonElement> ops =
                client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        int applied = 0;
        int tombsApplied = 0;
        int skipped = 0;
        for (JsonObject ch : changes) {
            try {
                Identifier key = Identifier.parse(ch.get("key").getAsString());
                // legacy shared keys stay buried (migration absorbs them on load)
                if (EnderChestKeys.isHiddenLegacyKey(key)) continue;
                BlockPos pos = ItemNormalizer.parsePos(ch.get("pos").getAsString());
                String updatedBy = ItemNormalizer.optStr(ch, "updatedBy");
                Instant pulledAt = ItemNormalizer.parseInstant(ItemNormalizer.optStr(ch, "updatedAt"));
                List<JsonObject> norm = new ArrayList<>();
                if (ch.has("items") && ch.get("items").isJsonArray())
                    ch.getAsJsonArray("items").forEach(e -> norm.add(e.getAsJsonObject()));
                JsonObject raw = (ch.has("raw") && ch.get("raw").isJsonObject())
                        ? ch.getAsJsonObject("raw") : null;
                int v = 1;
                try {
                    if (raw != null && raw.has("v")) v = raw.get("v").getAsInt();
                } catch (RuntimeException ignored) {
                }
                boolean legacy = v < 2;
                String pulledOvName = null;
                String pulledOvMode = ManualMode.DEFAULT.name();
                boolean hasOvBlob = !legacy && raw != null && raw.has("override")
                        && raw.get("override").isJsonObject();
                if (hasOvBlob) {
                    JsonObject o = raw.getAsJsonObject("override");
                    pulledOvName = ItemNormalizer.optStr(o, "customName");
                    String mm = ItemNormalizer.optStr(o, "manualMode");
                    if (mm != null) pulledOvMode = mm;
                }

                MemoryKeyImpl keyImpl = bank.getKeyInternal(key).orElse(null);
                Memory local = null;
                if (keyImpl != null) local = keyImpl.get(pos).orElse(null);
                List<JsonObject> localNorm = local != null
                        ? ItemNormalizer.toNormList(local.items()) : List.of();
                OverrideInfo localOv = keyImpl != null ? keyImpl.overrides().get(pos) : null;

                // own identical echo: skip (protects live entity tracking, kills churn).
                // Legacy senders carry no override data, so overrides are out of the comparison.
                if (updatedBy != null && updatedBy.equals(myUuid) && local != null
                        && ItemNormalizer.normItemsEqual(localNorm, norm)
                        && (legacy || overrideStateEquals(localOv, pulledOvName, pulledOvMode))) continue;

                // last-observation-wins: my fresher recording stands
                Instant localT = local != null ? local.realTimestamp() : null;
                if (local != null && pulledAt != null && localT != null && !localT.isBefore(pulledAt)) continue;

                // full NBT restore on same MC version, else names+counts fallback
                Memory mem = null;
                String rawMc = raw != null ? ItemNormalizer.optStr(raw, "mc") : null;
                if (raw != null && raw.has("memory") && raw.get("memory").isJsonObject()
                        && myMc.equals(rawMc)) {
                    try {
                        Memory parsed = Memory.CODEC.parse(ops, raw.get("memory")).result().orElse(null);
                        if (parsed != null) mem = stripEntities(parsed);
                    } catch (RuntimeException e) {
                        LOGGER.debug("cmsync: NBT decode failed, using names+counts fallback");
                    }
                }
                if (mem == null) {
                    mem = new Memory(ItemNormalizer.fromNormList(norm), null, List.of(), Optional.empty(),
                            loadedTime, gameTime, Instant.now(), null, null);
                }
                bank.addMemory(key, pos, mem);
                applied++;

                // overrides ride with their entry; v2 senders without the blob explicitly cleared
                if (!legacy && syncContainerNames) {
                    if (hasOvBlob) {
                        bank.setNameOverride(key, pos, pulledOvName != null ? pulledOvName : "");
                        try {
                            bank.setManualModeOverride(key, pos, ManualMode.valueOf(pulledOvMode));
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else {
                        bank.setNameOverride(key, pos, "");
                        bank.setManualModeOverride(key, pos, ManualMode.DEFAULT);
                    }
                }
            } catch (RuntimeException e) {
                skipped++;
                LOGGER.warn("skip bad pull entry: {}", e.getMessage());
            }
        }
        for (JsonObject t : tombstones) {
            try {
                Identifier kid = Identifier.parse(t.get("key").getAsString());
                if (EnderChestKeys.isHiddenLegacyKey(kid)) continue;
                BlockPos pos = ItemNormalizer.parsePos(t.get("pos").getAsString());
                Instant del = ItemNormalizer.parseInstant(ItemNormalizer.optStr(t, "deleted_at"));
                if (del == null) del = ItemNormalizer.parseInstant(ItemNormalizer.optStr(t, "deletedAt"));
                MemoryKeyImpl keyImpl = bank.getKeyInternal(kid).orElse(null);
                Memory local = keyImpl != null ? keyImpl.get(pos).orElse(null) : null;
                // my strictly-newer recording survives a stale delete; ties go to the delete
                if (local != null && del != null && local.realTimestamp() != null
                        && local.realTimestamp().isAfter(del)) continue;
                bank.removeMemory(kid, pos);
                tombsApplied++;
            } catch (RuntimeException e) {
                skipped++;
                LOGGER.warn("skip bad tombstone: {}", e.getMessage());
            }
        }
        CMSyncLog.log("merge", "bank=" + bankId + " applied=" + applied
                + " tombs=" + tombsApplied + " skipped=" + skipped);
    }

    private static boolean overrideStateEquals(@Nullable OverrideInfo local,
                                               @Nullable String pulledName, String pulledMode) {
        String localName = local != null ? local.getCustomName() : null;
        String localMode = local != null ? local.getManualMode().name() : ManualMode.DEFAULT.name();
        return Objects.equals(localName, pulledName) && Objects.equals(localMode, pulledMode);
    }

    /** Shared view is positional: entity references never survive a sync hop. */
    private static Memory stripEntities(Memory m) {
        if (m.entityId() == null && m.entityUuid() == null) return m;
        return new Memory(m.fullItems(), m.savedName(), m.otherPositions(), m.container(),
                nzL(m.loadedTimestamp(), Memory.UNKNOWN_LOADED_TIMESTAMP),
                nzL(m.inGameTimestamp(), Memory.UNKNOWN_WORLD_TIMESTAMP),
                nzT(m.realTimestamp()), null, null);
    }

    private static long nzL(@Nullable Long v, long fallback) {
        return v != null ? v : fallback;
    }

    private static Instant nzT(@Nullable Instant v) {
        return v != null ? v : Memory.UNKNOWN_REAL_TIMESTAMP;
    }

    /**
     * Server deterministically rejects our push body (e.g. HTTP 422). Retrying the
     * same bytes every 5s is spam — say it once with the server's reason, pause pushes
     * for a few minutes, keep pulling. Clears on next successful push or reconnect.
     */
    private void handleDeterministic(Minecraft client, String bankId, String note) {
        if (!bankId.equals(activeBankId)) return;
        this.failing = false;
        this.consecutiveConnFails = 0;
        this.quietUntilMs = 0;
        this.lastResult = "rejected: " + note;
        this.lastDetail = note;
        if (!note.equals(deterministicNote)) {
            this.deterministicNote = note;
            CMSyncLog.log("reject", "bank=" + bankId + " push rejected: " + CMSyncLog.trunc(note, 200));
            sendChat(client, Component.literal("CMSync push rejected by server: " + note), ChatFormatting.RED);
            sendChat(client, Component.literal("Push paused 5 min, pulls continue. "
                    + "If this persists, check the server log or update the mod."), ChatFormatting.GRAY);
        }
        this.deterministicUntilMs = System.currentTimeMillis() + DETERMINISTIC_COOLDOWN_MS;
    }

    private void handlePushResult(Minecraft client, String bankId, CMSyncHttp.Result r,
                                  @Nullable String hash, boolean skipped, String note, int pushedCount,
                                  int statusCode, long tookMs) {
        if (!bankId.equals(activeBankId)) return;
        this.lastDetail = pushDetail(r, statusCode, tookMs, pushedCount, note);
        if (r == CMSyncHttp.Result.SYNCED) {
            if (!skipped) {
                lastPushHash = hash;
                lastPushedCount = pushedCount;
                lastSuccess = Instant.now();
                lastResult = "synced";
            }
            deterministicNote = null;
            deterministicUntilMs = 0;
            consecutiveConnFails = 0;
            quietUntilMs = 0;
            if (failing) {
                failing = false;
                sendChat(client, Component.literal("CMSync re-established"), ChatFormatting.GREEN);
            }
        } else if (r == CMSyncHttp.Result.QUARANTINED) {
            lastResult = "quarantined";
            consecutiveConnFails = 0;
            quietUntilMs = 0;
            CMSyncLog.log("quarantine", "bank=" + bankId + " held mass-delete: " + CMSyncLog.trunc(note, 200));
            sendChat(client, Component.literal("CMSync held mass-delete: " + note), ChatFormatting.YELLOW);
        } else {
            lastResult = r.name();
            if (r == CMSyncHttp.Result.CONNECTION_FAILED) {
                consecutiveConnFails++;
                quietUntilMs = System.currentTimeMillis()
                        + Math.min(60_000L, 5_000L << Math.min(consecutiveConnFails - 1, 3));
            } else {
                consecutiveConnFails = 0;
                quietUntilMs = 0;
            }
            if (!failing) {
                failing = true;
                String msg = "CMSync push failed: " + r
                        + (note == null || note.isEmpty() ? "" : " — " + CMSyncLog.trunc(note, 120));
                sendChat(client, Component.literal(msg), ChatFormatting.RED);
            }
        }
    }

    private static String pushDetail(CMSyncHttp.Result r, int statusCode, long tookMs, int pushedCount, String note) {
        String base;
        if (r == CMSyncHttp.Result.SYNCED)
            base = "push HTTP " + statusCode + " in " + tookMs + "ms, " + pushedCount + " containers";
        else if (statusCode > 0) base = "push HTTP " + statusCode + " (" + r + ")";
        else base = "push failed: " + r + (tookMs > 0 ? " after " + tookMs + "ms" : "");
        if (note != null && !note.isEmpty()) base += " — " + CMSyncLog.trunc(note, 160);
        return base;
    }

    private void handlePullError(Minecraft client, String bankId, String note) {
        if (!bankId.equals(activeBankId)) return;
        lastResult = "connection failed";
        lastDetail = "pull failed: connection failed" + (note.isEmpty() ? "" : " — " + note);
        consecutiveConnFails++;
        quietUntilMs = System.currentTimeMillis()
                + Math.min(60_000L, 5_000L << Math.min(consecutiveConnFails - 1, 3));
        CMSyncLog.log("pull", "bank=" + bankId + " THREW connection failed"
                + (note.isEmpty() ? "" : " note=" + CMSyncLog.trunc(note, 160)));
        if (!failing) {
            failing = true;
            sendChat(client, Component.literal("CMSync connection failed"
                    + (note.isEmpty() ? "" : ": " + CMSyncLog.trunc(note, 120))), ChatFormatting.RED);
        }
    }

    private void sendChat(Minecraft client, Component msg, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(
                Component.literal("[CMSync] ").withStyle(ChatFormatting.GRAY)
                        .append(msg.copy().withStyle(color)));
    }

    private static String gameVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
