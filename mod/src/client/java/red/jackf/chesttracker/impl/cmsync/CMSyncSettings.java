package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.util.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-bank CMSync state. Sidecar file (no Metadata.CODEC edit required):
 * {@code <storageDir>/cmsync_<safeBankId>.json}
 *
 * <p>Deliberately NOT inherited by new banks (same rule as QMSyncSettings).
 */
public class CMSyncSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int DEFAULT_INTERVAL_SECONDS = 5;

    @Nullable public String url = null;
    @Nullable public String token = null;
    @Nullable public String boundServerId = null;
    public boolean enabled = false;
    public boolean paused = false;
    public int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    public boolean syncEnderChest = true;
    /** Also sync container custom names (chesttracker-specific metadata). */
    public boolean syncContainerNames = true;
    /** Show chat notification when a sync cycle completes (push+pull). */
    public boolean chatNotifications = true;
    /** Admin password for /cmsync wipealldata (falls back to the sync token). */
    @Nullable public String adminToken = null;
    /** Last wipe generation seen from the server. A newer generation clears locals. */
    public int generation = 0;
    /** Teammate uuid -> last seen name (for ender chest profiles). Refreshed on every pull. */
    public final Map<String, String> ownerNames = new HashMap<>();
    /** Last local key baseline whose push was acknowledged by the server. */
    public final Map<String, Set<String>> acknowledgedKeys = new HashMap<>();
    /** Observation timestamps for deletes detected but not yet acknowledged. */
    public final Map<String, Map<String, String>> pendingDeletes = new HashMap<>();
    /** Null means no acknowledged baseline exists yet. */
    @Nullable public Boolean baselineSyncEnder = null;
    /** Highest durable server change revision merged into this bank. */
    public int lastRevision = 0;
    /** Portable item envelopes keyed by key + position, retained across local edits. */
    public final Map<String, JsonObject> portableShadows = new HashMap<>();

    public boolean isActive() {
        return enabled && url != null && !paused;
    }

    public boolean isConnected() {
        return enabled && url != null;
    }

    public void forget() {
        url = null;
        token = null;
        boundServerId = null;
        enabled = false;
        paused = false;
        acknowledgedKeys.clear();
        pendingDeletes.clear();
        baselineSyncEnder = null;
        lastRevision = 0;
        portableShadows.clear();
    }

    // ---- persistence ----
    private static String safe(String bankId) {
        return bankId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static Path pathFor(String bankId) {
        return Constants.STORAGE_DIR.resolve("cmsync_" + safe(bankId) + ".json");
    }

    public static CMSyncSettings load(String bankId) {
        Path p = pathFor(bankId);
        if (!Files.isRegularFile(p)) return new CMSyncSettings();
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject o = GSON.fromJson(s, JsonObject.class);
            CMSyncSettings st = new CMSyncSettings();
            if (o.has("url")) st.url = o.get("url").isJsonNull() ? null : o.get("url").getAsString();
            if (o.has("token")) st.token = o.get("token").isJsonNull() ? null : o.get("token").getAsString();
            if (o.has("boundServerId")) st.boundServerId = o.get("boundServerId").isJsonNull() ? null : o.get("boundServerId").getAsString();
            if (o.has("enabled")) st.enabled = o.get("enabled").getAsBoolean();
            if (o.has("paused")) st.paused = o.get("paused").getAsBoolean();
            if (o.has("intervalSeconds")) st.intervalSeconds = o.get("intervalSeconds").getAsInt();
            if (o.has("syncEnderChest")) st.syncEnderChest = o.get("syncEnderChest").getAsBoolean();
            if (o.has("syncContainerNames")) st.syncContainerNames = o.get("syncContainerNames").getAsBoolean();
            if (o.has("chatNotifications")) st.chatNotifications = o.get("chatNotifications").getAsBoolean();
            if (o.has("adminToken")) st.adminToken = o.get("adminToken").isJsonNull() ? null : o.get("adminToken").getAsString();
            try {
                if (o.has("generation") && !o.get("generation").isJsonNull())
                    st.generation = o.get("generation").getAsInt();
            } catch (RuntimeException ignored) {
            }
            if (o.has("ownerNames") && o.get("ownerNames").isJsonObject()) {
                for (var e : o.getAsJsonObject("ownerNames").entrySet()) {
                    try {
                        if (!e.getValue().isJsonNull()) st.ownerNames.put(e.getKey(), e.getValue().getAsString());
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (o.has("acknowledgedKeys") && o.get("acknowledgedKeys").isJsonObject()) {
                for (var e : o.getAsJsonObject("acknowledgedKeys").entrySet()) {
                    try {
                        Set<String> positions = new HashSet<>();
                        if (e.getValue().isJsonArray()) {
                            e.getValue().getAsJsonArray().forEach(v -> positions.add(v.getAsString()));
                        }
                        st.acknowledgedKeys.put(e.getKey(), positions);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (o.has("pendingDeletes") && o.get("pendingDeletes").isJsonObject()) {
                for (var e : o.getAsJsonObject("pendingDeletes").entrySet()) {
                    try {
                        Map<String, String> positions = new HashMap<>();
                        if (e.getValue().isJsonObject()) {
                            for (var position : e.getValue().getAsJsonObject().entrySet()) {
                                if (!position.getValue().isJsonNull()) positions.put(position.getKey(), position.getValue().getAsString());
                            }
                        }
                        st.pendingDeletes.put(e.getKey(), positions);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (o.has("baselineSyncEnder") && !o.get("baselineSyncEnder").isJsonNull()) {
                try {
                    st.baselineSyncEnder = o.get("baselineSyncEnder").getAsBoolean();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                if (o.has("lastRevision") && !o.get("lastRevision").isJsonNull())
                    st.lastRevision = Math.max(0, o.get("lastRevision").getAsInt());
            } catch (RuntimeException ignored) {
            }
            if (o.has("portableShadows") && o.get("portableShadows").isJsonObject()) {
                for (var e : o.getAsJsonObject("portableShadows").entrySet()) {
                    if (e.getValue().isJsonObject()) st.portableShadows.put(e.getKey(), e.getValue().getAsJsonObject().deepCopy());
                }
            }
            return st;
        } catch (IOException | RuntimeException e) {
            return new CMSyncSettings();
        }
    }

    public void save(String bankId) {
        try {
            Files.createDirectories(Constants.STORAGE_DIR);
            JsonObject o = new JsonObject();
            o.addProperty("url", url);
            o.addProperty("token", token);
            o.addProperty("boundServerId", boundServerId);
            o.addProperty("enabled", enabled);
            o.addProperty("paused", paused);
            o.addProperty("intervalSeconds", intervalSeconds);
            o.addProperty("syncEnderChest", syncEnderChest);
            o.addProperty("syncContainerNames", syncContainerNames);
            o.addProperty("chatNotifications", chatNotifications);
            o.addProperty("adminToken", adminToken);
            o.addProperty("generation", generation);
            JsonObject owners = new JsonObject();
            for (var e : ownerNames.entrySet()) owners.addProperty(e.getKey(), e.getValue());
            o.add("ownerNames", owners);
            JsonObject baseline = new JsonObject();
            for (var e : acknowledgedKeys.entrySet()) {
                var positions = new com.google.gson.JsonArray();
                for (String pos : e.getValue()) positions.add(pos);
                baseline.add(e.getKey(), positions);
            }
            o.add("acknowledgedKeys", baseline);
            JsonObject pending = new JsonObject();
            for (var e : pendingDeletes.entrySet()) {
                JsonObject positions = new JsonObject();
                for (var p : e.getValue().entrySet()) positions.addProperty(p.getKey(), p.getValue());
                pending.add(e.getKey(), positions);
            }
            o.add("pendingDeletes", pending);
            if (baselineSyncEnder == null) o.add("baselineSyncEnder", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("baselineSyncEnder", baselineSyncEnder);
            o.addProperty("lastRevision", lastRevision);
            JsonObject shadows = new JsonObject();
            for (var e : portableShadows.entrySet()) shadows.add(e.getKey(), e.getValue().deepCopy());
            o.add("portableShadows", shadows);
            Files.writeString(pathFor(bankId), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
