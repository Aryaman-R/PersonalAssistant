package com.sentient.mesh;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Wire-protocol constants + small helpers for the {@code /mesh-sync}
 * WebSocket. Single source of truth so the server-side session machine and
 * the outbound {@link MeshSyncClient} can't drift.
 *
 * <p>Message taxonomy:
 * <pre>
 *   ┌── PAIRING (one-time, phrase-based) ─────────────────┐
 *   │  joiner → init :  pair_hello   { masterId, nonce }  │
 *   │  init → joiner :  pair_challenge { masterId, nonce, │
 *   │                                    hmac }            │
 *   │  joiner → init :  pair_complete { hmac }             │
 *   │  init → joiner :  paired                             │
 *   └──────────────────────────────────────────────────────┘
 *
 *   ┌── AUTH (already-paired peers reconnecting) ─────────┐
 *   │  caller → server : auth_hello { masterId, nonce,    │
 *   │                                 hmac }              │
 *   │  server → caller : auth_ok   { masterId, nonce,     │
 *   │                                hmac }               │
 *   └──────────────────────────────────────────────────────┘
 *
 *   ┌── SYNC (after either handshake) ────────────────────┐
 *   │  A → B  : vector  { lastSeen: { origin → HLC } }    │
 *   │  B → A  : ops     { ops: [<Op>...], done: bool }    │
 *   │  A → B  : ops     { ops: [<Op>...], done: bool }    │
 *   │   ... repeat until both sides have nothing new ...  │
 *   └──────────────────────────────────────────────────────┘
 *
 *   ┌── GOSSIP (steady-state, after sync settles) ────────┐
 *   │  X → peers : ops { ops: [<single op>], gossip:true }│
 *   └──────────────────────────────────────────────────────┘
 *
 *   error { code, msg }   — any side, any phase. Closes the session.
 * </pre>
 *
 * <p>HMAC domain-separation strings ({@code "I"}, {@code "J"}, {@code "C"},
 * {@code "S"}) are the first byte fed into HMAC. They make a pair-challenge
 * proof distinguishable from a pair-complete proof, etc., so an attacker who
 * captures one frame can't replay it as another role.
 *
 * <p>Phase 2 only HMAC-signs the handshake messages (pair / auth). The sync
 * frames after a successful handshake ride the trusted channel — same model
 * as TLS. Phase 5 promotes every frame to HMAC-signed when the proper Noise
 * XX handshake lands.
 */
public final class MeshSyncProtocol {

    private MeshSyncProtocol() {}

    // ── Message types ──────────────────────────────────

    public static final String MSG_PAIR_HELLO     = "pair_hello";
    public static final String MSG_PAIR_CHALLENGE = "pair_challenge";
    public static final String MSG_PAIR_COMPLETE  = "pair_complete";
    public static final String MSG_PAIRED         = "paired";

    public static final String MSG_AUTH_HELLO     = "auth_hello";
    public static final String MSG_AUTH_OK        = "auth_ok";

    public static final String MSG_VECTOR         = "vector";
    public static final String MSG_OPS            = "ops";

    public static final String MSG_ERROR          = "error";

    // ── HMAC domain-separation prefixes ────────────────

    public static final byte DOMAIN_INIT          = (byte) 'I';
    public static final byte DOMAIN_JOINER        = (byte) 'J';
    public static final byte DOMAIN_AUTH_CLIENT   = (byte) 'C';
    public static final byte DOMAIN_AUTH_SERVER   = (byte) 'S';

    /** Bytes-per-nonce; 16 bytes = 128 bits, plenty for replay-prevention. */
    public static final int NONCE_BYTES = 16;

    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        RNG.nextBytes(n);
        return n;
    }

    // ── HMAC payload assembly ──────────────────────────

    /**
     * Build the HMAC payload for a pairing-challenge (initiator proves to
     * joiner it knows K).
     *
     * <p>{@code = "I" || initiator_master_id_utf8 || joiner_nonce}.
     */
    public static byte[] payloadPairChallenge(String initiatorMasterId, byte[] joinerNonce) {
        return concat(new byte[] { DOMAIN_INIT },
                initiatorMasterId.getBytes(StandardCharsets.UTF_8),
                joinerNonce);
    }

    /**
     * Build the HMAC payload for a pairing-complete (joiner proves to
     * initiator it knows K).
     *
     * <p>{@code = "J" || joiner_master_id_utf8 || initiator_nonce}.
     */
    public static byte[] payloadPairComplete(String joinerMasterId, byte[] initiatorNonce) {
        return concat(new byte[] { DOMAIN_JOINER },
                joinerMasterId.getBytes(StandardCharsets.UTF_8),
                initiatorNonce);
    }

    /**
     * Build the HMAC payload for an auth-hello (caller proves to server it
     * knows the stored peer key).
     *
     * <p>{@code = "C" || caller_master_id_utf8 || caller_nonce}.
     */
    public static byte[] payloadAuthHello(String callerMasterId, byte[] callerNonce) {
        return concat(new byte[] { DOMAIN_AUTH_CLIENT },
                callerMasterId.getBytes(StandardCharsets.UTF_8),
                callerNonce);
    }

    /**
     * Build the HMAC payload for an auth-ok (server proves to caller it
     * knows the stored peer key, AND binds to the caller's nonce so a
     * replay of a prior auth_ok can't masquerade).
     *
     * <p>{@code = "S" || server_master_id_utf8 || server_nonce || caller_nonce}.
     */
    public static byte[] payloadAuthOk(String serverMasterId, byte[] serverNonce, byte[] callerNonce) {
        return concat(new byte[] { DOMAIN_AUTH_SERVER },
                serverMasterId.getBytes(StandardCharsets.UTF_8),
                serverNonce, callerNonce);
    }

    // ── Hex helpers (used for nonces over JSON) ────────

    public static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("non-hex char in: " + hex);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
