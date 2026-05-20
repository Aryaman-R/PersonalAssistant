package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentient.util.EnvLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Automation service that triggers configured webhooks in response to AI commands.
 *
 * <p>Configure webhooks via environment variables or the REST API:
 * <pre>
 *   AUTOMATION_WEBHOOK_LIGHTS_ON=https://your-server/hook/lights-on
 *   AUTOMATION_WEBHOOK_LIGHTS_OFF=https://your-server/hook/lights-off
 *   AUTOMATION_API_KEY=your-optional-bearer-token
 * </pre>
 * Any variable matching {@code AUTOMATION_WEBHOOK_<NAME>} becomes a callable automation.
 *
 * <p>The REST endpoint {@code POST /api/automation/trigger} accepts:
 * <pre>
 *   { "name": "lights_on" }
 * </pre>
 * The AI can also trigger automations via voice using the {@code [CMD:AUTOMATE:name]} tag.
 */
public class AutomationService {

    private static final String API_KEY = EnvLoader.get("AUTOMATION_API_KEY", "");
    private static final String ENV_PREFIX = "AUTOMATION_WEBHOOK_";
    private static final int MAX_ERROR_BODY_LENGTH = 200;

    private final HttpClient client;
    private final Gson gson;

    /**
     * In-memory registry of additional webhooks added at runtime
     * (e.g. registered via the REST API during the current session).
     */
    private final Map<String, String> runtimeWebhooks = new LinkedHashMap<>();

    public AutomationService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    /**
     * List all configured automations (name → webhook URL).
     * Sources: AUTOMATION_WEBHOOK_* env vars + runtime-registered webhooks.
     */
    public JsonArray listAutomations() {
        JsonArray arr = new JsonArray();
        // Environment-configured webhooks
        EnvLoader.load();
        Map<String, String> all = getAllWebhooks();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", entry.getKey());
            // Mask the URL for security — show only hostname
            String url = entry.getValue();
            try {
                URI uri = URI.create(url);
                obj.addProperty("endpoint", uri.getHost());
            } catch (Exception e) {
                obj.addProperty("endpoint", "[configured]");
            }
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Register a new webhook at runtime. These persist for the current session only.
     * Use the .env file for permanent configuration.
     */
    public void registerWebhook(String name, String url) {
        if (name != null && !name.isBlank() && url != null && !url.isBlank()) {
            runtimeWebhooks.put(normalise(name), url);
            System.out.println("[Automation] Registered webhook: " + name);
        }
    }

    /**
     * Trigger an automation by name.
     *
     * @param name   automation name (case-insensitive, spaces → underscores)
     * @param params optional JSON payload to POST to the webhook
     * @return a JsonObject with "success" and "message" fields
     */
    public CompletableFuture<JsonObject> trigger(String name, JsonObject params) {
        return CompletableFuture.supplyAsync(() -> {
            String key = normalise(name);
            Map<String, String> all = getAllWebhooks();
            String webhookUrl = all.get(key);

            JsonObject result = new JsonObject();
            if (webhookUrl == null || webhookUrl.isBlank()) {
                String msg = "Automation '" + name + "' is not configured. " +
                        "Add AUTOMATION_WEBHOOK_" + key.toUpperCase() + "=<url> to your .env file.";
                System.err.println("[Automation] " + msg);
                result.addProperty("success", false);
                result.addProperty("message", msg);
                return result;
            }

            try {
                String body = params != null ? gson.toJson(params) : "{}";
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

                if (!API_KEY.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + API_KEY);
                }

                HttpResponse<String> response = EgressClient.global().send(
                        client, reqBuilder.build(), HttpResponse.BodyHandlers.ofString(),
                        "automation", "trigger:" + name,
                        EgressClient.classes(EgressClient.DataClass.WEBHOOK_PAYLOAD));
                int status = response.statusCode();
                boolean ok = status >= 200 && status < 300;

                System.out.println("[Automation] Triggered '" + name + "' → HTTP " + status);
                result.addProperty("success", ok);
                result.addProperty("status", status);
                result.addProperty("message", ok
                        ? "Automation '" + name + "' triggered successfully."
                        : "Webhook returned HTTP " + status + ": " + response.body().substring(
                                0, Math.min(MAX_ERROR_BODY_LENGTH, response.body().length())));

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                result.addProperty("success", false);
                result.addProperty("message", "Request interrupted.");
            } catch (Exception e) {
                System.err.println("[Automation] Error triggering '" + name + "': " + e.getMessage());
                result.addProperty("success", false);
                result.addProperty("message", "Failed to reach webhook: " + e.getMessage());
            }

            return result;
        });
    }

    // ── Helpers ─────────────────────────────────────────

    /** Merge env-configured webhooks with runtime-registered ones. */
    private Map<String, String> getAllWebhooks() {
        Map<String, String> all = new LinkedHashMap<>();
        // Scan all loaded env vars for the prefix
        for (String key : EnvLoader.allKeys()) {
            if (key.startsWith(ENV_PREFIX)) {
                String name = normalise(key.substring(ENV_PREFIX.length()));
                String url = EnvLoader.get(key, "");
                if (!url.isBlank()) {
                    all.put(name, url);
                }
            }
        }
        all.putAll(runtimeWebhooks);
        return all;
    }

    /** Normalise an automation name to lowercase_with_underscores. */
    private static String normalise(String name) {
        return name.toLowerCase().trim().replaceAll("[^a-z0-9]+", "_");
    }
}
