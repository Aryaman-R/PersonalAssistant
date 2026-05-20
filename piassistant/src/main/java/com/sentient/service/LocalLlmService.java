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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to a locally-hosted LLM server with an OpenAI-compatible HTTP API.
 *
 * Designed for llama.cpp's {@code ./server} binary (default port 8080), but works
 * with any drop-in compatible runtime: Ollama (proxied), LM Studio, vLLM, etc.
 * Same payload shape as {@link GroqService} and {@link OpenClawService}, so the
 * command-tag protocol the rest of the app speaks is identical across engines.
 *
 * Keeps its own conversation history so the user can toggle engines without one
 * stomping the other. Settings live in
 * {@code ~/.sentient_local_llm.json} and survive restarts.
 */
public class LocalLlmService {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";
    private static final String DEFAULT_MODEL = "local";
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int CHAT_TIMEOUT_SECONDS = 180; // local models can be slow

    private static final Path CONFIG_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_local_llm.json");

    private final HttpClient client;
    private final Gson gson = new Gson();
    private final List<JsonObject> conversationHistory = new ArrayList<>();

    private volatile String baseUrl = DEFAULT_BASE_URL;
    private volatile String model = DEFAULT_MODEL;
    private volatile String authToken = "";
    private volatile boolean enabled = false;

    public LocalLlmService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        loadConfig();
    }

    public void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            this.baseUrl = url.trim().replaceAll("/+$", "");
        }
    }

    public void setModel(String m) {
        if (m != null && !m.isBlank()) this.model = m.trim();
    }

    public void setAuthToken(String t) {
        this.authToken = t == null ? "" : t.trim();
    }

    public void setEnabled(boolean e) { this.enabled = e; }

    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public boolean hasAuthToken() { return !authToken.isEmpty(); }
    public boolean isEnabled() { return enabled; }

    public void clearHistory() { conversationHistory.clear(); }

    // ── Health check ────────────────────────────────────

    /**
     * Quick liveness check — does the configured local server respond?
     * Probes {@code /v1/models} (OpenAI-compatible) then falls back to
     * {@code /health} (llama.cpp's lighter endpoint). Any 2xx/4xx means
     * "something's listening there"; only network errors mean down.
     */
    public boolean isUp() {
        if (!enabled) return false;
        String[] probes = { "/v1/models", "/health" };
        for (String p : probes) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + p))
                        .timeout(Duration.ofSeconds(3))
                        .GET();
                if (!authToken.isEmpty()) b.header("Authorization", "Bearer " + authToken);
                HttpResponse<Void> r = client.send(b.build(), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() < 500) return true;
            } catch (Exception ignored) {
                // try the next probe
            }
        }
        return false;
    }

    public JsonObject getStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("baseUrl", baseUrl);
        o.addProperty("model", model);
        o.addProperty("enabled", enabled);
        o.addProperty("hasAuthToken", !authToken.isEmpty());
        o.addProperty("up", isUp());
        return o;
    }

    // ── Persistence ─────────────────────────────────────

    private void loadConfig() {
        try {
            if (!Files.exists(CONFIG_FILE)) return;
            String raw = Files.readString(CONFIG_FILE).trim();
            if (raw.isEmpty()) return;
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            if (o.has("baseUrl")) baseUrl = o.get("baseUrl").getAsString();
            if (o.has("model")) model = o.get("model").getAsString();
            if (o.has("authToken")) authToken = o.get("authToken").getAsString();
            if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
        } catch (Exception e) {
            System.err.println("[LocalLlm] Could not load config: " + e.getMessage());
        }
    }

    public void persist() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("baseUrl", baseUrl);
            o.addProperty("model", model);
            o.addProperty("authToken", authToken);
            o.addProperty("enabled", enabled);
            Files.writeString(CONFIG_FILE, gson.toJson(o));
        } catch (Exception e) {
            System.err.println("[LocalLlm] Could not persist config: " + e.getMessage());
        }
    }

    // ── Chat ─────────────────────────────────────────────

    public CompletableFuture<String> processCommand(String userPrompt, String modelOverride,
                                                    String imageBase64, String fileName, String fileType) {
        return processCommand(userPrompt, modelOverride, imageBase64, fileName, fileType, null);
    }

    /**
     * Variant accepting a pre-built memory hint that the WebServer injects after
     * doing top-k retrieval against the episodic store. Null means no hint.
     */
    public CompletableFuture<String> processCommand(String userPrompt, String modelOverride,
                                                    String imageBase64, String fileName, String fileType,
                                                    String memoryBlock) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = inlineAttachmentsIfTextual(userPrompt, imageBase64, fileName, fileType);
            String systemPrompt = buildSystemPrompt(memoryBlock);
            String useModel = (modelOverride != null && !modelOverride.isBlank()
                    && !"AUTO".equalsIgnoreCase(modelOverride)) ? modelOverride : model;

            String response = callServer(useModel, prompt, imageBase64, fileType, systemPrompt);

            if (!response.startsWith("Error:") && !response.startsWith("Sorry,")) {
                JsonObject u = new JsonObject();
                u.addProperty("role", "user");
                u.addProperty("content", prompt);
                conversationHistory.add(u);
                JsonObject a = new JsonObject();
                a.addProperty("role", "assistant");
                a.addProperty("content", response);
                conversationHistory.add(a);
                while (conversationHistory.size() > MAX_HISTORY_TURNS * 2) {
                    conversationHistory.remove(0);
                    conversationHistory.remove(0);
                }
            }
            return response;
        });
    }

    public String testChat() {
        String sys = "You are a connectivity test. Reply with exactly one short sentence confirming you are alive.";
        return callServer(model, "Say hello.", null, null, sys);
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
            System.err.println("[LocalLlm] Attachment parse failed: " + e.getMessage());
        }
        return prompt;
    }

    private String callServer(String useModel, String prompt, String imageBase64, String fileType, String systemPrompt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", useModel);
        payload.addProperty("temperature", 0.5);
        payload.addProperty("max_tokens", 2048);
        payload.addProperty("stream", false);

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
            // Most local-vision models speak the same content-array shape as OpenAI.
            // If the user's chosen backend doesn't, image messages will just be ignored.
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

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(CHAT_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)));
        if (!authToken.isEmpty()) rb.header("Authorization", "Bearer " + authToken);

        try {
            HttpResponse<String> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            String body = r.body();
            System.out.println("[LocalLlm] HTTP " + code + " | bytes=" + (body == null ? 0 : body.length()));

            if (code == 401 || code == 403) {
                return "Sorry, the local LLM server rejected the request. Check the auth token in Settings.";
            }
            if (code >= 400) {
                if (body != null) {
                    System.err.println("[LocalLlm] Error body: "
                            + body.substring(0, Math.min(500, body.length())));
                }
                return "Sorry, the local LLM returned an error (HTTP " + code + ").";
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (java.net.ConnectException ce) {
            return "Error: Local LLM unreachable at " + baseUrl
                    + ". Is the server running? llama.cpp default: './server -m model.gguf --port 8080'.";
        } catch (java.net.http.HttpTimeoutException te) {
            return "Error: Local LLM timed out after " + CHAT_TIMEOUT_SECONDS + "s. Try a smaller model.";
        } catch (Exception e) {
            System.err.println("[LocalLlm] Call failed: " + e.getMessage());
            return "Error: Local LLM call failed (" + e.getClass().getSimpleName() + ").";
        }
    }

    /**
     * System prompt with the same command tags the other engines use, so client
     * behavior is identical no matter which engine answered. Mirrors
     * {@link OpenClawService} — when that file changes, this one should too.
     */
    private String buildSystemPrompt(String memoryBlock) {
        ProfileManager.UserProfile p = ProfileManager.getInstance().getUserProfile();
        String taskSummary = ProfileManager.getInstance().getAllTasks().stream()
                .map(t -> t.title + (t.dueDate.isEmpty() ? "" : " (due: " + t.dueDate + ")"))
                .reduce((a, b) -> a + ", " + b).orElse("none");

        StringBuilder sb = new StringBuilder();
        sb.append("You are a warm, friendly personal assistant for ").append(p.username).append(". ");
        sb.append("You are running entirely on the user's own hardware right now — no data is leaving the device for this turn. ");
        sb.append("Give thoughtful, fleshed-out responses. ");
        sb.append("Use a natural speaking tone since your responses will be read aloud by TTS.");
        sb.append("\n\nUser habits: ").append(String.join(", ", p.habits)).append(". ");
        sb.append("User preferences/likes: ").append(String.join(", ", p.preferences)).append(". ");
        sb.append("User dislikes: ").append(String.join(", ", p.dislikes)).append(". ");
        sb.append("User goals: ").append(String.join(", ", p.goals)).append(". ");
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append("\n\n").append(memoryBlock);
        }
        sb.append("\n\nYou can embed command tags in your response when the user's intent matches. ");
        sb.append("Always include a natural language response alongside any commands.\n");
        sb.append("Available commands:\n");
        sb.append("- [CMD:SWITCH_STUDY] / [CMD:SWITCH_HOME] / [CMD:SWITCH_SLEEP] / [CMD:SWITCH_TASKS] / [CMD:SWITCH_CALENDAR] / [CMD:SWITCH_SPOTIFY] — switch screens\n");
        sb.append("- [CMD:SET_TIMER:N] / [CMD:START_TIMER] / [CMD:PAUSE_TIMER] / [CMD:CANCEL_TIMER] — timer controls\n");
        sb.append("- [CMD:ADD_TASK:title|description|YYYY-MM-DD] / [CMD:REMOVE_TASK:title] — tasks\n");
        sb.append("- [CMD:ADD_COMMITMENT:text] / [CMD:REMOVE_COMMITMENT:text] — commitments\n");
        sb.append("- [CMD:ADD_EVENT:title|description|start|end] — calendar events\n");
        sb.append("- [CMD:CREATE_PLAYLIST:name] — Spotify playlist\n");
        sb.append("- [CMD:AUTOMATE:name] — fire a configured webhook\n");
        sb.append("- [CMD:SET_ALARM:HH:mm] / [CMD:DELETE_ALARM:HH:mm] — alarms\n");
        sb.append("- [CMD:FORGET:topic] — drop matching memories from the episodic store\n");
        sb.append("- [CMD:USE_CREDENTIAL:name] — open a saved service login + auto-fill on the user's device. ");
        sb.append("You NEVER see the password.\n");
        if (CredentialVault.current() != null && !CredentialVault.current().credentialNames().isEmpty()) {
            sb.append("Saved credentials: ").append(String.join(", ", CredentialVault.current().credentialNames())).append(".\n");
        }
        sb.append("- [CMD:CONTINUE_CONVERSATION] — include when the user likely wants to keep talking\n");
        sb.append("\nCurrent tasks: ").append(taskSummary).append(".\n");
        sb.append("Current commitments: ").append(String.join(", ", p.commitments)).append(".\n");
        sb.append("Only use commands when the user's intent clearly matches.");
        return sb.toString();
    }
}
