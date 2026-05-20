package com.sentient.mesh;

/**
 * Per-master Hybrid Logical Clock generator. Holds the largest
 * {@code (wall, logical)} this node has either emitted itself or seen from a
 * peer; emits a fresh {@link HLC} on {@link #now()} that's guaranteed to be
 * strictly greater than every prior emission.
 *
 * <p>The standard HLC algorithm (Kulkarni et al., 2014):
 * <pre>
 *   on local event:
 *       phys = wallClock()
 *       if phys &gt; wall:    wall = phys; logical = 0
 *       else:              logical++
 *
 *   on receive remote HLC r:
 *       phys = wallClock()
 *       new = max(phys, wall, r.wall)
 *       if new == wall == r.wall: logical = max(logical, r.logical) + 1
 *       elif new == wall:         logical++
 *       elif new == r.wall:       logical = r.logical + 1
 *       else:                     logical = 0
 *       wall = new
 * </pre>
 *
 * <p>Bounded drift assumption: the algorithm guarantees no more wall-clock
 * skew than roughly the largest observed clock-skew between paired masters.
 * If a peer's wall clock is wildly ahead, we will silently follow it — that
 * means a one-off "rogue" peer can shift the local clock forward, but it
 * cannot cause out-of-order delivery or lost updates. Worst case: future
 * wall-clock readings on this node are ignored until physical time catches
 * up, with {@code logical} doing the ordering.
 *
 * <p>All methods are {@code synchronized}. The clock is mutated by every
 * mesh op + every received op, so contention is real but each call is O(1).
 */
public final class HlcClock {

    private final String nodeId;
    private long wall;
    private int logical;

    public HlcClock(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) throw new IllegalArgumentException("nodeId required");
        this.nodeId = nodeId;
        this.wall = 0L;
        this.logical = 0;
    }

    /**
     * Construct with an initial state — used when bootstrapping from a snapshot
     * whose metadata recorded the highest HLC the previous incarnation emitted.
     */
    public HlcClock(String nodeId, long wall, int logical) {
        this(nodeId);
        if (wall < 0) throw new IllegalArgumentException("wall must be >= 0");
        if (logical < 0) throw new IllegalArgumentException("logical must be >= 0");
        this.wall = wall;
        this.logical = logical;
    }

    public synchronized HLC now() {
        long phys = System.currentTimeMillis();
        if (phys > wall) {
            wall = phys;
            logical = 0;
        } else {
            logical++;
        }
        return new HLC(wall, logical, nodeId);
    }

    /**
     * Merge in a remote HLC and emit a local HLC strictly greater than both.
     * Use this on the receive side of any cross-master message.
     */
    public synchronized HLC receive(HLC remote) {
        if (remote == null) return now();
        long phys = System.currentTimeMillis();
        long oldWall = wall;
        long newWall = Math.max(Math.max(phys, oldWall), remote.wall);
        if (newWall == oldWall && newWall == remote.wall) {
            logical = Math.max(logical, remote.logical) + 1;
        } else if (newWall == oldWall) {
            logical++;
        } else if (newWall == remote.wall) {
            logical = remote.logical + 1;
        } else {
            logical = 0;
        }
        wall = newWall;
        return new HLC(wall, logical, nodeId);
    }

    /** Current state without mutating. */
    public synchronized HLC snapshot() { return new HLC(wall, logical, nodeId); }

    public String nodeId() { return nodeId; }
}
