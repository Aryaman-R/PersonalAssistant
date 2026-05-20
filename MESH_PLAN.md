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

## What this branch ships (Phase 1)

The bar for landing this branch as "Phase 1 complete":

- [ ] **MESH_PLAN.md** committed (this doc).
- [ ] **CRDT primitives** (`HLC.java`, `LwwRegister.java`, `LwwMap.java`,
      `OrSet.java`) compiled, with javadoc on every public method.
- [ ] **`Op.java`** + JSON serialization (Gson-based, matches the
      `{ id, originMasterId, target, kind, payload }` shape above).
- [ ] **`OpLog.java`** — append-only `~/.sentient_mesh_oplog.ndjson`
      with rotation + replay-on-boot.
- [ ] **`ReplicatedState.java`** — materialized view + `apply(Op)` +
      `view()` returning a `UserProfile`-shaped record. Backed by the
      CRDT primitives above.
- [ ] **`ProfileManager` rewired** so every existing mutation produces an
      `Op` + applies it, but `getUserProfile()` still returns the same
      shape (no caller breaks).
- [ ] **Migration**: existing `user_profile.json` upgrades cleanly on
      first boot — every field becomes a stamped LWW/OR-Set entry. No
      data loss for any user upgrading from main.
- [ ] **`~/.sentient_master_id`** generated on first boot and reused.
- [ ] **`MeshService.java`** skeleton (config load + peer-list
      persistence; no networking yet).
- [ ] **Settings → MESH panel stub**: shows the master ID + an empty
      peer list. Pairing button is wired but says "Phase 2 — coming
      soon." Keeps the wiring honest end-to-end without the bits that
      aren't ready.
- [ ] **Build clean**: `mvn -q -DskipTests package` succeeds.
- [ ] **Manual smoke test**: start app, add a task, restart, verify
      restore.

Each bullet is at most one commit. Smaller commits are fine.

---

## Phase 2 (next branch)

- [ ] `/mesh-sync` WS endpoint + HKDF handshake.
- [ ] `MeshService` peer-connection lifecycle: dial, retry, backoff,
      heartbeats.
- [ ] Pairing UX: phrase generation + redemption.
- [ ] Vector-clock-based op exchange.
- [ ] Gossip re-broadcast.
- [ ] Origin tagging on every existing WS broadcast (`origin_master_id`).
- [ ] First-pass mesh end-to-end test: two `mvn javafx:run` instances
      on different ports, paired, both see each other's tasks within
      a second.

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
