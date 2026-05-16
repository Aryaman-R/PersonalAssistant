package com.sentient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-master credential vault. Holds two kinds of secrets:
 *
 * <ol>
 *   <li><b>Environment variables</b> — flat name/value pairs the AI/tools can
 *       reference by name (e.g. {@code STRIPE_API_KEY}). Values are encrypted
 *       at rest; the unencrypted value is returned to authenticated callers
 *       (since these are typically passed through to subprocesses).</li>
 *   <li><b>Service logins</b> — URL + username + password + optional notes.
 *       Passwords are encrypted at rest AND masked in API responses. The raw
 *       password only leaves the master via the {@link #autofillSnapshot}
 *       method, which is what feeds the in-browser auto-fill helper.</li>
 * </ol>
 *
 * <p>The vault is the single source of truth on the master. Every device that
 * fetches {@code /api/vault/*} sees the same list, so "sync across instances"
 * is a property of the architecture rather than a separate replication
 * mechanism.
 *
 * <p>Encryption: AES-256-GCM with a per-machine key stored at
 * {@code ~/.sentient_vault_key} (file mode 0600). The key is generated on
 * first run. Note that we are NOT promising security against a local-disk
 * attacker — defense-in-depth, not authenticated encryption of an
 * untrusted store.
 */
public class CredentialVault {

    private static final Path VAULT_FILE = Paths.get(System.getProperty("user.home"), ".sentient_vault.json");
    private static final Path KEY_FILE   = Paths.get(System.getProperty("user.home"), ".sentient_vault_key");
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final Object lock = new Object();
    private final Map<String, String> envVars = new HashMap<>(); // plaintext after decryption
    private final Map<String, Credential> credentials = new HashMap<>();
    private SecretKey key;

    /** Process-wide handle so system-prompt builders can read names without an injection chain. */
    private static volatile CredentialVault INSTANCE;
    public static CredentialVault current() { return INSTANCE; }

    public CredentialVault() {
        try {
            this.key = loadOrCreateKey();
            loadFromDisk();
            INSTANCE = this;
        } catch (Exception e) {
            System.err.println("[Vault] Failed to initialize: " + e.getMessage());
        }
    }

    // ── Env vars ───────────────────────────────────────

    public List<JsonObject> listEnvVars() {
        synchronized (lock) {
            List<JsonObject> out = new ArrayList<>();
            for (Map.Entry<String, String> e : envVars.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getKey());
                o.addProperty("value", e.getValue());
                out.add(o);
            }
            return out;
        }
    }

    /** Returns the raw value (for use by automation/tool layers). */
    public String getEnvVar(String name) {
        synchronized (lock) { return envVars.get(name); }
    }

    public void setEnvVar(String name, String value) {
        if (name == null || name.isBlank()) return;
        synchronized (lock) {
            envVars.put(name.trim(), value == null ? "" : value);
            persist();
        }
    }

    public boolean removeEnvVar(String name) {
        synchronized (lock) {
            boolean removed = envVars.remove(name) != null;
            if (removed) persist();
            return removed;
        }
    }

    // ── Credentials ────────────────────────────────────

    public static class Credential {
        public String name;          // friendly id used by the AI ("gmail", "amazon")
        public String url;           // login URL
        public String username;
        public String password;      // plaintext only inside the master
        public String notes;

        public JsonObject toMaskedJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name == null ? "" : name);
            o.addProperty("url", url == null ? "" : url);
            o.addProperty("username", username == null ? "" : username);
            o.addProperty("hasPassword", password != null && !password.isEmpty());
            o.addProperty("notes", notes == null ? "" : notes);
            return o;
        }
    }

    public JsonArray listCredentialsMasked() {
        JsonArray arr = new JsonArray();
        synchronized (lock) {
            for (Credential c : credentials.values()) arr.add(c.toMaskedJson());
        }
        return arr;
    }

    public void setCredential(String name, String url, String username, String password, String notes) {
        if (name == null || name.isBlank()) return;
        synchronized (lock) {
            Credential c = credentials.computeIfAbsent(name.trim(), k -> new Credential());
            c.name = name.trim();
            if (url != null) c.url = url;
            if (username != null) c.username = username;
            // Empty string means "leave password alone on an update"; null means clear.
            if (password != null && !password.isEmpty()) c.password = password;
            else if (password == null) c.password = "";
            if (notes != null) c.notes = notes;
            persist();
        }
    }

    public boolean removeCredential(String name) {
        synchronized (lock) {
            boolean removed = credentials.remove(name) != null;
            if (removed) persist();
            return removed;
        }
    }

    /**
     * Returns a snapshot suitable for the auto-fill helper: URL + username +
     * password. Designed to be sent to the originating browser tab over a WS
     * message — not exposed over plain REST GETs.
     */
    public JsonObject autofillSnapshot(String name) {
        synchronized (lock) {
            Credential c = credentials.get(name);
            if (c == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("name", c.name);
            o.addProperty("url", c.url == null ? "" : c.url);
            o.addProperty("username", c.username == null ? "" : c.username);
            o.addProperty("password", c.password == null ? "" : c.password);
            return o;
        }
    }

    /** Names only — safe to include in the AI's system prompt. */
    public List<String> credentialNames() {
        synchronized (lock) {
            return new ArrayList<>(credentials.keySet());
        }
    }

    public List<String> envVarNames() {
        synchronized (lock) {
            return new ArrayList<>(envVars.keySet());
        }
    }

    // ── Persistence (encrypted) ────────────────────────

    private SecretKey loadOrCreateKey() throws Exception {
        if (Files.exists(KEY_FILE)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(KEY_FILE).trim());
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(raw));
        try { Files.setPosixFilePermissions(KEY_FILE, PosixFilePermissions.fromString("rw-------")); }
        catch (Exception ignored) {}
        return new SecretKeySpec(raw, "AES");
    }

    private String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            System.err.println("[Vault] Encrypt failed: " + e.getMessage());
            return "";
        }
    }

    private String decrypt(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(all, 0, iv, 0, GCM_IV_BYTES);
            byte[] ct = new byte[all.length - GCM_IV_BYTES];
            System.arraycopy(all, GCM_IV_BYTES, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Vault] Decrypt failed: " + e.getMessage());
            return "";
        }
    }

    private void loadFromDisk() {
        try {
            if (!Files.exists(VAULT_FILE)) return;
            String raw = Files.readString(VAULT_FILE).trim();
            if (raw.isEmpty()) return;
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("envVars") && root.get("envVars").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("envVars")) {
                    JsonObject o = el.getAsJsonObject();
                    String n = o.has("name") ? o.get("name").getAsString() : "";
                    String enc = o.has("value") ? o.get("value").getAsString() : "";
                    if (!n.isEmpty()) envVars.put(n, decrypt(enc));
                }
            }
            if (root.has("credentials") && root.get("credentials").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("credentials")) {
                    JsonObject o = el.getAsJsonObject();
                    Credential c = new Credential();
                    c.name = o.has("name") ? o.get("name").getAsString() : "";
                    c.url = o.has("url") ? o.get("url").getAsString() : "";
                    c.username = o.has("username") ? o.get("username").getAsString() : "";
                    c.password = o.has("password") ? decrypt(o.get("password").getAsString()) : "";
                    c.notes = o.has("notes") ? o.get("notes").getAsString() : "";
                    if (!c.name.isEmpty()) credentials.put(c.name, c);
                }
            }
        } catch (Exception e) {
            System.err.println("[Vault] Load failed: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            JsonObject root = new JsonObject();
            JsonArray envArr = new JsonArray();
            for (Map.Entry<String, String> e : envVars.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getKey());
                o.addProperty("value", encrypt(e.getValue()));
                envArr.add(o);
            }
            root.add("envVars", envArr);
            JsonArray credArr = new JsonArray();
            for (Credential c : credentials.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", c.name);
                o.addProperty("url", c.url == null ? "" : c.url);
                o.addProperty("username", c.username == null ? "" : c.username);
                o.addProperty("password", encrypt(c.password));
                o.addProperty("notes", c.notes == null ? "" : c.notes);
                credArr.add(o);
            }
            root.add("credentials", credArr);
            Files.writeString(VAULT_FILE, root.toString());
            try { Files.setPosixFilePermissions(VAULT_FILE, PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[Vault] Persist failed: " + e.getMessage());
        }
    }
}
