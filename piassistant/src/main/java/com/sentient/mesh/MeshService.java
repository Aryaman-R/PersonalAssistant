package com.sentient.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Phase 1 mesh skeleton. Owns:
 *
 * <ul>
 *   <li>The {@link PeerRegistry} (paired peers + pending pairing phrases).</li>
 *   <li>A reference to the {@link ReplicatedDoc} that backs every replicated
 *       mutation (Phase 1's ProfileManager rewire created this).</li>
 *   <li>The master's stable origin id (re-exposed for convenience to callers
 *       like the UI that only have a {@code MeshService} handle).</li>
 * </ul>
 *
 * <p>No networking yet. Phase 2 will add:
 * <ul>
 *   <li>a {@code /mesh-sync} WebSocket endpoint on {@link com.sentient.WebServer},</li>
 *   <li>a {@code MeshSyncClient} that dials each paired peer, runs the
 *       HKDF-derived handshake (foundations already present:
 *       {@link PairingPhrase}, {@link MeshCrypto}), and pumps ops in both
 *       directions,</li>
 *   <li>an {@link ReplicatedDoc.OpListener} on the doc that fans newly-emitted
 *       local ops out to every connected peer.</li>
 * </ul>
 *
 * <p>The Phase 1 stub already exposes enough state for the Settings → MESH
 * panel to render correctly today (master id + paired-peer list). The
 * "GENERATE PAIRING PHRASE" button only works after Phase 2 lands the
 * matching joiner side, so the panel marks it as such.
 */
public final class MeshService {

    private final ReplicatedDoc doc;
    private final PeerRegistry registry;

    public MeshService(ReplicatedDoc doc, PeerRegistry registry) {
        if (doc == null) throw new IllegalArgumentException("doc required");
        if (registry == null) throw new IllegalArgumentException("registry required");
        this.doc = doc;
        this.registry = registry;
    }

    public String masterId() { return doc.originMasterId(); }
    public ReplicatedDoc doc() { return doc; }
    public PeerRegistry registry() { return registry; }

    /**
     * Public-safe snapshot for the UI: master id + every paired peer
     * (no signing keys) + simple counts. Hits {@link PeerRegistry#diagnostics()}
     * for the pending-phrase count, which is otherwise hidden.
     */
    public JsonObject infoJson() {
        JsonObject o = new JsonObject();
        o.addProperty("masterId", masterId());
        o.addProperty("phase", 1);
        o.addProperty("phaseNote",
                "Phase 1: peer-list persistence + state replication local-only. "
                        + "Phase 2 will land the actual sync protocol.");
        JsonArray peers = new JsonArray();
        for (PeerRegistry.Peer p : registry.sortedByMasterId()) peers.add(p.toPublicJson());
        o.add("peers", peers);
        o.addProperty("peerCount", peers.size());
        o.addProperty("pendingPhrases", registry.pendingCount());

        Map<String, Object> stateDiag = doc.state().diagnostics();
        JsonObject sd = new JsonObject();
        for (Map.Entry<String, Object> e : stateDiag.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number n) sd.addProperty(e.getKey(), n);
            else sd.addProperty(e.getKey(), v == null ? "" : v.toString());
        }
        o.add("state", sd);
        o.addProperty("opLogPath", doc.log().path().toString());
        return o;
    }

    /** Pending pairing phrases — for the Phase 2 joiner to verify against. Phase 1 only generates. */
    public String generatePairingPhrase() {
        return registry.createPendingPhrase();
    }

    /**
     * Phase 1 stub for redeem. The networking + handshake aren't wired yet;
     * calling this just verifies that the phrase is still redeemable
     * (returns key length on success, null on miss). Phase 2 promotes this
     * to actually upsert a peer + open a sync session.
     */
    public Integer redeemPairingPhraseStub(String phrase) {
        byte[] key = registry.redeemPhrase(phrase);
        return key == null ? null : key.length;
    }

    /**
     * Hook for the Phase 2 sync layer to register itself as an op listener
     * on the doc. Calling this in Phase 1 today simply wires the listener;
     * since no peer connections exist yet, the listener never fires across
     * the wire. Idempotent — last setter wins (mirrors ReplicatedDoc's API).
     */
    public void setOpListener(ReplicatedDoc.OpListener listener) {
        doc.setOpListener(listener);
    }

    // ── Diagnostics helpers ─────────────────────────────

    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("masterId", masterId());
        out.put("peers", registry.diagnostics());
        out.put("state", doc.state().diagnostics());
        return out;
    }
}
