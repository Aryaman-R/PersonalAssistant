package com.sentient.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbound side of the mesh sync. One instance per peer connection.
 *
 * <p>Two operating modes:
 * <ul>
 *   <li><b>Pairing mode</b> ({@link #pairWith}): drives the
 *       {@code pair_hello → pair_challenge → pair_complete → paired}
 *       handshake against an initiator that has a pending phrase. Used by
 *       the joiner side of the one-shot pairing flow.</li>
 *   <li><b>Auth mode</b> ({@link #connectAuthenticated}): drives the
 *       {@code auth_hello → auth_ok} handshake using a stored per-peer
 *       HMAC key from {@link PeerRegistry}. Used for every connection
 *       after the initial pairing.</li>
 * </ul>
 *
 * <p>After either handshake completes, the client immediately enters the
 * one-shot sync loop:
 * <ol>
 *   <li>Both sides exchange {@code vector} messages.</li>
 *   <li>Each side replies with the {@code ops} the other is missing.</li>
 *   <li>Both sides apply the received ops via
 *       {@link ReplicatedDoc#applyRemote(Op)}.</li>
 * </ol>
 *
 * <p>The completion future returned by {@link #pairWith}/{@link #connectAuthenticated}
 * resolves once <em>both</em>: (a) the handshake succeeded, and (b) at least
 * one full round of vector+ops has been received. Callers can then close the
 * client; persistent connections + gossip live in a follow-up commit.
 *
 * <p>Thread-safety: a single {@code MeshSyncClient} instance is single-use.
 * After a future completes (or fails) the client is done; create a new one
 * to reconnect.
 */
public final class MeshSyncClient implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final MeshService mesh;
    private final URI uri;

    private final AtomicReference<WebSocket> ws = new AtomicReference<>();
    private final CompletableFuture<Result> completed = new CompletableFuture<>();
    private final StringBuilder textBuffer = new StringBuilder();

    private String mode;             // "pair" | "auth"
    private String pairingPhrase;    // pair mode only
    private byte[] myNonce;
    private byte[] sessionKey;
    private String remoteMasterId;
    private int opsReceived;
    private int opsSent;
    private volatile boolean sentVector = false;
    private volatile boolean receivedPeerOps = false;

    public static final class Result {
        public final boolean success;
        public final String remoteMasterId;
        public final byte[] sessionKey;
        public final int opsReceived;
        public final int opsSent;
        public final String errorCode;
        public final String errorMessage;
        Result(boolean s, String rid, byte[] k, int recv, int sent, String code, String msg) {
            success = s; remoteMasterId = rid; sessionKey = k; opsReceived = recv; opsSent = sent;
            errorCode = code; errorMessage = msg;
        }
    }

    public MeshSyncClient(MeshService mesh, URI uri) {
        if (mesh == null || uri == null) throw new IllegalArgumentException();
        this.mesh = mesh;
        this.uri = uri;
    }

    /**
     * Joiner side of the pairing flow. The {@code phrase} must match a pending
     * phrase on the initiator (generated within the last 60 s by
     * {@link PeerRegistry#createPendingPhrase}).
     *
     * @return future that resolves with {@link Result#success} = true after
     *         pair_complete is accepted by the initiator + the first round of
     *         sync exchanges, or with a populated {@code errorCode} on failure.
     */
    public CompletableFuture<Result> pairWith(String phrase) {
        this.mode = "pair";
        this.pairingPhrase = phrase;
        connectInternal();
        return completed;
    }

    /**
     * Established-peer connect. Reads the stored HMAC key from
     * {@link PeerRegistry} keyed by {@code remoteMasterId}, runs the auth
     * handshake, drives one sync round.
     */
    public CompletableFuture<Result> connectAuthenticated(String remoteMasterIdHint) {
        this.mode = "auth";
        this.remoteMasterId = remoteMasterIdHint;
        PeerRegistry.Peer peer = mesh.registry().get(remoteMasterIdHint);
        if (peer == null || peer.keyB64 == null || peer.keyB64.isEmpty()) {
            completed.complete(new Result(false, remoteMasterIdHint, null, 0, 0, "no_key", "no stored key for peer"));
            return completed;
        }
        this.sessionKey = peer.key();
        connectInternal();
        return completed;
    }

    private void connectInternal() {
        HTTP.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .whenComplete((wsHandle, err) -> {
                    if (err != null) {
                        fail("connect", err.getMessage());
                    } else {
                        ws.set(wsHandle);
                        sendInitialMessage();
                    }
                });
    }

    private void sendInitialMessage() {
        try {
            myNonce = MeshSyncProtocol.randomNonce();
            JsonObject msg = new JsonObject();
            if ("pair".equals(mode)) {
                msg.addProperty("type", MeshSyncProtocol.MSG_PAIR_HELLO);
                msg.addProperty("masterId", mesh.masterId());
                msg.addProperty("nonce", MeshSyncProtocol.bytesToHex(myNonce));
                msg.addProperty("phrase", PairingPhrase.normalize(pairingPhrase));
            } else {
                msg.addProperty("type", MeshSyncProtocol.MSG_AUTH_HELLO);
                msg.addProperty("masterId", mesh.masterId());
                msg.addProperty("nonce", MeshSyncProtocol.bytesToHex(myNonce));
                byte[] proof = MeshCrypto.hmacSha256(sessionKey,
                        MeshSyncProtocol.payloadAuthHello(mesh.masterId(), myNonce));
                msg.addProperty("hmac", java.util.Base64.getEncoder().encodeToString(proof));
            }
            send(msg);
        } catch (Exception e) {
            fail("init_send", e.getMessage());
        }
    }

    // ── Message dispatch (called from Listener.onText after assembling a full line) ──

    private void onMessage(String raw) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            fail("bad_message", "non-JSON: " + raw);
            return;
        }
        String type = optString(msg, "type", "");
        switch (type) {
            case MeshSyncProtocol.MSG_PAIR_CHALLENGE: handlePairChallenge(msg); break;
            case MeshSyncProtocol.MSG_PAIRED:        handlePaired(msg); break;
            case MeshSyncProtocol.MSG_AUTH_OK:       handleAuthOk(msg); break;
            case MeshSyncProtocol.MSG_VECTOR:        handleVector(msg); break;
            case MeshSyncProtocol.MSG_OPS:           handleOps(msg); break;
            case MeshSyncProtocol.MSG_ERROR:         handleError(msg); break;
            default: fail("unknown_type", "unexpected message type: " + type);
        }
    }

    private void handlePairChallenge(JsonObject msg) {
        try {
            this.remoteMasterId = optString(msg, "masterId", "");
            byte[] initNonce = MeshSyncProtocol.hexToBytes(optString(msg, "nonce", ""));
            byte[] gotProof = java.util.Base64.getDecoder().decode(optString(msg, "hmac", ""));
            // Server's identity is implicit — we derive K from the phrase locally.
            byte[] key = MeshCrypto.derivePairKey(PairingPhrase.normalize(pairingPhrase));
            byte[] wantProof = MeshCrypto.hmacSha256(key,
                    MeshSyncProtocol.payloadPairChallenge(remoteMasterId, myNonce));
            if (!MeshCrypto.constantTimeEquals(gotProof, wantProof)) {
                fail("bad_proof", "pair_challenge hmac mismatch");
                return;
            }
            this.sessionKey = key;

            // Reply with pair_complete.
            byte[] proof = MeshCrypto.hmacSha256(key,
                    MeshSyncProtocol.payloadPairComplete(mesh.masterId(), initNonce));
            JsonObject out = new JsonObject();
            out.addProperty("type", MeshSyncProtocol.MSG_PAIR_COMPLETE);
            out.addProperty("hmac", java.util.Base64.getEncoder().encodeToString(proof));
            send(out);
        } catch (Exception e) {
            fail("pair_challenge", e.getMessage());
        }
    }

    private void handlePaired(JsonObject msg) {
        // Initiator confirmed pairing. Persist the peer with the derived key.
        mesh.registry().upsert(remoteMasterId, uri.toString(), sessionKey);
        // Sync starts: we send our vector, peer will send theirs too.
        sendVector();
    }

    private void handleAuthOk(JsonObject msg) {
        try {
            byte[] serverNonce = MeshSyncProtocol.hexToBytes(optString(msg, "nonce", ""));
            byte[] gotProof = java.util.Base64.getDecoder().decode(optString(msg, "hmac", ""));
            byte[] wantProof = MeshCrypto.hmacSha256(sessionKey,
                    MeshSyncProtocol.payloadAuthOk(remoteMasterId, serverNonce, myNonce));
            if (!MeshCrypto.constantTimeEquals(gotProof, wantProof)) {
                fail("bad_proof", "auth_ok hmac mismatch");
                return;
            }
            sendVector();
        } catch (Exception e) {
            fail("auth_ok", e.getMessage());
        }
    }

    private void sendVector() {
        if (sentVector) return;
        sentVector = true;
        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_VECTOR);
        JsonObject ls = new JsonObject();
        for (Map.Entry<String, String> e : maxHlcPerOrigin().entrySet()) {
            ls.addProperty(e.getKey(), e.getValue());
        }
        out.add("lastSeen", ls);
        send(out);
    }

    private void handleVector(JsonObject msg) {
        // Peer sent us their view of the world; reply with ops they're missing.
        Map<String, HLC> peerSeen = new HashMap<>();
        if (msg.has("lastSeen") && msg.get("lastSeen").isJsonObject()) {
            JsonObject ls = msg.getAsJsonObject("lastSeen");
            for (String k : ls.keySet()) {
                try { peerSeen.put(k, HLC.parse(ls.get(k).getAsString())); }
                catch (Exception ignored) {}
            }
        }
        List<Op> missing = opsMissing(peerSeen);
        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_OPS);
        JsonArray arr = new JsonArray();
        for (Op op : missing) arr.add(op.toJson());
        out.add("ops", arr);
        out.addProperty("done", true);
        opsSent = missing.size();
        send(out);

        // If we've already received peer's ops, the round is done.
        maybeComplete();
    }

    private void handleOps(JsonObject msg) {
        if (!msg.has("ops") || !msg.get("ops").isJsonArray()) return;
        JsonArray arr = msg.getAsJsonArray("ops");
        Map<String, String> highest = new HashMap<>();
        for (JsonElement el : arr) {
            try {
                Op op = Op.fromJson(el.getAsJsonObject());
                mesh.doc().applyRemote(op);
                opsReceived++;
                String prev = highest.get(op.origin);
                if (prev == null || HLC.parse(op.id).compareTo(HLC.parse(prev)) > 0) {
                    highest.put(op.origin, op.id);
                }
            } catch (Exception e) {
                System.err.println("[MeshSyncClient] bad op skipped: " + e.getMessage());
            }
        }
        if (!highest.isEmpty()) mesh.registry().recordSync(remoteMasterId, highest);
        receivedPeerOps = true;
        maybeComplete();
    }

    private void handleError(JsonObject msg) {
        fail(optString(msg, "code", "remote_error"), optString(msg, "msg", "remote error"));
    }

    private void maybeComplete() {
        // Done when we've sent our ops (in response to peer's vector) AND
        // received peer's ops (in response to our vector).
        if (sentVector && receivedPeerOps && !completed.isDone()) {
            completed.complete(new Result(true, remoteMasterId,
                    sessionKey == null ? null : Arrays.copyOf(sessionKey, sessionKey.length),
                    opsReceived, opsSent, null, null));
            // Close from our side a beat later so any in-flight messages flush.
            WebSocket s = ws.get();
            if (s != null) {
                try { s.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
                catch (Exception ignored) {}
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────

    private void send(JsonObject obj) {
        WebSocket s = ws.get();
        if (s == null) {
            fail("not_connected", "WebSocket not open");
            return;
        }
        try {
            s.sendText(obj.toString(), true).whenComplete((v, err) -> {
                if (err != null) fail("send", err.getMessage());
            });
        } catch (Exception e) {
            fail("send", e.getMessage());
        }
    }

    private void fail(String code, String msg) {
        if (completed.isDone()) return;
        completed.complete(new Result(false, remoteMasterId, null, opsReceived, opsSent, code, msg));
        try {
            WebSocket s = ws.get();
            if (s != null) s.sendClose(WebSocket.NORMAL_CLOSURE, code).join();
        } catch (Exception ignored) {}
    }

    private Map<String, String> maxHlcPerOrigin() {
        Map<String, HLC> max = new HashMap<>();
        try {
            mesh.doc().log().read(op -> {
                HLC h = op.hlc();
                HLC prev = max.get(op.origin);
                if (prev == null || h.compareTo(prev) > 0) max.put(op.origin, h);
            });
        } catch (Exception e) {
            System.err.println("[MeshSyncClient] log read failed: " + e.getMessage());
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, HLC> e : max.entrySet()) out.put(e.getKey(), e.getValue().encode());
        return out;
    }

    private List<Op> opsMissing(Map<String, HLC> peerSeen) {
        List<Op> missing = new ArrayList<>();
        try {
            mesh.doc().log().read(op -> {
                HLC opHlc = op.hlc();
                HLC seen = peerSeen.get(op.origin);
                if (seen == null || opHlc.compareTo(seen) > 0) missing.add(op);
            });
        } catch (Exception e) {
            System.err.println("[MeshSyncClient] log read failed: " + e.getMessage());
        }
        return missing;
    }

    private static String optString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    @Override
    public void close() {
        WebSocket s = ws.get();
        if (s != null) {
            try { s.sendClose(WebSocket.NORMAL_CLOSURE, "close").join(); }
            catch (Exception ignored) {}
        }
        if (!completed.isDone()) {
            completed.complete(new Result(false, remoteMasterId, null, opsReceived, opsSent,
                    "closed", "client closed"));
        }
    }

    // ── WebSocket.Listener — buffers text frames, decodes to JSON ──

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }
        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String full = textBuffer.toString();
                textBuffer.setLength(0);
                try { onMessage(full); }
                catch (Exception e) { fail("dispatch", e.getMessage()); }
            }
            ws.request(1);
            return null;
        }
        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!completed.isDone()) {
                completed.complete(new Result(false, remoteMasterId, null, opsReceived, opsSent,
                        "ws_close", "code=" + statusCode + " reason=" + reason));
            }
            return null;
        }
        @Override
        public void onError(WebSocket ws, Throwable error) {
            fail("ws_error", error.getMessage() == null ? "ws error" : error.getMessage());
        }
    }
}
