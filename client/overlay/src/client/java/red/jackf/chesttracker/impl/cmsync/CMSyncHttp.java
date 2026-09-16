package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP layer for CMSync v2. Mirrors QMSyncHttp but with protocolVersion=2,
 * mcVersion/modVersion, token header, and push/pull endpoints.
 */
public class CMSyncHttp {
    public static final int PROTOCOL_VERSION = 2;
    private static final Gson GSON = new Gson();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            // MUST stay HTTP/1.1: Java's default (HTTP_2) attempts a cleartext h2c upgrade
            // ("Upgrade: h2c"), and plain HTTP/1.1 servers (uvicorn/h11) drop the request
            // body on such requests — every POST then arrives EMPTY (server 422s).
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public enum Result {
        SYNCED, ACCESS_DENIED, QUARANTINED, URL_NOT_FOUND, NOT_A_CMSYNC_SERVER, CONNECTION_FAILED,
        /** Server rejected the body shape (HTTP 422). Deterministic: retrying won't help. */
        VALIDATION_ERROR
    }

    public record Identity(String playerUuid, String playerName, String serverId, String serverName,
                           String mcVersion, String modVersion) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("protocolVersion", PROTOCOL_VERSION);
            o.addProperty("playerUuid", playerUuid);
            o.addProperty("playerName", playerName);
            o.addProperty("serverId", serverId);
            o.addProperty("serverName", serverName);
            o.addProperty("mcVersion", mcVersion);
            o.addProperty("modVersion", modVersion);
            return o;
        }
    }

    public record PushOutcome(Result result, String note, int containers) {
    }

    public record PullOutcome(Result result, List<JsonObject> changes, List<JsonObject> tombstones, int containers) {
    }

    private CMSyncHttp() {
    }

    public static URI parseBaseUrl(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        // tolerance: players type "host:port" without scheme into the GUI box —
        // assume http rather than dead-ending on "bad URL"
        if (!s.contains("://")) s = "http://" + s;
        try {
            URI uri = URI.create(s);
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
                return null;
            if (uri.getHost() == null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpRequest.Builder base(String baseUrl, String endpoint, String token) {
        String b = baseUrl.strip();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(b + endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (token != null && !token.isBlank()) rb.header("X-CMSync-Token", token.strip());
        return rb;
    }

    public static CompletableFuture<Result> handshake(String baseUrl, String token, Identity ident) {
        HttpRequest req = base(baseUrl, "/api/handshake", token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(ident.toJson()), StandardCharsets.UTF_8))
                .build();
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(CMSyncHttp::classify)
                .exceptionally(CMSyncHttp::classifyError);
    }

    public static CompletableFuture<PushOutcome> push(String baseUrl, String token, Identity ident,
                                                      String baseHash, String fullHash, List<JsonObject> changes) {
        JsonObject body = ident.toJson();
        if (baseHash != null) body.addProperty("baseHash", baseHash);
        body.addProperty("fullHash", fullHash == null ? "" : fullHash);
        body.add("changes", GSON.toJsonTree(changes));
        HttpRequest req = base(baseUrl, "/api/push", token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    Result r = classify(resp);
                    String note = "";
                    int containers = -1;
                    try {
                        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (o.has("reason")) note = o.get("reason").getAsString();
                        if (o.has("note")) note = o.get("note").getAsString();
                        if (o.has("containers")) containers = o.get("containers").getAsInt();
                        if (o.has("status") && o.get("status").getAsString().equalsIgnoreCase("QUARANTINED"))
                            r = Result.QUARANTINED;
                        // FastAPI validation errors: surface the exact field (loc + msg) so the
                        // player (and server log) shows WHY instead of a generic failure.
                        if (note.isEmpty() && o.has("detail") && o.get("detail").isJsonArray()
                                && !o.getAsJsonArray("detail").isEmpty()) {
                            try {
                                JsonObject first = o.getAsJsonArray("detail").get(0).getAsJsonObject();
                                String loc = first.has("loc") ? first.get("loc").toString() : "";
                                String msg = first.has("msg") ? first.get("msg").getAsString() : "validation failed";
                                note = ("HTTP " + resp.statusCode() + ": " + loc + " " + msg).trim();
                                if (note.length() > 180) note = note.substring(0, 180);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    } catch (RuntimeException ignored) {
                    }
                    return new PushOutcome(r, note, containers);
                })
                .exceptionally(t -> new PushOutcome(classifyError(t), t.getMessage(), -1));
    }

    public static CompletableFuture<PullOutcome> pull(String baseUrl, String token, String serverId, String playerUuid) {
        String b = baseUrl.strip();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String url = b + "/api/pull?serverId=" + uri(serverId) + "&playerUuid=" + uri(playerUuid);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET();
        if (token != null && !token.isBlank()) rb.header("X-CMSync-Token", token.strip());
        return CLIENT.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    Result r = classify(resp);
                    if (r != Result.SYNCED) return new PullOutcome(r, List.of(), List.of(), -1);
                    try {
                        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                        List<JsonObject> ch = new java.util.ArrayList<>();
                        List<JsonObject> tb = new java.util.ArrayList<>();
                        if (o.has("changes")) o.getAsJsonArray("changes").forEach(e -> ch.add(e.getAsJsonObject()));
                        if (o.has("tombstones")) o.getAsJsonArray("tombstones").forEach(e -> tb.add(e.getAsJsonObject()));
                        int c = o.has("containers") ? o.get("containers").getAsInt() : -1;
                        return new PullOutcome(Result.SYNCED, ch, tb, c);
                    } catch (RuntimeException e) {
                        return new PullOutcome(Result.NOT_A_CMSYNC_SERVER, List.of(), List.of(), -1);
                    }
                })
                .exceptionally(t -> new PullOutcome(classifyError(t), List.of(), List.of(), -1));
    }

    private static String uri(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return s;
        }
    }

    static Result classify(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code == 404 || code == 405 || code == 410 || code == 501) return Result.NOT_A_CMSYNC_SERVER;
        if (code == 401 || code == 403) return Result.ACCESS_DENIED;
        if (code == 422) return Result.VALIDATION_ERROR;
        if (code < 200 || code >= 300) return Result.CONNECTION_FAILED;
        try {
            JsonElement el = JsonParser.parseString(resp.body());
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("status")) {
                    String st = o.get("status").getAsString().toUpperCase();
                    return switch (st) {
                        case "SYNCED" -> Result.SYNCED;
                        case "ACCESS_DENIED" -> Result.ACCESS_DENIED;
                        case "QUARANTINED" -> Result.QUARANTINED;
                        default -> Result.NOT_A_CMSYNC_SERVER;
                    };
                }
            }
        } catch (RuntimeException ignored) {
        }
        return Result.NOT_A_CMSYNC_SERVER;
    }

    static Result classifyError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.UnknownHostException) return Result.URL_NOT_FOUND;
            if (c instanceof java.net.ConnectException || c instanceof java.net.http.HttpTimeoutException)
                return Result.CONNECTION_FAILED;
        }
        return Result.CONNECTION_FAILED;
    }
}
