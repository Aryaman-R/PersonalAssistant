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

## Phase 2 (next branch)

Phase 1 left some foundation pieces in place for Phase 2 to build on:
{`PairingPhrase`, `MeshCrypto.derivePairKey`, `PeerRegistry.createPendingPhrase`,
`PeerRegistry.redeemPhrase`, `MeshService.setOpListener`} are all wired
locally — they just don't speak to anything over the wire yet.

- [ ] `/mesh-sync` WS endpoint on `WebServer` — separate from `/ws`
      (browsers) and `/helper` (native OS helpers). HMAC-authenticated
      using the per-peer signing key from `PeerRegistry`.
- [ ] Pairing protocol on `/mesh-sync`:
      1. Joiner sends `pair_hello { masterId, nonce_j }`.
      2. Initiator looks up the pending phrase via
         `PeerRegistry.redeemPhrase`; if found, derives the same key
         (it's HKDF-deterministic on the phrase) and sends
         `pair_challenge { masterId, nonce_i,
          hmac = HMAC(K, "I" || masterId || nonce_j) }`.
      3. Joiner verifies + responds
         `pair_complete { hmac = HMAC(K, "J" || masterId || nonce_i) }`.
      4. Both call `PeerRegistry.upsert(masterId, url, K)`.
- [ ] `MeshSyncClient`: dial each paired peer, run the auth handshake
      using the stored key, then enter the sync loop. Retry with
      jittered exponential backoff on disconnect.
- [ ] Vector-clock-based op exchange:
      `A → B  { type:"vector", lastSeen: { node-uuid → HLC.encode() } }`
      `B → A  { type:"ops", ops: [<Op>...] }`  (only ops B has whose
      `hlc` > A's lastSeen for that origin) ... until both sides settle.
- [ ] Gossip re-broadcast: route newly accepted remote ops to every
      OTHER paired peer (avoid the originator + the immediate sender).
- [ ] `origin_master_id` field on every existing WS broadcast.
      Browsers dedupe by `(origin_master_id, broadcast_seq)`.
- [ ] First-pass mesh end-to-end test (run inside the JVM, two
      `MeshService` instances + two `ReplicatedDoc`s + two `OpLog`s
      in temp dirs): pair them, mutate state on one, assert
      convergence on the other within 200 ms wallclock.

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
