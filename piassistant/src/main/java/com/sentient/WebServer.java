package com.sentient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sentient.service.AuthService;
import com.sentient.service.AutomationService;
import com.sentient.service.CredentialVault;
import com.sentient.service.GoogleTasksService;
import com.sentient.service.GoogleCalendarService;
import com.sentient.service.GroqService;
import com.sentient.service.Listener;
import com.sentient.service.OpenClawConfigManager;
import com.sentient.service.OpenClawService;
import com.sentient.service.SpotifyService;
import com.sentient.service.TailscaleService;
import com.sentient.service.TextToSpeech;
import com.sentient.util.ProfileManager;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded Javalin web server — replaces JavaFX UI.
 * Serves HTML/CSS/JS and provides WebSocket + REST endpoints.
 */
public class WebServer {

    private static final int PORT = 7070;
    private static final Pattern CMD_PATTERN = Pattern.compile("\\[CMD:(\\w+)(?::([^\\]]+))?\\]");

    private final Javalin app;
    private final Gson gson = new Gson();
    private final GroqService groq;
    private final OpenClawService openClaw;
    private final OpenClawConfigManager openClawConfig;
    private final SpotifyService spotify;
    private final AutomationService automation;
    private final GoogleTasksService googleTasks;
    private final GoogleCalendarService googleCalendar;
    private final TailscaleService tailscale;
    private final AuthService auth;
    private final CredentialVault vault;
    private Listener listener;

    // Active WebSocket clients
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    /** Per-WS-session metadata: deviceName, capabilities, lastSeen. */
    private final java.util.Map<String, JsonObject> deviceMeta = new ConcurrentHashMap<>();
    /** Native helper sessions keyed by sessionId; separate from browser {@code clients}. */
    private final java.util.Map<String, WsContext> helperClients = new ConcurrentHashMap<>();
    /** Per-helper-session metadata: name, platform, capabilities. */
    private final java.util.Map<String, JsonObject> helperMeta = new ConcurrentHashMap<>();
    /** One-shot listeners for VIEW_DEVICE / screen_frame round-trips, keyed by requestId. */
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<String>> pendingScreenCaptures =
            new ConcurrentHashMap<>();
    private String currentSessionId = "";

    // Response control — for stop/interrupt
    private volatile boolean responseActive = false;
    private volatile Thread activeStreamThread;
    private volatile Thread activeAudioThread;

    public WebServer() {
        this.groq = new GroqService();
        this.openClaw = new OpenClawService();
        this.openClawConfig = new OpenClawConfigManager();
        hydrateOpenClawFromConfig();
        this.spotify = new SpotifyService();
        this.automation = new AutomationService();
        this.googleTasks = new GoogleTasksService();
        this.googleCalendar = new GoogleCalendarService();
        this.tailscale = new TailscaleService();
        this.auth = new AuthService();
        this.vault = new CredentialVault();

        this.app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.http.defaultContentType = "application/json";
            // Wire Gson as the JSON mapper so ctx.json(...) actually works.
            // Without this, every ctx.json(<object>) call 500s on Javalin 6.
            config.jsonMapper(new io.javalin.json.JsonMapper() {
                @Override
                public String toJsonString(Object obj, java.lang.reflect.Type type) {
                    return gson.toJson(obj, type);
                }
                @Override
                public <T> T fromJsonString(String json, java.lang.reflect.Type targetType) {
                    return gson.fromJson(json, targetType);
                }
            });
        });

        setupAuthGate();
        setupWebSocket();
        setupRestEndpoints();
        initListener();
    }

    public void start() {
        app.start(PORT);
        System.out.println("[WebServer] Running at http://localhost:" + PORT);

        // Open browser automatically
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[] { "open", "http://localhost:" + PORT });
            } else if (os.contains("linux")) {
                Runtime.getRuntime().exec(new String[] { "xdg-open", "http://localhost:" + PORT });
            }
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        if (listener != null)
            listener.stop();
        TextToSpeech.shutdown();
        app.stop();
    }

    // ── Auth gate (shared password) ─────────────────────

    /**
     * Routes that should always be reachable, even when auth is required.
     * These are: the login screen + auth APIs, OAuth callback returns, and the
     * static asset root (so we can deliver the login page itself).
     */
    private static final java.util.Set<String> AUTH_OPEN_PATHS = java.util.Set.of(
            "/", "/login", "/login.html", "/favicon.ico",
            "/api/auth/status", "/api/auth/login",
            "/api/spotify/callback", "/api/tasks/google/callback");

    private void setupAuthGate() {
        // Auth endpoints always exist.
        app.get("/api/auth/status", ctx -> {
            JsonObject s = auth.statusJson();
            // Tell the client whether THEIR token is still valid.
            String tok = readToken(ctx);
            s.addProperty("loggedIn", !auth.isAuthRequired() || auth.isValidToken(tok));
            ctx.result(s.toString());
            ctx.contentType("application/json");
        });

        app.post("/api/auth/login", ctx -> {
            JsonObject body = ctx.body() == null || ctx.body().isBlank()
                    ? new JsonObject() : gson.fromJson(ctx.body(), JsonObject.class);
            String password = getStr(body, "password", "");
            if (!auth.checkPassword(password)) {
                ctx.status(401).result("{\"error\":\"Invalid password.\"}");
                ctx.contentType("application/json");
                return;
            }
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            // If no password is set, no token is needed; return a dummy so the client
            // can still store a flag in localStorage.
            out.addProperty("token", auth.isAuthRequired() ? auth.issueToken() : "OPEN");
            ctx.result(out.toString());
            ctx.contentType("application/json");
        });

        app.post("/api/auth/logout", ctx -> {
            auth.revokeToken(readToken(ctx));
            ctx.result("{\"success\":true}");
            ctx.contentType("application/json");
        });

        app.post("/api/auth/password", ctx -> {
            // Setting the first password is open (no prior token). Changing requires a valid token.
            if (auth.isAuthRequired() && !auth.isValidToken(readToken(ctx))) {
                ctx.status(401).result("{\"error\":\"Log in first.\"}");
                return;
            }
            JsonObject body = ctx.body() == null || ctx.body().isBlank()
                    ? new JsonObject() : gson.fromJson(ctx.body(), JsonObject.class);
            String pw = getStr(body, "password", "");
            auth.setPassword(pw);
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            out.addProperty("required", auth.isAuthRequired());
            ctx.result(out.toString());
            ctx.contentType("application/json");
        });

        // Filter: every /api/* request goes through the gate.
        app.before("/api/*", ctx -> {
            if (!auth.isAuthRequired()) return;
            String path = ctx.path();
            if (AUTH_OPEN_PATHS.contains(path)) return;
            String tok = readToken(ctx);
            if (!auth.isValidToken(tok)) {
                ctx.status(401).result("{\"error\":\"Authentication required.\"}");
                ctx.contentType("application/json");
                ctx.skipRemainingHandlers();
            }
        });

        // Standalone login page (served regardless of static-file path).
        app.get("/login", ctx -> {
            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(LOGIN_PAGE_HTML);
        });
    }

    private static String readToken(io.javalin.http.Context ctx) {
        String t = ctx.header("X-Sentient-Token");
        if (t == null || t.isEmpty()) t = ctx.queryParam("token");
        return t == null ? "" : t;
    }

    // Inline login page — small, no framework, no external assets.
    private static final String LOGIN_PAGE_HTML =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Sentient · Sign in</title>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>"
            + "body{background:#0d0d0d;color:#e5e7eb;font-family:ui-monospace,Menlo,monospace;"
            + "display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}"
            + ".box{background:#1a1a1a;padding:32px;border:1px solid #27272a;border-radius:12px;width:340px}"
            + "h1{margin:0 0 16px;font-size:18px;letter-spacing:1px;color:#a3e635}"
            + "input{width:100%;box-sizing:border-box;padding:10px;background:#0d0d0d;color:#e5e7eb;"
            + "border:1px solid #3f3f46;border-radius:6px;font:inherit;font-size:14px;margin-bottom:12px}"
            + "button{width:100%;padding:10px;background:#a3e635;color:#0d0d0d;border:0;border-radius:6px;"
            + "font:inherit;font-weight:600;cursor:pointer}"
            + ".err{color:#f87171;margin-top:8px;min-height:1em;font-size:13px}"
            + ".hint{color:#71717a;font-size:12px;margin-top:14px;line-height:1.5}"
            + "</style></head><body><div class=\"box\">"
            + "<h1>SENTIENT · SIGN IN</h1>"
            + "<form id=\"f\"><input id=\"pw\" type=\"password\" placeholder=\"password\" autofocus>"
            + "<button type=\"submit\">SIGN IN</button></form>"
            + "<div class=\"err\" id=\"err\"></div>"
            + "<div class=\"hint\">If you haven't set a password yet, leave the field empty and click "
            + "Sign In. Then set one from Settings → Device Login.</div>"
            + "<script>"
            + "document.getElementById('f').addEventListener('submit',async e=>{e.preventDefault();"
            + "const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({password:document.getElementById('pw').value})});"
            + "if(!r.ok){document.getElementById('err').textContent='Incorrect password.';return;}"
            + "const d=await r.json();localStorage.setItem('sentient_token',d.token);location.href='/';});"
            + "</script></div></body></html>";

    // ── WebSocket ───────────────────────────────────────

    private void setupWebSocket() {
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (auth.isAuthRequired()) {
                    String tok = ctx.queryParam("token");
                    if (tok == null) tok = ctx.header("X-Sentient-Token");
                    if (!auth.isValidToken(tok)) {
                        System.out.println("[WS] Rejecting unauthenticated connect.");
                        try { ctx.closeSession(); } catch (Exception ignored) {}
                        return;
                    }
                }
                clients.add(ctx);
                System.out.println("[WS] Client connected: " + ctx.sessionId());
            });

            ws.onClose(ctx -> {
                clients.remove(ctx);
                deviceMeta.remove(ctx.sessionId());
                System.out.println("[WS] Client disconnected: " + ctx.sessionId());
            });

            ws.onMessage(ctx -> {
                JsonObject msg = gson.fromJson(ctx.message(), JsonObject.class);
                String type = msg.has("type") ? msg.get("type").getAsString() : "";

                switch (type) {
                    case "chat":
                        String text = msg.get("text").getAsString();
                        boolean isWeb = msg.has("source") && "web".equals(msg.get("source").getAsString());
                        String modelOverride = msg.has("modelOverride") ? msg.get("modelOverride").getAsString() : "AUTO";
                        String imageBase64 = msg.has("image") ? msg.get("image").getAsString() : null;
                        String fileName = msg.has("fileName") ? msg.get("fileName").getAsString() : null;
                        String fileType = msg.has("fileType") ? msg.get("fileType").getAsString() : null;
                        String engine = msg.has("engine") ? msg.get("engine").getAsString() : "groq";
                        handleChat(text, !isWeb, modelOverride, imageBase64, fileName, fileType, engine);
                        break;
                    case "record":
                        if (listener != null)
                            listener.startListening();
                        break;
                    case "set_listener":
                        // Browser controls when the server-side wake-word listener is active.
                        // Sent on tab change: VOICE tab → paused=false, every other tab → paused=true.
                        boolean wantPaused = !msg.has("paused") || msg.get("paused").getAsBoolean();
                        if (listener != null) listener.setPaused(wantPaused);
                        break;
                    case "stop":
                        stopResponse();
                        break;
                    case "init":
                        String sid = msg.has("sessionId") ? msg.get("sessionId").getAsString() : "";
                        if (!sid.equals(currentSessionId)) {
                            currentSessionId = sid;
                            groq.extractSessionProfile();
                            groq.clearHistory();
                            
                            String username = ProfileManager.getInstance().getUserProfile().username;
                            JsonObject welcome = new JsonObject();
                            welcome.addProperty("type", "system");
                            welcome.addProperty("text", "Welcome back, " + username);
                            ctx.send(welcome.toString());
                        }
                        break;
                    case "ping":
                        // Keep-alive from web client handled silently
                        break;
                    case "register_device": {
                        // Browser announces this device's display name + capabilities.
                        JsonObject meta = new JsonObject();
                        meta.addProperty("sessionId", ctx.sessionId());
                        meta.addProperty("name", msg.has("name") ? msg.get("name").getAsString() : "Device");
                        meta.addProperty("platform", msg.has("platform") ? msg.get("platform").getAsString() : "");
                        if (msg.has("capabilities")) meta.add("capabilities", msg.get("capabilities"));
                        meta.addProperty("lastSeen", System.currentTimeMillis());
                        deviceMeta.put(ctx.sessionId(), meta);
                        // Broadcast device list update so every tab sees the new device
                        broadcastDeviceList();
                        break;
                    }
                    case "request_screen": {
                        // Forward a screen-frame request to the target device by sessionId.
                        String target = msg.has("targetSessionId") ? msg.get("targetSessionId").getAsString() : "";
                        String reqId = msg.has("requestId") ? msg.get("requestId").getAsString() : "";
                        JsonObject fwd = new JsonObject();
                        fwd.addProperty("type", "capture_screen");
                        fwd.addProperty("requestId", reqId);
                        fwd.addProperty("requesterSessionId", ctx.sessionId());
                        sendToSession(target, fwd);
                        break;
                    }
                    case "screen_frame": {
                        // Device replies with a captured frame — relay back to requester.
                        String requester = msg.has("requesterSessionId")
                                ? msg.get("requesterSessionId").getAsString() : "";
                        if (!requester.isEmpty()) sendToSession(requester, msg);

                        // VIEW_DEVICE round-trip: resolve any pending future waiting on this requestId.
                        String reqId = msg.has("requestId") && !msg.get("requestId").isJsonNull()
                                ? msg.get("requestId").getAsString() : null;
                        if (reqId != null) {
                            java.util.concurrent.CompletableFuture<String> pending =
                                    pendingScreenCaptures.remove(reqId);
                            if (pending != null) {
                                String frame = msg.has("frame") && !msg.get("frame").isJsonNull()
                                        ? msg.get("frame").getAsString() : "";
                                if (frame.isEmpty() || !(msg.has("ok") && msg.get("ok").getAsBoolean())) {
                                    pending.completeExceptionally(
                                            new java.util.concurrent.CancellationException(
                                                    "Device returned no frame (capture denied or failed)."));
                                } else {
                                    pending.complete(frame);
                                }
                            }
                        }
                        break;
                    }
                    case "remote_action": {
                        // Relay a control request to the target device.
                        // Browsers can act on `OPEN_URL` and `SWITCH_PANEL`. The native helper
                        // additionally handles `TYPE_TEXT`, `CLICK_AT`, `LAUNCH_APP`, `KEY_COMBO`.
                        // See DEVICE_CONTROL.md.
                        String target = msg.has("targetSessionId") ? msg.get("targetSessionId").getAsString() : "";
                        JsonObject fwd = new JsonObject();
                        fwd.addProperty("type", "remote_action");
                        if (msg.has("action")) fwd.add("action", msg.get("action"));
                        // Forward the well-known per-action fields verbatim.
                        for (String k : new String[]{"url", "panel", "text", "bundleId", "keys", "x", "y", "payload"}) {
                            if (msg.has(k)) fwd.add(k, msg.get(k));
                        }
                        String fromDevice = msg.has("fromDevice") ? msg.get("fromDevice").getAsString() : "";
                        if (fromDevice.isEmpty()) {
                            JsonObject meta = deviceMeta.get(ctx.sessionId());
                            fromDevice = meta != null && meta.has("name") ? meta.get("name").getAsString() : "another device";
                        }
                        fwd.addProperty("fromDevice", fromDevice);
                        fwd.addProperty("requesterSessionId", ctx.sessionId());
                        if (helperClients.containsKey(target)) {
                            forwardToHelper(target, fwd);
                        } else {
                            sendToSession(target, fwd);
                        }
                        break;
                    }
                    case "webrtc_offer":
                    case "webrtc_answer":
                    case "ice_candidate": {
                        // Pure signalling relay — server has no view of the SDP, just shuttles it.
                        String targetId = msg.has("targetSessionId") && !msg.get("targetSessionId").isJsonNull()
                                ? msg.get("targetSessionId").getAsString() : "";
                        if (targetId.isEmpty()) break;
                        sendToSession(targetId, msg);
                        break;
                    }
                    default:
                        System.out.println("[WS] Unknown message type: " + type);
                }
            });
        });

        // Native OS helper WS endpoint — only the per-device helper CLI connects here.
        // Kept separate from /ws so helpers aren't included in browser broadcasts.
        app.ws("/helper", ws -> {
            ws.onConnect(ctx -> {
                if (auth.isAuthRequired()) {
                    String tok = ctx.queryParam("token");
                    if (tok == null) tok = ctx.header("X-Sentient-Token");
                    if (!auth.isValidToken(tok)) {
                        System.out.println("[Helper] Rejecting unauthenticated connect.");
                        try { ctx.closeSession(); } catch (Exception ignored) {}
                        return;
                    }
                }
                helperClients.put(ctx.sessionId(), ctx);
                System.out.println("[Helper] connected: " + ctx.sessionId());
            });
            ws.onClose(ctx -> {
                helperClients.remove(ctx.sessionId());
                helperMeta.remove(ctx.sessionId());
                System.out.println("[Helper] disconnected: " + ctx.sessionId());
            });
            ws.onMessage(ctx -> {
                try {
                    JsonObject msg = gson.fromJson(ctx.message(), JsonObject.class);
                    String type = msg.has("type") ? msg.get("type").getAsString() : "";
                    switch (type) {
                        case "register_helper": {
                            JsonObject meta = new JsonObject();
                            meta.addProperty("sessionId", ctx.sessionId());
                            meta.addProperty("name", msg.has("name") ? msg.get("name").getAsString() : "Helper");
                            meta.addProperty("platform", msg.has("platform") ? msg.get("platform").getAsString() : "");
                            if (msg.has("capabilities")) meta.add("capabilities", msg.get("capabilities"));
                            meta.addProperty("lastSeen", System.currentTimeMillis());
                            meta.addProperty("kind", "helper");
                            helperMeta.put(ctx.sessionId(), meta);
                            break;
                        }
                        case "action_result": {
                            // Helper reports back: status of a remote action it executed.
                            String action = msg.has("action") ? msg.get("action").getAsString() : "?";
                            boolean ok = msg.has("success") && msg.get("success").getAsBoolean();
                            System.out.println("[Helper] " + action + " → " + (ok ? "ok" : "fail"));
                            break;
                        }
                        default:
                            System.out.println("[Helper] message: " + ctx.message());
                    }
                } catch (Exception e) {
                    System.err.println("[Helper] message parse error: " + e.getMessage());
                }
            });
        });
    }

    /**
     * Find a connected browser device's WS session id by display name (case-insensitive).
     * Returns null when no browser device with that name is currently connected.
     */
    private String lookupDeviceSession(String name) {
        if (name == null) return null;
        String want = name.trim();
        if (want.isEmpty()) return null;
        for (java.util.Map.Entry<String, JsonObject> e : deviceMeta.entrySet()) {
            JsonObject m = e.getValue();
            String n = m != null && m.has("name") ? m.get("name").getAsString() : "";
            if (want.equalsIgnoreCase(n)) return e.getKey();
        }
        return null;
    }

    /**
     * Find a connected native helper's WS session id by display name (case-insensitive).
     * Returns null when no helper with that name is currently connected.
     */
    private String lookupHelperSession(String name) {
        if (name == null) return null;
        String want = name.trim();
        if (want.isEmpty()) return null;
        for (java.util.Map.Entry<String, JsonObject> e : helperMeta.entrySet()) {
            JsonObject m = e.getValue();
            String n = m != null && m.has("name") ? m.get("name").getAsString() : "";
            if (want.equalsIgnoreCase(n)) return e.getKey();
        }
        return null;
    }

    /** Send a message to a single browser WS session. */
    private void forwardToSession(String sessionId, JsonObject msg) {
        sendToSession(sessionId, msg);
    }

    /** Send a message to a single native helper WS session. */
    private void forwardToHelper(String sessionId, JsonObject msg) {
        WsContext ctx = helperClients.get(sessionId);
        if (ctx == null) return;
        try { ctx.send(msg.toString()); } catch (Exception ignored) {}
    }

    private void sendToSession(String sessionId, JsonObject msg) {
        if (sessionId == null || sessionId.isEmpty()) return;
        String json = msg.toString();
        for (WsContext c : clients) {
            if (sessionId.equals(c.sessionId())) {
                try { c.send(json); } catch (Exception ignored) {}
                return;
            }
        }
    }

    private void broadcastVaultUpdated() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "vault_updated");
        broadcast(msg);
    }

    private void broadcastDeviceList() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "device_list");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (JsonObject m : deviceMeta.values()) arr.add(m);
        msg.add("devices", arr);
        broadcast(msg);
    }

    // ── Chat Handler ────────────────────────────────────

    private void handleChat(String text) {
        handleChat(text, true, "AUTO", null, null, null, "groq");
    }

    private void handleChat(String text, boolean playOnServer, String modelOverride, String imageBase64, String fileName, String fileType, String engine) {
        // Pick the engine. Falls back to Groq if OpenClaw is requested but unreachable.
        java.util.concurrent.CompletableFuture<String> future;
        if ("openclaw".equalsIgnoreCase(engine)) {
            if (openClaw.isGatewayUp()) {
                future = openClaw.processCommand(text, modelOverride, imageBase64, fileName, fileType);
            } else {
                System.err.println("[WebServer] OpenClaw selected but gateway unreachable — falling back to Groq.");
                JsonObject warn = new JsonObject();
                warn.addProperty("type", "system");
                warn.addProperty("text", "OpenClaw gateway is offline — using Groq for this turn.");
                broadcast(warn);
                future = groq.processCommand(text, modelOverride, imageBase64, fileName, fileType);
            }
        } else {
            future = groq.processCommand(text, modelOverride, imageBase64, fileName, fileType);
        }
        future.thenAccept(response -> {
            List<String[]> commands = extractCommands(response);
            String cleanResponse = CMD_PATTERN.matcher(response).replaceAll("").trim()
                    .replaceAll("\\s{2,}", " ").trim();

            // Check if AI wants to continue conversation
            boolean shouldContinue = commands.stream()
                    .anyMatch(cmd -> "CONTINUE_CONVERSATION".equals(cmd[0]));

            // Send commands to frontend (handle server-side commands first)
            for (String[] cmd : commands) {
                if ("CONTINUE_CONVERSATION".equals(cmd[0]))
                    continue;

                // Handle CREATE_PLAYLIST server-side
                if ("CREATE_PLAYLIST".equals(cmd[0]) && cmd[1] != null) {
                    String playlistName = cmd[1].trim();
                    JsonObject playlist = spotify.createPlaylist(playlistName, "", false);
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "CREATE_PLAYLIST");
                    if (playlist != null && playlist.has("id")) {
                        cmdMsg.addProperty("param", playlist.get("id").getAsString());
                        cmdMsg.addProperty("name", playlistName);
                    } else if (playlist != null && playlist.has("error")) {
                        cmdMsg.addProperty("error", playlist.get("error").getAsString());
                    }
                    broadcast(cmdMsg);
                    continue;
                }

                // Handle ADD_EVENT server-side — create calendar event
                if ("ADD_EVENT".equals(cmd[0]) && cmd[1] != null) {
                    String[] eventParts = cmd[1].split("\\|", 4);
                    String evTitle = eventParts.length > 0 ? eventParts[0].trim() : "Untitled";
                    String evDesc = eventParts.length > 1 ? eventParts[1].trim() : "";
                    String evStart = eventParts.length > 2 ? eventParts[2].trim() : "";
                    String evEnd = eventParts.length > 3 ? eventParts[3].trim() : evStart;

                    // Append local timezone offset if missing (AI sends 2026-03-25T10:00 without
                    // offset)
                    evStart = ensureTimezoneOffset(evStart);
                    evEnd = ensureTimezoneOffset(evEnd);

                    if (googleCalendar.isAuthenticated()) {
                        try {
                            googleCalendar.createEvent(evTitle, evDesc, evStart, evEnd).get();
                        } catch (Exception e) {
                            System.err.println("[WebServer] Failed to create Google event: " + e.getMessage());
                        }
                    } else {
                        // Save locally
                        ProfileManager.EventItem event = new ProfileManager.EventItem();
                        event.title = evTitle;
                        event.description = evDesc;
                        event.start = evStart;
                        event.end = evEnd;
                        event.allDay = evStart.length() <= 10;
                        ProfileManager.getInstance().addEvent(event);
                    }

                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "ADD_EVENT");
                    cmdMsg.addProperty("param", evTitle);
                    broadcast(cmdMsg);
                    continue;
                }

                // Handle AUTOMATE server-side — fire-and-forget
                if ("AUTOMATE".equals(cmd[0]) && cmd[1] != null) {
                    String automationName = cmd[1].trim();
                    automation.trigger(automationName, null).thenAccept(result -> {
                        JsonObject cmdMsg = new JsonObject();
                        cmdMsg.addProperty("type", "command");
                        cmdMsg.addProperty("action", "AUTOMATE");
                        cmdMsg.addProperty("param", automationName);
                        cmdMsg.addProperty("success", result.has("success") && result.get("success").getAsBoolean());
                        if (result.has("message"))
                            cmdMsg.addProperty("message", result.get("message").getAsString());
                        broadcast(cmdMsg);
                    });
                    continue;
                }

                // Handle ADD_TASK server-side — actually create the task + push to Google
                if ("ADD_TASK".equals(cmd[0]) && cmd[1] != null) {
                    String[] taskParts = cmd[1].split("\\|", 3);
                    String taskTitle = taskParts.length > 0 ? taskParts[0].trim() : "";
                    String taskDesc = taskParts.length > 1 ? taskParts[1].trim() : "";
                    String taskDate = taskParts.length > 2 ? taskParts[2].trim() : "";
                    if (!taskTitle.isEmpty()) {
                        ProfileManager.getInstance().addTask(taskTitle, taskDesc, taskDate);
                        ProfileManager.TaskItem added = ProfileManager.getInstance().findTaskAnywhere(taskTitle);
                        if (added != null) {
                            String listName = ProfileManager.getInstance().listNameForTask(added);
                            if (listName != null) pushTaskToGoogle(listName, added);
                        }
                    }
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "ADD_TASK");
                    cmdMsg.addProperty("param", taskTitle);
                    broadcast(cmdMsg);
                    continue;
                }

                // Handle REMOVE_TASK server-side — actually remove the task + delete on Google
                if ("REMOVE_TASK".equals(cmd[0]) && cmd[1] != null) {
                    String taskTitle = cmd[1].trim();
                    ProfileManager.TaskItem before = ProfileManager.getInstance().findTaskAnywhere(taskTitle);
                    if (before != null) {
                        String listName = ProfileManager.getInstance().listNameForTask(before);
                        if (listName != null) deleteTaskOnGoogle(listName, before);
                    }
                    ProfileManager.getInstance().removeTask(taskTitle);
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "REMOVE_TASK");
                    cmdMsg.addProperty("param", taskTitle);
                    broadcast(cmdMsg);
                    continue;
                }

                // Handle ADD_COMMITMENT server-side
                if ("ADD_COMMITMENT".equals(cmd[0]) && cmd[1] != null) {
                    String commitText = cmd[1].trim();
                    ProfileManager.getInstance().addCommitment(commitText);
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "ADD_COMMITMENT");
                    cmdMsg.addProperty("param", commitText);
                    broadcast(cmdMsg);
                    continue;
                }

                // Handle USE_CREDENTIAL server-side — send the password to the
                // requester's tab as an autofill_request. The AI never sees it.
                if ("USE_CREDENTIAL".equals(cmd[0]) && cmd[1] != null) {
                    String credName = cmd[1].trim();
                    JsonObject snap = vault.autofillSnapshot(credName);
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "USE_CREDENTIAL");
                    cmdMsg.addProperty("param", credName);
                    if (snap == null) {
                        cmdMsg.addProperty("error", "No credential named '" + credName + "' in the vault.");
                        broadcast(cmdMsg);
                    } else {
                        cmdMsg.addProperty("success", true);
                        broadcast(cmdMsg);
                        // Separately send the actual auto-fill payload (with the password).
                        // Broadcast so whichever logged-in device is in front of the user picks it up.
                        JsonObject autofill = new JsonObject();
                        autofill.addProperty("type", "autofill_request");
                        autofill.addProperty("name", snap.get("name").getAsString());
                        autofill.addProperty("url", snap.get("url").getAsString());
                        autofill.addProperty("username", snap.get("username").getAsString());
                        autofill.addProperty("password", snap.get("password").getAsString());
                        broadcast(autofill);
                    }
                    continue;
                }

                // Handle REMOVE_COMMITMENT server-side
                if ("REMOVE_COMMITMENT".equals(cmd[0]) && cmd[1] != null) {
                    String commitText = cmd[1].trim();
                    ProfileManager.getInstance().removeCommitment(commitText);
                    JsonObject cmdMsg = new JsonObject();
                    cmdMsg.addProperty("type", "command");
                    cmdMsg.addProperty("action", "REMOVE_COMMITMENT");
                    cmdMsg.addProperty("param", commitText);
                    broadcast(cmdMsg);
                    continue;
                }

                // VIEW_DEVICE — capture a screenshot from a named device, feed it into the
                // vision model in a follow-up turn. Frees the user from dragging an image in.
                if ("VIEW_DEVICE".equals(cmd[0]) && cmd[1] != null) {
                    String targetName = cmd[1].trim();
                    String targetSessionId = lookupDeviceSession(targetName);
                    if (targetSessionId == null) {
                        JsonObject warn = new JsonObject();
                        warn.addProperty("type", "system");
                        warn.addProperty("text", "Device '" + targetName + "' is not connected.");
                        broadcast(warn);
                        continue;
                    }
                    String requestId = java.util.UUID.randomUUID().toString();
                    JsonObject captureMsg = new JsonObject();
                    captureMsg.addProperty("type", "capture_screen");
                    captureMsg.addProperty("requestId", requestId);
                    // Empty requesterSessionId tells the device we're not expecting a relay
                    // back to a particular browser — we're consuming the frame server-side.
                    captureMsg.addProperty("requesterSessionId", "");

                    java.util.concurrent.CompletableFuture<String> frameFuture =
                            new java.util.concurrent.CompletableFuture<>();
                    pendingScreenCaptures.put(requestId, frameFuture);
                    sendToSession(targetSessionId, captureMsg);

                    final String capturedText = text;
                    final boolean capturedPlay = playOnServer;
                    final String capturedEngine = engine;
                    frameFuture.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .thenAccept(jpegBase64 -> {
                                String visionPrompt = "The user asked: \"" + capturedText
                                        + "\". Describe what you see on the screen of " + targetName + ".";
                                handleChat(visionPrompt, capturedPlay, "THINK", jpegBase64,
                                        "screen.jpg", "image/jpeg", capturedEngine);
                            })
                            .exceptionally(ex -> {
                                pendingScreenCaptures.remove(requestId);
                                JsonObject msg = new JsonObject();
                                msg.addProperty("type", "system");
                                msg.addProperty("text",
                                        "Screen capture from '" + targetName + "' "
                                                + (ex instanceof java.util.concurrent.CancellationException
                                                        ? "was declined."
                                                        : "timed out."));
                                broadcast(msg);
                                return null;
                            });
                    continue;
                }

                // REMOTE_OPEN:DeviceName|https://url — tell a connected device to open a URL.
                if ("REMOTE_OPEN".equals(cmd[0]) && cmd[1] != null) {
                    String[] parts = cmd[1].split("\\|", 2);
                    if (parts.length == 2) {
                        String targetDevice = parts[0].trim();
                        String url = parts[1].trim();
                        String targetId = lookupDeviceSession(targetDevice);
                        if (targetId == null) {
                            JsonObject warn = new JsonObject();
                            warn.addProperty("type", "system");
                            warn.addProperty("text", "Device '" + targetDevice + "' is not connected.");
                            broadcast(warn);
                            continue;
                        }
                        JsonObject m = new JsonObject();
                        m.addProperty("type", "remote_action");
                        m.addProperty("action", "OPEN_URL");
                        m.addProperty("url", url);
                        m.addProperty("fromDevice", "master");
                        forwardToSession(targetId, m);
                    }
                    continue;
                }

                // OS-level remote actions executed by the native helper on the target device.
                if (java.util.Arrays.asList("TYPE_TEXT", "LAUNCH_APP", "KEY_COMBO", "CLICK_AT")
                        .contains(cmd[0]) && cmd[1] != null) {
                    String[] parts = cmd[1].split("\\|", 2);
                    if (parts.length == 2) {
                        String targetDevice = parts[0].trim();
                        String payload = parts[1].trim();
                        String targetId = lookupHelperSession(targetDevice);
                        if (targetId == null) {
                            JsonObject warn = new JsonObject();
                            warn.addProperty("type", "system");
                            warn.addProperty("text", "Native helper for '" + targetDevice
                                    + "' is not connected.");
                            broadcast(warn);
                            continue;
                        }
                        JsonObject m = new JsonObject();
                        m.addProperty("type", "remote_action");
                        m.addProperty("action", cmd[0]);
                        m.addProperty("fromDevice", "master");
                        switch (cmd[0]) {
                            case "TYPE_TEXT":
                                m.addProperty("text", payload);
                                break;
                            case "LAUNCH_APP":
                                m.addProperty("bundleId", payload);
                                break;
                            case "KEY_COMBO": {
                                com.google.gson.JsonArray keys = new com.google.gson.JsonArray();
                                for (String k : payload.split("\\+")) {
                                    String t = k.trim();
                                    if (!t.isEmpty()) keys.add(t);
                                }
                                m.add("keys", keys);
                                break;
                            }
                            case "CLICK_AT": {
                                String[] xy = payload.split(",", 2);
                                if (xy.length == 2) {
                                    try {
                                        m.addProperty("x", Double.parseDouble(xy[0].trim()));
                                        m.addProperty("y", Double.parseDouble(xy[1].trim()));
                                    } catch (NumberFormatException nfe) {
                                        System.err.println("[WebServer] CLICK_AT payload not numeric: " + payload);
                                        continue;
                                    }
                                }
                                break;
                            }
                        }
                        forwardToHelper(targetId, m);
                    }
                    continue;
                }

                JsonObject cmdMsg = new JsonObject();
                cmdMsg.addProperty("type", "command");
                cmdMsg.addProperty("action", cmd[0]);
                if (cmd[1] != null)
                    cmdMsg.addProperty("param", cmd[1]);
                broadcast(cmdMsg);
            }

            // Mark response as active
            responseActive = true;

            // Generate TTS audio
            TextToSpeech tts = new TextToSpeech();
            TextToSpeech.AudioResult audioResult = tts.generateAudio(cleanResponse);

            // Calculate per-word delay
            String[] words = cleanResponse.split("\\s+");
            long msPerWord = 50;
            if (audioResult != null && words.length > 0) {
                msPerWord = Math.max(20, Math.min(200, audioResult.durationMs / words.length));
            }
            final long delay = msPerWord;

            // Stream text word-by-word
            // NOTE: This thread should NOT be stopped by the audio thread finishing.
            // Only manual "stop" should cancel it.
            Thread streamThread = new Thread(() -> {
                for (String word : words) {
                    JsonObject wordMsg = new JsonObject();
                    wordMsg.addProperty("type", "chat_word");
                    wordMsg.addProperty("word", word);
                    broadcast(wordMsg);
                    
                    if (responseActive) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            responseActive = false; // flush remainder instantly
                        }
                    }
                }
                JsonObject done = new JsonObject();
                done.addProperty("type", "chat_done");
                broadcast(done);
            });
            streamThread.setDaemon(true);
            activeStreamThread = streamThread;
            streamThread.start();

            // Play audio simultaneously
            if (audioResult != null) {
                // Read WAV file and broadcast it to web clients
                try {
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(audioResult.file.toPath());
                    String base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes);
                    JsonObject audioMsg = new JsonObject();
                    audioMsg.addProperty("type", "tts_audio");
                    audioMsg.addProperty("audioData", base64Audio);
                    broadcast(audioMsg);
                } catch (Exception e) {
                    System.err.println("[WebServer] Failed to encode audio for web clients.");
                }

                Thread audioThread = new Thread(() -> {
                    if (playOnServer) {
                        if (listener != null)
                            listener.setTtsSpeaking(true);
                        try {
                            tts.playFile(audioResult.file);
                        } finally {
                            if (listener != null)
                                listener.setTtsSpeaking(false);
                        }
                    } else {
                        // Web client is playing the audio, just wait for duration
                        try {
                            Thread.sleep(audioResult.durationMs);
                        } catch (InterruptedException ignored) {}
                    }

                    // Wait for stream thread to finish (don't cut it off)
                    try {
                        streamThread.join(30000); // wait up to 30s
                    } catch (InterruptedException ignored) {
                    }

                    boolean wasStopped = !responseActive;
                    responseActive = false;

                    if (shouldContinue && !wasStopped && listener != null) {
                        try {
                            Thread.sleep(playOnServer ? 500 : 1500);
                        } catch (InterruptedException ignored) {
                        }
                        if (playOnServer) {
                            System.out.println("[WebServer] AI wants to continue — activating server mic");
                            listener.startListening();
                        } else {
                            System.out.println("[WebServer] AI wants to continue — prompting web UI");
                            JsonObject recMsg = new JsonObject();
                            recMsg.addProperty("type", "web_record");
                            broadcast(recMsg);
                        }
                    }
                });
                audioThread.setDaemon(true);
                activeAudioThread = audioThread;
                audioThread.start();
            } else {
                // No audio — wait for stream to finish, then continue
                Thread contThread = new Thread(() -> {
                    try {
                        streamThread.join(30000);
                    } catch (InterruptedException ignored) {
                    }
                    responseActive = false;
                    if (shouldContinue && listener != null) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        listener.startListening();
                    }
                });
                contThread.setDaemon(true);
                contThread.start();
            }
        });
    }

    private void stopResponse() {
        responseActive = false;
        TextToSpeech.stopPlayback();
        Thread st = activeStreamThread;
        if (st != null)
            st.interrupt();
        if (listener != null) {
            listener.cancelTranscription();
            listener.setTtsSpeaking(false); // ensure TTS flag is cleared
        }
        // Notify frontend that recording/listening stopped
        JsonObject stateMsg = new JsonObject();
        stateMsg.addProperty("type", "voice_state");
        stateMsg.addProperty("listening", false);
        broadcast(stateMsg);
        System.out.println("[WebServer] Response stopped.");
    }

    private List<String[]> extractCommands(String response) {
        List<String[]> commands = new ArrayList<>();
        Matcher matcher = CMD_PATTERN.matcher(response);
        while (matcher.find()) {
            commands.add(new String[] { matcher.group(1), matcher.group(2) });
        }
        return commands;
    }

    /**
     * Appends the system's local timezone offset to a datetime string if it's
     * missing.
     * e.g. "2026-03-25T10:00" → "2026-03-25T10:00:00-07:00"
     */
    private String ensureTimezoneOffset(String dt) {
        if (dt == null || dt.isEmpty() || dt.length() <= 10)
            return dt; // date-only or empty
        // Already has offset like +HH:MM, -HH:MM, or Z
        if (dt.matches(".*[+-]\\d{2}:\\d{2}$") || dt.endsWith("Z"))
            return dt;
        // Append seconds if missing (2026-03-25T10:00 → 2026-03-25T10:00:00)
        if (dt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")) {
            dt = dt + ":00";
        }
        // Append local timezone offset
        String offset = ZonedDateTime.now().getOffset().toString(); // e.g. "-07:00"
        return dt + offset;
    }

    // ── REST Endpoints ──────────────────────────────────

    private void setupRestEndpoints() {
        // Get profile
        app.get("/api/profile", ctx -> {
            ctx.json(ProfileManager.getInstance().getUserProfile());
        });

        // Update profile field
        app.put("/api/profile", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            ProfileManager pm = ProfileManager.getInstance();

            if (body.has("username"))
                pm.setUsername(body.get("username").getAsString());

            ctx.json(pm.getUserProfile());
        });

        // ── Task Lists CRUD ────────────────────────────────

        // Get all task lists
        app.get("/api/tasklists", ctx -> {
            ctx.json(ProfileManager.getInstance().getTaskLists());
        });

        // Create a new task list
        app.post("/api/tasklists", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = body.has("name") ? body.get("name").getAsString().trim() : "";
            if (name.isEmpty()) {
                ctx.status(400).result("{\"error\":\"List name required\"}");
                return;
            }
            ProfileManager.getInstance().addTaskList(name);
            ctx.json(ProfileManager.getInstance().getTaskLists());
        });

        // Delete a task list
        app.delete("/api/tasklists/{name}", ctx -> {
            String name = ctx.pathParam("name");
            ProfileManager.getInstance().removeTaskList(name);
            ctx.json(ProfileManager.getInstance().getTaskLists());
        });

        // Add task to a specific list
        app.post("/api/tasklists/{name}/tasks", ctx -> {
            String listName = ctx.pathParam("name");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String title = body.has("title") ? body.get("title").getAsString().trim() : "";
            String desc = body.has("description") ? body.get("description").getAsString().trim() : "";
            String date = body.has("dueDate") ? body.get("dueDate").getAsString().trim() : "";

            if (!date.isEmpty()) {
                try {
                    LocalDate.parse(date);
                } catch (DateTimeParseException e) {
                    ctx.status(400).result("Invalid date format. Use YYYY-MM-DD.");
                    return;
                }
            }

            if (!title.isEmpty()) {
                ProfileManager.getInstance().addTaskToList(listName, title, desc, date);
                ProfileManager.TaskItem added = ProfileManager.getInstance().findTask(listName, title);
                if (added != null) pushTaskToGoogle(listName, added);
                ctx.json(ProfileManager.getInstance().getTaskLists());
            } else {
                ctx.status(400).result("Title required.");
            }
        });

        // Remove task from a specific list
        app.delete("/api/tasklists/{name}/tasks/{title}", ctx -> {
            String listName = ctx.pathParam("name");
            String title = ctx.pathParam("title");
            ProfileManager.TaskItem before = ProfileManager.getInstance().findTask(listName, title);
            if (before != null) deleteTaskOnGoogle(listName, before);
            ProfileManager.getInstance().removeTaskFromList(listName, title);
            ctx.json(ProfileManager.getInstance().getTaskLists());
        });

        // Legacy flat tasks (backward compat for AI commands)
        app.get("/api/tasks", ctx -> {
            ctx.json(ProfileManager.getInstance().getAllTasks());
        });

        app.post("/api/tasks", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String title = body.has("title") ? body.get("title").getAsString().trim() : "";
            String desc = body.has("description") ? body.get("description").getAsString().trim() : "";
            String date = body.has("dueDate") ? body.get("dueDate").getAsString().trim() : "";
            if (!title.isEmpty()) {
                ProfileManager.getInstance().addTask(title, desc, date);
                ProfileManager.TaskItem added = ProfileManager.getInstance().findTaskAnywhere(title);
                if (added != null) {
                    String listName = ProfileManager.getInstance().listNameForTask(added);
                    if (listName != null) pushTaskToGoogle(listName, added);
                }
                ctx.json(ProfileManager.getInstance().getAllTasks());
            } else {
                ctx.status(400).result("Title required.");
            }
        });

        app.delete("/api/tasks/{title}", ctx -> {
            String title = ctx.pathParam("title");
            ProfileManager.TaskItem before = ProfileManager.getInstance().findTaskAnywhere(title);
            if (before != null) {
                String listName = ProfileManager.getInstance().listNameForTask(before);
                if (listName != null) deleteTaskOnGoogle(listName, before);
            }
            ProfileManager.getInstance().removeTask(title);
            ctx.json(ProfileManager.getInstance().getAllTasks());
        });

        // Commitments CRUD
        app.get("/api/commitments", ctx -> {
            ctx.json(ProfileManager.getInstance().getUserProfile().commitments);
        });

        app.post("/api/commitments", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String text = body.has("text") ? body.get("text").getAsString().trim() : "";
            if (!text.isEmpty()) {
                ProfileManager.getInstance().addCommitment(text);
                ctx.json(ProfileManager.getInstance().getUserProfile().commitments);
            } else {
                ctx.status(400).result("Text required.");
            }
        });

        app.delete("/api/commitments/{name}", ctx -> {
            String name = ctx.pathParam("name");
            ProfileManager.getInstance().removeCommitment(name);
            ctx.json(ProfileManager.getInstance().getUserProfile().commitments);
        });

        // ── Spotify Endpoints ──────────────────────────────

        // Auth: redirect to Spotify login
        app.get("/api/spotify/auth", ctx -> {
            String url = spotify.getAuthorizationUrl();
            ctx.redirect(url);
        });

        // OAuth callback
        app.get("/api/spotify/callback", ctx -> {
            String code = ctx.queryParam("code");
            String error = ctx.queryParam("error");
            if (error != null) {
                ctx.html(
                        "<html><body style='background:#0d0d0d;color:#f87171;font-family:monospace;display:flex;align-items:center;justify-content:center;height:100vh'><h2>Spotify authorization denied.</h2></body></html>");
                return;
            }
            if (code != null && spotify.handleCallback(code)) {
                ctx.html(
                        "<html><body style='background:#0d0d0d;color:#4ade80;font-family:monospace;display:flex;align-items:center;justify-content:center;height:100vh'><h2>✓ Spotify connected! You can close this tab.</h2><script>setTimeout(()=>window.close(),2000)</script></body></html>");
            } else {
                ctx.html(
                        "<html><body style='background:#0d0d0d;color:#f87171;font-family:monospace;display:flex;align-items:center;justify-content:center;height:100vh'><h2>Spotify auth failed. Try again.</h2></body></html>");
            }
        });

        // Auth status — actually probes /v1/me, not just a flag check.
        app.get("/api/spotify/status", ctx -> {
            ctx.result(spotify.getAuthHealth().toString());
            ctx.contentType("application/json");
        });

        // Access token for Web Playback SDK
        app.get("/api/spotify/token", ctx -> {
            JsonObject token = new JsonObject();
            if (spotify.isAuthenticated()) {
                token.addProperty("token", spotify.getAccessToken());
            } else {
                token.addProperty("token", "");
            }
            ctx.json(token);
        });

        // User playlists
        app.get("/api/spotify/playlists", ctx -> {
            ctx.result(spotify.getUserPlaylists().toString());
            ctx.contentType("application/json");
        });

        // Featured/curated playlists
        app.get("/api/spotify/featured", ctx -> {
            ctx.result(spotify.getFeaturedPlaylists().toString());
            ctx.contentType("application/json");
        });

        // Playlist tracks
        app.get("/api/spotify/playlist/{id}/tracks", ctx -> {
            String id = ctx.pathParam("id");
            ctx.result(spotify.getPlaylistTracks(id).toString());
            ctx.contentType("application/json");
        });

        // Saved (Liked) tracks — not restricted by Spotify dev-mode policy
        app.get("/api/spotify/saved", ctx -> {
            ctx.result(spotify.getSavedTracks().toString());
            ctx.contentType("application/json");
        });

        // Recently played — not restricted by Spotify dev-mode policy
        app.get("/api/spotify/recent", ctx -> {
            ctx.result(spotify.getRecentlyPlayed().toString());
            ctx.contentType("application/json");
        });

        // Probe whether the Spotify app has extended quota (vs. dev-mode lock on playlist tracks).
        app.get("/api/spotify/access", ctx -> {
            ctx.result(spotify.probeExtendedQuota().toString());
            ctx.contentType("application/json");
        });

        // Search
        app.get("/api/spotify/search", ctx -> {
            String q = ctx.queryParam("q");
            if (q == null || q.isEmpty()) {
                ctx.status(400).result("Query parameter 'q' required.");
                return;
            }
            ctx.result(spotify.searchTracks(q).toString());
            ctx.contentType("application/json");
        });

        // Play
        app.post("/api/spotify/play", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String uri = body.has("uri") ? body.get("uri").getAsString() : "";
            String deviceId = body.has("device_id") ? body.get("device_id").getAsString() : null;
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.play(uri, deviceId));
            ctx.json(result);
        });

        // Pause
        app.post("/api/spotify/pause", ctx -> {
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.pause());
            ctx.json(result);
        });

        // Resume
        app.post("/api/spotify/resume", ctx -> {
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.resume());
            ctx.json(result);
        });

        // Skip next
        app.post("/api/spotify/skip", ctx -> {
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.skipNext());
            ctx.json(result);
        });

        // Previous
        app.post("/api/spotify/previous", ctx -> {
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.skipPrevious());
            ctx.json(result);
        });

        // Playback state
        app.get("/api/spotify/playback", ctx -> {
            ctx.result(spotify.getPlaybackState().toString());
            ctx.contentType("application/json");
        });

        // AI DJ
        app.post("/api/spotify/ai-dj", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String mood = body.has("mood") ? body.get("mood").getAsString() : "chill vibes";
            String deviceId = body.has("device_id") ? body.get("device_id").getAsString() : null;
            try {
                JsonObject djResult = spotify.aiDjPick(mood, groq).get();
                // Auto-play if we got tracks and a device
                if (djResult.has("uris") && djResult.get("uris").getAsJsonArray().size() > 0) {
                    java.util.List<String> uris = new java.util.ArrayList<>();
                    djResult.get("uris").getAsJsonArray().forEach(u -> uris.add(u.getAsString()));
                    spotify.playTracks(uris, deviceId);
                }
                ctx.result(djResult.toString());
                ctx.contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Available devices
        app.get("/api/spotify/devices", ctx -> {
            ctx.result(spotify.getAvailableDevices().toString());
            ctx.contentType("application/json");
        });

        // Transfer playback to device
        app.post("/api/spotify/transfer", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String deviceId = body.has("device_id") ? body.get("device_id").getAsString() : "";
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.transferPlayback(deviceId));
            ctx.json(result);
        });

        // Create playlist
        app.post("/api/spotify/playlist/create", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = body.has("name") ? body.get("name").getAsString() : "";
            String description = body.has("description") ? body.get("description").getAsString() : "";
            boolean isPublic = body.has("public") ? body.get("public").getAsBoolean() : false;
            if (name.isEmpty()) {
                ctx.status(400).result("{\"error\":\"Playlist name required\"}");
                return;
            }
            JsonObject playlist = spotify.createPlaylist(name, description, isPublic);
            if (playlist != null && playlist.has("id")) {
                ctx.result(playlist.toString());
                ctx.contentType("application/json");
            } else if (playlist != null && playlist.has("error")) {
                int statusCode = playlist.has("statusCode") ? playlist.get("statusCode").getAsInt() : 500;
                ctx.status(statusCode).result(playlist.toString());
            } else {
                ctx.status(500).result("{\"error\":\"Failed to create playlist\"}");
            }
        });

        // Add tracks to playlist
        app.post("/api/spotify/playlist/{id}/tracks", ctx -> {
            String id = ctx.pathParam("id");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            java.util.List<String> uris = new java.util.ArrayList<>();
            if (body.has("uris")) {
                body.get("uris").getAsJsonArray().forEach(u -> uris.add(u.getAsString()));
            }
            if (uris.isEmpty()) {
                ctx.status(400).result("{\"error\":\"No track URIs provided\"}");
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("success", spotify.addTracksToPlaylist(id, uris));
            ctx.json(result);
        });

        // ── Automation Endpoints ──────────────────────────────

        // List configured automations
        app.get("/api/automation", ctx -> {
            ctx.result(automation.listAutomations().toString());
            ctx.contentType("application/json");
        });

        // Register a new webhook at runtime
        app.post("/api/automation/register", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = body.has("name") ? body.get("name").getAsString() : "";
            String url = body.has("url") ? body.get("url").getAsString() : "";
            if (name.isEmpty() || url.isEmpty()) {
                ctx.status(400).result("{\"error\":\"'name' and 'url' are required\"}");
                return;
            }
            automation.registerWebhook(name, url);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Registered automation: " + name);
            ctx.json(result);
        });

        // Trigger an automation by name
        app.post("/api/automation/trigger", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = body.has("name") ? body.get("name").getAsString() : "";
            if (name.isEmpty()) {
                ctx.status(400).result("{\"error\":\"'name' is required\"}");
                return;
            }
            JsonObject params = body.has("params") ? body.getAsJsonObject("params") : null;
            try {
                JsonObject result = automation.trigger(name, params).get();
                int status = result.has("success") && result.get("success").getAsBoolean() ? 200 : 500;
                ctx.status(status).result(result.toString());
                ctx.contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // ── Google Tasks Endpoints ────────────────────────────

        // Auth status — actually probes Google Tasks, not just a flag.
        app.get("/api/tasks/google/status", ctx -> {
            ctx.result(googleTasks.getAuthHealth().toString());
            ctx.contentType("application/json");
        });

        // Start OAuth flow — redirect user to Google
        app.get("/api/tasks/google/auth", ctx -> {
            if (!googleTasks.isConfigured()) {
                ctx.html("<html><body style='font-family:monospace'>" +
                        "<h2>Google Tasks not configured</h2>" +
                        "<p>Add <code>GOOGLE_CLIENT_ID</code> and <code>GOOGLE_CLIENT_SECRET</code> " +
                        "to your <code>.env</code> file and restart.</p></body></html>");
                return;
            }
            ctx.redirect(googleTasks.getAuthorizationUrl());
        });

        // OAuth callback
        app.get("/api/tasks/google/callback", ctx -> {
            String code = ctx.queryParam("code");
            String error = ctx.queryParam("error");
            if (error != null) {
                ctx.html("<html><body style='background:#0d0d0d;color:#f87171;font-family:monospace;" +
                        "display:flex;align-items:center;justify-content:center;height:100vh'>" +
                        "<h2>Google authorization denied.</h2></body></html>");
                return;
            }
            if (code != null && googleTasks.handleCallback(code)) {
                // The Tasks + Calendar services share the same token file. Tell Calendar
                // to re-read it so we don't end up with a stale refresh_token on its side.
                googleCalendar.refreshFromDisk();
                ctx.html("<html><body style='background:#0d0d0d;color:#4ade80;font-family:monospace;" +
                        "display:flex;align-items:center;justify-content:center;height:100vh'>" +
                        "<h2>✓ Google connected (Tasks + Calendar). You can close this tab.</h2>" +
                        "<script>setTimeout(()=>window.close(),2000)</script></body></html>");
            } else {
                ctx.html("<html><body style='background:#0d0d0d;color:#f87171;font-family:monospace;" +
                        "display:flex;align-items:center;justify-content:center;height:100vh'>" +
                        "<h2>Google Tasks auth failed. Try again.</h2></body></html>");
            }
        });

        // Push local tasks → Google Tasks
        app.post("/api/tasks/google/push", ctx -> {
            try {
                JsonObject result = googleTasks.pushTasks().get();
                int status = result.has("error") ? 500 : 200;
                ctx.status(status).result(result.toString());
                ctx.contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Pull Google Tasks → local tasks
        app.post("/api/tasks/google/pull", ctx -> {
            try {
                JsonObject result = googleTasks.pullTasks().get();
                int status = result.has("error") ? 500 : 200;
                ctx.status(status).result(result.toString());
                ctx.contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // ── Google Calendar Endpoints ─────────────────────────

        // Refresh authentication file (shared with Tasks)
        app.post("/api/calendar/refresh-auth", ctx -> {
            googleCalendar.refreshFromDisk();
            ctx.status(200).result("{\"success\":true}");
        });

        // Auth status — actually probes Google Calendar, not just a flag.
        app.get("/api/calendar/status", ctx -> {
            ctx.result(googleCalendar.getAuthHealth().toString());
            ctx.contentType("application/json");
        });

        // List events (local fallback if unauthenticated)
        app.get("/api/calendar/events", ctx -> {
            String daysParam = ctx.queryParam("days");
            int days = (daysParam != null) ? Integer.parseInt(daysParam) : 30;

            if (googleCalendar.isAuthenticated()) {
                try {
                    JsonObject result = googleCalendar.listEvents(days).get();
                    if (result.has("error")) {
                        ctx.status(500).result(result.toString());
                        ctx.contentType("application/json");
                        return;
                    }
                    // Attach locally managed events as well
                    com.google.gson.JsonArray eventsList = result.has("events") ? result.getAsJsonArray("events")
                            : new com.google.gson.JsonArray();
                    for (ProfileManager.EventItem e : ProfileManager.getInstance().getUserProfile().events) {
                        JsonObject localEv = new JsonObject();
                        localEv.addProperty("id", e.id);
                        localEv.addProperty("title", e.title);
                        localEv.addProperty("description", e.description);
                        localEv.addProperty("start", e.start);
                        localEv.addProperty("end", e.end);
                        localEv.addProperty("allDay", e.allDay);
                        localEv.addProperty("isLocal", true);
                        eventsList.add(localEv);
                    }
                    result.add("events", eventsList);
                    result.addProperty("count", eventsList.size());
                    ctx.result(result.toString());
                    ctx.contentType("application/json");
                } catch (Exception e) {
                    ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                // Fallback to local
                JsonObject result = new JsonObject();
                com.google.gson.JsonArray eventsList = new com.google.gson.JsonArray();
                for (ProfileManager.EventItem e : ProfileManager.getInstance().getUserProfile().events) {
                    JsonObject localEv = new JsonObject();
                    localEv.addProperty("id", e.id);
                    localEv.addProperty("title", e.title);
                    localEv.addProperty("description", e.description);
                    localEv.addProperty("start", e.start);
                    localEv.addProperty("end", e.end);
                    localEv.addProperty("allDay", e.allDay);
                    localEv.addProperty("isLocal", true);
                    eventsList.add(localEv);
                }
                result.add("events", eventsList);
                result.addProperty("count", eventsList.size());
                ctx.result(result.toString());
                ctx.contentType("application/json");
            }
        });

        // Add event (local fallback if unauthenticated)
        app.post("/api/calendar/events", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String title = body.has("title") ? body.get("title").getAsString() : "Untitled";
            String desc = body.has("description") ? body.get("description").getAsString() : "";
            String start = body.has("start") ? body.get("start").getAsString() : "";
            String end = body.has("end") ? body.get("end").getAsString() : start;

            // Ensure timezone offset is present
            start = ensureTimezoneOffset(start);
            end = ensureTimezoneOffset(end);

            if (googleCalendar.isAuthenticated()) {
                try {
                    JsonObject result = googleCalendar.createEvent(title, desc, start, end).get();
                    int status = result.has("error") ? 500 : 200;
                    ctx.status(status).result(result.toString());
                    ctx.contentType("application/json");
                } catch (Exception e) {
                    ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                ProfileManager.EventItem event = new ProfileManager.EventItem();
                event.title = title;
                event.description = desc;
                event.start = start;
                event.end = end;
                event.allDay = start.length() <= 10;
                ProfileManager.getInstance().addEvent(event);
                ctx.status(200).result("{\"success\":true, \"message\":\"Saved locally\"}");
            }
        });

        // Delete event
        app.delete("/api/calendar/events/{id}", ctx -> {
            String id = ctx.pathParam("id");
            if (googleCalendar.isAuthenticated()) {
                // Let's check if it exists in local first
                boolean isLocal = ProfileManager.getInstance().getUserProfile().events.stream()
                        .anyMatch(e -> e.id.equals(id));
                if (isLocal) {
                    ProfileManager.getInstance().removeEvent(id);
                    ctx.status(200).result("{\"success\":true}");
                    return;
                }
                try {
                    JsonObject result = googleCalendar.deleteEvent(id).get();
                    int status = result.has("error") ? 500 : 200;
                    ctx.status(status).result(result.toString());
                    ctx.contentType("application/json");
                } catch (Exception e) {
                    ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                ProfileManager.getInstance().removeEvent(id);
                ctx.status(200).result("{\"success\":true}");
            }
        });

        // ── OpenClaw Endpoints ─────────────────────────────────

        // Status: is the binary installed, is the gateway reachable, where is the config?
        app.get("/api/openclaw/status", ctx -> {
            JsonObject status = openClaw.getConnectionStatus();
            status.addProperty("installed", openClawConfig.isInstalled());
            status.addProperty("gatewayUp", openClaw.isGatewayUp());
            status.addProperty("configPath", openClawConfig.configPath().toString());
            String bin = openClawConfig.findOpenClawBinary();
            if (bin != null) status.addProperty("binary", bin);
            ctx.json(status);
        });

        // GET current remote-gateway configuration (token masked).
        app.get("/api/openclaw/connection", ctx -> {
            ctx.result(openClaw.getConnectionStatus().toString());
            ctx.contentType("application/json");
        });

        // POST to update local vs remote mode + remote credentials.
        app.post("/api/openclaw/connection", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            boolean useRemote = body.has("useRemote") && body.get("useRemote").getAsBoolean();
            String remoteUrl = getStr(body, "remoteBaseUrl", "");
            String remoteToken = getStr(body, "remoteAuthToken", "");
            openClaw.setRemoteGateway(remoteUrl, remoteToken);
            openClaw.setUseRemote(useRemote);
            JsonObject result = openClaw.getConnectionStatus();
            result.addProperty("success", true);
            result.addProperty("gatewayUp", openClaw.isGatewayUp());
            ctx.result(result.toString());
            ctx.contentType("application/json");
        });

        // Read the current OpenClaw config (sanitized: API keys masked).
        app.get("/api/openclaw/config", ctx -> {
            JsonObject cfg = openClawConfig.loadConfig();
            // Mask any value under "secrets"
            if (cfg.has("secrets") && cfg.get("secrets").isJsonObject()) {
                JsonObject masked = new JsonObject();
                JsonObject secrets = cfg.getAsJsonObject("secrets");
                for (String k : secrets.keySet()) masked.addProperty(k, "••••••");
                cfg.add("secrets", masked);
            }
            ctx.result(cfg.toString());
            ctx.contentType("application/json");
        });

        // Set provider + key + model, write the config, restart the gateway.
        app.post("/api/openclaw/provider", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String provider = getStr(body, "provider", "anthropic");
            String apiKey   = getStr(body, "apiKey", "");
            String model    = getStr(body, "model", "");
            String baseUrl  = getStr(body, "baseUrl", "");
            String apiType  = getStr(body, "apiType", "openai-completions");
            String gwToken  = getStr(body, "gatewayToken", "");

            try {
                openClawConfig.applyProvider(provider, apiKey, model, baseUrl, apiType, gwToken);
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"Failed to write config: " + e.getMessage().replace("\"", "'") + "\"}");
                ctx.contentType("application/json");
                return;
            }

            // Update the running OpenClawService too (so the next chat uses the right model + token)
            openClaw.setAuthToken(gwToken);
            if (!model.isBlank()) {
                String fq = model.contains("/") ? model : provider + "/" + model;
                openClaw.setDefaultModel(fq);
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", true);

            if (openClawConfig.isInstalled()) {
                String restart = openClawConfig.restartGateway();
                result.addProperty("restart", restart);
                result.addProperty("message", "Config saved. " + restart);
            } else {
                result.addProperty("message", "Config saved. OpenClaw binary not found — install it, then start the gateway.");
            }

            ctx.result(result.toString());
            ctx.contentType("application/json");
        });

        // Set Composio consumer key + enabled toolkits.
        app.post("/api/openclaw/composio", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String key = getStr(body, "consumerKey", "");
            java.util.List<String> enabled = new java.util.ArrayList<>();
            if (body.has("enabled") && body.get("enabled").isJsonArray()) {
                for (com.google.gson.JsonElement e : body.getAsJsonArray("enabled")) {
                    enabled.add(e.getAsString());
                }
            }
            try {
                openClawConfig.applyComposio(key, enabled);
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"Failed to write config: " + e.getMessage().replace("\"", "'") + "\"}");
                ctx.contentType("application/json");
                return;
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            if (openClawConfig.isInstalled()) {
                String restart = openClawConfig.restartGateway();
                result.addProperty("restart", restart);
                result.addProperty("message", "Composio config saved. " + restart);
            } else {
                result.addProperty("message", "Composio config saved. Install OpenClaw to activate.");
            }
            ctx.result(result.toString());
            ctx.contentType("application/json");
        });

        // One-shot probe: send a tiny prompt through the gateway and return what comes back.
        app.post("/api/openclaw/test", ctx -> {
            if (!openClaw.isGatewayUp()) {
                ctx.status(503).result("{\"error\":\"OpenClaw gateway is not reachable at " + openClaw.getBaseUrl() + "\"}");
                ctx.contentType("application/json");
                return;
            }
            String reply = openClaw.testChat();
            JsonObject result = new JsonObject();
            if (reply.startsWith("Error:") || reply.startsWith("Sorry,")) {
                result.addProperty("error", reply);
                ctx.status(502);
            } else {
                result.addProperty("success", true);
                result.addProperty("reply", reply);
            }
            ctx.result(result.toString());
            ctx.contentType("application/json");
        });

        // ── Devices (browser tabs / clients) ───────────────────
        app.get("/api/devices", ctx -> {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (JsonObject m : deviceMeta.values()) arr.add(m);
            ctx.json(arr);
        });

        // ── Credential vault (env vars + service logins) ───────
        // Every mutation broadcasts {type:"vault_updated"} so every connected
        // device knows to refetch — that's how "sync across instances" works.
        app.get("/api/vault/env", ctx -> {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (JsonObject o : vault.listEnvVars()) arr.add(o);
            ctx.json(arr);
        });
        app.post("/api/vault/env", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = getStr(body, "name", "").trim();
            String value = getStr(body, "value", "");
            if (name.isEmpty()) { ctx.status(400).result("{\"error\":\"'name' required\"}"); return; }
            vault.setEnvVar(name, value);
            broadcastVaultUpdated();
            ctx.status(200).result("{\"success\":true}");
        });
        app.delete("/api/vault/env/{name}", ctx -> {
            boolean removed = vault.removeEnvVar(ctx.pathParam("name"));
            if (removed) broadcastVaultUpdated();
            ctx.status(removed ? 200 : 404).result("{\"success\":" + removed + "}");
        });

        app.get("/api/vault/credentials", ctx -> {
            ctx.result(vault.listCredentialsMasked().toString());
            ctx.contentType("application/json");
        });
        app.post("/api/vault/credentials", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = getStr(body, "name", "").trim();
            if (name.isEmpty()) { ctx.status(400).result("{\"error\":\"'name' required\"}"); return; }
            // Pass null for password when the field is absent so existing password is preserved.
            String url = getStr(body, "url", "");
            String username = getStr(body, "username", "");
            String notes = getStr(body, "notes", "");
            String password = body.has("password") && !body.get("password").isJsonNull()
                    ? body.get("password").getAsString() : null;
            vault.setCredential(name, url, username, password, notes);
            broadcastVaultUpdated();
            ctx.status(200).result("{\"success\":true}");
        });
        app.delete("/api/vault/credentials/{name}", ctx -> {
            boolean removed = vault.removeCredential(ctx.pathParam("name"));
            if (removed) broadcastVaultUpdated();
            ctx.status(removed ? 200 : 404).result("{\"success\":" + removed + "}");
        });

        // Server-side "use credential": never returns the password as text to
        // the caller. Sends an autofill_request message to a chosen device.
        app.post("/api/vault/use", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = getStr(body, "name", "");
            String targetSession = getStr(body, "targetSessionId", "");
            JsonObject snap = vault.autofillSnapshot(name);
            if (snap == null) { ctx.status(404).result("{\"error\":\"unknown credential\"}"); return; }
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "autofill_request");
            msg.addProperty("name", snap.get("name").getAsString());
            msg.addProperty("url", snap.get("url").getAsString());
            msg.addProperty("username", snap.get("username").getAsString());
            msg.addProperty("password", snap.get("password").getAsString());
            if (!targetSession.isEmpty()) sendToSession(targetSession, msg);
            else broadcast(msg);
            ctx.status(200).result("{\"success\":true}");
        });

        // ── Tailscale (Funnel) Endpoints ───────────────────────
        app.get("/api/tailscale/status", ctx -> {
            ctx.result(tailscale.status(PORT).toString());
            ctx.contentType("application/json");
        });

        app.post("/api/tailscale/funnel", ctx -> {
            JsonObject body = ctx.body() == null || ctx.body().isBlank()
                    ? new JsonObject()
                    : gson.fromJson(ctx.body(), JsonObject.class);
            boolean enable = body.has("enable") && body.get("enable").getAsBoolean();
            JsonObject result = enable ? tailscale.enableFunnel(PORT) : tailscale.disableFunnel();
            int status = result.has("error") ? 500 : 200;
            ctx.status(status).result(result.toString());
            ctx.contentType("application/json");
        });

        // Update event
        app.put("/api/calendar/events/{id}", ctx -> {
            String id = ctx.pathParam("id");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String title = body.has("title") ? body.get("title").getAsString() : null;
            String desc = body.has("description") ? body.get("description").getAsString() : null;
            String start = body.has("start") ? body.get("start").getAsString() : null;
            String end = body.has("end") ? body.get("end").getAsString() : start;

            // In local list?
            ProfileManager.EventItem ev = ProfileManager.getInstance().getUserProfile().events.stream()
                    .filter(e -> e.id.equals(id)).findFirst().orElse(null);

            if (ev != null) {
                if (title != null)
                    ev.title = title;
                if (desc != null)
                    ev.description = desc;
                if (start != null) {
                    ev.start = start;
                    ev.allDay = start.length() <= 10;
                }
                if (end != null)
                    ev.end = end;
                ProfileManager.getInstance().saveProfile();
                ctx.status(200).result("{\"success\":true}");
            } else if (googleCalendar.isAuthenticated()) {
                try {
                    JsonObject result = googleCalendar.updateEvent(id, title, desc, start, end).get();
                    int status = result.has("error") ? 500 : 200;
                    ctx.status(status).result(result.toString());
                    ctx.contentType("application/json");
                } catch (Exception e) {
                    ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                ctx.status(404).result("{\"error\":\"Event not found\"}");
            }
        });
    }

    // ── Listener (Voice) ────────────────────────────────

    private void initListener() {
        try {
            listener = new Listener();
            listener.setCallback(new Listener.ListenerCallback() {
                @Override
                public void onPartialResult(String text) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "voice_partial");
                    msg.addProperty("text", text);
                    broadcast(msg);
                }

                @Override
                public void onFinalResult(String text) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "voice_final");
                    msg.addProperty("text", text);
                    broadcast(msg);
                    // Auto-send as chat
                    handleChat(text);
                }

                @Override
                public void onListeningStateChanged(boolean listening) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "voice_state");
                    msg.addProperty("listening", listening);
                    broadcast(msg);
                }
            });
            listener.start();
            System.out.println("[WebServer] Listener started.");
        } catch (Throwable e) {
            System.err.println("[WebServer] Could not start Listener: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Read OpenClaw's config at startup so chats use the saved provider/model/token. */
    private void hydrateOpenClawFromConfig() {
        try {
            JsonObject cfg = openClawConfig.loadConfig();
            if (cfg.has("gateway") && cfg.get("gateway").isJsonObject()) {
                JsonObject gw = cfg.getAsJsonObject("gateway");
                if (gw.has("defaultModel")) openClaw.setDefaultModel(gw.get("defaultModel").getAsString());
                if (gw.has("auth") && gw.get("auth").isJsonObject()) {
                    JsonObject auth = gw.getAsJsonObject("auth");
                    if (auth.has("token")) openClaw.setAuthToken(auth.get("token").getAsString());
                }
                if (gw.has("port")) {
                    int port = gw.get("port").getAsInt();
                    openClaw.setBaseUrl("http://127.0.0.1:" + port);
                }
            }
        } catch (Exception e) {
            System.err.println("[WebServer] Could not hydrate OpenClaw config: " + e.getMessage());
        }
    }

    /**
     * Fire-and-forget single-task push to Google. No-ops when Google Tasks is
     * not authenticated. Mutates the local TaskItem on success to record IDs.
     */
    private void pushTaskToGoogle(String listName, ProfileManager.TaskItem task) {
        if (!googleTasks.isAuthenticated() || task == null || task.title == null || task.title.isBlank()) return;
        googleTasks.pushSingleTask(listName, task).thenAccept(ok -> {
            if (!ok) System.err.println("[WebServer] pushTaskToGoogle failed for '" + task.title + "' in " + listName);
        });
    }

    /** Fire-and-forget single-task delete on Google. No-op when not authenticated. */
    private void deleteTaskOnGoogle(String listName, ProfileManager.TaskItem task) {
        if (!googleTasks.isAuthenticated() || task == null || task.title == null || task.title.isBlank()) return;
        googleTasks.deleteSingleTask(listName, task).thenAccept(ok -> {
            if (!ok) System.err.println("[WebServer] deleteTaskOnGoogle failed for '" + task.title + "' in " + listName);
        });
    }

    private static String getStr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    // ── Broadcast to all WS clients ─────────────────────

    private void broadcast(JsonObject msg) {
        String json = msg.toString();
        clients.removeIf(ctx -> {
            try {
                ctx.send(json);
                return false;
            } catch (Exception e) {
                return true; // remove dead connections
            }
        });
    }
}
