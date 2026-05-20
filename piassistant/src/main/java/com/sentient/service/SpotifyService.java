package com.sentient.service;

import com.google.gson.*;
import com.sentient.util.EnvLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Spotify integration — direct HTTP against the Spotify Web API.
 *
 * <p>Rewritten from a thin wrapper around the {@code spotify-web-api-java} SDK to a
 * dependency-free HTTP client. The SDK was returning stale or null-laden objects on
 * repeated polling; this version always hits the live API and surfaces the real
 * HTTP status to callers.
 */
public class SpotifyService {

    private static final String CLIENT_ID = EnvLoader.get("SPOTIFY_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("SPOTIFY_CLIENT_SECRET", "");
    private static final String REDIRECT_URI = EnvLoader.get(
            "SPOTIFY_REDIRECT_URI", "http://127.0.0.1:7070/api/spotify/callback");

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1";

    private static final String SCOPES = String.join(" ",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "streaming",
            "user-read-email",
            "user-read-private",
            "user-library-read");

    private static final Path TOKEN_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_spotify_token");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    private volatile String accessToken = "";
    private volatile String refreshToken = "";
    private volatile long tokenExpiresAt = 0L;
    private volatile boolean authenticated = false;
    private volatile String cachedUserId = "";

    public SpotifyService() {
        restoreSession();
    }

    // ── OAuth ───────────────────────────────────────────

    public String getAuthorizationUrl() {
        return AUTH_URL
                + "?client_id=" + enc(CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=" + enc(SCOPES)
                + "&show_dialog=true";
    }

    public boolean handleCallback(String code) {
        if (CLIENT_ID.isBlank() || CLIENT_SECRET.isBlank()) {
            System.err.println("[Spotify] Missing SPOTIFY_CLIENT_ID/SECRET in .env");
            return false;
        }
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT_URI);
            HttpResponse<String> r = EgressClient.global().send(
                    http,
                    HttpRequest.newBuilder(URI.create(TOKEN_URL))
                            .header("Authorization", basicAuthHeader())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(),
                    "spotify_auth", "oauth-callback",
                    EgressClient.classes(EgressClient.DataClass.OAUTH_TOKEN));
            if (r.statusCode() != 200) {
                System.err.println("[Spotify] Token exchange HTTP " + r.statusCode() + ": " + r.body());
                return false;
            }
            applyTokenResponse(JsonParser.parseString(r.body()).getAsJsonObject());
            saveTokenFile();
            cachedUserId = "";
            System.out.println("[Spotify] Authenticated.");
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] OAuth callback failed: " + e.getMessage());
            return false;
        }
    }

    private void restoreSession() {
        try {
            if (!Files.exists(TOKEN_FILE)) return;
            String raw = Files.readString(TOKEN_FILE).trim();
            if (raw.isEmpty()) return;

            // Backwards compat: older builds stored only the refresh token string.
            if (raw.startsWith("{")) {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                refreshToken = strOr(o, "refresh_token", "");
                accessToken = strOr(o, "access_token", "");
                tokenExpiresAt = o.has("expires_at") ? o.get("expires_at").getAsLong() : 0L;
            } else {
                refreshToken = raw;
            }
            if (refreshToken.isBlank() || CLIENT_ID.isBlank()) return;
            authenticated = true;
            // Don't refresh synchronously here — ensureFreshToken() will do it on first call.
            System.out.println("[Spotify] Session restored from saved token.");
        } catch (Exception e) {
            System.err.println("[Spotify] Could not restore session: " + e.getMessage());
        }
    }

    private void applyTokenResponse(JsonObject json) {
        accessToken = json.get("access_token").getAsString();
        long ttl = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
        tokenExpiresAt = System.currentTimeMillis() + ttl * 1000L;
        if (json.has("refresh_token")) refreshToken = json.get("refresh_token").getAsString();
        authenticated = true;
    }

    private void saveTokenFile() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("access_token", accessToken);
            o.addProperty("refresh_token", refreshToken);
            o.addProperty("expires_at", tokenExpiresAt);
            Files.writeString(TOKEN_FILE, gson.toJson(o));
        } catch (Exception e) {
            System.err.println("[Spotify] Could not persist token: " + e.getMessage());
        }
    }

    /** Refresh if expiring within 60s or missing. Returns true if we have a usable token. */
    private synchronized boolean ensureFreshToken() {
        if (!authenticated || refreshToken.isBlank()) return false;
        if (!accessToken.isBlank() && System.currentTimeMillis() < tokenExpiresAt - 60_000) return true;
        return refreshAccessToken();
    }

    private boolean refreshAccessToken() {
        try {
            String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken);
            HttpResponse<String> r = EgressClient.global().send(
                    http,
                    HttpRequest.newBuilder(URI.create(TOKEN_URL))
                            .header("Authorization", basicAuthHeader())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(),
                    "spotify_auth", "token-refresh",
                    EgressClient.classes(EgressClient.DataClass.OAUTH_TOKEN));
            if (r.statusCode() != 200) {
                System.err.println("[Spotify] Refresh HTTP " + r.statusCode() + ": " + r.body());
                if (r.statusCode() == 400 || r.statusCode() == 401) authenticated = false;
                return false;
            }
            applyTokenResponse(JsonParser.parseString(r.body()).getAsJsonObject());
            saveTokenFile();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Refresh error: " + e.getMessage());
            return false;
        }
    }

    private String basicAuthHeader() {
        String s = CLIENT_ID + ":" + CLIENT_SECRET;
        return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isAuthenticated() { return authenticated; }

    public String getAccessToken() {
        return ensureFreshToken() ? accessToken : "";
    }

    public JsonObject getAuthHealth() {
        JsonObject out = new JsonObject();
        out.addProperty("authenticated", authenticated);
        if (!authenticated) {
            out.addProperty("working", false);
            out.addProperty("error", "Not connected to Spotify. Click 'Connect Spotify' to authorize.");
            return out;
        }
        if (!ensureFreshToken()) {
            out.addProperty("working", false);
            out.addProperty("authenticated", false);
            out.addProperty("error", "Spotify token refresh failed. Reconnect Spotify.");
            return out;
        }
        HttpResp r = apiGet(API + "/me");
        out.addProperty("statusCode", r.status);
        if (r.status == 200) {
            JsonObject me = parseObj(r.body);
            out.addProperty("working", true);
            if (me.has("id")) {
                out.addProperty("userId", me.get("id").getAsString());
                cachedUserId = me.get("id").getAsString();
            }
            if (me.has("display_name") && !me.get("display_name").isJsonNull()) {
                out.addProperty("displayName", me.get("display_name").getAsString());
            }
            if (me.has("product") && !me.get("product").isJsonNull()) {
                out.addProperty("product", me.get("product").getAsString());
            }
            return out;
        }
        out.addProperty("working", false);
        if (r.status == 401) {
            authenticated = false;
            out.addProperty("authenticated", false);
            out.addProperty("error", "Spotify access token rejected. Reconnect Spotify.");
        } else {
            out.addProperty("error", "Spotify returned HTTP " + r.status
                    + (r.body == null || r.body.isEmpty() ? "" : " — " + r.body));
        }
        return out;
    }

    // ── Playlists ───────────────────────────────────────

    public JsonArray getUserPlaylists() {
        JsonArray all = new JsonArray();
        String url = API + "/me/playlists?limit=50";
        while (url != null) {
            HttpResp r = apiGet(url);
            if (r.status != 200) {
                System.err.println("[Spotify] getUserPlaylists HTTP " + r.status + ": " + r.body);
                break;
            }
            JsonObject body = parseObj(r.body);
            if (body.has("items")) {
                for (JsonElement el : body.getAsJsonArray("items")) {
                    if (el.isJsonNull()) continue;
                    JsonObject p = el.getAsJsonObject();
                    if (!p.has("id") || p.get("id").isJsonNull()) continue;
                    all.add(playlistToSimpleJson(p));
                }
            }
            url = body.has("next") && !body.get("next").isJsonNull()
                    ? body.get("next").getAsString() : null;
            if (all.size() >= 100) break; // sane cap
        }
        return all;
    }

    public JsonArray getFeaturedPlaylists() {
        // /browse/featured-playlists is dev-mode-restricted for many apps. Search is reliable.
        return searchPlaylists("Top Hits", 20);
    }

    private JsonArray searchPlaylists(String query, int limit) {
        HttpResp r = apiGet(API + "/search?type=playlist&limit=" + limit + "&q=" + enc(query));
        JsonArray arr = new JsonArray();
        if (r.status != 200) {
            System.err.println("[Spotify] searchPlaylists HTTP " + r.status + ": " + r.body);
            return arr;
        }
        JsonObject body = parseObj(r.body);
        if (!body.has("playlists")) return arr;
        JsonObject playlists = body.getAsJsonObject("playlists");
        if (!playlists.has("items")) return arr;
        for (JsonElement el : playlists.getAsJsonArray("items")) {
            if (el.isJsonNull()) continue;
            JsonObject p = el.getAsJsonObject();
            if (!p.has("id") || p.get("id").isJsonNull()) continue;
            arr.add(playlistToSimpleJson(p));
        }
        return arr;
    }

    public JsonArray getPlaylistTracks(String playlistId) {
        // Try the full playlist endpoint first (returns embedded tracks).
        HttpResp p = apiGet(API + "/playlists/" + playlistId
                + "?fields=tracks.items(track(id,name,uri,duration_ms,artists(name),album(name,images)))");
        if (p.status == 200) {
            JsonObject body = parseObj(p.body);
            return extractPlaylistItems(body);
        }
        // Fall back to /playlists/{id}/tracks — works in some app configurations.
        HttpResp r = apiGet(API + "/playlists/" + playlistId
                + "/tracks?limit=100&fields=items(track(id,name,uri,duration_ms,artists(name),album(name,images)))");
        if (r.status != 200) {
            System.err.println("[Spotify] getPlaylistTracks HTTP " + r.status + ": " + r.body);
            return new JsonArray();
        }
        JsonObject body = parseObj(r.body);
        JsonArray out = new JsonArray();
        if (body.has("items")) collectTrackItems(body.getAsJsonArray("items"), out);
        return out;
    }

    private JsonArray extractPlaylistItems(JsonObject body) {
        JsonArray out = new JsonArray();
        if (!body.has("tracks") || body.get("tracks").isJsonNull()) return out;
        JsonObject tracks = body.getAsJsonObject("tracks");
        if (!tracks.has("items")) return out;
        collectTrackItems(tracks.getAsJsonArray("items"), out);
        return out;
    }

    private void collectTrackItems(JsonArray items, JsonArray out) {
        for (JsonElement el : items) {
            if (el.isJsonNull()) continue;
            JsonObject item = el.getAsJsonObject();
            if (!item.has("track") || item.get("track").isJsonNull()) continue;
            JsonObject t = item.getAsJsonObject("track");
            if (!t.has("id") || t.get("id").isJsonNull()) continue;
            out.add(trackToSimpleJson(t, item));
        }
    }

    // ── Library / recents ─────────────────────────────────

    public JsonArray getSavedTracks() {
        return libraryTracksRaw(API + "/me/tracks?limit=50", "[SavedTracks]");
    }

    public JsonArray getRecentlyPlayed() {
        return libraryTracksRaw(API + "/me/player/recently-played?limit=50", "[Recent]");
    }

    private JsonArray libraryTracksRaw(String url, String tag) {
        HttpResp r = apiGet(url);
        if (r.status != 200) {
            System.err.println("[Spotify] " + tag + " HTTP " + r.status + ": " + r.body);
            return new JsonArray();
        }
        JsonObject body = parseObj(r.body);
        JsonArray out = new JsonArray();
        if (!body.has("items")) return out;
        for (JsonElement el : body.getAsJsonArray("items")) {
            JsonObject item = el.getAsJsonObject();
            if (!item.has("track") || item.get("track").isJsonNull()) continue;
            JsonObject t = item.getAsJsonObject("track");
            if (!t.has("id") || t.get("id").isJsonNull()) continue;
            out.add(trackToSimpleJson(t, item));
        }
        return out;
    }

    public JsonObject probeExtendedQuota() {
        JsonObject result = new JsonObject();
        JsonArray pls = getUserPlaylists();
        if (pls.size() == 0) {
            result.addProperty("extendedQuota", true);
            return result;
        }
        String pid = pls.get(0).getAsJsonObject().get("id").getAsString();
        HttpResp r = apiGet(API + "/playlists/" + pid + "/tracks?limit=1");
        result.addProperty("statusCode", r.status);
        result.addProperty("probedPlaylist", pid);
        result.addProperty("extendedQuota", r.status == 200);
        return result;
    }

    // ── Search ──────────────────────────────────────────

    public JsonArray searchTracks(String query) {
        HttpResp r = apiGet(API + "/search?type=track&limit=20&q=" + enc(query));
        JsonArray out = new JsonArray();
        if (r.status != 200) {
            System.err.println("[Spotify] search HTTP " + r.status + ": " + r.body);
            return out;
        }
        JsonObject body = parseObj(r.body);
        if (!body.has("tracks")) return out;
        JsonObject tracks = body.getAsJsonObject("tracks");
        if (!tracks.has("items")) return out;
        for (JsonElement el : tracks.getAsJsonArray("items")) {
            if (el.isJsonNull()) continue;
            JsonObject t = el.getAsJsonObject();
            if (!t.has("id") || t.get("id").isJsonNull()) continue;
            out.add(trackToSimpleJson(t, null));
        }
        return out;
    }

    // ── Devices ─────────────────────────────────────────

    public JsonArray getAvailableDevices() {
        HttpResp r = apiGet(API + "/me/player/devices");
        JsonArray arr = new JsonArray();
        if (r.status != 200) {
            if (r.status != 204) System.err.println("[Spotify] devices HTTP " + r.status + ": " + r.body);
            return arr;
        }
        JsonObject body = parseObj(r.body);
        if (!body.has("devices")) return arr;
        for (JsonElement el : body.getAsJsonArray("devices")) {
            JsonObject d = el.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.addProperty("id", strOr(d, "id", ""));
            o.addProperty("name", strOr(d, "name", ""));
            o.addProperty("type", strOr(d, "type", ""));
            o.addProperty("active", d.has("is_active") && d.get("is_active").getAsBoolean());
            if (d.has("volume_percent") && !d.get("volume_percent").isJsonNull()) {
                o.addProperty("volume", d.get("volume_percent").getAsInt());
            }
            arr.add(o);
        }
        return arr;
    }

    public boolean transferPlayback(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return false;
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(deviceId);
        body.add("device_ids", ids);
        body.addProperty("play", true);
        HttpResp r = apiSend("PUT", API + "/me/player", body.toString());
        if (r.status / 100 != 2) {
            System.err.println("[Spotify] transferPlayback HTTP " + r.status + ": " + r.body);
            return false;
        }
        return true;
    }

    private String findActiveDeviceId() {
        for (JsonElement el : getAvailableDevices()) {
            JsonObject d = el.getAsJsonObject();
            if (d.has("active") && d.get("active").getAsBoolean()) return d.get("id").getAsString();
        }
        JsonArray devs = getAvailableDevices();
        if (devs.size() > 0) return devs.get(0).getAsJsonObject().get("id").getAsString();
        return null;
    }

    // ── Playback ────────────────────────────────────────

    public boolean play(String uri, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) deviceId = findActiveDeviceId();
        String endpoint = API + "/me/player/play"
                + (deviceId != null && !deviceId.isBlank() ? "?device_id=" + enc(deviceId) : "");
        JsonObject payload = new JsonObject();
        if (uri != null && !uri.isBlank()) {
            if (uri.contains(":track:")) {
                JsonArray uris = new JsonArray();
                uris.add(uri);
                payload.add("uris", uris);
            } else {
                payload.addProperty("context_uri", uri);
            }
        }
        HttpResp r = apiSend("PUT", endpoint, payload.toString());
        if (r.status / 100 != 2) {
            System.err.println("[Spotify] play HTTP " + r.status + ": " + r.body);
            return false;
        }
        return true;
    }

    public boolean playTracks(List<String> trackUris, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) deviceId = findActiveDeviceId();
        String endpoint = API + "/me/player/play"
                + (deviceId != null && !deviceId.isBlank() ? "?device_id=" + enc(deviceId) : "");
        JsonObject payload = new JsonObject();
        JsonArray uris = new JsonArray();
        for (String u : trackUris) uris.add(u);
        payload.add("uris", uris);
        HttpResp r = apiSend("PUT", endpoint, payload.toString());
        if (r.status / 100 != 2) {
            System.err.println("[Spotify] playTracks HTTP " + r.status + ": " + r.body);
            return false;
        }
        return true;
    }

    public boolean pause() { return controlPlayback("PUT", API + "/me/player/pause"); }
    public boolean resume() { return play("", null); }
    public boolean skipNext() { return controlPlayback("POST", API + "/me/player/next"); }
    public boolean skipPrevious() { return controlPlayback("POST", API + "/me/player/previous"); }

    private boolean controlPlayback(String method, String url) {
        HttpResp r = apiSend(method, url, "");
        if (r.status / 100 != 2) {
            System.err.println("[Spotify] " + method + " " + url + " HTTP " + r.status + ": " + r.body);
            return false;
        }
        return true;
    }

    public JsonObject getPlaybackState() {
        JsonObject empty = new JsonObject();
        empty.addProperty("playing", false);
        if (!authenticated) return empty;
        HttpResp r = apiGet(API + "/me/player");
        if (r.status == 204) return empty; // no active device
        if (r.status != 200) {
            if (r.status == 401) authenticated = false;
            return empty;
        }
        JsonObject body = parseObj(r.body);
        JsonObject state = new JsonObject();
        state.addProperty("playing", body.has("is_playing") && body.get("is_playing").getAsBoolean());
        if (body.has("progress_ms") && !body.get("progress_ms").isJsonNull()) {
            state.addProperty("progress_ms", body.get("progress_ms").getAsInt());
        }
        if (body.has("device") && body.get("device").isJsonObject()) {
            JsonObject d = body.getAsJsonObject("device");
            state.addProperty("device_id", strOr(d, "id", ""));
            state.addProperty("device_name", strOr(d, "name", ""));
        }
        if (body.has("item") && body.get("item").isJsonObject()) {
            JsonObject t = body.getAsJsonObject("item");
            state.add("track", trackToSimpleJson(t, null));
        }
        return state;
    }

    // ── AI DJ ───────────────────────────────────────────

    public CompletableFuture<JsonObject> aiDjPick(String moodPrompt, GroqService groqService) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            try {
                String aiPrompt = "You are a music DJ AI. The user wants music for: \"" + moodPrompt + "\". " +
                        "Generate exactly 5 Spotify search queries that would find great songs matching this mood/vibe. " +
                        "Each query should be a specific song title, artist name, or descriptive search term. " +
                        "Output ONLY the queries, one per line, with no numbering, no explanations, no extra text.";
                String aiResponse = groqService.callGroqForDJ(aiPrompt);
                String[] queries = aiResponse == null ? new String[0] : aiResponse.trim().split("\\n");

                JsonArray allTracks = new JsonArray();
                Set<String> seen = new LinkedHashSet<>();
                for (String raw : queries) {
                    String q = raw == null ? "" : raw.replaceAll("^[\\d.\\-\\s]+", "").trim();
                    if (q.isEmpty()) continue;
                    JsonArray results = searchTracks(q);
                    int taken = 0;
                    for (JsonElement el : results) {
                        JsonObject track = el.getAsJsonObject();
                        String uri = track.get("uri").getAsString();
                        if (seen.add(uri)) {
                            allTracks.add(track);
                            taken++;
                            if (taken >= 2) break;
                        }
                    }
                }
                result.addProperty("mood", moodPrompt);
                result.add("tracks", allTracks);
                JsonArray uris = new JsonArray();
                for (String u : seen) uris.add(u);
                result.add("uris", uris);
                result.addProperty("message", "AI DJ picked " + allTracks.size() + " tracks for: " + moodPrompt);
                return result;
            } catch (Exception e) {
                System.err.println("[Spotify] AI DJ failed: " + e.getMessage());
                result.addProperty("error", "AI DJ failed: " + e.getMessage());
                return result;
            }
        });
    }

    // ── Playlist creation ───────────────────────────────

    public JsonObject createPlaylist(String name, String description, boolean isPublic) {
        String userId = getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Could not determine your Spotify user ID. Please re-authenticate.");
            return err;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("name", name);
        payload.addProperty("description", description == null ? "" : description);
        payload.addProperty("public", isPublic);
        HttpResp r = apiSend("POST", API + "/users/" + userId + "/playlists", payload.toString());
        if (r.status == 200 || r.status == 201) {
            JsonObject pl = parseObj(r.body);
            JsonObject out = new JsonObject();
            out.addProperty("id", pl.get("id").getAsString());
            out.addProperty("name", pl.get("name").getAsString());
            out.addProperty("uri", pl.get("uri").getAsString());
            return out;
        }
        JsonObject err = new JsonObject();
        err.addProperty("statusCode", r.status);
        if (r.status == 401) {
            authenticated = false;
            err.addProperty("error", "Spotify token expired. Re-authenticate via /api/spotify/auth.");
        } else if (r.status == 403) {
            err.addProperty("error", "Forbidden (403). If your Spotify app is in development mode, add your account " +
                    "as a test user at developer.spotify.com → Dashboard → User Management, then re-auth.");
        } else {
            err.addProperty("error", "Spotify API error " + r.status + ": " + r.body);
        }
        return err;
    }

    public boolean addTracksToPlaylist(String playlistId, List<String> trackUris) {
        if (trackUris == null || trackUris.isEmpty()) return false;
        JsonObject payload = new JsonObject();
        JsonArray uris = new JsonArray();
        for (String u : trackUris) uris.add(u);
        payload.add("uris", uris);
        HttpResp r = apiSend("POST", API + "/playlists/" + playlistId + "/tracks", payload.toString());
        if (r.status / 100 == 2) return true;
        System.err.println("[Spotify] addTracksToPlaylist HTTP " + r.status + ": " + r.body);
        return false;
    }

    // ── Internals ───────────────────────────────────────

    private String getCurrentUserId() {
        if (!cachedUserId.isBlank()) return cachedUserId;
        HttpResp r = apiGet(API + "/me");
        if (r.status != 200) return null;
        JsonObject me = parseObj(r.body);
        if (me.has("id")) cachedUserId = me.get("id").getAsString();
        return cachedUserId;
    }

    /** GET wrapper that auto-retries once on 401 after a token refresh. */
    private HttpResp apiGet(String url) { return apiSend("GET", url, null); }

    private HttpResp apiSend(String method, String url, String body) {
        if (!ensureFreshToken()) return new HttpResp(401, "{\"error\":\"no_token\"}");
        HttpResp r = doRequest(method, url, body, accessToken);
        if (r.status == 401 && refreshAccessToken()) {
            r = doRequest(method, url, body, accessToken);
        }
        return r;
    }

    private HttpResp doRequest(String method, String url, String body, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15));
            HttpRequest.BodyPublisher bp = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            if (body != null) b.header("Content-Type", "application/json; charset=UTF-8");
            switch (method) {
                case "GET":    b.GET(); break;
                case "POST":   b.POST(bp); break;
                case "PUT":    b.PUT(bp); break;
                case "DELETE": b.DELETE(); break;
                default:       b.method(method, bp);
            }
            HttpResponse<String> resp = EgressClient.global().send(
                    http, b.build(), HttpResponse.BodyHandlers.ofString(),
                    "spotify", method + " " + URI.create(url).getPath(),
                    EgressClient.classes(EgressClient.DataClass.AUTH));
            return new HttpResp(resp.statusCode(), resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            System.err.println("[Spotify] " + method + " " + url + " failed: " + e.getMessage());
            return new HttpResp(0, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static class HttpResp {
        final int status; final String body;
        HttpResp(int status, String body) { this.status = status; this.body = body; }
    }

    private static JsonObject parseObj(String s) {
        try {
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String strOr(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private JsonObject playlistToSimpleJson(JsonObject p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", strOr(p, "id", ""));
        o.addProperty("name", strOr(p, "name", "Untitled"));
        o.addProperty("uri", strOr(p, "uri", ""));
        if (p.has("tracks") && p.get("tracks").isJsonObject()) {
            JsonObject tr = p.getAsJsonObject("tracks");
            o.addProperty("trackCount", tr.has("total") && !tr.get("total").isJsonNull()
                    ? tr.get("total").getAsInt() : 0);
        } else {
            o.addProperty("trackCount", 0);
        }
        String img = firstImageUrl(p);
        o.addProperty("imageUrl", img == null ? "" : img);
        if (p.has("owner") && p.get("owner").isJsonObject()) {
            JsonObject owner = p.getAsJsonObject("owner");
            if (owner.has("display_name") && !owner.get("display_name").isJsonNull()) {
                o.addProperty("owner", owner.get("display_name").getAsString());
            }
        }
        return o;
    }

    private JsonObject trackToSimpleJson(JsonObject t, JsonObject parentItem) {
        JsonObject o = new JsonObject();
        o.addProperty("id", strOr(t, "id", ""));
        o.addProperty("name", strOr(t, "name", ""));
        o.addProperty("uri", strOr(t, "uri", ""));
        if (t.has("duration_ms") && !t.get("duration_ms").isJsonNull()) {
            o.addProperty("duration_ms", t.get("duration_ms").getAsInt());
        }
        if (t.has("artists") && t.get("artists").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray artists = t.getAsJsonArray("artists");
            for (int i = 0; i < artists.size(); i++) {
                JsonObject a = artists.get(i).getAsJsonObject();
                if (i > 0) sb.append(", ");
                sb.append(strOr(a, "name", ""));
            }
            o.addProperty("artists", sb.toString());
        }
        if (t.has("album") && t.get("album").isJsonObject()) {
            JsonObject album = t.getAsJsonObject("album");
            o.addProperty("album", strOr(album, "name", ""));
            String img = firstImageUrl(album);
            if (img != null) o.addProperty("imageUrl", img);
        }
        if (parentItem != null) {
            if (parentItem.has("added_at")) o.addProperty("addedAt", strOr(parentItem, "added_at", ""));
            if (parentItem.has("played_at")) o.addProperty("playedAt", strOr(parentItem, "played_at", ""));
        }
        return o;
    }

    private static String firstImageUrl(JsonObject parent) {
        if (parent == null || !parent.has("images")) return null;
        JsonArray imgs = parent.getAsJsonArray("images");
        if (imgs.size() == 0) return null;
        int idx = imgs.size() > 1 ? 1 : 0;
        JsonObject img = imgs.get(idx).getAsJsonObject();
        return strOr(img, "url", null);
    }
}
