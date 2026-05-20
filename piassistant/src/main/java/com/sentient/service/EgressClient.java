package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central choke-point for every outbound HTTP call the assistant makes.
 *
 * Each service hands its {@link HttpClient}-bound {@link HttpRequest} to
 * {@link #send(HttpClient, HttpRequest, HttpResponse.BodyHandler, String, String, Set)}
 * along with a {@code destination} label (e.g. {@code "groq"},
 * {@code "spotify"}, {@code "openclaw_local"}) and a {@code purpose}
 * string. The egress firewall:
 *
 * <ul>
 *   <li>Looks up the destination in a per-destination policy table
 *       persisted at {@code ~/.sentient_egress.json}.</li>
 *   <li>If "panic mode" is on, blocks everything except the allowlisted
 *       local LLM endpoint and any explicit panic-mode allowlist.</li>
 *   <li>If the destination is blocked, throws {@link EgressDeniedException}
 *       immediately — the call never hits the wire.</li>
 *   <li>Otherwise sends, records bytes in/out, and updates the recent-call
 *       ring buffer so the PRIVACY tab can show a live log.</li>
 * </ul>
 *
 * Data-class tags live on each call so a future per-class redaction policy
 * can drop, say, {@code voice_transcript} payloads without touching the
 * destination toggles.
 *
 * Implements STRETCH §15.
 */
public final class EgressClient {

    private static final Path POLICY_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_egress.json");
    private static final int LOG_RING_CAPACITY = 500;

    // ── Singleton ──────────────────────────────────────

    private static volatile EgressClient INSTANCE;

    public static EgressClient global() {
        EgressClient local = INSTANCE;
        if (local == null) {
            synchronized (EgressClient.class) {
                if (INSTANCE == null) INSTANCE = new EgressClient();
                local = INSTANCE;
            }
        }
        return local;
    }

    // ── State ─────────────────────────────────────────

    /** Per-destination metadata. */
    public static class Destination {
        public String id;             // stable key: "groq", "openclaw_local", …
        public String label;          // human-readable
        public String host;           // expected hostname; "*" for "any"
        public boolean allowed = true;
        public boolean isLocal = false; // 127.0.0.1 / localhost
        public long bytesOut = 0;
        public long bytesIn = 0;
        public long calls = 0;
        public long blocked = 0;
        public long lastCall = 0;
        public String lastStatus = "";
        public String purpose = "";   // last purpose recorded
    }

    public static class LogEntry {
        public long ts;
        public String destination;
        public String method;
        public String url;        // path only — full host is redundant with destination
        public int status;
        public long bytesOut;
        public long bytesIn;
        public String purpose;
        public boolean allowed;
        public Set<String> dataClasses;
        public String error; // populated when allowed=false or send threw
    }

    /** Reasons a request can be denied — exposed so callers can branch. */
    public static class EgressDeniedException extends IOException {
        public final String destination;
        public final String reason;
        public EgressDeniedException(String destination, String reason) {
            super("Egress to '" + destination + "' denied: " + reason);
            this.destination = destination;
            this.reason = reason;
        }
    }

    private final Map<String, Destination> destinations = new ConcurrentHashMap<>();
    private final Deque<LogEntry> log = new ArrayDeque<>();
    private final Object logLock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private volatile boolean panicMode = false;
    /** Destinations explicitly allowed when {@link #panicMode} is on. */
    private volatile Set<String> panicAllowlist = Set.of("local_llm");

    private EgressClient() {
        seedDefaults();
        load();
    }

    private void seedDefaults() {
        // The destinations we know we'll hit. Loading from disk overrides these,
        // but seeding gives the UI labels + sensible "isLocal" flags before any
        // actual call has gone out.
        seed("groq",          "Groq (cloud LLM)",         "api.groq.com",                  false);
        seed("openclaw_local","OpenClaw gateway (local)", "127.0.0.1",                     true);
        seed("openclaw_remote","OpenClaw gateway (remote)","*",                            false);
        seed("local_llm",     "Local LLM (offline)",      "127.0.0.1",                     true);
        seed("spotify",       "Spotify Web API",          "api.spotify.com",               false);
        seed("spotify_auth",  "Spotify OAuth",            "accounts.spotify.com",          false);
        seed("google",        "Google APIs (Tasks/Cal)",  "www.googleapis.com",            false);
        seed("google_oauth",  "Google OAuth",             "oauth2.googleapis.com",         false);
        seed("openai",        "OpenAI (vision/fallback)", "api.openai.com",                false);
        seed("gemini",        "Google Gemini",            "generativelanguage.googleapis.com", false);
        seed("composio",      "Composio MCP",             "connect.composio.dev",          false);
        seed("automation",    "User automations (webhooks)","*",                           false);
        seed("misc",          "Other / unclassified",     "*",                             false);
    }

    private void seed(String id, String label, String host, boolean isLocal) {
        Destination d = new Destination();
        d.id = id;
        d.label = label;
        d.host = host;
        d.isLocal = isLocal;
        destinations.putIfAbsent(id, d);
    }

    // ── Persistence ──────────────────────────────────

    private void load() {
        try {
            if (!Files.exists(POLICY_FILE)) return;
            String raw = Files.readString(POLICY_FILE).trim();
            if (raw.isEmpty()) return;
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("panicMode")) panicMode = root.get("panicMode").getAsBoolean();
            if (root.has("destinations")) {
                JsonObject ds = root.getAsJsonObject("destinations");
                for (String id : ds.keySet()) {
                    JsonObject entry = ds.getAsJsonObject(id);
                    Destination d = destinations.computeIfAbsent(id, k -> {
                        Destination n = new Destination();
                        n.id = id;
                        n.label = id;
                        n.host = "*";
                        return n;
                    });
                    if (entry.has("label")) d.label = entry.get("label").getAsString();
                    if (entry.has("host")) d.host = entry.get("host").getAsString();
                    if (entry.has("allowed")) d.allowed = entry.get("allowed").getAsBoolean();
                    if (entry.has("isLocal")) d.isLocal = entry.get("isLocal").getAsBoolean();
                }
            }
        } catch (Exception e) {
            System.err.println("[Egress] Could not load policy: " + e.getMessage());
        }
    }

    public synchronized void persist() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("panicMode", panicMode);
            JsonObject ds = new JsonObject();
            for (Destination d : destinations.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("label", d.label);
                entry.addProperty("host", d.host);
                entry.addProperty("allowed", d.allowed);
                entry.addProperty("isLocal", d.isLocal);
                ds.add(d.id, entry);
            }
            root.add("destinations", ds);
            Files.writeString(POLICY_FILE, gson.toJson(root));
        } catch (IOException ioe) {
            System.err.println("[Egress] Persist failed: " + ioe.getMessage());
        }
    }

    // ── Mutators (called by REST layer) ──────────────

    public boolean isPanicMode() { return panicMode; }

    public synchronized void setPanicMode(boolean on) {
        this.panicMode = on;
        persist();
    }

    /** Toggle a single destination. Unknown ids are auto-created. */
    public synchronized boolean setAllowed(String id, boolean allowed) {
        Destination d = destinations.get(id);
        if (d == null) {
            d = new Destination();
            d.id = id;
            d.label = id;
            d.host = "*";
            destinations.put(id, d);
        }
        d.allowed = allowed;
        persist();
        return true;
    }

    public List<Destination> listDestinations() {
        List<Destination> out = new ArrayList<>(destinations.values());
        out.sort((a, b) -> {
            if (a.isLocal != b.isLocal) return a.isLocal ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    public List<LogEntry> recentLog(int limit) {
        synchronized (logLock) {
            List<LogEntry> snapshot = new ArrayList<>(log);
            int n = Math.min(limit, snapshot.size());
            return snapshot.subList(snapshot.size() - n, snapshot.size());
        }
    }

    public synchronized void clearStats() {
        for (Destination d : destinations.values()) {
            d.bytesIn = 0;
            d.bytesOut = 0;
            d.calls = 0;
            d.blocked = 0;
            d.lastCall = 0;
            d.lastStatus = "";
            d.purpose = "";
        }
        synchronized (logLock) { log.clear(); }
    }

    // ── The actual send pipeline ─────────────────────

    /**
     * Send a request through the firewall. Throws
     * {@link EgressDeniedException} when policy blocks the destination —
     * services should catch it and convey "this integration is currently
     * disabled" to the user.
     */
    public <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> handler,
                                    String destinationId, String purpose,
                                    Set<String> dataClasses)
            throws IOException, InterruptedException {

        Destination d = destinations.get(destinationId);
        if (d == null) {
            // Unknown destination → register as "misc" so the UI can show it.
            d = new Destination();
            d.id = destinationId == null ? "misc" : destinationId;
            d.label = d.id;
            d.host = request.uri().getHost() == null ? "*" : request.uri().getHost();
            d.isLocal = isLocalHost(d.host);
            destinations.put(d.id, d);
        }

        boolean allowed = computeAllowed(d);
        if (!allowed) {
            d.blocked++;
            d.lastCall = System.currentTimeMillis();
            recordLog(d.id, request, 0, 0, 0, purpose, false,
                    dataClasses, "denied by egress policy");
            throw new EgressDeniedException(d.id,
                    panicMode ? "panic mode is on" : "destination toggled off in Settings → Privacy");
        }

        long outBytes = estimateBodyLength(request);

        try {
            HttpResponse<T> resp = client.send(request, handler);
            long inBytes = estimateResponseLength(resp);
            d.bytesOut += outBytes;
            d.bytesIn += inBytes;
            d.calls++;
            d.lastCall = System.currentTimeMillis();
            d.lastStatus = "HTTP " + resp.statusCode();
            d.purpose = purpose == null ? "" : purpose;
            recordLog(d.id, request, resp.statusCode(), outBytes, inBytes, purpose,
                    true, dataClasses, null);
            return resp;
        } catch (IOException ioe) {
            d.bytesOut += outBytes;
            d.calls++;
            d.lastCall = System.currentTimeMillis();
            d.lastStatus = "ERR " + ioe.getClass().getSimpleName();
            d.purpose = purpose == null ? "" : purpose;
            recordLog(d.id, request, 0, outBytes, 0, purpose, true, dataClasses,
                    ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
            throw ioe;
        }
    }

    /** Cheap probe — checks policy without actually sending anything. */
    public boolean isAllowed(String destinationId) {
        Destination d = destinations.get(destinationId);
        return d != null && computeAllowed(d);
    }

    private boolean computeAllowed(Destination d) {
        if (!d.allowed) return false;
        if (panicMode) {
            if (d.isLocal) return true;
            return panicAllowlist.contains(d.id);
        }
        return true;
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1")
                || h.startsWith("192.168.") || h.startsWith("10.")
                || (h.startsWith("172.") && hasPrivateClassBOctet(h));
    }

    private static boolean hasPrivateClassBOctet(String h) {
        try {
            int second = Integer.parseInt(h.split("\\.")[1]);
            return second >= 16 && second <= 31;
        } catch (Exception e) {
            return false;
        }
    }

    private void recordLog(String destinationId, HttpRequest req, int status,
                           long bytesOut, long bytesIn, String purpose, boolean allowed,
                           Set<String> dataClasses, String error) {
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.destination = destinationId;
        e.method = req.method();
        URI u = req.uri();
        e.url = u.getPath() + (u.getQuery() == null ? "" : "?" + u.getQuery());
        e.status = status;
        e.bytesOut = bytesOut;
        e.bytesIn = bytesIn;
        e.purpose = purpose == null ? "" : purpose;
        e.allowed = allowed;
        e.dataClasses = dataClasses == null ? Set.of() : dataClasses;
        e.error = error;
        synchronized (logLock) {
            log.addLast(e);
            while (log.size() > LOG_RING_CAPACITY) log.pollFirst();
        }
    }

    private static long estimateBodyLength(HttpRequest req) {
        return req.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L);
    }

    private static <T> long estimateResponseLength(HttpResponse<T> resp) {
        T body = resp.body();
        if (body == null) return 0;
        if (body instanceof byte[]) return ((byte[]) body).length;
        if (body instanceof String) return ((String) body).getBytes(StandardCharsets.UTF_8).length;
        // Stream / Void / Path bodies: we don't have an easy length, so report 0.
        return 0;
    }

    // ── JSON serializers (for REST layer) ────────────

    public JsonObject toJson(Destination d) {
        JsonObject o = new JsonObject();
        o.addProperty("id", d.id);
        o.addProperty("label", d.label);
        o.addProperty("host", d.host);
        o.addProperty("allowed", d.allowed);
        o.addProperty("isLocal", d.isLocal);
        o.addProperty("calls", d.calls);
        o.addProperty("blocked", d.blocked);
        o.addProperty("bytesOut", d.bytesOut);
        o.addProperty("bytesIn", d.bytesIn);
        o.addProperty("lastCall", d.lastCall);
        o.addProperty("lastStatus", d.lastStatus);
        o.addProperty("purpose", d.purpose);
        o.addProperty("effectiveAllowed", computeAllowed(d));
        return o;
    }

    public JsonObject toJson(LogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", e.ts);
        o.addProperty("destination", e.destination);
        o.addProperty("method", e.method);
        o.addProperty("url", e.url);
        o.addProperty("status", e.status);
        o.addProperty("bytesOut", e.bytesOut);
        o.addProperty("bytesIn", e.bytesIn);
        o.addProperty("purpose", e.purpose);
        o.addProperty("allowed", e.allowed);
        if (e.error != null) o.addProperty("error", e.error);
        JsonArray cls = new JsonArray();
        for (String c : e.dataClasses) cls.add(c);
        o.add("dataClasses", cls);
        return o;
    }

    public JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("panicMode", panicMode);
        JsonArray dests = new JsonArray();
        for (Destination d : listDestinations()) dests.add(toJson(d));
        root.add("destinations", dests);
        return root;
    }

    // ── Convenience data-class constants ─────────────

    public static final class DataClass {
        public static final String CHAT_TEXT = "chat_text";
        public static final String VOICE_TRANSCRIPT = "voice_transcript";
        public static final String EMAIL_SUBJECT = "email_subject";
        public static final String CALENDAR_EVENT = "calendar_event";
        public static final String TASK = "task";
        public static final String IMAGE = "image";
        public static final String CREDENTIAL_NAME = "credential_name";
        public static final String OAUTH_TOKEN = "oauth_token";
        public static final String AUTH = "auth";
        public static final String TELEMETRY = "telemetry";
        public static final String USER_PROFILE = "user_profile";
        public static final String WEBHOOK_PAYLOAD = "webhook_payload";
        private DataClass() {}
    }

    /** Shorthand for callers that only have one class. */
    public static Set<String> classes(String... cs) {
        if (cs == null || cs.length == 0) return Set.of();
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String c : cs) if (c != null && !c.isBlank()) m.put(c, Boolean.TRUE);
        return new java.util.LinkedHashSet<>(m.keySet());
    }
}
