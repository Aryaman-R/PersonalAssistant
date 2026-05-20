package com.sentient.service;

import com.google.gson.*;
import com.sentient.util.EnvLoader;
import com.sentient.util.ProfileManager;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Google Tasks integration — full rewrite focused on token freshness, idempotent
 * sync, and clear surface area for the UI.
 *
 * <p>Behavior in this iteration:
 * <ul>
 *   <li>Tokens auto-refresh on first use after expiry AND transparently on a 401.</li>
 *   <li>Pull merges every Google list into local lists, matching by name. Tasks on
 *       both sides are merged: titles in Google but missing locally are added; locally-
 *       known Google IDs get their {@code completed} flag updated from Google.</li>
 *   <li>Push creates missing Google lists and tasks. De-dupes by title within a list.</li>
 *   <li>Single-task push/delete bypass the full sync — they're the live-CRUD path.</li>
 * </ul>
 */
public class GoogleTasksService {

    private static final String CLIENT_ID = EnvLoader.get("GOOGLE_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("GOOGLE_CLIENT_SECRET", "");
    private static final String REDIRECT_URI = EnvLoader.get(
            "GOOGLE_REDIRECT_URI", "http://localhost:7070/api/tasks/google/callback");
    private static final String SCOPE =
            "https://www.googleapis.com/auth/tasks https://www.googleapis.com/auth/calendar";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String TASKS_API = "https://tasks.googleapis.com/tasks/v1";

    private static final Path TOKEN_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_google_tasks_token");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final Gson gson = new Gson();

    private volatile String accessToken = "";
    private volatile String refreshToken = "";
    private volatile long tokenExpiresAt = 0L;
    private volatile boolean authenticated = false;

    public GoogleTasksService() { restoreSession(); }

    public boolean isAuthenticated() { return authenticated && !CLIENT_ID.isBlank(); }
    public boolean isConfigured() { return !CLIENT_ID.isBlank() && !CLIENT_SECRET.isBlank(); }

    public JsonObject getAuthHealth() {
        JsonObject out = new JsonObject();
        out.addProperty("configured", isConfigured());
        if (!isAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Not authenticated. Connect Google to sync Tasks.");
            return out;
        }
        if (!ensureFreshToken()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Tasks token refresh failed. Please reconnect Google.");
            return out;
        }
        HttpResp r = apiSend("GET", TASKS_API + "/users/@me/lists?maxResults=1", null);
        out.addProperty("statusCode", r.status);
        if (r.status == 200) {
            out.addProperty("authenticated", true);
            out.addProperty("working", true);
            return out;
        }
        out.addProperty("authenticated", false);
        out.addProperty("working", false);
        out.addProperty("error", "Google Tasks returned HTTP " + r.status + ".");
        return out;
    }

    public String getAuthorizationUrl() {
        return AUTH_URL
                + "?client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                + "&access_type=offline"
                + "&prompt=consent";
    }

    public boolean handleCallback(String code) {
        try {
            String body = "code=" + enc(code)
                    + "&client_id=" + enc(CLIENT_ID)
                    + "&client_secret=" + enc(CLIENT_SECRET)
                    + "&redirect_uri=" + enc(REDIRECT_URI)
                    + "&grant_type=authorization_code";
            HttpResponse<String> resp = EgressClient.global().send(
                    http,
                    HttpRequest.newBuilder(URI.create(TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(),
                    "google_oauth", "oauth-callback",
                    EgressClient.classes(EgressClient.DataClass.OAUTH_TOKEN));
            if (resp.statusCode() != 200) {
                System.err.println("[GoogleTasks] Token exchange failed: " + resp.body());
                return false;
            }
            applyTokenResponse(JsonParser.parseString(resp.body()).getAsJsonObject());
            saveTokenFile();
            System.out.println("[GoogleTasks] Authenticated successfully.");
            return true;
        } catch (Exception e) {
            System.err.println("[GoogleTasks] handleCallback error: " + e.getMessage());
            return false;
        }
    }

    // ── Sync operations ────────────────────────────────────────────────────

    public CompletableFuture<JsonObject> pushTasks() {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", notConfiguredMessage());
                return out;
            }
            int pushed = 0;
            int listsCreated = 0;
            JsonArray perList = new JsonArray();

            for (ProfileManager.TaskList local : ProfileManager.getInstance().getTaskLists()) {
                if (local.name == null || local.name.isBlank()) continue;
                ListResolveResult resolved = findOrCreateList(local.name);
                if (resolved.id == null) {
                    JsonObject row = new JsonObject();
                    row.addProperty("list", local.name);
                    row.addProperty("error", "Could not find or create matching Google list");
                    perList.add(row);
                    continue;
                }
                if (resolved.created) listsCreated++;

                Map<String, JsonObject> existing = byTitle(listTasksInList(resolved.id));
                int listPushed = 0;
                for (ProfileManager.TaskItem task : local.items) {
                    if (task.title == null || task.title.isBlank()) continue;
                    if (existing.containsKey(task.title.trim().toLowerCase())) continue;
                    JsonObject created = createGoogleTask(resolved.id, task);
                    if (created != null && created.has("id")) {
                        task.googleId = created.get("id").getAsString();
                        task.googleListId = resolved.id;
                        listPushed++;
                        pushed++;
                    }
                }
                if (listPushed > 0) ProfileManager.getInstance().saveProfile();

                JsonObject row = new JsonObject();
                row.addProperty("list", local.name);
                row.addProperty("pushed", listPushed);
                row.addProperty("created", resolved.created);
                perList.add(row);
            }

            out.addProperty("pushed", pushed);
            out.addProperty("listsCreated", listsCreated);
            out.add("byList", perList);
            out.addProperty("message", "Pushed " + pushed + " new tasks across "
                    + perList.size() + " list(s). Created " + listsCreated + " Google list(s).");
            return out;
        });
    }

    public CompletableFuture<JsonObject> pullTasks() {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", notConfiguredMessage());
                return out;
            }
            JsonArray googleLists = listAllGoogleLists();
            int pulled = 0;
            int updated = 0;
            int listsAdded = 0;
            JsonArray perList = new JsonArray();
            ProfileManager pm = ProfileManager.getInstance();

            for (JsonElement listEl : googleLists) {
                JsonObject googleList = listEl.getAsJsonObject();
                if (!googleList.has("id") || !googleList.has("title")) continue;
                String listId = googleList.get("id").getAsString();
                String listName = googleList.get("title").getAsString().trim();
                if (listName.isEmpty()) continue;

                boolean existed = pm.getTaskLists().stream()
                        .anyMatch(l -> l.name.equalsIgnoreCase(listName));
                if (!existed) {
                    pm.addTaskList(listName);
                    listsAdded++;
                }

                // Build a local-titles map for de-dup, and a googleId map for upsert.
                Set<String> existingTitles = new HashSet<>();
                Map<String, ProfileManager.TaskItem> byGoogleId = new HashMap<>();
                for (ProfileManager.TaskList l : pm.getTaskLists()) {
                    if (!l.name.equalsIgnoreCase(listName)) continue;
                    for (ProfileManager.TaskItem t : l.items) {
                        if (t.title != null) existingTitles.add(t.title.trim().toLowerCase());
                        if (t.googleId != null && !t.googleId.isEmpty()) byGoogleId.put(t.googleId, t);
                    }
                }

                int listPulled = 0;
                int listUpdated = 0;
                for (JsonElement elem : listTasksInList(listId)) {
                    JsonObject t = elem.getAsJsonObject();
                    String title = t.has("title") ? t.get("title").getAsString().trim() : "";
                    if (title.isEmpty()) continue;
                    String notes = strOr(t, "notes", "");
                    String rawDue = strOr(t, "due", "");
                    String due = rawDue.length() >= 10 ? rawDue.substring(0, 10) : "";
                    String gId = strOr(t, "id", "");
                    boolean done = t.has("status") && "completed".equals(strOr(t, "status", ""));

                    // Update path: we already have this Google ID locally — sync metadata.
                    if (byGoogleId.containsKey(gId)) {
                        ProfileManager.TaskItem local = byGoogleId.get(gId);
                        boolean changed = false;
                        if (!local.title.equals(title)) { local.title = title; changed = true; }
                        if (!local.description.equals(notes)) { local.description = notes; changed = true; }
                        if (!local.dueDate.equals(due)) { local.dueDate = due; changed = true; }
                        if (local.completed != done) { local.completed = done; changed = true; }
                        if (changed) { listUpdated++; updated++; }
                        continue;
                    }
                    // Insert path: new from Google.
                    if (existingTitles.contains(title.toLowerCase())) {
                        // Title match but no googleId — stamp it.
                        for (ProfileManager.TaskList l : pm.getTaskLists()) {
                            if (!l.name.equalsIgnoreCase(listName)) continue;
                            for (ProfileManager.TaskItem ti : l.items) {
                                if (ti.title.equalsIgnoreCase(title)) {
                                    ti.googleId = gId;
                                    ti.googleListId = listId;
                                    ti.completed = done;
                                }
                            }
                        }
                        continue;
                    }
                    pm.addTaskToList(listName, title, notes, due);
                    for (ProfileManager.TaskList l : pm.getTaskLists()) {
                        if (!l.name.equalsIgnoreCase(listName)) continue;
                        for (ProfileManager.TaskItem ti : l.items) {
                            if (ti.title.equalsIgnoreCase(title)) {
                                ti.googleId = gId;
                                ti.googleListId = listId;
                                ti.completed = done;
                            }
                        }
                    }
                    listPulled++;
                    pulled++;
                }
                pm.saveProfile();

                JsonObject row = new JsonObject();
                row.addProperty("list", listName);
                row.addProperty("pulled", listPulled);
                row.addProperty("updated", listUpdated);
                row.addProperty("createdLocally", !existed);
                perList.add(row);
            }

            out.addProperty("pulled", pulled);
            out.addProperty("updated", updated);
            out.addProperty("listsAdded", listsAdded);
            out.add("byList", perList);
            out.addProperty("message", "Pulled " + pulled + " new + updated " + updated
                    + " task(s) from " + googleLists.size() + " Google list(s).");
            return out;
        });
    }

    public CompletableFuture<Boolean> pushSingleTask(String listName, ProfileManager.TaskItem task) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ensureFreshToken()) return false;
            ListResolveResult resolved = findOrCreateList(listName);
            if (resolved.id == null) return false;
            JsonObject created = createGoogleTask(resolved.id, task);
            if (created == null || !created.has("id")) return false;
            task.googleId = created.get("id").getAsString();
            task.googleListId = resolved.id;
            ProfileManager.getInstance().saveProfile();
            return true;
        });
    }

    public CompletableFuture<Boolean> deleteSingleTask(String listName, ProfileManager.TaskItem task) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ensureFreshToken()) return false;
            String listId = task.googleListId != null && !task.googleListId.isEmpty()
                    ? task.googleListId : findOrCreateList(listName).id;
            if (listId == null) return false;

            String taskId = task.googleId;
            if (taskId == null || taskId.isEmpty()) {
                for (JsonElement el : listTasksInList(listId)) {
                    JsonObject t = el.getAsJsonObject();
                    if (t.has("title") && t.get("title").getAsString().equalsIgnoreCase(task.title)) {
                        taskId = t.get("id").getAsString();
                        break;
                    }
                }
            }
            if (taskId == null || taskId.isEmpty()) return false;
            HttpResp r = apiSend("DELETE", TASKS_API + "/lists/" + listId + "/tasks/" + taskId, null);
            return r.status == 200 || r.status == 204;
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private JsonArray listAllGoogleLists() {
        HttpResp r = apiSend("GET", TASKS_API + "/users/@me/lists", null);
        if (r.status != 200) {
            System.err.println("[GoogleTasks] listAllGoogleLists HTTP " + r.status + ": " + r.body);
            return new JsonArray();
        }
        JsonObject body = parseObj(r.body);
        return body.has("items") ? body.getAsJsonArray("items") : new JsonArray();
    }

    private static class ListResolveResult {
        String id; boolean created;
        ListResolveResult(String id, boolean created) { this.id = id; this.created = created; }
    }

    private ListResolveResult findOrCreateList(String name) {
        for (JsonElement el : listAllGoogleLists()) {
            JsonObject list = el.getAsJsonObject();
            if (list.has("title") && list.get("title").getAsString().equalsIgnoreCase(name)) {
                return new ListResolveResult(list.get("id").getAsString(), false);
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("title", name);
        HttpResp r = apiSend("POST", TASKS_API + "/users/@me/lists", payload.toString());
        if (r.status == 200 || r.status == 201) {
            JsonObject body = parseObj(r.body);
            return new ListResolveResult(body.get("id").getAsString(), true);
        }
        System.err.println("[GoogleTasks] Create list '" + name + "' failed HTTP " + r.status + ": " + r.body);
        return new ListResolveResult(null, false);
    }

    private JsonObject createGoogleTask(String listId, ProfileManager.TaskItem task) {
        JsonObject payload = new JsonObject();
        payload.addProperty("title", task.title);
        if (task.description != null && !task.description.isEmpty()) payload.addProperty("notes", task.description);
        if (task.dueDate != null && !task.dueDate.isEmpty()) {
            payload.addProperty("due", task.dueDate + "T00:00:00.000Z");
        }
        HttpResp r = apiSend("POST", TASKS_API + "/lists/" + listId + "/tasks", payload.toString());
        if (r.status == 200 || r.status == 201) return parseObj(r.body);
        System.err.println("[GoogleTasks] createGoogleTask HTTP " + r.status + ": " + r.body);
        return null;
    }

    private JsonArray listTasksInList(String listId) {
        HttpResp r = apiSend("GET", TASKS_API + "/lists/" + listId
                + "/tasks?showCompleted=true&showHidden=true&maxResults=100", null);
        if (r.status != 200) {
            System.err.println("[GoogleTasks] listTasksInList HTTP " + r.status + ": " + r.body);
            return new JsonArray();
        }
        JsonObject body = parseObj(r.body);
        return body.has("items") ? body.getAsJsonArray("items") : new JsonArray();
    }

    private static Map<String, JsonObject> byTitle(JsonArray tasks) {
        Map<String, JsonObject> m = new HashMap<>();
        for (JsonElement el : tasks) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("title")) m.put(o.get("title").getAsString().trim().toLowerCase(), o);
        }
        return m;
    }

    // ── Token plumbing ─────────────────────────────────────────────────────

    private synchronized boolean ensureFreshToken() {
        if (!authenticated || refreshToken.isBlank()) return false;
        if (!accessToken.isBlank() && System.currentTimeMillis() < tokenExpiresAt - 60_000) return true;
        return refreshAccessToken();
    }

    private boolean refreshAccessToken() {
        try {
            String body = "refresh_token=" + enc(refreshToken)
                    + "&client_id=" + enc(CLIENT_ID)
                    + "&client_secret=" + enc(CLIENT_SECRET)
                    + "&grant_type=refresh_token";
            HttpResponse<String> resp = EgressClient.global().send(
                    http,
                    HttpRequest.newBuilder(URI.create(TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(),
                    "google_oauth", "token-refresh",
                    EgressClient.classes(EgressClient.DataClass.OAUTH_TOKEN));
            if (resp.statusCode() == 200) {
                applyTokenResponse(JsonParser.parseString(resp.body()).getAsJsonObject());
                saveTokenFile();
                return true;
            }
            System.err.println("[GoogleTasks] Refresh HTTP " + resp.statusCode() + ": " + resp.body());
            if (resp.statusCode() == 400 || resp.statusCode() == 401) authenticated = false;
            return false;
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Refresh error: " + e.getMessage());
            return false;
        }
    }

    private void applyTokenResponse(JsonObject json) {
        accessToken = json.get("access_token").getAsString();
        long ttl = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
        tokenExpiresAt = System.currentTimeMillis() + ttl * 1000L;
        if (json.has("refresh_token")) refreshToken = json.get("refresh_token").getAsString();
        authenticated = true;
    }

    private void restoreSession() {
        if (CLIENT_ID.isBlank()) return;
        try {
            if (!Files.exists(TOKEN_FILE)) return;
            String raw = Files.readString(TOKEN_FILE).trim();
            if (raw.isEmpty()) return;
            JsonObject saved = JsonParser.parseString(raw).getAsJsonObject();
            refreshToken = strOr(saved, "refresh_token", "");
            accessToken = strOr(saved, "access_token", "");
            tokenExpiresAt = saved.has("expires_at") ? saved.get("expires_at").getAsLong() : 0L;
            authenticated = !refreshToken.isBlank();
            if (authenticated) System.out.println("[GoogleTasks] Session restored from saved token.");
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Could not restore session: " + e.getMessage());
        }
    }

    private void saveTokenFile() {
        try {
            JsonObject saved = new JsonObject();
            saved.addProperty("access_token", accessToken);
            saved.addProperty("refresh_token", refreshToken);
            saved.addProperty("expires_at", tokenExpiresAt);
            Files.writeString(TOKEN_FILE, gson.toJson(saved));
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Could not save token: " + e.getMessage());
        }
    }

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
                    "google", method + " " + URI.create(url).getPath(),
                    EgressClient.classes(EgressClient.DataClass.TASK));
            return new HttpResp(resp.statusCode(), resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            System.err.println("[GoogleTasks] " + method + " " + url + " failed: " + e.getMessage());
            return new HttpResp(0, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static class HttpResp {
        final int status; final String body;
        HttpResp(int s, String b) { status = s; body = b; }
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

    private static String notConfiguredMessage() {
        return "Google Tasks is not authenticated. Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET " +
                "to your .env file, then authenticate at /api/tasks/google/auth.";
    }
}
