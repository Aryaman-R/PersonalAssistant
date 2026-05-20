package com.sentient.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent list of peers this master has paired with, plus an in-memory
 * tracker for unredeemed pairing phrases.
 *
 * <p>On disk: {@code ~/.sentient_mesh_peers.json}, file mode 0600. Each peer
 * record holds:
 * <ul>
 *   <li>{@code masterId} — the peer's stable UUID.</li>
 *   <li>{@code url} — base URL (e.g. {@code ws://192.168.1.42:7070}); the
 *       sync layer appends {@code /mesh-sync} on connect.</li>
 *   <li>{@code keyB64} — Base64-encoded 32-byte HMAC signing key derived
 *       from the original pairing phrase via {@link MeshCrypto#derivePairKey}.
 *       This key is what authenticates every subsequent reconnect — the
 *       phrase itself is never stored.</li>
 *   <li>{@code lastSyncedMs} — best-effort timestamp of last successful
 *       op exchange.</li>
 *   <li>{@code lastSeenHlc} — vector clock: {@code masterId → HLC.encode()}.
 *       What the sync protocol uses to compute "ops the peer is missing".</li>
 * </ul>
 *
 * <p>Pending pairing phrases live only in memory and expire after a
 * configurable window (default 60 s). On master restart, every pending
 * phrase is discarded — pairing is genuinely one-shot.
 */
public final class PeerRegistry {

    /** How long a generated pairing phrase remains redeemable. */
    public static final long PAIRING_WINDOW_MS = 60_000;

    private static final Path DEFAULT_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_mesh_peers.json");

    private final Path file;
    private final Object lock = new Object();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>(); // masterId → peer
    private final Map<String, Pending> pending = new ConcurrentHashMap<>(); // phrase → pending

    public PeerRegistry() { this(DEFAULT_FILE); }
    public PeerRegistry(Path file) {
        this.file = file;
        load();
    }

    // ── Peers ───────────────────────────────────────────

    public static final class Peer {
        public String masterId;
        public String url;
        public String keyB64;
        public long lastSyncedMs;
        public Map<String, String> lastSeenHlc = new LinkedHashMap<>();

        public byte[] key() { return Base64.getDecoder().decode(keyB64); }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("masterId", masterId);
            o.addProperty("url", url);
            o.addProperty("keyB64", keyB64);
            o.addProperty("lastSyncedMs", lastSyncedMs);
            JsonObject hlc = new JsonObject();
            for (Map.Entry<String, String> e : lastSeenHlc.entrySet()) {
                hlc.addProperty(e.getKey(), e.getValue());
            }
            o.add("lastSeenHlc", hlc);
            return o;
        }

        /** Public-safe JSON: hides the shared key. UI fetches this shape. */
        public JsonObject toPublicJson() {
            JsonObject o = new JsonObject();
            o.addProperty("masterId", masterId);
            o.addProperty("url", url);
            o.addProperty("lastSyncedMs", lastSyncedMs);
            o.addProperty("hasKey", keyB64 != null && !keyB64.isEmpty());
            return o;
        }

        static Peer fromJson(JsonObject o) {
            Peer p = new Peer();
            p.masterId = o.has("masterId") ? o.get("masterId").getAsString() : "";
            p.url = o.has("url") ? o.get("url").getAsString() : "";
            p.keyB64 = o.has("keyB64") ? o.get("keyB64").getAsString() : "";
            p.lastSyncedMs = o.has("lastSyncedMs") ? o.get("lastSyncedMs").getAsLong() : 0L;
            if (o.has("lastSeenHlc") && o.get("lastSeenHlc").isJsonObject()) {
                JsonObject hlc = o.getAsJsonObject("lastSeenHlc");
                for (String k : hlc.keySet()) p.lastSeenHlc.put(k, hlc.get(k).getAsString());
            }
            return p;
        }
    }

    public Peer get(String masterId) { return peers.get(masterId); }

    public Collection<Peer> all() { return peers.values(); }

    public List<Peer> sortedByMasterId() {
        List<Peer> out = new ArrayList<>(peers.values());
        out.sort((a, b) -> a.masterId.compareTo(b.masterId));
        return out;
    }

    /**
     * Insert or update a peer record. The {@code keyB64} on the new peer must
     * be non-empty — we refuse to record a peer without a signing key
     * (that would be a half-paired state we can't authenticate against).
     */
    public Peer upsert(String masterId, String url, byte[] key) {
        if (masterId == null || masterId.isEmpty()) throw new IllegalArgumentException("masterId required");
        if (key == null || key.length == 0) throw new IllegalArgumentException("key required");
        synchronized (lock) {
            Peer p = peers.computeIfAbsent(masterId, k -> new Peer());
            p.masterId = masterId;
            if (url != null && !url.isEmpty()) p.url = url;
            p.keyB64 = Base64.getEncoder().encodeToString(key);
            persist();
            return p;
        }
    }

    public boolean remove(String masterId) {
        synchronized (lock) {
            Peer p = peers.remove(masterId);
            if (p != null) persist();
            return p != null;
        }
    }

    public void recordSync(String masterId, Map<String, String> newHlc) {
        Peer p = peers.get(masterId);
        if (p == null) return;
        synchronized (lock) {
            p.lastSyncedMs = System.currentTimeMillis();
            if (newHlc != null && !newHlc.isEmpty()) p.lastSeenHlc.putAll(newHlc);
            persist();
        }
    }

    // ── Pending pairing phrases ─────────────────────────

    /** Pending phrase awaiting redemption. */
    public static final class Pending {
        public final String phrase;          // normalized form
        public final byte[] key;             // pre-derived for cheap lookup
        public final long expiresMs;
        public Pending(String phrase, byte[] key, long expiresMs) {
            this.phrase = phrase;
            this.key = key;
            this.expiresMs = expiresMs;
        }
        public boolean isExpired() { return System.currentTimeMillis() > expiresMs; }
    }

    /**
     * Generate a fresh pairing phrase + derive its key + stash as pending.
     * Returns the human-readable phrase the caller shows to the user.
     */
    public String createPendingPhrase() {
        purgeExpired();
        String phrase = PairingPhrase.generate();
        byte[] key = MeshCrypto.derivePairKey(phrase);
        pending.put(PairingPhrase.normalize(phrase), new Pending(
                PairingPhrase.normalize(phrase), key, System.currentTimeMillis() + PAIRING_WINDOW_MS));
        return phrase;
    }

    /** Try to claim a pending phrase. Returns the derived key, or null if no match. */
    public byte[] redeemPhrase(String inputPhrase) {
        purgeExpired();
        String norm = PairingPhrase.normalize(inputPhrase);
        Pending p = pending.remove(norm);
        if (p == null || p.isExpired()) return null;
        return p.key;
    }

    /** Number of currently-redeemable phrases (after a purge pass). */
    public int pendingCount() {
        purgeExpired();
        return pending.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresMs <= now);
    }

    // ── Persistence ─────────────────────────────────────

    private void load() {
        try {
            if (!Files.exists(file)) return;
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return;
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("peers") && root.get("peers").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("peers")) {
                    Peer p = Peer.fromJson(el.getAsJsonObject());
                    if (p.masterId != null && !p.masterId.isEmpty()) peers.put(p.masterId, p);
                }
            }
        } catch (Exception e) {
            System.err.println("[PeerRegistry] load failed: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Peer p : peers.values()) arr.add(p.toJson());
            root.add("peers", arr);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) {}
        } catch (IOException e) {
            System.err.println("[PeerRegistry] persist failed: " + e.getMessage());
        }
    }

    /** Test seam — drop all in-memory + on-disk state. */
    public void wipe() {
        synchronized (lock) {
            peers.clear();
            pending.clear();
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    Path file() { return file; }

    // Diagnostics
    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new HashMap<>();
        out.put("peers", peers.size());
        out.put("pendingPhrases", pendingCount());
        return out;
    }
}
