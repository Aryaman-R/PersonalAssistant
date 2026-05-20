package com.sentient.mesh;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Crypto helpers for the mesh layer: HMAC-SHA256 + HKDF-SHA256 (RFC 5869).
 *
 * <p>Both are implemented in straight Java on top of {@link Mac} so the mesh
 * adds zero new dependencies. The Phase 1 protocol uses HKDF to derive a
 * per-peer signing key from the 6-word pairing phrase, and HMAC for the
 * pairing-handshake proof messages.
 *
 * <p>Note: this is deliberately a small, focused crypto surface. Phase 5
 * replaces the pairing protocol with proper Noise XX; this file stays around
 * for HMAC frame signing.
 */
public final class MeshCrypto {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final byte[] EMPTY_SALT = new byte[32]; // zero-filled

    private MeshCrypto() {}

    /** Compute HMAC-SHA256(key, message). */
    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 unavailable: " + e.getMessage(), e);
        }
    }

    /** Constant-time byte-array equality. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }

    /**
     * HKDF-SHA256 (RFC 5869). Derives {@code outLen} bytes of pseudorandom
     * output material from {@code ikm} (input keying material) using {@code
     * salt} (optional; null is treated as 32 zero bytes per RFC) and
     * {@code info} (application-specific context label).
     *
     * <p>The mesh uses this exclusively to turn the 6-word pairing phrase
     * into a 32-byte HMAC signing key:
     * <pre>K = HKDF-SHA256(phrase_utf8, null, "sentient-mesh-v1-pair", 32).</pre>
     * The fixed info label is a domain-separation tag so the same phrase
     * couldn't accidentally produce a key valid in another context.
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        if (ikm == null) ikm = new byte[0];
        if (info == null) info = new byte[0];
        if (salt == null || salt.length == 0) salt = EMPTY_SALT;
        if (outLen <= 0) throw new IllegalArgumentException("outLen must be > 0");
        if (outLen > 255 * 32) throw new IllegalArgumentException("outLen exceeds RFC 5869 limit");

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        byte[] prk = hmacSha256(salt, ikm);

        // Expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
        byte[] out = new byte[outLen];
        byte[] prev = new byte[0];
        int offset = 0;
        int n = (outLen + 31) / 32;
        for (int i = 1; i <= n; i++) {
            byte[] in = new byte[prev.length + info.length + 1];
            System.arraycopy(prev, 0, in, 0, prev.length);
            System.arraycopy(info, 0, in, prev.length, info.length);
            in[in.length - 1] = (byte) i;
            prev = hmacSha256(prk, in);
            int take = Math.min(32, outLen - offset);
            System.arraycopy(prev, 0, out, offset, take);
            offset += take;
        }
        return out;
    }

    /** Domain-separated derivation of the 32-byte pairing key from a phrase. */
    public static byte[] derivePairKey(String normalizedPhrase) {
        return hkdfSha256(
                normalizedPhrase.getBytes(StandardCharsets.UTF_8),
                null,
                "sentient-mesh-v1-pair".getBytes(StandardCharsets.UTF_8),
                32);
    }
}
