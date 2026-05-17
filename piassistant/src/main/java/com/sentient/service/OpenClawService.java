package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentient.util.ProfileManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to a locally-installed OpenClaw gateway via its OpenAI-compatible HTTP API.
 *
 * The gateway lives at http://127.0.0.1:18789 by default. We POST to /v1/chat/completions
 * with the same payload shape as Groq/OpenAI, including the command-emitting system prompt
 * the rest of the app already understands.
 *
 * Keeps its own conversation history, separate from GroqService — that way the user can
 * toggle engines without one stomping the other.
 */
public class OpenClawService {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:18789";
    private static final String DEFAULT_MODEL = "openclaw/default";
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int TIMEOUT_SECONDS = 60;

    /** Persists the "remote vs local" toggle and credentials so it survives restarts. */
    private static final java.nio.file.Path CONNECTION_FILE = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".sentient_openclaw_connection.json");

    private final HttpClient client;
    private final Gson gson = new Gson();
    private final List<JsonObject> conversationHistory = new ArrayList<>();

    private volatile String localBaseUrl = DEFAULT_BASE_URL;
    private volatile String remoteBaseUrl = "";
    private volatile boolean useRemote = false;
    private volatile String localAuthToken = "";
    private volatile String remoteAuthToken = "";

    private volatile String defaultModel = DEFAULT_MODEL;

    public OpenClawService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        loadConnectionFile();
    }

    /** Effective base URL given the current mode. */
    private String activeBaseUrl() {
        if (useRemote && remoteBaseUrl != null && !remoteBaseUrl.isBlank()) return remoteBaseUrl;
        return localBaseUrl;
    }

    private String activeAuthToken() {
        return useRemote ? remoteAuthToken : localAuthToken;
    }

    // ── Configuration mutators (called by WebServer when settings change) ──

    /** Sets the LOCAL gateway URL (the one started by `openclaw gateway start` on this host). */
    public void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) this.localBaseUrl = url.replaceAll("/+$", "");
    }

    public void setDefaultModel(String model) {
        if (model != null && !model.isBlank()) this.defaultModel = model;
    }

    /** Sets the LOCAL gateway auth token. */
    public void setAuthToken(String token) {
        this.localAuthToken = token == null ? "" : token.trim();
    }

    /** Toggle between local and remote gateways. */
    public void setUseRemote(boolean useRemote) {
        this.useRemote = useRemote;
        persistConnectionFile();
    }

    /** Configure the remote gateway (VPS / other device). */
    public void setRemoteGateway(String url, String token) {
        this.remoteBaseUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.remoteAuthToken = token == null ? "" : token.trim();
        persistConnectionFile();
    }

    public String getBaseUrl() { return activeBaseUrl(); }
    public String getDefaultModel() { return defaultModel; }
    public boolean isUsingRemote() { return useRemote; }
    public String getLocalBaseUrl() { return localBaseUrl; }
    public String getRemoteBaseUrl() { return remoteBaseUrl; }

    public void clearHistory() { conversationHistory.clear(); }

    // ── Health check ────────────────────────────────────

    /** Quick check — does the active gateway respond at all? */
    public boolean isGatewayUp() {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(activeBaseUrl() + "/v1/models"))
                    .timeout(Duration.ofSeconds(4))
                    .GET();
            String tok = activeAuthToken();
            if (!tok.isEmpty()) b.header("Authorization", "Bearer " + tok);
            HttpResponse<Void> r = client.send(b.build(), HttpResponse.BodyHandlers.discarding());
            int code = r.statusCode();
            // Any 2xx/4xx response from the right port = it's up. Only network errors mean down.
            return code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Connection persistence (remote toggle survives restarts) ──

    private void persistConnectionFile() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("useRemote", useRemote);
            o.addProperty("remoteBaseUrl", remoteBaseUrl);
            o.addProperty("remoteAuthToken", remoteAuthToken);
            java.nio.file.Files.writeString(CONNECTION_FILE, gson.toJson(o));
        } catch (Exception e) {
            System.err.println("[OpenClaw] Could not persist connection file: " + e.getMessage());
        }
    }

    private void loadConnectionFile() {
        try {
            if (!java.nio.file.Files.exists(CONNECTION_FILE)) return;
            String raw = java.nio.file.Files.readString(CONNECTION_FILE).trim();
            if (raw.isEmpty()) return;
            JsonObject o = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (o.has("useRemote")) useRemote = o.get("useRemote").getAsBoolean();
            if (o.has("remoteBaseUrl")) remoteBaseUrl = o.get("remoteBaseUrl").getAsString();
            if (o.has("remoteAuthToken")) remoteAuthToken = o.get("remoteAuthToken").getAsString();
        } catch (Exception e) {
            System.err.println("[OpenClaw] Could not load connection file: " + e.getMessage());
        }
    }

    /** Snapshot of current connection state for the UI. */
    public JsonObject getConnectionStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("useRemote", useRemote);
        o.addProperty("activeBaseUrl", activeBaseUrl());
        o.addProperty("localBaseUrl", localBaseUrl);
        o.addProperty("remoteBaseUrl", remoteBaseUrl);
        o.addProperty("hasRemoteToken", !remoteAuthToken.isEmpty());
        return o;
    }

    // ── Chat ─────────────────────────────────────────────

    public CompletableFuture<String> processCommand(String userPrompt, String modelOverride,
                                                    String imageBase64, String fileName, String fileType) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = inlineAttachmentsIfTextual(userPrompt, imageBase64, fileName, fileType);

            String systemPrompt = buildChatSystemPrompt();
            String model = (modelOverride != null && !modelOverride.isBlank() && !"AUTO".equalsIgnoreCase(modelOverride))
                    ? modelOverride
                    : defaultModel;

            String response = callGateway(model, prompt, imageBase64, fileType, systemPrompt);

            // Record turn in history only if it wasn't an error reply
            if (!response.startsWith("Error:") && !response.startsWith("Sorry,")) {
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", prompt);
                conversationHistory.add(userMsg);

                JsonObject assistantMsg = new JsonObject();
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", response);
                conversationHistory.add(assistantMsg);

                while (conversationHistory.size() > MAX_HISTORY_TURNS * 2) {
                    conversationHistory.remove(0);
                    conversationHistory.remove(0);
                }
            }

            return response;
        });
    }

    /** Simple test ping — used by the Settings "TEST CHAT" button. */
    public String testChat() {
        String systemPrompt = "You are a connectivity test. Reply with exactly one short sentence confirming you are alive.";
        return callGateway(defaultModel, "Say hello.", null, null, systemPrompt);
    }

    // ── Implementation ──────────────────────────────────

    private String inlineAttachmentsIfTextual(String prompt, String imageBase64, String fileName, String fileType) {
        if (imageBase64 == null || fileType == null) return prompt;
        try {
            String cleanBase64 = imageBase64.contains(",") ? imageBase64.split(",")[1] : imageBase64;
            if (fileType.contains("pdf")) {
                byte[] pdfBytes = java.util.Base64.getDecoder().decode(cleanBase64);
                try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                    return prompt + "\n\n[Attached PDF: " + fileName + "]\n" + text;
                }
            }
            if (fileType.contains("text")) {
                byte[] bytes = java.util.Base64.getDecoder().decode(cleanBase64);
                return prompt + "\n\n[Attached text: " + fileName + "]\n"
                        + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[OpenClaw] Attachment parse failed: " + e.getMessage());
        }
        return prompt;
    }

    private String callGateway(String model, String prompt, String imageBase64, String fileType, String systemPrompt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("temperature", 0.5);
        payload.addProperty("max_tokens", 2048);

        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        for (JsonObject m : conversationHistory) messages.add(m);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        boolean isImage = imageBase64 != null && fileType != null && fileType.startsWith("image/");
        if (isImage) {
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", prompt);
            content.add(text);

            JsonObject img = new JsonObject();
            img.addProperty("type", "image_url");
            JsonObject url = new JsonObject();
            url.addProperty("url", imageBase64);
            img.add("image_url", url);
            content.add(img);

            user.add("content", content);
        } else {
            user.addProperty("content", prompt);
        }
        messages.add(user);

        payload.add("messages", messages);

        String base = activeBaseUrl();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)));

        String tok = activeAuthToken();
        if (!tok.isEmpty()) {
            rb.header("Authorization", "Bearer " + tok);
        }

        try {
            HttpResponse<String> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            String body = r.body();
            System.out.println("[OpenClaw] HTTP " + code + " | bytes=" + (body == null ? 0 : body.length()));

            if (code == 401 || code == 403) {
                return "Sorry, OpenClaw gateway rejected the request. Check the gateway auth token in Settings.";
            }
            if (code >= 400) {
                System.err.println("[OpenClaw] Error body: " + body.substring(0, Math.min(500, body.length())));
                return "Sorry, OpenClaw returned an error (HTTP " + code + "). Check your provider config.";
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (java.net.ConnectException ce) {
            return "Error: OpenClaw gateway unreachable at " + base
                    + (useRemote ? ". Is the remote host reachable and the gateway running?"
                                 : ". Is it running? Try 'openclaw gateway start'.");
        } catch (Exception e) {
            System.err.println("[OpenClaw] Call failed: " + e.getMessage());
            return "Error: OpenClaw call failed (" + e.getClass().getSimpleName() + ").";
        }
    }

    /**
     * Same command-emitting system prompt the Groq CHAT path uses, so client-side and
     * server-side command handling work identically across engines.
     */
    private String buildChatSystemPrompt() {
        ProfileManager.UserProfile p = ProfileManager.getInstance().getUserProfile();
        String taskSummary = ProfileManager.getInstance().getAllTasks().stream()
                .map(t -> t.title + (t.dueDate.isEmpty() ? "" : " (due: " + t.dueDate + ")"))
                .reduce((a, b) -> a + ", " + b).orElse("none");

        return "You are a warm, friendly personal assistant for " + p.username + ". " +
                "You genuinely care about the user and enjoy chatting with them. " +
                "Give thoughtful, fleshed-out responses — not just one-liners. " +
                "Use a natural speaking tone since your responses will be read aloud by TTS." +
                "\n\nUser habits: " + String.join(", ", p.habits) + ". " +
                "User preferences/likes: " + String.join(", ", p.preferences) + ". " +
                "User dislikes: " + String.join(", ", p.dislikes) + ". " +
                "User goals: " + String.join(", ", p.goals) + ". " +
                "\n\nYou can embed command tags in your response when the user's intent matches. " +
                "Always include a natural language response alongside any commands.\n" +
                "Available commands:\n" +
                "- [CMD:SWITCH_STUDY] / [CMD:SWITCH_HOME] / [CMD:SWITCH_SLEEP] / [CMD:SWITCH_TASKS] / [CMD:SWITCH_CALENDAR] / [CMD:SWITCH_SPOTIFY] — switch screens\n" +
                "- [CMD:SET_TIMER:N] / [CMD:START_TIMER] / [CMD:PAUSE_TIMER] / [CMD:CANCEL_TIMER] — timer controls\n" +
                "- [CMD:ADD_TASK:title|description|YYYY-MM-DD] / [CMD:REMOVE_TASK:title] — tasks\n" +
                "- [CMD:ADD_COMMITMENT:text] / [CMD:REMOVE_COMMITMENT:text] — commitments\n" +
                "- [CMD:ADD_EVENT:title|description|start|end] — calendar events\n" +
                "- [CMD:CREATE_PLAYLIST:name] — Spotify playlist\n" +
                "- [CMD:AUTOMATE:name] — fire a configured webhook\n" +
                "- [CMD:SET_ALARM:HH:mm] / [CMD:DELETE_ALARM:HH:mm] — alarms\n" +
                "- [CMD:USE_CREDENTIAL:name] — open a saved service login + auto-fill on the user's device. " +
                "You NEVER see the password; the master injects it into the browser. " +
                (CredentialVault.current() == null || CredentialVault.current().credentialNames().isEmpty()
                        ? "(No credentials saved yet.)\n"
                        : ("Saved credentials: " + String.join(", ", CredentialVault.current().credentialNames()) + ".\n")) +
                (CredentialVault.current() == null || CredentialVault.current().envVarNames().isEmpty()
                        ? ""
                        : ("Env vars on master (names only): "
                                + String.join(", ", CredentialVault.current().envVarNames()) + ".\n")) +
                "— Devices / remote control (only when the user explicitly names a device):\n" +
                "- [CMD:VIEW_DEVICE:DeviceName] — capture a screenshot from a connected device and analyze it visually.\n" +
                "- [CMD:REMOTE_OPEN:DeviceName|https://url] — open a URL in the browser tab on the named device.\n" +
                "- [CMD:TYPE_TEXT:DeviceName|text to type] — type text into the focused app on the named device (helper only).\n" +
                "- [CMD:LAUNCH_APP:DeviceName|com.example.bundle] — launch a macOS app by bundle id (helper only).\n" +
                "- [CMD:KEY_COMBO:DeviceName|cmd+space] — send a key combination on the named device (helper only).\n" +
                "- [CMD:CLICK_AT:DeviceName|x,y] — click at screen coordinates on the named device (helper only).\n" +
                "- [CMD:CONTINUE_CONVERSATION] — include when the user likely wants to keep talking\n" +
                "\nCurrent tasks: " + taskSummary + ".\n" +
                "Current commitments: " + String.join(", ", p.commitments) + ".\n" +
                "Only use commands when the user's intent clearly matches.";
    }
}
