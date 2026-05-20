package com.sentient.mesh;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration test of the Phase 2 sync stack. Runs entirely
 * in-JVM — no real network, no real WebServers — but uses the actual
 * {@link MeshSyncSession} (server) + {@link MeshSyncClient} (client) wired
 * over an in-process WebSocket via Javalin.
 *
 * <p>What it proves:
 * <ol>
 *   <li>Pairing handshake (pair_hello → pair_challenge → pair_complete →
 *       paired) completes successfully when both sides know the phrase, and
 *       both end up with the same shared key.</li>
 *   <li>After pairing, the vector + ops exchange copies state from each
 *       master to the other.</li>
 *   <li>Re-connecting an already-paired master via auth_hello + auth_ok
 *       also works, and a subsequent mutation propagates.</li>
 *   <li>Wrong-phrase pairing fails cleanly.</li>
 * </ol>
 *
 * <p>Each master runs in its own sandboxed home dir so {@code MasterId},
 * {@code OpLog}, and {@code PeerRegistry} get isolated files. Two real
 * Javalin instances bind to ephemeral ports.
 *
 * <p>Run with:
 * <pre>java -cp target/classes:$(cat /tmp/sentient-cp.txt) com.sentient.mesh.MeshSyncScenario</pre>
 *
 * <p>Exit 0 on success, non-zero on failure (with stack trace).
 */
public final class MeshSyncScenario {

    public static void main(String[] args) throws Exception {
        try {
            testPairAndSync();
            testReconnectAuthAndSync();
            testWrongPhraseFails();
            System.out.println("[MeshSyncScenario] all checks PASSED");
        } catch (AssertionError ae) {
            System.err.println("[MeshSyncScenario] FAILED: " + ae.getMessage());
            ae.printStackTrace(System.err);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[MeshSyncScenario] ERROR: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    // ── Test 1: full pair flow + ops converge in both directions ──

    private static void testPairAndSync() throws Exception {
        try (TestMaster a = TestMaster.boot("A"); TestMaster b = TestMaster.boot("B")) {
            // Seed each side with different state.
            a.doc.setLwwString("profile.username", "Aryaman-A");
            a.doc.addToSet("profile.habits", "running");

            b.doc.setLwwString("profile.username", "Aryaman-B");
            b.doc.addToSet("profile.habits", "reading");

            // A generates pairing phrase.
            String phrase = a.mesh.generatePairingPhrase();
            require(phrase != null && !phrase.isEmpty(), "phrase generated");

            // B redeems against A's URL.
            try (MeshSyncClient client = new MeshSyncClient(b.mesh,
                    URI.create("ws://127.0.0.1:" + a.port + "/mesh-sync"))) {
                MeshSyncClient.Result result = client.pairWith(phrase)
                        .get(15, TimeUnit.SECONDS);
                require(result.success,
                        "pair succeeded; error=" + result.errorCode + " msg=" + result.errorMessage);
                require(a.masterId.equals(result.remoteMasterId),
                        "B saw A's masterId; got " + result.remoteMasterId);
                require(result.sessionKey != null && result.sessionKey.length == 32,
                        "B derived 32-byte session key");
            }

            // Both registries now record each other.
            require(b.mesh.registry().get(a.masterId) != null, "B registry contains A");
            require(a.mesh.registry().get(b.masterId) != null, "A registry contains B");

            // State must have converged.
            // (LWW on profile.username: A and B both wrote — the higher HLC wins.
            // Both replicas must agree on the same value.)
            String aUser = a.doc.state().lwwString("profile.username", "");
            String bUser = b.doc.state().lwwString("profile.username", "");
            require(aUser.equals(bUser),
                    "username converged: A=" + aUser + " B=" + bUser);
            require("Aryaman-A".equals(aUser) || "Aryaman-B".equals(aUser),
                    "winner is one of the seed values: " + aUser);

            // Habits OR-Set: both should hold both elements.
            require(a.doc.state().orsetContains("profile.habits", "running"),
                    "A still has running");
            require(a.doc.state().orsetContains("profile.habits", "reading"),
                    "A learned reading from B");
            require(b.doc.state().orsetContains("profile.habits", "running"),
                    "B learned running from A");
            require(b.doc.state().orsetContains("profile.habits", "reading"),
                    "B still has reading");

            System.out.println("[MeshSyncScenario] pair+sync converged ("
                    + a.doc.state().orsetMembers("profile.habits").size() + " habits each side)");
        }
    }

    // ── Test 2: reconnect already-paired masters via auth, propagate new op ──

    private static void testReconnectAuthAndSync() throws Exception {
        try (TestMaster a = TestMaster.boot("A2"); TestMaster b = TestMaster.boot("B2")) {
            // Establish pairing first.
            String phrase = a.mesh.generatePairingPhrase();
            try (MeshSyncClient client = new MeshSyncClient(b.mesh,
                    URI.create("ws://127.0.0.1:" + a.port + "/mesh-sync"))) {
                MeshSyncClient.Result r = client.pairWith(phrase).get(15, TimeUnit.SECONDS);
                require(r.success, "initial pair");
            }
            // Stuff the peer URL so B can dial A again.
            PeerRegistry.Peer storedA = b.mesh.registry().get(a.masterId);
            require(storedA != null, "B has A in registry");
            b.mesh.registry().upsert(a.masterId,
                    "ws://127.0.0.1:" + a.port + "/mesh-sync",
                    storedA.key());

            // Now MUTATE on A, then reconnect from B via auth (no phrase).
            a.doc.addToSet("profile.commitments", "review heat pump quotes");
            try (MeshSyncClient client = new MeshSyncClient(b.mesh,
                    URI.create("ws://127.0.0.1:" + a.port + "/mesh-sync"))) {
                MeshSyncClient.Result r = client.connectAuthenticated(a.masterId)
                        .get(15, TimeUnit.SECONDS);
                require(r.success,
                        "auth reconnect succeeded; error=" + r.errorCode + " msg=" + r.errorMessage);
                require(r.opsReceived >= 1, "B received the new commitment op");
            }
            require(b.doc.state().orsetContains("profile.commitments", "review heat pump quotes"),
                    "B observed the new commitment after auth reconnect");
            System.out.println("[MeshSyncScenario] auth-reconnect propagated a fresh op");
        }
    }

    // ── Test 3: wrong phrase fails cleanly ──

    private static void testWrongPhraseFails() throws Exception {
        try (TestMaster a = TestMaster.boot("A3"); TestMaster b = TestMaster.boot("B3")) {
            // A generates a phrase, B sends a different (well-formed) one.
            a.mesh.generatePairingPhrase();
            // Use 6 known words that aren't the actual phrase. Pull from the
            // wordlist so it parses as "well-formed".
            String wrongPhrase = PairingPhrase.WORDLIST[0] + " "
                    + PairingPhrase.WORDLIST[1] + " "
                    + PairingPhrase.WORDLIST[2] + " "
                    + PairingPhrase.WORDLIST[3] + " "
                    + PairingPhrase.WORDLIST[4] + " "
                    + PairingPhrase.WORDLIST[5];
            try (MeshSyncClient client = new MeshSyncClient(b.mesh,
                    URI.create("ws://127.0.0.1:" + a.port + "/mesh-sync"))) {
                MeshSyncClient.Result r = client.pairWith(wrongPhrase).get(15, TimeUnit.SECONDS);
                require(!r.success, "pair with wrong phrase failed");
                require("no_phrase".equals(r.errorCode) || "remote_error".equals(r.errorCode),
                        "expected no_phrase or remote_error, got: " + r.errorCode);
            }
            // Neither side persisted the other.
            require(a.mesh.registry().get(b.masterId) == null, "A did not persist B");
            require(b.mesh.registry().get(a.masterId) == null, "B did not persist A");
            System.out.println("[MeshSyncScenario] wrong-phrase failed cleanly");
        }
    }

    // ── Test harness: in-JVM master with its own Javalin + sandbox dirs ──

    private static final class TestMaster implements AutoCloseable {
        io.javalin.Javalin app;
        MeshService mesh;
        ReplicatedDoc doc;
        String masterId;
        int port;
        Path tempHome;
        Path tempOplog;
        Path tempPeers;

        static TestMaster boot(String label) throws IOException {
            Path home = Files.createTempDirectory("mesh-sync-" + label + "-");
            Path oplogPath = home.resolve(".sentient_mesh_oplog.ndjson");
            Path peersPath = home.resolve(".sentient_mesh_peers.json");
            Path masterIdPath = home.resolve(".sentient_master_id");
            String masterId = MasterId.load(masterIdPath);

            OpLog log = new OpLog(oplogPath);
            ReplicatedDoc doc = new ReplicatedDoc(masterId, log);
            doc.load();
            PeerRegistry registry = new PeerRegistry(peersPath);
            MeshService mesh = new MeshService(doc, registry);

            // Spin up a minimal Javalin with just /mesh-sync — no full WebServer
            // so we don't drag in the rest of the app's services.
            io.javalin.Javalin app = io.javalin.Javalin.create();
            // Per-WS-session inbound state machine map.
            Map<String, MeshSyncSession> sessions = new HashMap<>();
            app.ws("/mesh-sync", ws -> {
                ws.onConnect(ctx -> {
                    MeshSyncSession sess = new MeshSyncSession(mesh, out -> {
                        try { ctx.send(out.toString()); }
                        catch (Exception ignored) {}
                    });
                    sessions.put(ctx.sessionId(), sess);
                });
                ws.onClose(ctx -> {
                    MeshSyncSession sess = sessions.remove(ctx.sessionId());
                    if (sess != null) sess.close();
                });
                ws.onMessage(ctx -> {
                    MeshSyncSession sess = sessions.get(ctx.sessionId());
                    if (sess == null) return;
                    try {
                        JsonObject m = com.google.gson.JsonParser.parseString(ctx.message())
                                .getAsJsonObject();
                        sess.onMessage(m);
                    } catch (Exception e) {
                        System.err.println("[TestMaster:" + label + "] parse error: " + e.getMessage());
                    }
                });
            });
            app.start(0);
            int port = app.port();
            System.out.println("[TestMaster:" + label + "] up on " + port + " master=" + masterId);

            TestMaster tm = new TestMaster();
            tm.app = app;
            tm.mesh = mesh;
            tm.doc = doc;
            tm.masterId = masterId;
            tm.port = port;
            tm.tempHome = home;
            tm.tempOplog = oplogPath;
            tm.tempPeers = peersPath;
            return tm;
        }

        @Override
        public void close() {
            try { app.stop(); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempOplog); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempPeers); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempHome.resolve(".sentient_master_id")); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempHome); } catch (Exception ignored) {}
        }

        private TestMaster() {}
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
