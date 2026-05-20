# MESH_PLAN.md — Multi-master mesh (STRETCH §9)

Design + phased delivery plan for the multi-master CRDT mesh. This branch
(`feature/9-multi-master-mesh`) lands the foundations; the harder bits
(Noise handshake, mDNS auto-discovery, transparent browser failover, AR/XR
goodies) are explicitly deferred to follow-up phases on later branches.

The goal: spinning up a second master and pairing it (one click + one
short verification phrase) makes the two devices a peer set. State edits
on either propagate within seconds. Each device gets redundancy without
trusting any single piece of hardware.

---

## Status — 2026-05-20

A quick-read snapshot of where this branch sits today. Detail lives in
the per-phase sections below.

### ✅ Done (this branch, 7 commits)

**The replication backbone is in AND two masters can now actually pair
+ sync over a real WebSocket.** Every state mutation that used to go
through `ProfileManager` flows through a CRDT op log; the legacy
`user_profile.json` is a derived snapshot of that log. Phase 2 added
the wire layer — paired masters exchange a vector clock + missing ops
via `/mesh-sync` and converge. The Phase 2 integration test boots two
real Javalin instances in-process on ephemeral ports, runs the full
pair flow, mutates state on each side, and asserts convergence.

| Piece | File(s) | Status |
|---|---|---|
| Phased delivery plan | `MESH_PLAN.md` | ✅ |
| CRDT primitives (HLC, HlcClock, LwwRegister, OrSet) | `mesh/{HLC,HlcClock,LwwRegister,OrSet}.java` | ✅ |
| Op + JSON serialization | `mesh/Op.java` | ✅ |
| Append-only op log + dedup + replay-on-boot | `mesh/OpLog.java` | ✅ |
| ReplicatedState (apply / read) + ReplicatedDoc (clock + log + origin) | `mesh/ReplicatedState.java`, `mesh/ReplicatedDoc.java` | ✅ |
| Stable per-master UUID | `mesh/MasterId.java` (→ `~/.sentient_master_id`) | ✅ |
| ProfileManager rewired through op log, with reconcile-on-saveProfile that catches legacy direct-mutation patterns | `util/ProfileManager.java` | ✅ |
| One-shot migration from legacy `user_profile.json` | inside `ProfileManager` ctor | ✅ |
| Peer-list persistence (paired peers + their HMAC keys) | `mesh/PeerRegistry.java` (→ `~/.sentient_mesh_peers.json`, 0600) | ✅ |
| 6-word pairing phrase generator + constant-time validator | `mesh/PairingPhrase.java` | ✅ |
| HMAC-SHA256 + HKDF-SHA256 (no JNI) | `mesh/MeshCrypto.java` | ✅ |
| MeshService skeleton + `GET /api/mesh/info` | `mesh/MeshService.java`, `WebServer.java` | ✅ |
| Settings → MESH UI panel (read-only — pair button is still a Phase 2 stub) | `index.html`, `app.js`, `styles.css` | ✅ |
| **Phase 2 wire protocol** (`pair_hello` / `pair_challenge` / `pair_complete` / `paired`; `auth_hello` / `auth_ok`; `vector` / `ops`; `error`; HMAC domain-separation) | `mesh/MeshSyncProtocol.java` | ✅ |
| **Phase 2 server-side state machine** (per-WS session: pairing OR auth → AUTHED → handle vector/ops) | `mesh/MeshSyncSession.java` | ✅ |
| **Phase 2 outbound client** (Java 17 `HttpClient.newWebSocketBuilder` — zero new deps; `pairWith(phrase)` and `connectAuthenticated(id)` entry points; returns a future with op-count result) | `mesh/MeshSyncClient.java` | ✅ |
| **Phase 2 `/mesh-sync` WS endpoint + REST**: `POST /api/mesh/pair/start`, `POST /api/mesh/pair/redeem`, `POST /api/mesh/sync/{peerId}`, `DELETE /api/mesh/peers/{peerId}` | `WebServer.java` | ✅ |
| **Phase 2 integration test**: two real Javalin masters in one JVM, three cases (pair+sync converges; auth-reconnect propagates a fresh op; wrong phrase fails cleanly with no persistence) | `mesh/MeshSyncScenario.java` | ✅ |
| Tests: 19-assertion `MeshSelfTest` + sandbox-rooted `ProfileManagerScenario` + 3-case `MeshSyncScenario` | `mesh/MeshSelfTest.java`, `mesh/ProfileManagerScenario.java`, `mesh/MeshSyncScenario.java` | ✅ |

**Test coverage today:**
- `MeshSelfTest`: HLC ordering / monotonicity / receive-merge; LwwRegister later-wins + earlier-rejected; OrSet add / remove / concurrent-add / re-add; Op JSON round-trip; OpLog append + dedup + readback; ReplicatedState idempotent apply + older-HLC rejection; ReplicatedDoc local mutators + bootstrap restore + remote idempotency; **two-doc convergence** (all ops shared); **two-doc concurrent-edit resolution** (LWW deterministic, OR-Set add-wins); OpListener fires once per fresh op only.
- `ProfileManagerScenario`: legacy `user_profile.json` migrates → 15 ops emitted; typed setters work; legacy direct-mutation pattern (`task.googleId = …`) reconciled into ops at `saveProfile()`; reflective "restart" preserves state; master id stable across restarts; restart emits zero redundant ops.
- `MeshSyncScenario`: two Javalin masters in one JVM on ephemeral ports + sandboxed home dirs; **pair + sync** drives the full handshake and verifies both replicas hold the same LWW winner + the union of OR-Set adds; **auth + reconnect** uses the stored HMAC key (no phrase) and propagates a fresh op; **wrong-phrase** fails cleanly with `no_phrase` / `remote_error` and neither side persists the other.

### 🔲 Not yet done

| Phase | Scope | Status |
|---|---|---|
| **Phase 2 — remainder** | Persistent sync connections + gossip on local ops (so mutations after the initial round propagate live); `origin_master_id` field on every existing `/ws` broadcast (lays Phase 3 groundwork); MESH-panel pairing UX in the browser (the REST endpoints exist, the UI buttons don't drive them yet); reconnect-with-backoff in `MeshSyncClient` | ⏳ next |
| **Phase 3** | Browser transparent failover (`peers` list pushed to clients, dedup of broadcasts, "bound master" sidebar badge) | ⏳ |
| **Phase 4** | Auto-discovery (mDNS via `jmdns`, Tailscale-tag enumeration, "discovered peers" UI) | ⏳ |
| **Phase 5** | Hardening (proper Noise XX handshake replacing the HKDF stub; anti-replay; replication-exclusion regression test; optional TLS for `/mesh-sync`) | ⏳ |

**Phase 2 — what's left, ordered by user-visibility:**

1. **Gossip / persistent connections.** Today sync is a one-shot vector
   exchange. After it completes, the WS closes; a new mutation only
   propagates if someone re-calls `POST /api/mesh/sync/{peerId}` or
   re-pairs. To make edits flow live we need: an `MeshSyncOutbound`
   interface implemented by both sides, a `MeshSyncBroadcaster` that
   the `ReplicatedDoc.OpListener` feeds into, and a keep-alive mode in
   `MeshSyncClient`. Once that lands, `MeshService.maintainPeerConnections()`
   can dial every paired peer at boot and keep the connection up with
   jittered exponential-backoff reconnect.
2. **MESH panel pairing UX.** Backend is done; the UI just needs two
   buttons: "GENERATE PHRASE" → calls `POST /api/mesh/pair/start`,
   shows the 6-word phrase + 60 s countdown; "PAIR WITH MASTER" →
   prompts for host:port + phrase, calls `POST /api/mesh/pair/redeem`,
   shows the result. The current button still says "Phase 2 — coming
   soon."
3. **`origin_master_id` on `/ws` broadcasts.** Phase 3 needs this for
   browser dedup, but it's a Phase 2 footprint change (touches every
   `broadcast(...)` callsite in `WebServer.java`). Cheap to add now.

**Foundations already laid (don't re-design these):**
`PairingPhrase.generate / normalize / equalsConstantTime / isWellFormed`,
`MeshCrypto.hmacSha256 / hkdfSha256 / derivePairKey / constantTimeEquals`,
`PeerRegistry.createPendingPhrase / redeemPhrase / upsert / recordSync /
get / sortedByMasterId`,
`MeshSyncProtocol.payload{PairChallenge,PairComplete,AuthHello,AuthOk}`,
`MeshSyncClient.{pairWith,connectAuthenticated}` (both return a
`CompletableFuture<Result>` with op counts + error code),
`MeshSyncSession` (server-side state machine — currently registers no
op listener; gossip work just needs it to subscribe + push),
`MeshService.setOpListener` (fan-out hook on the doc),
`ReplicatedDoc.applyRemote` (idempotent inbound apply).

### Open design questions (carried forward)

- Should chat history opt-in to replicate? Currently per-master. Knob in MESH settings is cheap; defer until voice ID (§3) lands so we can scope per-user.
- Should Spotify playback state replicate? Almost certainly not — speakers are physical, tied to one master's LAN.
- Conflict-resolution UI? v1 just LWW-picks. If users start losing edits, add a "merge conflicts" feed in the MESH panel.

---

## What replicates vs. what stays per-master

The hard rule: **the vault, auth state, and OAuth tokens never leave the
master that holds them.** Replicating those would turn one compromised
master into a key-leakage incident across the mesh.

### Replicates across the mesh

Everything inside `user_profile.json` today:

| Field | CRDT shape | Why |
|---|---|---|
| `username`, `restriction_mode` | LWW-Register (HLC-stamped) | Single-value scalar fields. |
| `habits`, `preferences`, `dislikes`, `goals`, `nicknames`, `notes`, `commitments`, `alarms`, `past_conversations_summary` | OR-Set (string-valued) | Each item is unique by lowercase string; add/remove are commutative. |
| `taskLists` | OR-Set of list IDs + per-list LWW-Map of `TaskItem` records keyed by stable `id` | Lists are added/removed independently; per-task fields use LWW. |
| `events` | OR-Set keyed by `event.id` | Events have explicit UUIDs already. |

### Stays per-master (NEVER replicates)

| File / state | Owner | Why excluded |
|---|---|---|
| `~/.sentient_vault.json` | `CredentialVault` | Per-master env vars and service logins. Replicating leaks secrets to every paired peer. |
| `~/.sentient_vault_key` | `CredentialVault` | The AES key that decrypts the vault. |
| `~/.sentient_auth.json` | `AuthService` | Per-master shared password + device tokens. Each master keeps its own login state. |
| `piassistant/spotify_token.json` | `SpotifyService` | OAuth refresh tokens. Audience-bound to one app/master. |
| `piassistant/google_auth.json` | Google Tasks/Calendar | Same — OAuth tokens are per-master. |
| `piassistant/.env` | `EnvLoader` | API keys for Groq, OpenAI, etc. |
| `~/.sentient_setup.json` | `SetupService` | First-run wizard state. Each master does its own setup. |
| `~/.sentient_local_llm.json`, `~/.config/openclaw/openclaw.json5` | LLM engines | Per-master engine routing. |
| Chat history | `GroqService` / `OpenClawService` / `LocalLlmService` | Per-master append-only log; turn-by-turn replication would be noisy and privacy-leaky. |

The system that ships in this branch will route every mutation through one
of three "buckets": **replicated**, **per-master**, or **chat-stream**.
A test (Phase 5) enforces that nothing in the per-master bucket ever
makes it onto the wire.

---

## CRDT primitives

Hand-rolled, ~400 LOC of pure Java. No JNI, no third-party CRDT lib.
Reasons over `automerge-java`:

- Native libs per platform are fragile on Pi/ARM and add 30+ MB to the
  shaded jar — incompatible with the project's "fits on a Pi" thesis.
- Our state shape is narrow and well-bounded (one profile.json doc).
  Automerge's rich text + nested OT is overkill.
- Easier to debug when conflicts surprise us.
- Matches the codebase's existing philosophy ("no LangChain / no kitchen
  sink"; see `STRETCH.md`'s out-of-scope section).

### `HLC` — Hybrid Logical Clock

`(wallclock_ms, logical, nodeId)` triple. Compares by wallclock then
logical then nodeId. `now(physicalNow)` advances logical when the wall
clock didn't move forward; `receive(remote, physicalNow)` merges.
HLC > pure wall-clock because two events on different masters in the
same millisecond still get a total order.

### `LwwRegister<T>`

`{ value, ts: HLC }`. `set(value, hlc)` is monotonic by HLC. Used for
scalar profile fields.

### `LwwMap<K, V>` (when V itself has fields)

Map from stable `id` (UUID-string) to a record-shaped value. Internally a
map of `K -> LwwRegister<V>` plus a tombstone set so deletions are also
LWW.

### `OrSet<E>`

Observed-Remove Set with per-element add-tags. Adds carry an HLC; removes
carry the set of currently-known add-tags. Concurrent add/remove of the
same element resolves correctly. The element type for v1 is `String`
(case-insensitive, matching current `ProfileManager` semantics).

### `Op`

Every mutation is an `Op` of the form:
```
{ id: <hlc>, originMasterId: <uuid>, target: <jsonpath>, kind: <enum>, payload: <json> }
```
Kinds: `LWW_SET`, `LWW_MAP_PUT`, `LWW_MAP_DEL`, `ORSET_ADD`, `ORSET_REM`.
The `target` is a dotted path into the materialized state (`profile.username`,
`taskLists.<listId>.items.<taskId>.dueDate`, etc.).

---

## Op log + snapshot

- **`~/.sentient_mesh_oplog.ndjson`** — append-only, one Op per line.
  Rotated weekly. Truth source for replication.
- **`piassistant/user_profile.json`** — a periodically-flushed
  materialized snapshot. Faster to bootstrap on cold start; lossy
  (snapshot doesn't know HLCs). The op log wins when they disagree.
- **Snapshot interval**: every 60 s of activity, or on shutdown.
- **Bootstrap order**: load snapshot, then replay any newer ops from the
  log (compared by max HLC seen in the snapshot's `_meta` block).

This is identical to how most LSM stores work, and it's safe even if the
process is `kill -9`ed mid-write because both files are atomic-renamed.

---

## Master identity

`~/.sentient_master_id` holds a UUID generated on first boot. It survives
reinstalls of the app as long as the home directory is intact. Every
broadcast WS message gets an `origin_master_id` field — browsers and
peers use it to dedupe when they're connected to more than one master.

---

## Sync protocol (Phase 2 work)

Master-to-master is **another** WebSocket — separate endpoint
`/mesh-sync` (gated by an HMAC token derived from the pairing phrase).
Not on the existing `/ws` (which is for browsers) nor `/helper` (native
OS helpers).

Handshake (v1, HKDF-derived, NOT Noise — that comes in Phase 5):
```
A → B :  hello { masterId, hlc }                    (no auth on hello)
B → A :  challenge { nonce_32B, masterId, hlc }
A → B :  proof { hmac_sha256(hkdf(phrase, nonce), masterId || hlc) }
B → A :  accept | reject
```
After accept, every message is HMAC-signed with the same key. The phrase
itself is 6 words from a 256-entry wordlist → ~48 bits — fine for a
30-second pairing window but not strong long-term, which is why
**Phase 5 swaps to a proper Noise XX handshake** and stores per-peer
long-lived signing keys.

Sync loop:
```
A → B :  vector { lastSeenByMaster: { masterId -> hlc } }
B → A :  ops [ <Op>... ]   // every op B has that A is missing
A → B :  vector            // updated
… until both sides have identical clocks …
```

Ops are idempotent (the HLC is the key), so a duplicate delivery is a
no-op. The receiving master applies each op to its `ReplicatedState`,
appends to its own op log (tagged with the origin), and rebroadcasts to
**other peers** for transitive sync (the gossip step).

---

## Pairing UX (Phase 2)

`Settings → MESH → Add peer`:

1. Master A clicks **GENERATE PAIRING PHRASE**. A shows
   `aubrey lemon panda velvet quartz march` + a 60-second countdown.
2. Master B opens its own MESH panel, clicks **PAIR WITH ANOTHER MASTER**,
   pastes A's `host:port` and the 6-word phrase, hits **PAIR**.
3. Under the hood B connects to A's `/mesh-sync`, runs the HKDF handshake
   above, receives long-lived signing keys, persists them to
   `~/.sentient_mesh_peers.json`.
4. Both UIs update to show the peer pair, "last sync = just now".

Once paired, peers reconnect automatically across reboots. No phrase
needed again.

---

## Browser failover (Phase 3)

On first connect, master sends a `peers` list to the browser:
```json
{ "type": "mesh_peers", "peers": [{"name":"home","url":"home.local:7070"}, ...] }
```
The browser persists this list to `localStorage` under `sentient_peers`.
When the WS or a REST call fails (and reconnect times out twice), the
browser walks the list and tries the next peer. Existing
`sentient_masterHost` setting is preserved as a "preferred" peer; it just
gets first slot.

WS messages now carry `origin_master_id`. The browser dedupes by
`(origin_master_id, msg_seq)` so it doesn't render the same chat word
twice if it's connected to both masters during a brief overlap.

---

## What this branch ships (Phase 1) — COMPLETE

The bar for landing this branch as "Phase 1 complete":

- [x] **MESH_PLAN.md** committed (this doc).
- [x] **CRDT primitives** (`HLC.java`, `HlcClock.java`, `LwwRegister.java`,
      `OrSet.java`) compiled, with javadoc on every public method.
      Self-test at `com.sentient.mesh.MeshSelfTest` covers ordering,
      monotonicity, receive-merge, idempotent apply, add-wins.
- [x] **`Op.java`** + JSON serialization (Gson-based, matches the
      `{ id, origin, kind, path, payload }` shape above).
- [x] **`OpLog.java`** — append-only `~/.sentient_mesh_oplog.ndjson`
      with replay-on-boot + in-memory dedup. Per-line `SYNC` writes;
      tolerant of partial trailing lines after a kill -9.
- [x] **`ReplicatedState.java`** + **`ReplicatedDoc.java`** —
      `apply(Op)` is the single mutation entry, replay-deterministic.
      Doc composes state + clock + log + origin, exposes
      `setLwwString / setLwwBool / addToSet / removeFromSet /
      applyRemote` and an `OpListener` hook for the Phase 2 gossip
      layer.
- [x] **`ProfileManager` rewired** through `ReplicatedDoc`. Every public
      method (addHabit / addTaskToList / removeEvent / etc.) keeps its
      signature; the legacy "direct field mutation + saveProfile()"
      pattern is handled by a `reconcile()` pass at save time that
      diffs the cached `UserProfile` against the CRDT view and emits
      ops for any divergence — `GoogleTasksService` setting
      `task.googleId`, `GroqService` doing
      `getUserProfile().preferences.add(arg)`, and the calendar PUT
      endpoint all continue to work without source changes.
- [x] **Migration**: existing `user_profile.json` is read once + poured
      into the op log on first boot. Plus the legacy
      `tasks[]` → `taskLists[0].items` sub-migration is preserved.
- [x] **`~/.sentient_master_id`** generated on first boot and reused.
      `MasterId.load()` handles the missing-file / garbage-file /
      io-error cases.
- [x] **`MeshService.java`** skeleton + **`PeerRegistry.java`**
      (paired peers persisted to `~/.sentient_mesh_peers.json`,
      file mode 0600) + **`PairingPhrase.java`** (256-word list,
      6-word phrase generator + constant-time validator) +
      **`MeshCrypto.java`** (HMAC-SHA256 + HKDF-SHA256 in-process,
      no JNI). No networking yet — Phase 2 wires the actual sync.
- [x] **Settings → MESH panel** in the UI: shows master ID,
      op-log path, replicated-path counts, paired peer list (empty in
      Phase 1), and a "PAIR WITH ANOTHER MASTER" button that says
      "Pairing arrives in Phase 2 — the peer-list backbone is wired
      today; the sync protocol + handshake land in the next branch."
- [x] **Build clean**: `mvn -q -DskipTests compile` succeeds.
- [x] **Tests green**:
      - `MeshSelfTest` — 19 in-process assertions: HLC / clock /
        LwwRegister / OrSet primitive semantics; Op round-trip;
        OpLog append + dedup + readback; ReplicatedState idempotent
        apply + older-HLC rejection; ReplicatedDoc local mutators +
        bootstrap restore + remote idempotency; two-doc convergence
        when ops are mirrored; two-doc concurrent-edit resolution
        (LWW deterministic winner + OR-Set add-wins); OpListener
        fires once per fresh op (and not for dedup'd remotes).
      - `ProfileManagerScenario` — sandbox-rooted end-to-end:
        writes a legacy `user_profile.json` with rich fixtures →
        boots fresh ProfileManager → verifies migration into op log
        (15 ops on the fixture) → exercises typed setters AND the
        legacy direct-mutation pattern (`task.googleId = …`) →
        "restarts" the singleton via reflection → verifies state
        round-trips → confirms master id is stable + that the
        restart pass emits zero redundant ops.

Each bullet was at most one commit. Five commits total on this branch:
1. `Add MESH_PLAN.md` (this doc)
2. `mesh: CRDT primitives (HLC, LwwRegister, OrSet) + self-test`
3. `mesh: Op + OpLog + ReplicatedState + ReplicatedDoc`
4. `mesh: route ProfileManager through op log + add MasterId`
5. `mesh: MeshService skeleton + PeerRegistry + MESH settings panel`

---

## Phase 2 (this branch — partial)

Phase 1 left foundation pieces (PairingPhrase, MeshCrypto.derivePairKey,
PeerRegistry's pending-phrase + upsert, ReplicatedDoc.applyRemote,
MeshService.setOpListener). Phase 2 builds the wire layer on top.

- [x] `/mesh-sync` WS endpoint on `WebServer` — separate from `/ws`
      (browsers) and `/helper` (native OS helpers). HMAC-authenticated
      via the per-peer signing key from `PeerRegistry`.
- [x] Pairing protocol on `/mesh-sync`:
      1. Joiner sends `pair_hello { masterId, nonce, phrase }`.
      2. Initiator looks up the pending phrase via
         `PeerRegistry.redeemPhrase`; if found, derives the same key
         (it's HKDF-deterministic on the phrase) and sends
         `pair_challenge { masterId, nonce, hmac }`.
      3. Joiner verifies + responds
         `pair_complete { hmac }`.
      4. Initiator sends `paired { masterId }`.
      5. Both call `PeerRegistry.upsert(masterId, url, K)`.
- [x] Auth handshake for already-paired peers:
      `auth_hello { masterId, nonce, hmac }` →
      `auth_ok    { masterId, nonce, hmac }`.
- [x] `MeshSyncClient`: dial a peer, run the joiner/auth handshake,
      drive one round of vector + ops exchange.
- [x] Vector-clock-based op exchange:
      `A → B  { type:"vector", lastSeen: { origin → HLC.encode() } }`
      `B → A  { type:"ops", ops: [<Op>...], done }`. Both sides
      initiate; convergence guaranteed because applyRemote is idempotent.
- [x] REST surface: `POST /api/mesh/pair/start` (init generates phrase),
      `POST /api/mesh/pair/redeem` (joiner pairs against host+phrase),
      `POST /api/mesh/sync/{peerId}` (re-sync via stored key),
      `DELETE /api/mesh/peers/{peerId}`.
- [x] In-JVM end-to-end test (`MeshSyncScenario`) — two real Javalin
      masters on ephemeral ports inside one JVM, sandboxed home dirs;
      three cases (pair+sync, auth-reconnect, wrong-phrase).
- [ ] **Persistent connections + gossip**: keep the WS open after the
      first round; subscribe to `ReplicatedDoc.OpListener`; when a
      local op fires, push it as a single-op `ops` message to every
      connected peer except the op's origin. `MeshSyncOutbound`
      interface + `MeshSyncBroadcaster` carry the fan-out. This is
      what makes edits flow live (today a mutation after pairing
      requires a manual `POST /api/mesh/sync/{peerId}` to propagate).
- [ ] Reconnect-with-backoff: jittered exponential, capped at ~60 s.
      `MeshService.maintainPeerConnections()` schedules a periodic dial
      for every peer in the registry that isn't currently connected.
- [ ] `origin_master_id` field on every existing WS broadcast in
      `WebServer.java`. Browsers dedupe by `(origin_master_id, seq)`
      once Phase 3 ships failover.
- [ ] MESH panel pairing UX (HTML + JS): "GENERATE PHRASE" + "PAIR
      WITH MASTER" buttons that drive the REST endpoints above.

## Phase 3 (next-next branch)

- [ ] Browser `peers` list + transparent failover on WS / REST.
- [ ] Dedup of broadcast messages by `origin_master_id`.
- [ ] UI badge in the sidebar showing which master the browser is
      currently bound to.

## Phase 4

- [ ] mDNS auto-discovery (`jmdns`), advertise `_sentient-master._tcp`
      with the master ID in TXT.
- [ ] Tailscale-aware discovery: enumerate peers by reading
      `tailscale status --json`, filter to nodes with the sentient tag.
- [ ] "Discovered peers" list in MESH panel — one-click pair.

## Phase 5

- [ ] Replace HKDF handshake with proper Noise XX (using
      `southernstorm/noise-java` — no JNI).
- [ ] Per-peer long-lived signing keys.
- [ ] Anti-replay (HLC monotonicity check on every op accepted from a
      given origin).
- [ ] Replication-exclusion test: monitor that nothing under
      `~/.sentient_vault.json` or OAuth files leaves the master.
- [ ] Optional TLS for `/mesh-sync` (`wss://`) for Funnel-exposed masters.

---

## Open questions tracked for later

- **Should chat history opt-in to replicate?** Currently per-master. A
  knob in MESH settings to mirror to peers would be cheap, but raises
  privacy concerns when family members each have their own master.
  Defer to after voice ID (STRETCH §3) lands so we can scope per-user.
- **What about Spotify playback state?** Probably stays per-master —
  speakers are physical and tied to one master's network.
- **Conflict-resolution UI?** For v1 we just LWW-pick and move on. If
  users start losing edits, add a "merge conflicts" feed in the MESH
  panel and surface the loser values for one-click restore.

---

## How to use this doc

Each phase checkbox above is a candidate commit. Tick as you go; don't
"batch" five phases into one PR — small commits make bisecting much
easier when something breaks.

When a phase is done, copy its checklist into the commit message body
so reviewers can scan what shipped without opening this file.
