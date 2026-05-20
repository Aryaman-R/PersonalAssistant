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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Google Calendar integration — rewritten to share token file with
 * {@link GoogleTasksService} and to retry on 401 automatically.
 */
public class GoogleCalendarService {

    private static final String CLIENT_ID = EnvLoader.get("GOOGLE_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("GOOGLE_CLIENT_SECRET", "");
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3";

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

    public GoogleCalendarService() { restoreSession(); }

    public boolean isAuthenticated() { return authenticated && !CLIENT_ID.isBlank(); }
    public boolean isConfigured() { return !CLIENT_ID.isBlank() && !CLIENT_SECRET.isBlank(); }

    public void refreshFromDisk() { restoreSession(); }

    public JsonObject getAuthHealth() {
        JsonObject out = new JsonObject();
        out.addProperty("configured", isConfigured());
        if (!isAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Not authenticated. Connect Google to sync Calendar.");
            return out;
        }
        if (!ensureFreshToken()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Calendar token refresh failed. Please reconnect Google.");
            return out;
        }
        HttpResp r = apiSend("GET", CALENDAR_API + "/users/me/calendarList?maxResults=1", null);
        out.addProperty("statusCode", r.status);
        if (r.status == 200) {
            out.addProperty("authenticated", true);
            out.addProperty("working", true);
            return out;
        }
        out.addProperty("authenticated", false);
        out.addProperty("working", false);
        out.addProperty("error", "Google Calendar returned HTTP " + r.status + ".");
        return out;
    }

    public CompletableFuture<JsonObject> listEvents(int days) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", "Google Calendar not authenticated.");
                return out;
            }
            try {
                String timeMin = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                String timeMax = ZonedDateTime.now().plusDays(days).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                String url = CALENDAR_API + "/calendars/primary/events"
                        + "?timeMin=" + enc(timeMin)
                        + "&timeMax=" + enc(timeMax)
                        + "&singleEvents=true&orderBy=startTime&maxResults=250";
                HttpResp r = apiSend("GET", url, null);
                if (r.status != 200) {
                    out.addProperty("error", "Failed to fetch events: HTTP " + r.status);
                    return out;
                }
                JsonObject body = parseObj(r.body);
                JsonArray items = body.has("items") ? body.getAsJsonArray("items") : new JsonArray();
                JsonArray events = new JsonArray();
                for (JsonElement el : items) {
                    JsonObject e = el.getAsJsonObject();
                    JsonObject simple = new JsonObject();
                    simple.addProperty("id", strOr(e, "id", ""));
                    simple.addProperty("title", strOr(e, "summary", "(No title)"));
                    simple.addProperty("description", strOr(e, "description", ""));
                    if (e.has("start")) {
                        JsonObject s = e.getAsJsonObject("start");
                        simple.addProperty("start", strOr(s, "dateTime", strOr(s, "date", "")));
                        simple.addProperty("allDay", !s.has("dateTime"));
                    }
                    if (e.has("end")) {
                        JsonObject end = e.getAsJsonObject("end");
                        simple.addProperty("end", strOr(end, "dateTime", strOr(end, "date", "")));
                    }
                    events.add(simple);
                }
                out.add("events", events);
                out.addProperty("count", events.size());
            } catch (Exception e) {
                out.addProperty("error", "Failed to list events: " + e.getMessage());
            }
            return out;
        });
    }

    public CompletableFuture<JsonObject> createEvent(String title, String description, String startTime, String endTime) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", "Google Calendar not authenticated.");
                return out;
            }
            JsonObject event = new JsonObject();
            event.addProperty("summary", title);
            if (description != null && !description.isEmpty()) event.addProperty("description", description);
            JsonObject start = new JsonObject(), end = new JsonObject();
            if (startTime != null && startTime.length() <= 10) {
                start.addProperty("date", startTime);
                end.addProperty("date", endTime);
            } else {
                start.addProperty("dateTime", startTime);
                start.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
                end.addProperty("dateTime", endTime);
                end.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
            }
            event.add("start", start);
            event.add("end", end);

            HttpResp r = apiSend("POST", CALENDAR_API + "/calendars/primary/events", event.toString());
            if (r.status == 200 || r.status == 201) {
                JsonObject created = parseObj(r.body);
                out.addProperty("success", true);
                out.addProperty("id", strOr(created, "id", ""));
                out.addProperty("message", "Event created: " + title);
            } else {
                out.addProperty("error", "Failed to create event: HTTP " + r.status + " " + r.body);
            }
            return out;
        });
    }

    public CompletableFuture<JsonObject> deleteEvent(String eventId) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", "Google Calendar not authenticated.");
                return out;
            }
            HttpResp r = apiSend("DELETE", CALENDAR_API + "/calendars/primary/events/" + eventId, null);
            if (r.status == 200 || r.status == 204) {
                out.addProperty("success", true);
                out.addProperty("message", "Event deleted.");
            } else {
                out.addProperty("error", "Failed to delete event: HTTP " + r.status);
            }
            return out;
        });
    }

    public CompletableFuture<JsonObject> updateEvent(String eventId, String title,
                                                     String description, String startTime, String endTime) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject out = new JsonObject();
            if (!ensureFreshToken()) {
                out.addProperty("error", "Google Calendar not authenticated.");
                return out;
            }
            JsonObject patch = new JsonObject();
            if (title != null && !title.isEmpty()) patch.addProperty("summary", title);
            if (description != null) patch.addProperty("description", description);
            if (startTime != null && !startTime.isEmpty()) {
                JsonObject s = new JsonObject();
                if (startTime.length() <= 10) s.addProperty("date", startTime);
                else { s.addProperty("dateTime", startTime); s.addProperty("timeZone", ZonedDateTime.now().getZone().getId()); }
                patch.add("start", s);
            }
            if (endTime != null && !endTime.isEmpty()) {
                JsonObject e = new JsonObject();
                if (endTime.length() <= 10) e.addProperty("date", endTime);
                else { e.addProperty("dateTime", endTime); e.addProperty("timeZone", ZonedDateTime.now().getZone().getId()); }
                patch.add("end", e);
            }
            HttpResp r = apiSend("PATCH", CALENDAR_API + "/calendars/primary/events/" + eventId, patch.toString());
            if (r.status == 200) {
                out.addProperty("success", true);
                out.addProperty("message", "Event updated.");
            } else {
                out.addProperty("error", "Failed to update event: HTTP " + r.status);
            }
            return out;
        });
    }

    /** Human-readable summary for AI context. */
    public String getEventsSummary(int days) {
        try {
            JsonObject result = listEvents(days).get();
            if (result.has("error")) return "Calendar not available.";
            JsonArray events = result.getAsJsonArray("events");
            if (events.size() == 0) return "No upcoming events in the next " + days + " days.";
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : events) {
                JsonObject e = el.getAsJsonObject();
                sb.append("- ").append(e.get("title").getAsString());
                if (e.has("start") && !e.get("start").getAsString().isEmpty()) {
                    sb.append(" (").append(e.get("start").getAsString()).append(")");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Calendar not available.";
        }
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
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                accessToken = json.get("access_token").getAsString();
                long ttl = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
                tokenExpiresAt = System.currentTimeMillis() + ttl * 1000L;
                if (json.has("refresh_token")) refreshToken = json.get("refresh_token").getAsString();
                saveTokenFile();
                return true;
            }
            System.err.println("[GoogleCalendar] Refresh HTTP " + resp.statusCode() + ": " + resp.body());
            if (resp.statusCode() == 400 || resp.statusCode() == 401) authenticated = false;
            return false;
        } catch (Exception e) {
            System.err.println("[GoogleCalendar] Refresh error: " + e.getMessage());
            return false;
        }
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
            if (authenticated) System.out.println("[GoogleCalendar] Session restored from shared token.");
        } catch (Exception e) {
            System.err.println("[GoogleCalendar] Could not restore session: " + e.getMessage());
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
            System.err.println("[GoogleCalendar] Could not save token: " + e.getMessage());
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
                case "PATCH":  b.method("PATCH", bp); break;
                default:       b.method(method, bp);
            }
            HttpResponse<String> resp = EgressClient.global().send(
                    http, b.build(), HttpResponse.BodyHandlers.ofString(),
                    "google", method + " " + URI.create(url).getPath(),
                    EgressClient.classes(EgressClient.DataClass.CALENDAR_EVENT));
            return new HttpResp(resp.statusCode(), resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            System.err.println("[GoogleCalendar] " + method + " " + url + " failed: " + e.getMessage());
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
}
