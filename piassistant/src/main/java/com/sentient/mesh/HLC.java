package com.sentient.mesh;

import java.util.Objects;

/**
 * Hybrid Logical Clock value. Three-tuple of {@code (wall, logical, node)}:
 *
 * <ul>
 *   <li>{@code wall} — physical wall-clock milliseconds since the epoch, or
 *       the largest such value this clock has observed, whichever is greater.</li>
 *   <li>{@code logical} — monotonic counter that ticks when two events share
 *       a wall-clock millisecond, so we still get a total order.</li>
 *   <li>{@code node} — the master's UUID. Final tie-breaker between two
 *       masters whose clocks land in the same {@code (wall, logical)} cell.</li>
 * </ul>
 *
 * <p>Ordering is lexicographic on the triple. That makes HLCs a strict total
 * order: any two HLCs from any pair of nodes can be compared deterministically,
 * which is exactly what last-writer-wins CRDTs need.
 *
 * <p>The value is immutable. {@link HlcClock} is the per-node generator.
 *
 * <p>Wire encoding is {@code "<wall>.<logical>.<node>"} — compact, single-line,
 * parseable without quoting. Used directly in op-log NDJSON and in WS frames.
 */
public final class HLC implements Comparable<HLC> {

    /** Wall-clock ms since epoch (or the largest observed). Never negative. */
    public final long wall;
    /** Per-millisecond tie-break counter. Never negative. */
    public final int logical;
    /** Origin master UUID. Never null, never empty. */
    public final String node;

    public HLC(long wall, int logical, String node) {
        if (wall < 0) throw new IllegalArgumentException("wall must be >= 0");
        if (logical < 0) throw new IllegalArgumentException("logical must be >= 0");
        if (node == null || node.isEmpty()) throw new IllegalArgumentException("node required");
        this.wall = wall;
        this.logical = logical;
        this.node = node;
    }

    /**
     * Lexicographic compare on (wall, logical, node). Two HLCs are equal under
     * {@link #compareTo} iff every field matches; the {@link #equals} contract
     * is consistent with that.
     */
    @Override
    public int compareTo(HLC other) {
        int c = Long.compare(this.wall, other.wall);
        if (c != 0) return c;
        c = Integer.compare(this.logical, other.logical);
        if (c != 0) return c;
        return this.node.compareTo(other.node);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HLC h)) return false;
        return wall == h.wall && logical == h.logical && node.equals(h.node);
    }

    @Override
    public int hashCode() { return Objects.hash(wall, logical, node); }

    /** Wire encoding: {@code "<wall>.<logical>.<node>"}. */
    public String encode() { return wall + "." + logical + "." + node; }

    /**
     * Parse the wire encoding produced by {@link #encode()}. The node UUID may
     * itself contain dashes — we split on the first two dots only, so a node
     * like {@code "a1b2c3-d4e5-..."} survives the round-trip.
     */
    public static HLC parse(String s) {
        if (s == null) throw new IllegalArgumentException("HLC string is null");
        int d1 = s.indexOf('.');
        if (d1 < 0) throw new IllegalArgumentException("HLC missing first dot: " + s);
        int d2 = s.indexOf('.', d1 + 1);
        if (d2 < 0) throw new IllegalArgumentException("HLC missing second dot: " + s);
        long wall;
        int logical;
        try {
            wall = Long.parseLong(s.substring(0, d1));
            logical = Integer.parseInt(s.substring(d1 + 1, d2));
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("HLC numeric parse failed: " + s, nfe);
        }
        String node = s.substring(d2 + 1);
        if (node.isEmpty()) throw new IllegalArgumentException("HLC missing node: " + s);
        return new HLC(wall, logical, node);
    }

    @Override
    public String toString() { return encode(); }
}
