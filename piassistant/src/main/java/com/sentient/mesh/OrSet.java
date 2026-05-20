package com.sentient.mesh;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Observed-Remove Set. The canonical add-wins CRDT: each {@link #add(Object, HLC)}
 * stamps the element with a unique tag (the HLC); each {@link #remove(Object, Collection)}
 * tombstones a specific set of tags. An element is "in" the set if it has at
 * least one non-tombstoned tag.
 *
 * <p>Concurrency model:
 * <pre>
 *   A: add("eggs")           → tag T1 → set contains eggs
 *   B: add("eggs")           → tag T2 → set still contains eggs (two tags)
 *   A: remove("eggs", {T1})  → tombstones T1; T2 still alive → set still contains eggs
 *   B: remove("eggs", {T2})  → tombstones T2 → set finally empty
 * </pre>
 * Concurrent add + remove resolve "add wins" by design, which matches user
 * expectation for habit/task lists (you said "add eggs" + "remove eggs" at
 * roughly the same time? we keep eggs and let the next explicit edit decide).
 *
 * <p>For elements of type {@link String} the OrSet semantics intentionally
 * keep the original casing of the first add; lookups in
 * {@link ProfileManager}-style code that does case-insensitive matching live
 * in the caller, not here.
 *
 * <p>Memory grows linearly with churn. {@link #compact()} discards entries
 * for elements that have zero live tags AND zero remaining tombstones (i.e.
 * the element was added then fully removed AND every peer has acknowledged
 * the removal). Until safe-to-compact tracking lands in Phase 2 we just
 * leave the tombstones in place — bounded by the number of distinct
 * (element, HLC) pairs the user has ever produced.
 */
public final class OrSet<E> {

    /** element → set of add-tags (HLCs) that introduced it. */
    private final Map<E, Set<HLC>> tags = new HashMap<>();
    /** tags that have been observed-removed; never resurrected. */
    private final Set<HLC> tombstones = new HashSet<>();

    /**
     * Apply an add op. The caller (an {@code Op} replayer) generates the tag
     * via the local {@link HlcClock} and passes it in so the same op replayed
     * on multiple peers produces identical state.
     *
     * @return true if this was a new tag (the receiver should record + gossip),
     *         false if we'd already seen it (idempotent replay)
     */
    public synchronized boolean add(E element, HLC tag) {
        if (element == null) throw new IllegalArgumentException("element required");
        if (tag == null) throw new IllegalArgumentException("tag required");
        Set<HLC> ts = tags.computeIfAbsent(element, k -> new LinkedHashSet<>());
        return ts.add(tag);
    }

    /**
     * Apply a remove op. The {@code observedTags} are the HLC tags this
     * remover had observed for the element at the moment of the remove.
     * Any tag in that set becomes a tombstone; later adds with new tags
     * survive (that's the "add wins" property).
     *
     * @return true if at least one tombstone was newly recorded
     */
    public synchronized boolean remove(E element, Collection<HLC> observedTags) {
        if (element == null) throw new IllegalArgumentException("element required");
        if (observedTags == null || observedTags.isEmpty()) return false;
        boolean changed = false;
        for (HLC t : observedTags) {
            if (t != null && tombstones.add(t)) changed = true;
        }
        return changed;
    }

    /** Live tags for an element — what {@link #remove} would tombstone right now. */
    public synchronized Set<HLC> tagsOf(E element) {
        Set<HLC> all = tags.get(element);
        if (all == null) return Collections.emptySet();
        Set<HLC> live = new LinkedHashSet<>();
        for (HLC t : all) if (!tombstones.contains(t)) live.add(t);
        return live;
    }

    public synchronized boolean contains(E element) {
        Set<HLC> all = tags.get(element);
        if (all == null) return false;
        for (HLC t : all) if (!tombstones.contains(t)) return true;
        return false;
    }

    public synchronized Set<E> snapshot() {
        Set<E> out = new LinkedHashSet<>();
        for (Map.Entry<E, Set<HLC>> e : tags.entrySet()) {
            for (HLC t : e.getValue()) {
                if (!tombstones.contains(t)) { out.add(e.getKey()); break; }
            }
        }
        return out;
    }

    public synchronized int size() { return snapshot().size(); }
    public synchronized boolean isEmpty() { return snapshot().isEmpty(); }

    /** Number of tombstones — for diagnostics + compaction heuristics. */
    public synchronized int tombstoneCount() { return tombstones.size(); }
}
