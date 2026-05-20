package com.sentient.mesh;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hand-rolled smoke tests for the mesh CRDT primitives. Run as
 * {@code java -cp <classpath> com.sentient.mesh.MeshSelfTest}. The project
 * doesn't ship a JUnit dependency so this is the cheapest way to keep these
 * checks in-tree without bloating the fat jar.
 *
 * <p>Every check uses {@code require(...)} which throws {@link AssertionError}
 * on failure regardless of the {@code -ea} flag — so the exit code reliably
 * reflects pass/fail in CI.
 */
public final class MeshSelfTest {

    private MeshSelfTest() {}

    public static void main(String[] args) {
        try {
            testHlcOrdering();
            testHlcEncode();
            testHlcClockMonotonic();
            testHlcClockReceive();
            testLwwRegisterBasic();
            testLwwRegisterIdempotent();
            testOrSetAddRemove();
            testOrSetConcurrentAddRemove();
            testOrSetReAdd();
            System.out.println("[MeshSelfTest] all checks PASSED");
        } catch (AssertionError ae) {
            System.err.println("[MeshSelfTest] FAILED: " + ae.getMessage());
            System.exit(1);
        }
    }

    // ── HLC ─────────────────────────────────────────────

    private static void testHlcOrdering() {
        HLC a = new HLC(100, 0, "node-1");
        HLC b = new HLC(100, 1, "node-1");
        HLC c = new HLC(101, 0, "node-1");
        HLC d = new HLC(100, 0, "node-2");
        require(a.compareTo(b) < 0, "logical breaks tie");
        require(b.compareTo(c) < 0, "wall outranks logical");
        require(a.compareTo(d) < 0, "node breaks total tie");
        require(a.compareTo(a) == 0, "self-compare zero");
    }

    private static void testHlcEncode() {
        HLC original = new HLC(1234567890L, 42, "abc-def-ghi");
        HLC parsed = HLC.parse(original.encode());
        require(original.equals(parsed), "round-trip equality");
        require(original.compareTo(parsed) == 0, "round-trip order");
    }

    private static void testHlcClockMonotonic() {
        HlcClock c = new HlcClock("node-1");
        HLC h1 = c.now();
        HLC h2 = c.now();
        HLC h3 = c.now();
        require(h1.compareTo(h2) < 0, "now() #1 < #2");
        require(h2.compareTo(h3) < 0, "now() #2 < #3");
    }

    private static void testHlcClockReceive() {
        HlcClock a = new HlcClock("node-a");
        HlcClock b = new HlcClock("node-b");
        // a emits an HLC far in the future; b receives it; b's next now()
        // must be strictly greater than that future HLC.
        HLC future = new HLC(System.currentTimeMillis() + 10_000_000L, 0, "node-a");
        HLC bAfter = b.receive(future);
        require(bAfter.compareTo(future) > 0, "receive bumps past remote");
        HLC bNext = b.now();
        require(bNext.compareTo(bAfter) > 0, "now after receive monotonic");
    }

    // ── LwwRegister ─────────────────────────────────────

    private static void testLwwRegisterBasic() {
        HlcClock clock = new HlcClock("node-1");
        LwwRegister<String> reg = new LwwRegister<>();
        require(!reg.isSet(), "fresh register unset");
        require(reg.set("alpha", clock.now()), "first set takes");
        require("alpha".equals(reg.get()), "value stored");
        HLC later = clock.now();
        require(reg.set("beta", later), "later set takes");
        require("beta".equals(reg.get()), "later value visible");
    }

    private static void testLwwRegisterIdempotent() {
        HlcClock clock = new HlcClock("node-1");
        LwwRegister<String> reg = new LwwRegister<>();
        HLC ts1 = clock.now();
        HLC ts2 = clock.now();
        require(reg.set("first", ts2), "later HLC writes");
        require(!reg.set("oops", ts1), "earlier HLC rejected");
        require("first".equals(reg.get()), "earlier write didn't overwrite");
    }

    // ── OrSet ───────────────────────────────────────────

    private static void testOrSetAddRemove() {
        HlcClock clock = new HlcClock("node-1");
        OrSet<String> set = new OrSet<>();
        HLC t1 = clock.now();
        set.add("eggs", t1);
        require(set.contains("eggs"), "added present");
        Set<HLC> live = set.tagsOf("eggs");
        require(live.size() == 1, "one live tag");
        set.remove("eggs", live);
        require(!set.contains("eggs"), "after remove absent");
    }

    private static void testOrSetConcurrentAddRemove() {
        // Master A: add tag T1. Master B: add tag T2 concurrently.
        // Master A then removes — sees only T1, tombstones T1. T2 lives,
        // so the set still contains "eggs" — that's the add-wins semantic.
        HlcClock a = new HlcClock("node-a");
        HlcClock b = new HlcClock("node-b");
        OrSet<String> set = new OrSet<>();
        HLC t1 = a.now();
        HLC t2 = b.now();
        set.add("eggs", t1);
        set.add("eggs", t2);
        require(set.tagsOf("eggs").size() == 2, "two live tags");
        // A removes — only knows about T1.
        Set<HLC> aObserved = new LinkedHashSet<>();
        aObserved.add(t1);
        set.remove("eggs", aObserved);
        require(set.contains("eggs"), "add-wins: T2 still alive after A's remove");
        // Now B removes too (sees only T2 — but in practice B also gossiped T1, so it
        // sees both). Simulate the "B saw both" common case.
        set.remove("eggs", set.tagsOf("eggs"));
        require(!set.contains("eggs"), "after second remove fully absent");
    }

    private static void testOrSetReAdd() {
        HlcClock clock = new HlcClock("node-1");
        OrSet<String> set = new OrSet<>();
        HLC t1 = clock.now();
        set.add("milk", t1);
        set.remove("milk", set.tagsOf("milk"));
        require(!set.contains("milk"), "milk removed");
        HLC t2 = clock.now();
        set.add("milk", t2);
        require(set.contains("milk"), "re-added milk visible");
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // suppress unused-import lint
    @SuppressWarnings("unused")
    private static void touch() { new HashSet<>(); }
}
