package com.sentient.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// You will need to add the Gson dependency to your Maven pom.xml for these to work
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentient.util.ProfileManager;
import com.sentient.util.EnvLoader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class GroqService {

    // 1. The Groq Endpoint and Key
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_API_KEY = EnvLoader.get("GROQ_API_KEY", "");

    // 2. The Updated Model Roster
    private static final String ROUTER_MODEL = "llama-3.1-8b-instant";
    private static final String CHAT_MODEL = "llama-3.3-70b-versatile";
    private static final String THINK_MODEL = "qwen/qwen3-32b"; // The new reasoning alternative

    private final HttpClient client;
    private final Gson gson;

    // Conversation history for the CHAT model (user + assistant turns)
    private final List<JsonObject> conversationHistory = new ArrayList<>();
    private static final int MAX_HISTORY_TURNS = 10; // keep last 10 pairs (20 messages)
    private static final int MAX_RETRIES = 2; // retry transient errors once

    public GroqService() {
        // 30s timeout — AI generation can take several seconds
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.gson = new Gson();
    }

    /** Clear conversation history (e.g. on session end). */
    public void clearHistory() {
        conversationHistory.clear();
    }

    public CompletableFuture<String> processCommand(String userPrompt) {
        return processCommand(userPrompt, "AUTO", null, null, null);
    }

    public CompletableFuture<String> processCommand(String userPrompt, String modelOverride, String imageBase64, String fileName, String fileType) {
        return CompletableFuture.supplyAsync(() -> {
            
            String finalPrompt = userPrompt;
            String finalImageBase64 = imageBase64;
            
            if (fileType != null && fileType.contains("pdf") && finalImageBase64 != null) {
                try {
                    String cleanBase64 = finalImageBase64.contains(",") ? finalImageBase64.split(",")[1] : finalImageBase64;
                    byte[] pdfBytes = java.util.Base64.getDecoder().decode(cleanBase64);
                    try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        String pdfText = stripper.getText(document);
                        finalPrompt += "\n\n[Attached PDF Document: " + fileName + "]\n" + pdfText;
                    }
                    finalImageBase64 = null; // Parsed strictly as text, no Vision model needed
                } catch (Exception e) {
                    System.err.println("Failed to parse PDF: " + e.getMessage());
                }
            } else if (fileType != null && fileType.contains("text") && finalImageBase64 != null) {
                try {
                    String cleanBase64 = finalImageBase64.contains(",") ? finalImageBase64.split(",")[1] : finalImageBase64;
                    byte[] textBytes = java.util.Base64.getDecoder().decode(cleanBase64);
                    String textContent = new String(textBytes, java.nio.charset.StandardCharsets.UTF_8);
                    finalPrompt += "\n\n[Attached Text Document: " + fileName + "]\n" + textContent;
                    finalImageBase64 = null; // Parsed strictly as text, no Vision model needed
                } catch (Exception e) {
                    System.err.println("Failed to parse text: " + e.getMessage());
                }
            }
            
            System.out.println("User asked: " + finalPrompt);
            String routeDecision;
            
            if (finalImageBase64 != null) {
                routeDecision = "VISION";
            } else if ("THINK".equalsIgnoreCase(modelOverride)) {
                routeDecision = "THINK";
            } else if ("CHAT".equalsIgnoreCase(modelOverride)) {
                routeDecision = "CHAT";
            } else {
                routeDecision = getRoute(finalPrompt);
            }

            String taskSummary = ProfileManager.getInstance().getAllTasks().stream()
                    .map(t -> t.title + (t.dueDate.isEmpty() ? "" : " (due: " + t.dueDate + ")"))
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            if (routeDecision.equals("VISION")) {
                System.out.println("--> Router selected: VISION MODEL (meta-llama/llama-4-scout-17b-16e-instruct)");
                String systemPrompt = "You are a helpful AI assistant analyzing an image provided by the user. State clearly what you see and answer their question concisely.";
                return callGroqApi("meta-llama/llama-4-scout-17b-16e-instruct", finalPrompt, finalImageBase64, null, systemPrompt);
            } else if (routeDecision.contains("THINK")) {
                System.out.println("--> Router selected: THE THINKER (Qwen 3 32B)");
                // THINK model is stateless — no history needed
                String systemPrompt = "You are a knowledgeable, thoughtful assistant for " +
                        ProfileManager.getInstance().getUserProfile().username + ". " +
                        "You handle complex questions — code, math, deep research, and multi-step explanations. " +
                        "Break down your reasoning step by step. When writing code, include clear comments. " +
                        "When explaining concepts, use analogies and examples to make them click. " +
                        "Be thorough but organized — use structure (numbered steps, bullet points) when it helps. " +
                        "Speak naturally since your response will be read aloud by TTS.";

                return callGroqApi(THINK_MODEL, finalPrompt, null, null, systemPrompt);
            } else {
                System.out.println("--> Router selected: THE CONVERSATIONALIST (Llama 3.3 70B)");
                String systemPrompt = "You are a warm, friendly personal assistant for " +
                        ProfileManager.getInstance().getUserProfile().username + ". " +
                        "You genuinely care about the user and enjoy chatting with them. " +
                        "Give thoughtful, fleshed-out responses — not just one-liners. " +
                        "Explain things clearly, share interesting details, and ask follow-up questions to keep the conversation going. "
                        +
                        "But don't overshare or provide unnecessary details about the user to the user, and don't go off topic. Don't say anything uncessary for the situation "
                        +
                        "Be encouraging, supportive, and conversational — like a close friend who also happens to be really knowledgeable. "
                        +
                        "Use a natural speaking tone since your responses will be read aloud by TTS." +
                        "\n\nUser habits: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().habits) + ". " +
                        "User preferences/likes: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().preferences) + ". " +
                        "User dislikes: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().dislikes) + ". " +
                        "User goals: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().goals) + ". " +
                        "User nicknames: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().nicknames) + ". " +
                        "Notes about user: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().notes) + ". " +
                        "\n\nYou can embed command tags in your response when the user's intent matches. " +
                        "Always include a natural language response alongside any commands.\n" +
                        "Available commands:\n" +
                        "- [CMD:SWITCH_STUDY] — Switch to Study screen\n" +
                        "- [CMD:SWITCH_HOME] — Switch to Home screen\n" +
                        "- [CMD:SWITCH_SLEEP] — Switch to Sleep screen\n" +
                        "- [CMD:SWITCH_TASKS] — Switch to Tasks screen\n" +
                        "- [CMD:SET_TIMER:N] — Set focus timer to N minutes\n" +
                        "- [CMD:START_TIMER] / [CMD:PAUSE_TIMER] / [CMD:CANCEL_TIMER] — Timer controls\n" +
                        "- [CMD:SET_USERNAME:name] — Change display name\n" +
                        "- [CMD:SET_RESTRICTION:ON] or [CMD:SET_RESTRICTION:OFF] — Toggle restriction mode\n" +
                        "— Tasks (structured with title, description, date):\n" +
                        "- [CMD:ADD_TASK:title|description|YYYY-MM-DD] — Add a task (e.g. [CMD:ADD_TASK:Finish homework|Complete chapter 5 exercises|2026-02-25])\n"
                        +
                        "- [CMD:REMOVE_TASK:title] — Remove/complete a task (e.g. [CMD:REMOVE_TASK:Finish homework])\n"
                        +
                        "— Commitments:\n" +
                        "- [CMD:ADD_COMMITMENT:description] — Add a commitment (e.g. [CMD:ADD_COMMITMENT:Gym at 6pm])\n"
                        +
                        "- [CMD:REMOVE_COMMITMENT:description] — Remove a commitment\n" +
                        "— Calendar:\n" +
                        "- [CMD:SWITCH_CALENDAR] — Switch to Calendar screen\n" +
                        "- [CMD:ADD_EVENT:title|description|YYYY-MM-DDTHH:MM|YYYY-MM-DDTHH:MM] — Add a calendar event with start and end times (e.g. [CMD:ADD_EVENT:Team Meeting|Weekly standup|2026-03-25T10:00|2026-03-25T11:00])\n"
                        +
                        "— Spotify / Music:\n" +
                        "- [CMD:CREATE_PLAYLIST:name] — Create a new Spotify playlist with the given name (e.g. [CMD:CREATE_PLAYLIST:Chill Vibes])\n"
                        +
                        "- [CMD:SWITCH_SPOTIFY] — Switch to Music/Spotify screen\n" +
                        "— Automation:\n" +
                        "- [CMD:AUTOMATE:name] — Trigger a configured automation (e.g. [CMD:AUTOMATE:lights_on])\n" +
                        "— Alarms:\n" +
                        "- [CMD:SET_ALARM:HH:mm] — Set an alarm (e.g. [CMD:SET_ALARM:07:30])\n" +
                        "- [CMD:DELETE_ALARM:HH:mm] — Delete an alarm\n" +
                        "— Credentials (vault):\n" +
                        "- [CMD:USE_CREDENTIAL:name] — Open the saved service login for `name` and auto-fill it on the user's current device. " +
                        "You NEVER see the password — it's pulled from the vault and handed to the browser directly. " +
                        "Only use this when the user explicitly asks to sign in / open a saved login. " +
                        (CredentialVault.current() == null || CredentialVault.current().credentialNames().isEmpty()
                                ? "(No credentials saved yet.)\n"
                                : ("Saved credential names: " + String.join(", ", CredentialVault.current().credentialNames()) + ".\n")) +
                        (CredentialVault.current() == null || CredentialVault.current().envVarNames().isEmpty()
                                ? ""
                                : ("Available env-var names (values stay on the master, never disclose them): "
                                        + String.join(", ", CredentialVault.current().envVarNames()) + ".\n")) +
                        "— Devices / remote control (only use when the user explicitly asks to act on a specific device):\n" +
                        "- [CMD:VIEW_DEVICE:DeviceName] — capture a screenshot from a connected device and analyze it visually. Use the names visible in the device registry.\n" +
                        "- [CMD:REMOTE_OPEN:DeviceName|https://url] — open a URL in the browser tab on the named device.\n" +
                        "- [CMD:TYPE_TEXT:DeviceName|text to type] — type text into the focused app on the named device (requires the native helper).\n" +
                        "- [CMD:LAUNCH_APP:DeviceName|com.example.bundle] — launch a macOS app by bundle id (helper only).\n" +
                        "- [CMD:KEY_COMBO:DeviceName|cmd+space] — send a key combination on the named device (helper only).\n" +
                        "- [CMD:CLICK_AT:DeviceName|x,y] — click at screen coordinates on the named device (helper only).\n" +
                        "— Conversation:\n" +
                        "- [CMD:CONTINUE_CONVERSATION] — Include this when you believe the user wants to keep talking "
                        +
                        "(e.g. you asked a follow-up question, or the conversation naturally continues). " +
                        "This will automatically turn on the microphone to capture the user's next voice input.\n" +
                        "\nCurrent user tasks: " + taskSummary + ".\n" +
                        "Current user commitments: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().commitments) + ".\n" +
                        "Current user alarms: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().alarms) + ".\n" +
                        "\nExamples:\n" +
                        "User: 'Add a task to finish homework by Friday' → text + [CMD:ADD_TASK:Finish homework|Complete the assigned work|2026-02-28]\n"
                        +
                        "User: 'What tasks do I have?' → text listing tasks + [CMD:SWITCH_TASKS] [CMD:CONTINUE_CONVERSATION]\n"
                        +
                        "User: 'Start a 45 minute study session' → text + [CMD:SWITCH_STUDY] [CMD:SET_TIMER:45] [CMD:START_TIMER]\n"
                        +
                        "User: 'Create a playlist called Chill Vibes' → text + [CMD:CREATE_PLAYLIST:Chill Vibes] [CMD:SWITCH_SPOTIFY]\n"
                        +
                        "User: 'Make me a workout playlist' → text + [CMD:CREATE_PLAYLIST:Workout Mix] [CMD:SWITCH_SPOTIFY]\n"
                        +
                        "User: 'How are you doing?' → friendly text + [CMD:CONTINUE_CONVERSATION]\n" +
                        "Only use commands when the user's intent clearly matches. For normal conversation, include CONTINUE_CONVERSATION if you think the user wants to keep chatting.";

                String response = callGroqApi(CHAT_MODEL, finalPrompt, null, conversationHistory, systemPrompt);

                // Only store history when the response is a genuine AI reply (not a service
                // error)
                boolean isApiError = response.startsWith("Sorry, the AI service returned an error") ||
                        response.startsWith("Error: Could not reach the AI service") ||
                        response.startsWith("Error: Request interrupted");
                if (!isApiError) {
                    JsonObject userMsg = new JsonObject();
                    userMsg.addProperty("role", "user");
                    userMsg.addProperty("content", finalPrompt);
                    conversationHistory.add(userMsg);

                    JsonObject assistantMsg = new JsonObject();
                    assistantMsg.addProperty("role", "assistant");
                    assistantMsg.addProperty("content", response);
                    conversationHistory.add(assistantMsg);

                    // Trim history if it gets too long (keep last N pairs)
                    while (conversationHistory.size() > MAX_HISTORY_TURNS * 2) {
                        conversationHistory.remove(0);
                        conversationHistory.remove(0);
                    }
                }

                return response;
            }
        });
    }

    private String getRoute(String userPrompt) {
        String systemPrompt = "You are the routing brain of a voice assistant. " +
                "Your ONLY job is to analyze the user's prompt and output a single word: 'CHAT' or 'THINK'. " +
                "Output NO other text, punctuation, or explanation.\n\n" +
                "Rules:\n" +
                "Output 'CHAT' if the user is asking for home automation (lights, timers, music), " +
                "making small talk, asking for quick facts, or giving basic commands.\n" +
                "Output 'THINK' if the user is asking you to write code, solve math problems, " +
                "brainstorm complex logic, or explain deep, multi-step concepts.\n\n" +
                "Examples:\n" +
                "User: 'Turn off the lights and set a timer for 10 minutes.' -> CHAT\n" +
                "User: 'How are you doing today?' -> CHAT\n" +
                "User: 'Write a Java method to parse JSON.' -> THINK\n" +
                "User: 'Explain quantum entanglement.' -> THINK";
        String response = callGroqApi(ROUTER_MODEL, userPrompt, null, null, systemPrompt);
        return response.toUpperCase();
    }

    /**
     * Specialized Groq call for AI DJ — generates search queries from a mood
     * prompt.
     * Used by SpotifyService for the AI DJ feature.
     */
    public String callGroqForDJ(String prompt) {
        String systemPrompt = "You are a music recommendation AI. " +
                "When given a mood, activity, or music request, output exactly 5 Spotify search queries " +
                "that would find great matching songs. Output ONLY the queries, one per line, " +
                "no numbering, no explanations, no extra text.";
        return callGroqApi(CHAT_MODEL, prompt, null, null, systemPrompt);
    }

    /**
     * Specialized AI call for calendar and task suggestions.
     * Expects a JSON object matching the requested schema.
     */
    public CompletableFuture<String> callGroqForCalendar(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            String systemPrompt = "You are a calendar assistant helping to schedule events and manage tasks." +
                    "Follow the user's instructions regarding formatting carefully.";
            return callGroqApi(THINK_MODEL, prompt, null, null, systemPrompt);
        });
    }

    private String callGroqApi(String modelName, String prompt, String imageBase64, List<JsonObject> history, String systemPrompt) {

        // 1. Safely build the JSON Request using Gson
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName);
        payload.addProperty("temperature", 0.5);
        payload.addProperty("max_tokens", 2048);

        // Tell Groq to hide the raw <think> tags so Piper TTS doesn't read them
        if (modelName.equals(THINK_MODEL)) {
            payload.addProperty("reasoning_format", "hidden");
        }

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        // Include conversation history if provided
        if (history != null && !history.isEmpty()) {
            for (JsonObject msg : history) {
                messages.add(msg);
            }
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        if (imageBase64 == null) {
            userMsg.addProperty("content", prompt);
        } else {
            JsonArray contentArr = new JsonArray();
            JsonObject textObj = new JsonObject();
            textObj.addProperty("type", "text");
            textObj.addProperty("text", prompt);
            contentArr.add(textObj);
            
            JsonObject imgObj = new JsonObject();
            imgObj.addProperty("type", "image_url");
            JsonObject urlObj = new JsonObject();
            urlObj.addProperty("url", imageBase64);
            imgObj.add("image_url", urlObj);
            contentArr.add(imgObj);
            
            userMsg.add("content", contentArr);
        }
        messages.add(userMsg);

        payload.add("messages", messages);

        // Convert the Java Object into a clean JSON String
        String jsonPayload = gson.toJson(payload);

        // 2. Send the HTTP Request with retry on transient failures
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_API_URL))
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    System.out
                            .println("[Groq] Retry attempt " + attempt + "/" + MAX_RETRIES + " for model " + modelName);
                    Thread.sleep(1000L * attempt); // back-off: 1s, 2s
                }

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                int statusCode = response.statusCode();

                // Debug: log status and truncated response
                System.out.println("[Groq] HTTP " + statusCode + " | Response length: " + body.length());

                if (statusCode == 429) {
                    // Rate-limited — always retry with back-off
                    System.err.println("[Groq] Rate limited (429). Waiting before retry...");
                    lastException = new RuntimeException("Rate limited");
                    Thread.sleep(2000L * (attempt + 1));
                    continue;
                }

                if (statusCode != 200) {
                    System.err.println("[Groq] Error response: " + body.substring(0, Math.min(500, body.length())));
                    return "Sorry, the AI service returned an error. Please try again.";
                }

                // Safely extract the AI's response text using Gson
                JsonObject responseJson = JsonParser.parseString(body).getAsJsonObject();

                String content = responseJson
                        .getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                // Check for truncation
                String finishReason = responseJson
                        .getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .get("finish_reason").getAsString();
                if (!"stop".equals(finishReason)) {
                    System.out
                            .println("[Groq] Warning: finish_reason=" + finishReason + " (response may be truncated)");
                }

                System.out.println("[Groq] AI said (" + content.length() + " chars): "
                        + content.substring(0, Math.min(100, content.length())) + "...");
                return content;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "Error: Request interrupted.";
            } catch (Exception e) {
                lastException = e;
                System.err.println("[Groq] API Error (attempt " + (attempt + 1) + "): " + e.getMessage());
            }
        }

        // All retries exhausted
        System.err.println("[Groq] All retries failed. Last error: " +
                (lastException != null ? lastException.getMessage() : "unknown"));
        return "Error: Could not reach the AI service after " + (MAX_RETRIES + 1) + " attempts. " +
                "Check your internet connection and GROQ_API_KEY.";
    }

    public void extractSessionProfile() {
        if (conversationHistory.isEmpty()) return;
        
        System.out.println("--> Extracting session profile from history...");
        List<JsonObject> historySnapshot = new ArrayList<>(conversationHistory);
        
        CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            for (JsonObject msg : historySnapshot) {
                sb.append(msg.get("role").getAsString()).append(": ").append(msg.get("content").getAsString()).append("\n");
            }
            
            String prompt = "Review this conversation history between a USER and an ASSISTANT. " +
                            "Identify any NEW personal facts, user preferences, habits, goals, dislikes, or nicknames that the assistant should remember for the future. " +
                            "Output them strictly as commands (e.g. [CMD:ADD_PREFERENCE:likes coffee], [CMD:ADD_NOTE:plays guitar]). " +
                            "Only extract solid, explicit facts. If nothing new is found, output nothing.\n\n" + 
                            "Conversation Window:\n" + sb.toString();
            
            try {
                String result = callGroqApi(CHAT_MODEL, prompt, null, null, "You are a background profiling agent. Output strictly the ADD commands based on the history provided. Do not include introductory or concluding text.");
                
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[CMD:([^\\]]+)\\]").matcher(result);
                boolean updated = false;
                while (m.find()) {
                    String cmdStr = m.group(1);
                    String[] parts = cmdStr.split(":", 2);
                    if (parts.length == 2) {
                        String type = parts[0];
                        String arg = parts[1];
                        switch (type) {
                            case "ADD_PREFERENCE": ProfileManager.getInstance().getUserProfile().preferences.add(arg); updated = true; break;
                            case "ADD_DISLIKE": ProfileManager.getInstance().getUserProfile().dislikes.add(arg); updated = true; break;
                            case "ADD_GOAL": ProfileManager.getInstance().getUserProfile().goals.add(arg); updated = true; break;
                            case "ADD_HABIT": ProfileManager.getInstance().getUserProfile().habits.add(arg); updated = true; break;
                            case "ADD_NICKNAME": ProfileManager.getInstance().getUserProfile().nicknames.add(arg); updated = true; break;
                            case "ADD_NOTE": ProfileManager.getInstance().getUserProfile().notes.add(arg); updated = true; break;
                        }
                    }
                }
                if (updated) {
                    System.out.println("--> Background profiled successfully, saved " + historySnapshot.size() + " turns of context.");
                    ProfileManager.getInstance().saveProfile();
                }
            } catch (Exception e) {
                System.err.println("Session profile extraction failed: " + e.getMessage());
            }
        });
    }
}