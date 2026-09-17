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
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.compat.servers.hypixel.HypixelProvider;
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
    public static final String MOD_VERSION = "cmsync.2";
    private static final Logger LOGGER = ChestTracker.getLogger("CMSync");
    private static final Set<Identifier> ENDER_CHEST_KEYS = Set.of(
            CommonKeys.ENDER_CHEST_KEY,
            CommonKeys.SHARE_ENDER_CHEST,
            HypixelProvider.SKYBLOCK_ENDER_CHEST
    );

    private static final double MAX_DELETE_FRACTION = 0.20;
    private static final int MAX_DELETE_COUNT = 50;

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

    @Nullable private Instant lastSuccess = null;
    @Nullable private String lastResult = null;

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
    }

    public void deactivate() {
        resetSession();
    }

    public Optional<Instant> getLastSuccess() {
        return Optional.ofNullable(lastSuccess);
    }

    public Optional<String> getLastResult() {
        return Optional.ofNullable(lastResult);
    }

    private void resetSession() {
        this.activeBankId = null;
        this.lastPushHash = null;
        this.lastPushedCount = -1;
        this.failing = false;
        this.lastAttemptMs = 0;
        this.deterministicNote = null;
        this.deterministicUntilMs = 0;
        this.lastSuccess = null;
        this.lastResult = null;
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
        // queue lane: if network busy, skip this tick (coalesce) — keeps FPS smooth
        if (!CMSyncQueue.tryClaim()) return;
        lastAttemptMs = now;

        // ---- fast copy on client thread (no Gson, no codec, no HTTP) ----
        final String bankId = bank.getId();
        final String url = settings.url;
        final String token = settings.token;
        final boolean syncEnder = settings.syncEnderChest;
        final String playerUuid = client.player.getUUID().toString();
        final String playerName = client.player.getName().getString();
        final String serverId = coord.id();
        final String serverName = coord.userFriendlyName();
        final String mcVersion = gameVersion();
        final DynamicOps<JsonElement> ops =
                client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        List<RawEntry> snapshot = new ArrayList<>();
        try {
            for (Map.Entry<Identifier, MemoryKeyImpl> e : bank.getMemories().entrySet()) {
                if (!syncEnder && ENDER_CHEST_KEYS.contains(e.getKey())) continue;
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
            return;
        }

        // ---- everything heavy runs on queue lane ----
        CMSyncQueue.executor().execute(() -> {
            try {
                runSyncJob(client, bankId, url, token, playerUuid, playerName,
                        serverId, serverName, mcVersion, ops, snapshot);
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
                            String mcVersion, DynamicOps<JsonElement> ops, List<RawEntry> snapshot) {
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
        final String fullHash = ItemNormalizer.hashProjections(hashProj);
        final boolean emptyLocal = changes.isEmpty();
        final String prevHash = this.lastPushHash;
        final boolean dirty = this.failing || !fullHash.equals(prevHash);

        // local mass-delete hold: propagate deletes, but not wipes
        if (!emptyLocal && lastPushedCount > 0) {
            int drop = lastPushedCount - changes.size();
            if (drop >= MAX_DELETE_COUNT || drop >= (int) (lastPushedCount * MAX_DELETE_FRACTION)) {
                client.execute(() -> {
                    if (!bankId.equals(activeBankId)) return;
                    lastResult = "held local mass-delete";
                    sendChat(client, Component.literal("CMSync held push: you lost " + drop
                            + " containers locally (last=" + lastPushedCount + " now=" + changes.size()
                            + "). Re-open chests or /cmsync stop if intentional."), ChatFormatting.YELLOW);
                });
                // still pull so we don't go stale
                doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion);
                return;
            }
        }

        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                playerUuid, playerName, serverId, serverName, mcVersion, MOD_VERSION);

        try {
            if (dirty && !emptyLocal) {
                CMSyncHttp.PushOutcome push = CMSyncHttp.push(url, token, ident, prevHash, fullHash, changes).join();
                // deterministic rejections would fail identically on every retry —
                // handle separately (one message + cooldown) instead of the flap loop
                if (push.result() == CMSyncHttp.Result.VALIDATION_ERROR
                        || push.result() == CMSyncHttp.Result.NOT_A_CMSYNC_SERVER) {
                    final String note = push.note().isEmpty() ? push.result().name() : push.note();
                    client.execute(() -> handleDeterministic(client, bankId, note));
                    doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion);
                    return;
                }
                client.execute(() -> handlePushResult(client, bankId, push.result(),
                        push.result() == CMSyncHttp.Result.SYNCED ? fullHash : prevHash,
                        false, push.note(), changes.size()));
                if (push.result() == CMSyncHttp.Result.QUARANTINED) {
                    doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion);
                    return;
                }
            } else if (emptyLocal) {
                // hub-wipe protection: never push empties, but keep pulling
                client.execute(() -> {
                    if (bankId.equals(activeBankId)) lastResult = "empty-local, pull-only";
                });
            }
            doPullBlocking(client, bankId, url, token, playerUuid, serverId, playerName, serverName, mcVersion);
        } catch (RuntimeException ex) {
            LOGGER.error("cmsync job failed", ex);
            client.execute(() -> handlePushResult(client, bankId,
                    CMSyncHttp.Result.CONNECTION_FAILED, prevHash, false, "", lastPushedCount));
        }
    }

    private void doPullBlocking(Minecraft client, String bankId, String url, String token,
                                String playerUuid, String serverId, String playerName,
                                String serverName, String mcVersion) {
        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                playerUuid, playerName, serverId, serverName, mcVersion, MOD_VERSION);
        CMSyncHttp.PullOutcome pull;
        try {
            pull = CMSyncHttp.pull(url, token, serverId, playerUuid).join();
        } catch (RuntimeException ex) {
            client.execute(() -> handlePullError(client, bankId));
            return;
        }
        final CMSyncHttp.PullOutcome f = pull;
        client.execute(() -> {
            if (!bankId.equals(activeBankId)) return;
            if (f.result() == CMSyncHttp.Result.SYNCED) {
                applyPull(client, bankId, f.changes(), f.tombstones(), playerUuid, mcVersion);
                lastSuccess = Instant.now();
                lastResult = "synced";
                if (failing) {
                    failing = false;
                    sendChat(client, Component.literal("CMSync re-established"), ChatFormatting.GREEN);
                }
            } else {
                lastResult = f.result().name();
                if (!failing) {
                    failing = true;
                    sendChat(client, Component.literal("CMSync pull failed: " + f.result()), ChatFormatting.RED);
                }
            }
        });
    }

    private void applyPull(Minecraft client, String bankId, List<JsonObject> changes,
                           List<JsonObject> tombstones, String myUuid, String myMc) {
        Optional<MemoryBankImpl> opt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (opt.isEmpty() || !opt.get().getId().equals(bankId) || client.level == null) return;
        MemoryBankImpl bank = opt.get();
        long loadedTime = bank.getMetadata().getLoadedTime();
        long gameTime = client.level.getGameTime();
        DynamicOps<JsonElement> ops =
                client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        for (JsonObject ch : changes) {
            try {
                Identifier key = Identifier.parse(ch.get("key").getAsString());
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

                // overrides ride with their entry; v2 senders without the blob explicitly cleared
                if (!legacy) {
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
                LOGGER.warn("skip bad pull entry: {}", e.getMessage());
            }
        }
        for (JsonObject t : tombstones) {
            try {
                Identifier kid = Identifier.parse(t.get("key").getAsString());
                BlockPos pos = ItemNormalizer.parsePos(t.get("pos").getAsString());
                Instant del = ItemNormalizer.parseInstant(ItemNormalizer.optStr(t, "deleted_at"));
                if (del == null) del = ItemNormalizer.parseInstant(ItemNormalizer.optStr(t, "deletedAt"));
                MemoryKeyImpl keyImpl = bank.getKeyInternal(kid).orElse(null);
                Memory local = keyImpl != null ? keyImpl.get(pos).orElse(null) : null;
                // my strictly-newer recording survives a stale delete; ties go to the delete
                if (local != null && del != null && local.realTimestamp() != null
                        && local.realTimestamp().isAfter(del)) continue;
                bank.removeMemory(kid, pos);
            } catch (RuntimeException e) {
                LOGGER.warn("skip bad tombstone: {}", e.getMessage());
            }
        }
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
        this.lastResult = "rejected: " + note;
        if (!note.equals(deterministicNote)) {
            this.deterministicNote = note;
            sendChat(client, Component.literal("CMSync push rejected by server: " + note), ChatFormatting.RED);
            sendChat(client, Component.literal("Push paused 5 min, pulls continue. "
                    + "If this persists, check the server log or update the mod."), ChatFormatting.GRAY);
        }
        this.deterministicUntilMs = System.currentTimeMillis() + DETERMINISTIC_COOLDOWN_MS;
    }

    private void handlePushResult(Minecraft client, String bankId, CMSyncHttp.Result r,
                                  @Nullable String hash, boolean skipped, String note, int pushedCount) {
        if (!bankId.equals(activeBankId)) return;
        if (r == CMSyncHttp.Result.SYNCED) {
            if (!skipped) {
                lastPushHash = hash;
                lastPushedCount = pushedCount;
                lastSuccess = Instant.now();
                lastResult = "synced";
            }
            deterministicNote = null;
            deterministicUntilMs = 0;
            if (failing) {
                failing = false;
                sendChat(client, Component.literal("CMSync re-established"), ChatFormatting.GREEN);
            }
        } else if (r == CMSyncHttp.Result.QUARANTINED) {
            lastResult = "quarantined";
            sendChat(client, Component.literal("CMSync held mass-delete: " + note), ChatFormatting.YELLOW);
        } else {
            lastResult = r.name();
            if (!failing) {
                failing = true;
                sendChat(client, Component.literal("CMSync push failed: " + r), ChatFormatting.RED);
            }
        }
    }

    private void handlePullError(Minecraft client, String bankId) {
        if (!bankId.equals(activeBankId)) return;
        lastResult = "connection failed";
        if (!failing) {
            failing = true;
            sendChat(client, Component.literal("CMSync connection failed"), ChatFormatting.RED);
        }
    }

    private void sendChat(Minecraft client, Component msg, ChatFormatting color) {
        if (client.player == null) return;
        client.player.displayClientMessage(
                Component.literal("[CMSync] ").withStyle(ChatFormatting.GRAY)
                        .append(msg.copy().withStyle(color)), false);
    }

    private static String gameVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
