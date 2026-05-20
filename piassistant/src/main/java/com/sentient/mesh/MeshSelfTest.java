package com.sentient.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
            testOpRoundTrip();
            testOpBuilders();
            testOpLogAppendDedup();
            testOpLogReadback();
            testReplicatedStateApply();
            testReplicatedDocLocalMutations();
            testReplicatedDocBootstrap();
            testReplicatedDocRemoteIdempotent();
            testTwoDocsConverge();
            testTwoDocsConcurrentEdits();
            testListenerFires();
            System.out.println("[MeshSelfTest] all checks PASSED");
        } catch (AssertionError ae) {
            System.err.println("[MeshSelfTest] FAILED: " + ae.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[MeshSelfTest] ERROR: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
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

    // ── Op ──────────────────────────────────────────────

    private static void testOpRoundTrip() {
        HlcClock c = new HlcClock("node-1");
        HLC ts = c.now();
        Op lww = Op.lwwSet(ts, "profile.username", "Aryaman", "node-1");
        String wire = lww.toJsonString();
        Op decoded = Op.fromJsonString(wire);
        require(decoded.equals(lww), "Op equals by id after round-trip");
        require("Aryaman".equals(decoded.lwwValue().getAsString()), "round-trip value");
        require(decoded.lwwTs().equals(ts), "round-trip ts");
        require(decoded.hlc().equals(ts), "id parses to ts");
    }

    private static void testOpBuilders() {
        HlcClock c = new HlcClock("node-1");
        HLC ts = c.now();
        Op s = Op.lwwSet(ts, "profile.restriction_mode", true, "node-1");
        require(Op.LWW_SET.equals(s.kind), "kind");
        require(s.lwwValue().getAsBoolean(), "boolean lww value");

        HLC tag = c.now();
        Op a = Op.orsetAdd(tag, "profile.habits", "running", "node-1");
        require(Op.ORSET_ADD.equals(a.kind), "orset_add kind");
        require("running".equals(a.orsetElement()), "orset element");

        HLC remTag = c.now();
        List<HLC> obs = new ArrayList<>();
        obs.add(tag);
        Op r = Op.orsetRem(remTag, "profile.habits", "running", obs, "node-1");
        require(Op.ORSET_REM.equals(r.kind), "orset_rem kind");
        require(r.orsetTags().equals(obs), "orset tags survive round-trip");
    }

    // ── OpLog ───────────────────────────────────────────

    private static void testOpLogAppendDedup() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        HlcClock c = new HlcClock("node-1");
        Op op = Op.lwwSet(c.now(), "profile.username", "Aryaman", "node-1");
        require(log.append(op), "first append");
        require(!log.append(op), "second append dedup");
        require(log.size() == 1, "log size 1 after dedup");
        require(log.contains(op.id), "contains by id");
        Files.deleteIfExists(file);
    }

    private static void testOpLogReadback() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        HlcClock c = new HlcClock("node-1");
        Op a = Op.lwwSet(c.now(), "profile.username", "Aryaman", "node-1");
        Op b = Op.orsetAdd(c.now(), "profile.habits", "running", "node-1");
        log.append(a);
        log.append(b);
        // Re-open and verify dedup map is repopulated from disk
        OpLog reopened = new OpLog(file);
        reopened.open();
        require(reopened.contains(a.id), "reopen sees first op");
        require(reopened.contains(b.id), "reopen sees second op");
        List<Op> read = reopened.snapshot();
        require(read.size() == 2, "snapshot has both");
        require(read.get(0).id.equals(a.id), "order preserved");
        require(read.get(1).id.equals(b.id), "order preserved 2");
        Files.deleteIfExists(file);
    }

    // ── ReplicatedState ─────────────────────────────────

    private static void testReplicatedStateApply() {
        ReplicatedState s = new ReplicatedState();
        HlcClock c = new HlcClock("node-1");
        Op u1 = Op.lwwSet(c.now(), "profile.username", "Aryaman", "node-1");
        require(s.apply(u1), "first lww accepted");
        require("Aryaman".equals(s.lwwString("profile.username", "")), "lww visible");
        // Older HLC must lose.
        Op olderShouldLose = Op.lwwSet(new HLC(1L, 0, "node-1"), "profile.username", "OldName", "node-1");
        require(!s.apply(olderShouldLose), "older lww rejected");
        require("Aryaman".equals(s.lwwString("profile.username", "")), "older didn't overwrite");

        // OR-Set
        Op h1 = Op.orsetAdd(c.now(), "profile.habits", "running", "node-1");
        require(s.apply(h1), "orset add accepted");
        require(s.orsetContains("profile.habits", "running"), "orset has element");
        require(!s.apply(h1), "second add idempotent");
    }

    // ── ReplicatedDoc ───────────────────────────────────

    private static void testReplicatedDocLocalMutations() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        ReplicatedDoc doc = new ReplicatedDoc("node-1", log);
        doc.load();

        doc.setLwwString("profile.username", "Aryaman");
        require("Aryaman".equals(doc.state().lwwString("profile.username", "")),
                "username visible after setLwwString");
        doc.addToSet("profile.habits", "running");
        require(doc.state().orsetContains("profile.habits", "running"),
                "habit added");
        Op rem = doc.removeFromSet("profile.habits", "running");
        require(rem != null, "remove returned an op");
        require(!doc.state().orsetContains("profile.habits", "running"),
                "habit removed");
        // Remove a non-member returns null (no op generated).
        require(doc.removeFromSet("profile.habits", "ghost") == null,
                "remove of absent returns null");
        require(log.size() == 3, "log holds 3 ops (set + add + rem)");
        Files.deleteIfExists(file);
    }

    private static void testReplicatedDocBootstrap() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        ReplicatedDoc doc = new ReplicatedDoc("node-1", log);
        doc.load();
        doc.setLwwString("profile.username", "Aryaman");
        doc.setLwwBool("profile.restriction_mode", true);
        doc.addToSet("profile.habits", "running");
        doc.addToSet("profile.habits", "reading");

        // Reload — same log path, fresh ReplicatedDoc.
        OpLog reLog = new OpLog(file);
        reLog.open();
        ReplicatedDoc reDoc = new ReplicatedDoc("node-1", reLog);
        reDoc.load();
        require("Aryaman".equals(reDoc.state().lwwString("profile.username", "")),
                "username survived restart");
        require(reDoc.state().lwwBool("profile.restriction_mode", false),
                "restriction_mode survived");
        require(reDoc.state().orsetContains("profile.habits", "running"),
                "running habit survived");
        require(reDoc.state().orsetContains("profile.habits", "reading"),
                "reading habit survived");
        Files.deleteIfExists(file);
    }

    private static void testReplicatedDocRemoteIdempotent() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        ReplicatedDoc doc = new ReplicatedDoc("node-1", log);
        doc.load();

        // Build a remote op by hand.
        HlcClock remote = new HlcClock("node-2");
        Op remoteOp = Op.lwwSet(remote.now(), "profile.username", "Sister", "node-2");
        require(doc.applyRemote(remoteOp), "first apply effective");
        require(!doc.applyRemote(remoteOp), "second apply idempotent (op already in log)");
        require("Sister".equals(doc.state().lwwString("profile.username", "")),
                "remote op visible");
        // Our local clock should have moved past the remote HLC.
        HLC ours = doc.clock().now();
        require(ours.compareTo(remoteOp.hlc()) > 0, "clock moved past remote HLC");
        Files.deleteIfExists(file);
    }

    /**
     * Two masters, both seeing every op. End state must be identical (this is
     * the strong-eventual-consistency property of CRDTs).
     */
    private static void testTwoDocsConverge() throws IOException {
        Path fileA = Files.createTempFile("mesh-test-a-", ".ndjson");
        Path fileB = Files.createTempFile("mesh-test-b-", ".ndjson");
        Files.deleteIfExists(fileA);
        Files.deleteIfExists(fileB);

        OpLog logA = new OpLog(fileA);
        logA.open();
        OpLog logB = new OpLog(fileB);
        logB.open();
        ReplicatedDoc a = new ReplicatedDoc("node-a", logA);
        a.load();
        ReplicatedDoc b = new ReplicatedDoc("node-b", logB);
        b.load();

        // Capture local ops via listener, then ferry them to the peer.
        List<Op> aOut = new ArrayList<>();
        List<Op> bOut = new ArrayList<>();
        a.setOpListener((op, local) -> { if (local) aOut.add(op); });
        b.setOpListener((op, local) -> { if (local) bOut.add(op); });

        a.setLwwString("profile.username", "Aryaman");
        b.addToSet("profile.habits", "running");
        b.addToSet("profile.habits", "reading");
        a.addToSet("profile.habits", "writing");

        // Ferry.
        for (Op op : aOut) b.applyRemote(op);
        for (Op op : bOut) a.applyRemote(op);

        // States must match.
        require("Aryaman".equals(b.state().lwwString("profile.username", "")),
                "username converged on B");
        require("Aryaman".equals(a.state().lwwString("profile.username", "")),
                "username unchanged on A");
        require(a.state().orsetMembers("profile.habits").size() == 3, "A has 3 habits");
        require(b.state().orsetMembers("profile.habits").size() == 3, "B has 3 habits");
        require(a.state().orsetMembers("profile.habits")
                        .equals(b.state().orsetMembers("profile.habits")) ||
                        b.state().orsetMembers("profile.habits")
                                .containsAll(a.state().orsetMembers("profile.habits")),
                "habits match on both sides");

        Files.deleteIfExists(fileA);
        Files.deleteIfExists(fileB);
    }

    /**
     * Concurrent edits on the same field: LWW must pick the later HLC, and
     * both replicas must agree on the winner. Concurrent OR-Set add+remove
     * must add-win.
     */
    private static void testTwoDocsConcurrentEdits() throws IOException {
        Path fileA = Files.createTempFile("mesh-test-a-", ".ndjson");
        Path fileB = Files.createTempFile("mesh-test-b-", ".ndjson");
        Files.deleteIfExists(fileA);
        Files.deleteIfExists(fileB);

        OpLog logA = new OpLog(fileA);
        logA.open();
        OpLog logB = new OpLog(fileB);
        logB.open();
        ReplicatedDoc a = new ReplicatedDoc("node-a", logA);
        a.load();
        ReplicatedDoc b = new ReplicatedDoc("node-b", logB);
        b.load();

        List<Op> aOut = new ArrayList<>();
        List<Op> bOut = new ArrayList<>();
        a.setOpListener((op, local) -> { if (local) aOut.add(op); });
        b.setOpListener((op, local) -> { if (local) bOut.add(op); });

        // Concurrent LWW: A writes "A-value", B writes "B-value". The two HLCs
        // share a wall but differ in node, so the total order tie-breaks on
        // node id (b > a alphabetically). The winner is deterministic.
        a.setLwwString("profile.username", "A-value");
        b.setLwwString("profile.username", "B-value");
        for (Op op : aOut) b.applyRemote(op);
        for (Op op : bOut) a.applyRemote(op);
        String winnerA = a.state().lwwString("profile.username", "");
        String winnerB = b.state().lwwString("profile.username", "");
        require(winnerA.equals(winnerB),
                "concurrent LWW resolves to same winner on both replicas: "
                        + winnerA + " vs " + winnerB);

        aOut.clear();
        bOut.clear();

        // Concurrent OR-Set add + remove of the same element. Should add-win.
        a.addToSet("profile.habits", "eggs");
        for (Op op : aOut) b.applyRemote(op);
        aOut.clear();
        // Now both replicas see the tag. B adds again; A removes (with only A's view).
        b.addToSet("profile.habits", "eggs");
        // A removes — A only sees its own tag, not B's later one.
        a.removeFromSet("profile.habits", "eggs");
        // Ferry both.
        for (Op op : aOut) b.applyRemote(op);
        for (Op op : bOut) a.applyRemote(op);
        require(a.state().orsetContains("profile.habits", "eggs"),
                "add-wins on A");
        require(b.state().orsetContains("profile.habits", "eggs"),
                "add-wins on B");

        Files.deleteIfExists(fileA);
        Files.deleteIfExists(fileB);
    }

    private static void testListenerFires() throws IOException {
        Path file = Files.createTempFile("mesh-test-", ".ndjson");
        Files.deleteIfExists(file);
        OpLog log = new OpLog(file);
        log.open();
        ReplicatedDoc doc = new ReplicatedDoc("node-1", log);
        doc.load();
        int[] localCount = { 0 };
        int[] remoteCount = { 0 };
        doc.setOpListener((op, isLocal) -> {
            if (isLocal) localCount[0]++; else remoteCount[0]++;
        });
        doc.setLwwString("profile.username", "Aryaman");
        require(localCount[0] == 1, "local listener fires once");

        HlcClock other = new HlcClock("node-2");
        Op fromPeer = Op.orsetAdd(other.now(), "profile.habits", "reading", "node-2");
        doc.applyRemote(fromPeer);
        require(remoteCount[0] == 1, "remote listener fires once");
        // Re-deliver — listener should NOT fire (already in log).
        doc.applyRemote(fromPeer);
        require(remoteCount[0] == 1, "duplicate remote does not fire listener again");

        Files.deleteIfExists(file);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // suppress unused-import lint
    @SuppressWarnings("unused")
    private static void touch() {
        new HashSet<>();
        new JsonObject().add("x", new JsonPrimitive(1));
        UUID.randomUUID();
    }
}
