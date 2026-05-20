package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * Episodic memory store backed by an append-only NDJSON file at
 * {@code ~/.sentient_memories.ndjson}.
 *
 * Every chat turn (user + assistant) is captured here, plus any explicit
 * "remember this" facts. At chat time the WebServer calls
 * {@link #recall(String, int)} to pull the top-k most relevant past entries
 * and injects them into the system prompt as a {@code ## Relevant memories}
 * block — the leap from a 20-turn rolling history to actually remembering.
 *
 * Retrieval scoring is BM25-style token overlap with a recency bias, written
 * in pure Java so the assistant doesn't grow a new heavy dependency. The
 * design intentionally leaves room to swap in a real embedding model
 * (BGE-small ONNX) later — the {@link #scoreEntry} method is the only thing
 * that would need to change.
 *
 * Thread-safety: writes go through a synchronized append; the in-memory
 * mirror is a {@link ConcurrentLinkedDeque} for cheap iteration. Concurrent
 * deletes serialize on the file lock.
 */
public class MemoryService {

    public static final Path DEFAULT_PATH = Paths.get(
            System.getProperty("user.home"), ".sentient_memories.ndjson");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{Nd}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "am", "are", "was", "were", "be", "been", "being",
            "of", "in", "on", "at", "to", "for", "and", "or", "but", "if", "then",
            "this", "that", "these", "those", "i", "you", "he", "she", "it", "we",
            "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
            "our", "their", "do", "does", "did", "have", "has", "had", "will", "would",
            "could", "should", "can", "may", "might", "must", "with", "as", "by",
            "from", "about", "into", "out", "up", "down", "over", "under", "again");
    private static final int MAX_INDEXED = 5000; // cap on in-memory mirror

    public static class Entry {
        public String id;          // sha-1 of ts + content (dedup)
        public long ts;            // ms since epoch
        public String namespace;   // "chat", "fact", "summary", …
        public String source;      // free-text: which engine, which device
        public String role;        // "user" | "assistant" | "system" | "fact"
        public String content;     // raw text
        public List<String> tags;  // optional, lowercased

        public Entry() { this.tags = new ArrayList<>(); }
    }

    private final Path path;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ConcurrentLinkedDeque<Entry> mirror = new ConcurrentLinkedDeque<>();
    /** Average doc length, kept fresh for BM25. */
    private volatile double avgDocLen = 1.0;
    private final Object writeLock = new Object();

    /** Singleton-ish handle for the LLM services to look up the current instance. */
    private static volatile MemoryService current;
    public static MemoryService current() { return current; }

    public MemoryService() {
        this(DEFAULT_PATH);
    }

    public MemoryService(Path path) {
        this.path = path;
        load();
        current = this;
    }

    // ── Loading + persistence ──────────────────────────

    private void load() {
        try {
            if (!Files.exists(path)) {
                Files.createFile(path);
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    Entry e = gson.fromJson(line, Entry.class);
                    if (e != null && e.content != null) {
                        mirror.add(e);
                    }
                } catch (Exception parseEx) {
                    // skip corrupted line; keep going so one bad record doesn't lose the store
                }
            }
            recomputeAvgDocLen();
            // If the mirror grew beyond MAX_INDEXED, trim the oldest from memory only;
            // the file still holds the full history.
            while (mirror.size() > MAX_INDEXED) mirror.pollFirst();
        } catch (IOException e) {
            System.err.println("[Memory] Could not load store: " + e.getMessage());
        }
    }

    private void recomputeAvgDocLen() {
        long total = 0;
        long n = 0;
        for (Entry e : mirror) {
            total += tokenize(e.content).size();
            n++;
        }
        avgDocLen = n == 0 ? 1.0 : Math.max(1.0, (double) total / n);
    }

    private void appendLine(Entry e) throws IOException {
        synchronized (writeLock) {
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(gson.toJson(e));
                w.newLine();
            }
        }
    }

    /** Rewrite the file with the current mirror. Used by delete operations. */
    private void rewriteFile() throws IOException {
        synchronized (writeLock) {
            List<String> lines = new ArrayList<>();
            for (Entry e : mirror) lines.add(gson.toJson(e));
            Files.write(path, lines, StandardCharsets.UTF_8);
        }
    }

    // ── Public API ─────────────────────────────────────

    /**
     * Save a memory. {@code namespace}: "chat" for conversation turns,
     * "fact" for user-asserted facts, "summary" for periodic rollups.
     */
    public Entry remember(String namespace, String role, String content, String source) {
        if (content == null || content.isBlank()) return null;
        Entry e = new Entry();
        e.ts = System.currentTimeMillis();
        e.namespace = namespace == null ? "chat" : namespace;
        e.role = role == null ? "system" : role;
        e.source = source == null ? "" : source;
        e.content = content.trim();
        e.id = idFor(e.ts, e.content);
        e.tags = autoTag(e.content);
        mirror.add(e);
        while (mirror.size() > MAX_INDEXED) mirror.pollFirst();
        recomputeAvgDocLen();
        try {
            appendLine(e);
        } catch (IOException ioe) {
            System.err.println("[Memory] Append failed: " + ioe.getMessage());
        }
        return e;
    }

    /** Return up to k most relevant memories for the given query, newest-first on tie. */
    public List<Entry> recall(String query, int k) {
        if (query == null || query.isBlank() || mirror.isEmpty()) return List.of();
        List<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) return List.of();

        Map<String, Double> idf = computeIdf(qTokens);

        // Score every entry, keep top-k.
        List<double[]> scored = new ArrayList<>(); // [index, score]
        List<Entry> snapshot = new ArrayList<>(mirror);
        long now = System.currentTimeMillis();
        for (int i = 0; i < snapshot.size(); i++) {
            double s = scoreEntry(snapshot.get(i), qTokens, idf, now);
            if (s > 0) scored.add(new double[]{i, s});
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            out.add(snapshot.get((int) scored.get(i)[0]));
        }
        return out;
    }

    /** List all memories, newest first. */
    public List<Entry> all() {
        List<Entry> copy = new ArrayList<>(mirror);
        Collections.reverse(copy);
        return copy;
    }

    /** Remove a single memory by id. Returns true if found + removed. */
    public boolean forgetById(String id) {
        if (id == null) return false;
        boolean removed = mirror.removeIf(e -> id.equals(e.id));
        if (removed) {
            try { rewriteFile(); } catch (IOException ioe) {
                System.err.println("[Memory] Rewrite after delete failed: " + ioe.getMessage());
            }
            recomputeAvgDocLen();
        }
        return removed;
    }

    /**
     * Remove all memories whose content matches the given topic (substring,
     * case-insensitive). Returns the number deleted.
     */
    public int forgetByTopic(String topic) {
        if (topic == null || topic.isBlank()) return 0;
        String needle = topic.trim().toLowerCase();
        List<Entry> doomed = new ArrayList<>();
        for (Entry e : mirror) {
            if (e.content != null && e.content.toLowerCase().contains(needle)) doomed.add(e);
            else if (e.tags != null && e.tags.contains(needle)) doomed.add(e);
        }
        if (doomed.isEmpty()) return 0;
        mirror.removeAll(doomed);
        try { rewriteFile(); } catch (IOException ioe) {
            System.err.println("[Memory] Rewrite after forget-topic failed: " + ioe.getMessage());
        }
        recomputeAvgDocLen();
        return doomed.size();
    }

    /** Wipe everything. The UI's "purge" button. */
    public void purgeAll() {
        mirror.clear();
        try { rewriteFile(); } catch (IOException ioe) {
            System.err.println("[Memory] Purge rewrite failed: " + ioe.getMessage());
        }
        recomputeAvgDocLen();
    }

    public int size() { return mirror.size(); }

    /**
     * Build a Markdown-ish "## Relevant memories" block for the system prompt.
     * Returns an empty string when nothing relevant is found, so the caller
     * can unconditionally append it.
     */
    public String buildPromptBlock(String query, int k) {
        List<Entry> hits = recall(query, k);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Relevant memories from this user\n");
        sb.append("(These are things the user has said or done before. Use them naturally — never recite verbatim.)\n");
        for (Entry e : hits) {
            sb.append("- [").append(prettyAge(e.ts)).append(" · ").append(e.role).append("] ")
              .append(truncate(e.content, 280)).append("\n");
        }
        return sb.toString();
    }

    // ── Scoring (BM25 + recency) ───────────────────────

    private double scoreEntry(Entry e, List<String> qTokens, Map<String, Double> idf, long now) {
        List<String> tokens = tokenize(e.content);
        if (tokens.isEmpty()) return 0.0;
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);

        double k1 = 1.5;
        double b = 0.75;
        double docLen = tokens.size();
        double bm25 = 0.0;
        for (String qt : qTokens) {
            int f = tf.getOrDefault(qt, 0);
            if (f == 0) continue;
            double idfQ = idf.getOrDefault(qt, 0.0);
            double num = f * (k1 + 1);
            double den = f + k1 * (1 - b + b * (docLen / avgDocLen));
            bm25 += idfQ * (num / den);
        }
        if (bm25 == 0.0) return 0.0;

        // Recency boost: 1.0 today, ~0.5 at 30 days, asymptotic floor.
        double days = Math.max(0, (now - e.ts) / 86_400_000.0);
        double recency = 1.0 / (1.0 + days / 30.0);

        // Light role weighting: user statements are usually more "fact-like".
        double roleBoost = "fact".equals(e.namespace) ? 1.4
                : "user".equals(e.role) ? 1.1
                : 1.0;

        return bm25 * (0.85 + 0.15 * recency) * roleBoost;
    }

    private Map<String, Double> computeIdf(List<String> qTokens) {
        int n = mirror.size();
        Map<String, Integer> df = new HashMap<>();
        for (String t : qTokens) df.put(t, 0);
        for (Entry e : mirror) {
            Set<String> uniq = new HashSet<>(tokenize(e.content));
            for (String t : qTokens) {
                if (uniq.contains(t)) df.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        for (String t : qTokens) {
            int dfT = df.getOrDefault(t, 0);
            // Robertson-Sparck-Jones IDF with +0.5 smoothing, clamped to non-negative.
            double v = Math.log(1.0 + (n - dfT + 0.5) / (dfT + 0.5));
            idf.put(t, Math.max(0.0, v));
        }
        return idf;
    }

    // ── Tokenisation + tagging ─────────────────────────

    static List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        var m = TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group().toLowerCase();
            if (t.length() <= 1) continue;
            if (STOPWORDS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    /** Very lightweight tagger: pick the longest tokens, treat them as keywords. */
    private List<String> autoTag(String content) {
        List<String> toks = tokenize(content);
        // Sort by length desc, dedupe, keep top 5. Good enough for a hint.
        Set<String> seen = new HashSet<>();
        List<String> sorted = new ArrayList<>(toks);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        List<String> out = new ArrayList<>();
        for (String t : sorted) {
            if (seen.add(t)) out.add(t);
            if (out.size() >= 5) break;
        }
        return out;
    }

    // ── Helpers ────────────────────────────────────────

    private static String idFor(long ts, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((ts + ":" + content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String prettyAge(long ts) {
        long delta = System.currentTimeMillis() - ts;
        long s = delta / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 48) return h + "h ago";
        long d = h / 24;
        if (d < 30) return d + "d ago";
        long mo = d / 30;
        if (mo < 12) return mo + "mo ago";
        return (mo / 12) + "y ago";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── JSON wire format helpers for the REST layer ───

    public JsonObject toJson(Entry e) {
        return JsonParser.parseString(gson.toJson(e)).getAsJsonObject();
    }
}
