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
import java.util.Map;

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
    /** Last wipe generation seen from the server. A newer generation clears locals. */
    public int generation = 0;
    /** Teammate uuid -> last seen name (for ender chest profiles). Refreshed on every pull. */
    public final Map<String, String> ownerNames = new HashMap<>();

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
            o.addProperty("generation", generation);
            JsonObject owners = new JsonObject();
            for (var e : ownerNames.entrySet()) owners.addProperty(e.getKey(), e.getValue());
            o.add("ownerNames", owners);
            Files.writeString(pathFor(bankId), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
