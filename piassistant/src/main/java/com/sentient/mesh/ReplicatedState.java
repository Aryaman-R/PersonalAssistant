package com.sentient.mesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The replicated CRDT document for one mesh. Stores:
 *
 * <ul>
 *   <li>{@code lww} — every LWW-Register keyed by dotted path
 *       ({@code profile.username}, {@code taskLists.&lt;listId&gt;.name},
 *       {@code taskLists.&lt;listId&gt;.items.&lt;itemId&gt;.title}, etc.).
 *       Value type is {@link JsonElement} so we can hold strings, booleans,
 *       and the occasional null without erasure pain.</li>
 *   <li>{@code orsets} — every {@link OrSet} keyed by dotted path
 *       ({@code profile.habits}, {@code taskLists}, etc.). Elements are
 *       opaque strings: literal habit names for primitive sets, UUIDs for
 *       collection-of-records sets.</li>
 * </ul>
 *
 * <p>All public methods are thread-safe via the underlying CRDT primitive
 * locks plus the concurrent maps.
 *
 * <p>State here is pure data. It does NOT own the clock, the op log, or the
 * master id — those live in {@link ReplicatedDoc} which composes this class
 * with {@link HlcClock} and {@link OpLog}.
 *
 * <p>{@link #apply(Op)} is the single entry point for mutation. Local
 * writes always go through it via {@link ReplicatedDoc}; remote ops arrive
 * the same way. That guarantees the state is exactly a function of the set
 * of ops that have ever been applied — replay-deterministic, replay-safe.
 */
public final class ReplicatedState {

    /** Path → LWW register holding the JSON value. */
    private final Map<String, LwwRegister<JsonElement>> lww = new ConcurrentHashMap<>();
    /** Path → OR-Set of string elements (literal values or UUIDs). */
    private final Map<String, OrSet<String>> orsets = new ConcurrentHashMap<>();

    // ── Op application ─────────────────────────────────

    /**
     * Idempotent apply. Same op delivered N times produces the same state.
     * Returns {@code true} when the op caused an observable state change
     * (the LWW register accepted a newer HLC, or the OR-Set actually
     * recorded a new tag / tombstone) — callers use that to decide whether
     * to gossip onwards.
     */
    public boolean apply(Op op) {
        switch (op.kind) {
            case Op.LWW_SET: {
                LwwRegister<JsonElement> reg = lww.computeIfAbsent(op.path, k -> new LwwRegister<>());
                return reg.set(op.lwwValue(), op.lwwTs());
            }
            case Op.ORSET_ADD: {
                OrSet<String> set = orsets.computeIfAbsent(op.path, k -> new OrSet<>());
                return set.add(op.orsetElement(), op.orsetTag());
            }
            case Op.ORSET_REM: {
                OrSet<String> set = orsets.computeIfAbsent(op.path, k -> new OrSet<>());
                return set.remove(op.orsetElement(), op.orsetTags());
            }
            default:
                throw new IllegalArgumentException("unknown op kind: " + op.kind);
        }
    }

    // ── Direct reads (no ops generated) ─────────────────

    /** LWW value at path, or null if never set. */
    public JsonElement lwwValue(String path) {
        LwwRegister<JsonElement> r = lww.get(path);
        return r == null ? null : r.get();
    }

    public String lwwString(String path, String defaultIfMissing) {
        JsonElement el = lwwValue(path);
        if (el == null || el.isJsonNull()) return defaultIfMissing;
        try { return el.getAsString(); } catch (Exception e) { return defaultIfMissing; }
    }

    public boolean lwwBool(String path, boolean defaultIfMissing) {
        JsonElement el = lwwValue(path);
        if (el == null || el.isJsonNull()) return defaultIfMissing;
        try { return el.getAsBoolean(); } catch (Exception e) { return defaultIfMissing; }
    }

    /** Members of an OR-Set at path, in insertion order. Empty if never seen. */
    public Set<String> orsetMembers(String path) {
        OrSet<String> s = orsets.get(path);
        return s == null ? Collections.emptySet() : s.snapshot();
    }

    /** Live HLC tags for an element — what an ORSET_REM op should tombstone. */
    public Set<HLC> orsetTags(String path, String element) {
        OrSet<String> s = orsets.get(path);
        return s == null ? Collections.emptySet() : s.tagsOf(element);
    }

    public boolean orsetContains(String path, String element) {
        OrSet<String> s = orsets.get(path);
        return s != null && s.contains(element);
    }

    // ── Diagnostics ─────────────────────────────────────

    public int lwwCount() { return lww.size(); }
    public int orsetCount() { return orsets.size(); }

    /** Snapshot the set of LWW paths currently held. Stable order: insertion. */
    public Set<String> lwwPaths() { return new LinkedHashSet<>(lww.keySet()); }

    /** Snapshot the set of OR-Set paths currently held. */
    public Set<String> orsetPaths() { return new LinkedHashSet<>(orsets.keySet()); }

    /** Total tombstone count across all OR-Sets — used by compaction. */
    public int totalTombstones() {
        int total = 0;
        for (OrSet<?> s : orsets.values()) total += s.tombstoneCount();
        return total;
    }

    // ── Path helpers (shared with ReplicatedDoc + tests) ─

    public static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null || parts[i].isEmpty()) continue;
            if (b.length() > 0) b.append('.');
            b.append(parts[i]);
        }
        return b.toString();
    }

    // Small helpers used by ReplicatedDoc's view-builder; intentionally not in
    // the public CRDT API.

    static JsonPrimitive prim(String s) { return s == null ? null : new JsonPrimitive(s); }
    static JsonPrimitive prim(boolean b) { return new JsonPrimitive(b); }

    /** Mostly here so external callers can pre-allocate a map without leaking the field. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lww_paths", lww.size());
        out.put("orset_paths", orsets.size());
        out.put("tombstones", totalTombstones());
        return out;
    }

    // ── Utility for callers that need to dump current state ──

    /** For debugging / Phase 2 sync probes. Returns a list of (path, hlc) pairs. */
    public List<String[]> lwwIndex() {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, LwwRegister<JsonElement>> e : lww.entrySet()) {
            HLC ts = e.getValue().ts();
            out.add(new String[] { e.getKey(), ts == null ? "" : ts.encode() });
        }
        return out;
    }

    public boolean hasLww(String path) { return lww.containsKey(path); }
    public boolean hasOrset(String path) { return orsets.containsKey(path); }

    /** Drop everything — used by tests + by fresh-master reset. */
    public void clear() {
        lww.clear();
        orsets.clear();
    }

    // Prevent uses that would short-circuit the apply() pipeline.
    JsonNull markPureData() { return JsonNull.INSTANCE; }
}
