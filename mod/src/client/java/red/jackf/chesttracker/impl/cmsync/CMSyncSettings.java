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
            Files.writeString(pathFor(bankId), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
