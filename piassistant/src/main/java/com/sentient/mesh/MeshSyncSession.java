package com.sentient.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server-side state machine for ONE inbound {@code /mesh-sync} WebSocket
 * connection. The {@link com.sentient.WebServer} owns one of these per
 * connected WS context.
 *
 * <p>Lifecycle:
 * <pre>
 *   INIT
 *     ├── on pair_hello → handlePairHello → AWAITING_PAIR_COMPLETE
 *     │      (server emits pair_challenge)
 *     ├── on auth_hello → handleAuthHello → AUTHED
 *     │      (server emits auth_ok)
 *     └── anything else → error
 *
 *   AWAITING_PAIR_COMPLETE
 *     ├── on pair_complete (valid hmac) → upsert peer, emit `paired`,
 *     │      then start sync loop → AUTHED
 *     └── anything else → error
 *
 *   AUTHED
 *     ├── on vector  → reply with ops the caller is missing
 *     ├── on ops     → apply each via ReplicatedDoc.applyRemote +
 *     │                update our per-peer lastSeenHlc
 *     └── anything else → error
 * </pre>
 *
 * <p>This class is single-threaded per-instance. The WebServer must serialise
 * onMessage / sendError / close on a per-session basis (Javalin's
 * {@code WsContext} already gives one onMessage thread per session).
 */
public final class MeshSyncSession {

    public enum State { INIT, AWAITING_PAIR_COMPLETE, AUTHED, CLOSED }

    private final MeshService mesh;
    private final Consumer<JsonObject> sender;

    private State state = State.INIT;
    private String remoteMasterId = "";
    private byte[] sessionKey;
    private byte[] myNonce;        // remembered between rounds
    private byte[] remoteNonce;
    private boolean openedPairingFlow = false;

    public MeshSyncSession(MeshService mesh, Consumer<JsonObject> sender) {
        if (mesh == null || sender == null) throw new IllegalArgumentException();
        this.mesh = mesh;
        this.sender = sender;
    }

    public State state() { return state; }
    public String remoteMasterId() { return remoteMasterId; }
    public boolean isAuthenticated() { return state == State.AUTHED; }
    public boolean fromPairing() { return openedPairingFlow; }

    public void onMessage(JsonObject msg) {
        if (state == State.CLOSED) return;
        String type = optString(msg, "type", "");
        try {
            switch (state) {
                case INIT:
                    if (MeshSyncProtocol.MSG_PAIR_HELLO.equals(type)) handlePairHello(msg);
                    else if (MeshSyncProtocol.MSG_AUTH_HELLO.equals(type)) handleAuthHello(msg);
                    else sendError("unexpected", "first message must be pair_hello or auth_hello, got: " + type);
                    break;
                case AWAITING_PAIR_COMPLETE:
                    if (MeshSyncProtocol.MSG_PAIR_COMPLETE.equals(type)) handlePairComplete(msg);
                    else sendError("unexpected", "expected pair_complete, got: " + type);
                    break;
                case AUTHED:
                    if (MeshSyncProtocol.MSG_VECTOR.equals(type)) handleVector(msg);
                    else if (MeshSyncProtocol.MSG_OPS.equals(type)) handleOps(msg);
                    else sendError("unexpected", "expected vector or ops, got: " + type);
                    break;
                case CLOSED:
                    break;
            }
        } catch (Exception e) {
            sendError("internal", e.getMessage() == null ? "exception" : e.getMessage());
        }
    }

    public void close() {
        state = State.CLOSED;
        sessionKey = null;
    }

    // ── Handshake handlers ──────────────────────────────

    private void handlePairHello(JsonObject msg) {
        // Phase 1 stash: PeerRegistry must have a pending phrase that we can
        // redeem. The joiner doesn't send the phrase over the wire — instead,
        // we look up by trying every pending key against the proof in
        // pair_complete (joiner sends pair_complete proof in its first round
        // here... wait, no: pair_hello is just an introduction. We need the
        // phrase. Re-read the protocol.)
        //
        // Actually the protocol: joiner already KNOWS the phrase (user typed it
        // on the joiner side). Joiner has computed K locally. Joiner sends
        // pair_hello { masterId, nonce } — no proof yet.
        // Server must independently know K too. To do that, the joiner sends
        // the *phrase* in pair_hello so the server can look up + verify it's
        // a pending one. We'll send the phrase here (the only message it ever
        // appears on the wire; LAN-trust for v2, Noise wraps it in v5).
        String joinerMasterId = optString(msg, "masterId", "");
        String nonceHex = optString(msg, "nonce", "");
        String phrase = optString(msg, "phrase", "");
        if (joinerMasterId.isEmpty() || nonceHex.isEmpty() || phrase.isEmpty()) {
            sendError("bad_request", "pair_hello requires masterId, nonce, phrase");
            return;
        }
        byte[] joinerNonce;
        try {
            joinerNonce = MeshSyncProtocol.hexToBytes(nonceHex);
        } catch (Exception e) {
            sendError("bad_request", "pair_hello nonce not hex");
            return;
        }
        if (joinerNonce.length != MeshSyncProtocol.NONCE_BYTES) {
            sendError("bad_request", "pair_hello nonce wrong length");
            return;
        }

        byte[] key = mesh.registry().redeemPhrase(phrase);
        if (key == null) {
            sendError("no_phrase", "no matching pending pairing phrase (expired or never generated)");
            return;
        }

        // Looks good — emit our challenge. Stash state for pair_complete.
        this.remoteMasterId = joinerMasterId;
        this.remoteNonce = joinerNonce;
        this.sessionKey = key;
        this.myNonce = MeshSyncProtocol.randomNonce();
        this.openedPairingFlow = true;

        byte[] proof = MeshCrypto.hmacSha256(key,
                MeshSyncProtocol.payloadPairChallenge(mesh.masterId(), joinerNonce));

        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_PAIR_CHALLENGE);
        out.addProperty("masterId", mesh.masterId());
        out.addProperty("nonce", MeshSyncProtocol.bytesToHex(myNonce));
        out.addProperty("hmac", java.util.Base64.getEncoder().encodeToString(proof));
        send(out);
        state = State.AWAITING_PAIR_COMPLETE;
    }

    private void handlePairComplete(JsonObject msg) {
        String hmacB64 = optString(msg, "hmac", "");
        if (hmacB64.isEmpty() || sessionKey == null || myNonce == null) {
            sendError("bad_request", "pair_complete missing fields or no prior pair_hello");
            return;
        }
        byte[] gotProof;
        try {
            gotProof = java.util.Base64.getDecoder().decode(hmacB64);
        } catch (Exception e) {
            sendError("bad_request", "pair_complete hmac not base64");
            return;
        }
        byte[] wantProof = MeshCrypto.hmacSha256(sessionKey,
                MeshSyncProtocol.payloadPairComplete(remoteMasterId, myNonce));
        if (!MeshCrypto.constantTimeEquals(gotProof, wantProof)) {
            sendError("bad_proof", "pair_complete hmac mismatch");
            return;
        }

        // Mutual proof done. Persist the peer.
        mesh.registry().upsert(remoteMasterId, "", sessionKey);

        JsonObject paired = new JsonObject();
        paired.addProperty("type", MeshSyncProtocol.MSG_PAIRED);
        paired.addProperty("masterId", mesh.masterId());
        send(paired);
        state = State.AUTHED;

        // Kick off sync by emitting our vector.
        sendVector();
    }

    private void handleAuthHello(JsonObject msg) {
        String callerMasterId = optString(msg, "masterId", "");
        String nonceHex = optString(msg, "nonce", "");
        String hmacB64 = optString(msg, "hmac", "");
        if (callerMasterId.isEmpty() || nonceHex.isEmpty() || hmacB64.isEmpty()) {
            sendError("bad_request", "auth_hello requires masterId, nonce, hmac");
            return;
        }
        PeerRegistry.Peer peer = mesh.registry().get(callerMasterId);
        if (peer == null || peer.keyB64 == null || peer.keyB64.isEmpty()) {
            sendError("unknown_peer", "no stored key for masterId; pair first");
            return;
        }
        byte[] key = peer.key();
        byte[] callerNonce = MeshSyncProtocol.hexToBytes(nonceHex);
        byte[] gotProof = java.util.Base64.getDecoder().decode(hmacB64);
        byte[] wantProof = MeshCrypto.hmacSha256(key,
                MeshSyncProtocol.payloadAuthHello(callerMasterId, callerNonce));
        if (!MeshCrypto.constantTimeEquals(gotProof, wantProof)) {
            sendError("bad_proof", "auth_hello hmac mismatch");
            return;
        }

        // Caller proven. Now we prove ourselves back.
        this.remoteMasterId = callerMasterId;
        this.remoteNonce = callerNonce;
        this.sessionKey = key;
        this.myNonce = MeshSyncProtocol.randomNonce();
        this.openedPairingFlow = false;

        byte[] proof = MeshCrypto.hmacSha256(key,
                MeshSyncProtocol.payloadAuthOk(mesh.masterId(), myNonce, callerNonce));

        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_AUTH_OK);
        out.addProperty("masterId", mesh.masterId());
        out.addProperty("nonce", MeshSyncProtocol.bytesToHex(myNonce));
        out.addProperty("hmac", java.util.Base64.getEncoder().encodeToString(proof));
        send(out);
        state = State.AUTHED;

        sendVector();
    }

    // ── Sync handlers ───────────────────────────────────

    private void sendVector() {
        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_VECTOR);
        Map<String, String> vec = maxHlcPerOrigin();
        JsonObject ls = new JsonObject();
        for (Map.Entry<String, String> e : vec.entrySet()) ls.addProperty(e.getKey(), e.getValue());
        out.add("lastSeen", ls);
        send(out);
    }

    private void handleVector(JsonObject msg) {
        Map<String, HLC> peerSeen = new HashMap<>();
        if (msg.has("lastSeen") && msg.get("lastSeen").isJsonObject()) {
            JsonObject ls = msg.getAsJsonObject("lastSeen");
            for (String k : ls.keySet()) {
                try { peerSeen.put(k, HLC.parse(ls.get(k).getAsString())); }
                catch (Exception ignored) {}
            }
        }
        // Compute ops we have that the peer doesn't.
        List<Op> missing = opsMissing(peerSeen);
        sendOps(missing);
    }

    private void handleOps(JsonObject msg) {
        if (!msg.has("ops") || !msg.get("ops").isJsonArray()) return;
        JsonArray arr = msg.getAsJsonArray("ops");
        int applied = 0;
        Map<String, String> highestPerOrigin = new HashMap<>();
        for (JsonElement el : arr) {
            try {
                Op op = Op.fromJson(el.getAsJsonObject());
                boolean changed = mesh.doc().applyRemote(op);
                if (changed) applied++;
                String prev = highestPerOrigin.get(op.origin);
                if (prev == null || HLC.parse(op.id).compareTo(HLC.parse(prev)) > 0) {
                    highestPerOrigin.put(op.origin, op.id);
                }
            } catch (Exception e) {
                System.err.println("[MeshSyncSession] bad op skipped: " + e.getMessage());
            }
        }
        // Update per-peer lastSeenHlc with the highest HLCs we just absorbed.
        if (!highestPerOrigin.isEmpty()) {
            mesh.registry().recordSync(remoteMasterId, highestPerOrigin);
        }
    }

    private void sendOps(List<Op> ops) {
        JsonObject out = new JsonObject();
        out.addProperty("type", MeshSyncProtocol.MSG_OPS);
        JsonArray arr = new JsonArray();
        for (Op op : ops) arr.add(op.toJson());
        out.add("ops", arr);
        out.addProperty("done", true);
        send(out);
    }

    // ── Helpers ─────────────────────────────────────────

    /**
     * Walk the local op log and compute the maximum HLC seen for each origin.
     * O(N) but fine at Phase 1 scale (<100k ops).
     */
    private Map<String, String> maxHlcPerOrigin() {
        Map<String, HLC> max = new HashMap<>();
        try {
            mesh.doc().log().read(op -> {
                HLC h = op.hlc();
                HLC prev = max.get(op.origin);
                if (prev == null || h.compareTo(prev) > 0) max.put(op.origin, h);
            });
        } catch (Exception e) {
            System.err.println("[MeshSyncSession] log read failed: " + e.getMessage());
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
                if (seen == null || opHlc.compareTo(seen) > 0) {
                    missing.add(op);
                }
            });
        } catch (Exception e) {
            System.err.println("[MeshSyncSession] log read failed: " + e.getMessage());
        }
        return missing;
    }

    private void send(JsonObject obj) {
        try { sender.accept(obj); }
        catch (Exception e) { System.err.println("[MeshSyncSession] send failed: " + e.getMessage()); }
    }

    private void sendError(String code, String text) {
        JsonObject err = new JsonObject();
        err.addProperty("type", MeshSyncProtocol.MSG_ERROR);
        err.addProperty("code", code);
        err.addProperty("msg", text);
        send(err);
        state = State.CLOSED;
    }

    private static String optString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    // Tested-only escape hatch — used by integration tests to inspect.
    byte[] sessionKeyForTests() {
        return sessionKey == null ? null : Arrays.copyOf(sessionKey, sessionKey.length);
    }
}
