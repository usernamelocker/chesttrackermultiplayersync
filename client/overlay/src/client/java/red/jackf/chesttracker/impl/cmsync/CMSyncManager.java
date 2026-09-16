package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import red.jackf.jackfredlib.client.api.gps.Coordinate;
import red.jackf.chesttracker.api.memory.Memory;

import java.time.Instant;
import java.util.*;

/**
 * Bidirectional sync with explicit async queue — never blocks the client thread.
 *
 * <p>Client thread does ONLY: guards + fast copy of ItemStacks. All Gson/HTTP/hash runs on
 * {@link CMSyncQueue} single background lane with coalescing (max 1 running, extra ticks drop).
 * Merge back happens via {@code client.execute()} (main thread).
 */
public class CMSyncManager {
    public static final CMSyncManager INSTANCE = new CMSyncManager();
    private static final Logger LOGGER = ChestTracker.getLogger("CMSync");
    private static final Gson GSON = new Gson();
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

        // ---- fast copy on client thread (no Gson, no HTTP) ----
        final String bankId = bank.getId();
        final String url = settings.url;
        final String token = settings.token;
        final boolean syncEnder = settings.syncEnderChest;
        final String playerUuid = client.player.getUUID().toString();
        final String playerName = client.player.getName().getString();
        final String serverId = coord.id();
        final String serverName = coord.userFriendlyName();
        final String mcVersion = gameVersion(client);

        List<RawEntry> snapshot = new ArrayList<>();
        try {
            for (Map.Entry<Identifier, MemoryKeyImpl> e : bank.getMemories().entrySet()) {
                if (!syncEnder && ENDER_CHEST_KEYS.contains(e.getKey())) continue;
                String key = e.getKey().toString();
                for (Map.Entry<BlockPos, Memory> m : e.getValue().getMemories().entrySet()) {
                    List<ItemStack> copies = new ArrayList<>(m.getValue().items().size());
                    for (ItemStack s : m.getValue().items()) {
                        if (!s.isEmpty()) copies.add(s.copy());
                    }
                    snapshot.add(new RawEntry(key, ItemNormalizer.posToString(m.getKey()),
                            m.getValue().realTimestamp().toString(), copies));
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
                        serverId, serverName, mcVersion, snapshot);
            } finally {
                CMSyncQueue.release();
            }
        });
    }

    private record RawEntry(String key, String pos, String updatedAt, List<ItemStack> stacks) {
    }

    /** Background lane: normalize + hash + push + pull (blocking). Merge happens back on client thread. */
    private void runSyncJob(Minecraft client, String bankId, String url, String token,
                            String playerUuid, String playerName, String serverId, String serverName,
                            String mcVersion, List<RawEntry> snapshot) {
        // normalize off-thread using COPIES (safe, no game access)
        List<JsonObject> changes = new ArrayList<>(snapshot.size());
        for (RawEntry r : snapshot) {
            JsonObject ch = new JsonObject();
            ch.addProperty("key", r.key());
            ch.addProperty("pos", r.pos());
            ch.addProperty("deleted", false);
            ch.addProperty("updatedAt", r.updatedAt());
            ch.addProperty("updatedBy", playerUuid);
            ch.addProperty("mcVersion", mcVersion);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (ItemStack s : r.stacks()) {
                JsonObject n = ItemNormalizer.toNorm(s);
                if (n != null) arr.add(n);
            }
            ch.add("items", arr);
            changes.add(ch);
        }
        final String fullHash = ItemNormalizer.hashChanges(changes);
        final boolean emptyLocal = changes.isEmpty();
        final String prevHash = this.lastPushHash;
        final boolean dirty = this.failing || !fullHash.equals(prevHash);

        // local mass-delete hold (friend asked: propagate deletes, but not wipes)
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
                playerUuid, playerName, serverId, serverName, mcVersion, "cmsync.1");

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
                playerUuid, playerName, serverId, serverName, mcVersion, "cmsync.1");
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
                applyPull(client, bankId, f.changes(), f.tombstones());
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

    private void applyPull(Minecraft client, String bankId, List<JsonObject> changes, List<JsonObject> tombstones) {
        Optional<MemoryBankImpl> opt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (opt.isEmpty() || !opt.get().getId().equals(bankId) || client.level == null) return;
        MemoryBankImpl bank = opt.get();
        long loadedTime = bank.getMetadata().getLoadedTime();
        long gameTime = client.level.getGameTime();

        for (JsonObject ch : changes) {
            try {
                Identifier key = Identifier.parse(ch.get("key").getAsString());
                BlockPos pos = ItemNormalizer.parsePos(ch.get("pos").getAsString());
                List<JsonObject> norm = new ArrayList<>();
                if (ch.has("items") && ch.get("items").isJsonArray())
                    ch.getAsJsonArray("items").forEach(e -> norm.add(e.getAsJsonObject()));
                List<ItemStack> stacks = ItemNormalizer.fromNormList(norm);
                Memory mem = new Memory(stacks, null, List.of(), Optional.empty(),
                        loadedTime, gameTime, Instant.now(), null, null);
                bank.addMemory(key, pos, mem);
            } catch (RuntimeException e) {
                LOGGER.warn("skip bad pull entry: {}", e.getMessage());
            }
        }
        for (JsonObject t : tombstones) {
            try {
                String key = t.has("key") ? t.get("key").getAsString() : t.get("key ").getAsString();
                bank.removeMemory(Identifier.parse(key), ItemNormalizer.parsePos(t.get("pos").getAsString()));
            } catch (RuntimeException e) {
                LOGGER.warn("skip bad tombstone: {}", e.getMessage());
            }
        }
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

    private static String gameVersion(Minecraft client) {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
