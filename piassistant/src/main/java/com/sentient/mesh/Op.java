package com.sentient.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Single CRDT operation. Everything that mutates {@link ReplicatedState} is
 * one of these. Wire-stable JSON shape, serializable by hand (no Gson type
 * registration needed):
 *
 * <pre>
 * {
 *   "id":     "&lt;HLC encoded&gt;",         // unique per op; primary dedup key
 *   "origin": "&lt;master UUID&gt;",         // which master FIRST emitted this op
 *   "kind":   "LWW_SET"|"ORSET_ADD"|"ORSET_REM",
 *   "path":   "profile.username",         // dotted path into the state tree
 *   "payload": { ... }                    // shape depends on kind
 * }
 * </pre>
 *
 * <p>Payload shapes:
 * <ul>
 *   <li>{@code LWW_SET}: {@code { "value": &lt;json&gt;, "ts": "&lt;HLC&gt;" }}.
 *       The op's {@code id} and the payload's {@code ts} are the same value;
 *       it's repeated for readability + so the LWW register can be advanced
 *       on apply without parsing the op id twice.</li>
 *   <li>{@code ORSET_ADD}: {@code { "element": "&lt;string&gt;", "tag": "&lt;HLC&gt;" }}.
 *       The {@code tag} again matches the op id; same reasoning.</li>
 *   <li>{@code ORSET_REM}: {@code { "element": "&lt;string&gt;",
 *       "tags": ["&lt;HLC&gt;", ...] }}. Each tag is an HLC observed by the
 *       remover; the OR-Set tombstones exactly those tags.</li>
 * </ul>
 *
 * <p>Equality is intentionally NOT by object identity — two Op instances with
 * the same {@code id} are the same op. Useful for op-log dedup.
 */
public final class Op {

    public static final String LWW_SET = "LWW_SET";
    public static final String ORSET_ADD = "ORSET_ADD";
    public static final String ORSET_REM = "ORSET_REM";

    public final String id;
    public final String origin;
    public final String kind;
    public final String path;
    public final JsonObject payload;

    public Op(String id, String origin, String kind, String path, JsonObject payload) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id required");
        if (origin == null || origin.isEmpty()) throw new IllegalArgumentException("origin required");
        if (kind == null || kind.isEmpty()) throw new IllegalArgumentException("kind required");
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path required");
        if (payload == null) throw new IllegalArgumentException("payload required");
        if (!LWW_SET.equals(kind) && !ORSET_ADD.equals(kind) && !ORSET_REM.equals(kind)) {
            throw new IllegalArgumentException("unknown kind: " + kind);
        }
        this.id = id;
        this.origin = origin;
        this.kind = kind;
        this.path = path;
        this.payload = payload;
    }

    /** Parse the HLC out of {@link #id}. */
    public HLC hlc() { return HLC.parse(id); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("origin", origin);
        o.addProperty("kind", kind);
        o.addProperty("path", path);
        o.add("payload", payload);
        return o;
    }

    public String toJsonString() { return toJson().toString(); }

    public static Op fromJson(JsonObject o) {
        return new Op(
                o.get("id").getAsString(),
                o.get("origin").getAsString(),
                o.get("kind").getAsString(),
                o.get("path").getAsString(),
                o.getAsJsonObject("payload"));
    }

    public static Op fromJsonString(String line) {
        return fromJson(JsonParser.parseString(line).getAsJsonObject());
    }

    // ── Builders ────────────────────────────────────────

    /** Build an LWW_SET op for a scalar field. */
    public static Op lwwSet(HLC tag, String path, JsonElement value, String origin) {
        JsonObject p = new JsonObject();
        p.add("value", value == null ? JsonNull.INSTANCE : value);
        p.addProperty("ts", tag.encode());
        return new Op(tag.encode(), origin, LWW_SET, path, p);
    }

    public static Op lwwSet(HLC tag, String path, String value, String origin) {
        return lwwSet(tag, path, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value), origin);
    }

    public static Op lwwSet(HLC tag, String path, boolean value, String origin) {
        return lwwSet(tag, path, new JsonPrimitive(value), origin);
    }

    /** Build an OR-Set add op. */
    public static Op orsetAdd(HLC tag, String path, String element, String origin) {
        JsonObject p = new JsonObject();
        p.addProperty("element", element);
        p.addProperty("tag", tag.encode());
        return new Op(tag.encode(), origin, ORSET_ADD, path, p);
    }

    /** Build an OR-Set remove op tombstoning the given observed tags. */
    public static Op orsetRem(HLC opTag, String path, String element, List<HLC> observedTags, String origin) {
        JsonObject p = new JsonObject();
        p.addProperty("element", element);
        JsonArray arr = new JsonArray();
        for (HLC t : observedTags) arr.add(new JsonPrimitive(t.encode()));
        p.add("tags", arr);
        return new Op(opTag.encode(), origin, ORSET_REM, path, p);
    }

    // ── Convenience extractors ──────────────────────────

    /** LWW value as JSON — caller knows what type to coerce. */
    public JsonElement lwwValue() { return payload.get("value"); }

    public HLC lwwTs() { return HLC.parse(payload.get("ts").getAsString()); }

    public String orsetElement() { return payload.get("element").getAsString(); }
    public HLC orsetTag() { return HLC.parse(payload.get("tag").getAsString()); }

    public List<HLC> orsetTags() {
        JsonArray arr = payload.getAsJsonArray("tags");
        List<HLC> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) out.add(HLC.parse(e.getAsString()));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Op op)) return false;
        return id.equals(op.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return kind + "@" + id + ":" + path; }
}
