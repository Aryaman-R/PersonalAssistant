package com.sentient.mesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The mesh's per-master document handle. Composes a {@link ReplicatedState}
 * with an {@link HlcClock} (for generating op HLCs) and an {@link OpLog}
 * (for durable persistence), plus the master's stable origin UUID.
 *
 * <p>This is the class that callers in the rest of the app speak to. Local
 * mutations go through the {@code setLww / addToSet / removeFromSet} helpers,
 * each of which:
 *
 * <ol>
 *   <li>Generates a fresh {@link HLC} via the local clock.</li>
 *   <li>Builds the corresponding {@link Op}.</li>
 *   <li>Applies the op to local state (so the next read sees the mutation).</li>
 *   <li>Appends it to the durable {@link OpLog}.</li>
 *   <li>Returns the op so the caller (e.g. Phase 2's MeshService) can gossip
 *       it to peers.</li>
 * </ol>
 *
 * <p>Remote ops arrive via {@link #applyRemote(Op)}, which is idempotent:
 * delivering the same op N times leaves the state unchanged on every call
 * after the first.
 *
 * <p>Bootstrap: {@link #load()} reads the entire op log and replays each op
 * through {@link ReplicatedState#apply(Op)}. Cold starts cost O(opcount)
 * I/O + CPU. With the Phase 1 working assumption of &lt;100k lifetime ops
 * that's milliseconds.
 */
public final class ReplicatedDoc {

    private final String originMasterId;
    private final ReplicatedState state;
    private final HlcClock clock;
    private final OpLog log;
    private final AtomicReference<OpListener> listener = new AtomicReference<>();

    /** Hook so the mesh layer can pick up newly emitted ops for gossip. */
    @FunctionalInterface
    public interface OpListener {
        /**
         * Fired whenever an op was either generated locally or accepted from
         * a remote. Implementations should be fast + non-blocking — they run
         * on the mutating thread.
         *
         * @param op       the op just applied + persisted
         * @param isLocal  true if this master created the op, false if it
         *                 came in via {@link #applyRemote(Op)}
         */
        void onOp(Op op, boolean isLocal);
    }

    public ReplicatedDoc(String originMasterId, OpLog log) {
        if (originMasterId == null || originMasterId.isEmpty())
            throw new IllegalArgumentException("originMasterId required");
        if (log == null) throw new IllegalArgumentException("log required");
        this.originMasterId = originMasterId;
        this.state = new ReplicatedState();
        this.clock = new HlcClock(originMasterId);
        this.log = log;
    }

    public String originMasterId() { return originMasterId; }
    public ReplicatedState state() { return state; }
    public HlcClock clock() { return clock; }
    public OpLog log() { return log; }

    /** Wire the gossip hook. Pass null to clear. */
    public void setOpListener(OpListener l) { listener.set(l); }

    /**
     * Bootstrap: read every line in the op log, apply each op, advance the
     * clock past every HLC. Safe to call multiple times — second call is a
     * no-op because the in-memory dedup map is reset on each {@link OpLog#open()}.
     */
    public void load() throws IOException {
        log.open();
        log.read(op -> {
            state.apply(op);
            clock.receive(op.hlc());
        });
    }

    // ── Local mutators ──────────────────────────────────

    public Op setLwwString(String path, String value) {
        return setLwwInternal(path, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public Op setLwwBool(String path, boolean value) {
        return setLwwInternal(path, new JsonPrimitive(value));
    }

    public Op setLwwInt(String path, long value) {
        return setLwwInternal(path, new JsonPrimitive(value));
    }

    public Op setLwwJson(String path, JsonElement value) {
        return setLwwInternal(path, value == null ? JsonNull.INSTANCE : value);
    }

    private Op setLwwInternal(String path, JsonElement value) {
        HLC ts = clock.now();
        Op op = Op.lwwSet(ts, path, value, originMasterId);
        state.apply(op);
        persistSilently(op);
        fireListener(op, true);
        return op;
    }

    public Op addToSet(String path, String element) {
        HLC tag = clock.now();
        Op op = Op.orsetAdd(tag, path, element, originMasterId);
        state.apply(op);
        persistSilently(op);
        fireListener(op, true);
        return op;
    }

    /**
     * Remove an element. Returns the generated op, or {@code null} if the
     * element wasn't currently in the set (in which case there's nothing to
     * tombstone and we'd just be writing a useless op).
     */
    public Op removeFromSet(String path, String element) {
        Set<HLC> live = state.orsetTags(path, element);
        if (live.isEmpty()) return null;
        HLC opTag = clock.now();
        Op op = Op.orsetRem(opTag, path, element, new ArrayList<>(live), originMasterId);
        state.apply(op);
        persistSilently(op);
        fireListener(op, true);
        return op;
    }

    // ── Remote apply ────────────────────────────────────

    /**
     * Apply a remote op. Idempotent — second delivery of the same op id is a
     * no-op. Returns {@code true} if this call actually changed observable
     * state (the LWW register accepted a newer HLC, the OR-Set learned a new
     * tag, etc.). Useful for the mesh gossip layer to decide whether to keep
     * re-broadcasting.
     */
    public boolean applyRemote(Op op) {
        if (op == null) return false;
        if (log.contains(op.id)) return false;
        boolean changed = state.apply(op);
        persistSilently(op);
        clock.receive(op.hlc());
        fireListener(op, false);
        return changed;
    }

    // ── helpers ─────────────────────────────────────────

    private void persistSilently(Op op) {
        try { log.append(op); }
        catch (IOException ioe) { System.err.println("[ReplicatedDoc] log append failed: " + ioe.getMessage()); }
    }

    private void fireListener(Op op, boolean local) {
        OpListener l = listener.get();
        if (l == null) return;
        try { l.onOp(op, local); }
        catch (Exception e) { System.err.println("[ReplicatedDoc] listener threw: " + e.getMessage()); }
    }
}
