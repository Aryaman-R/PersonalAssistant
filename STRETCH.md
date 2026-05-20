# STRETCH.md — features that would make Sentient Assistant something else entirely

This is the deep-end wishlist. Not the next sprint, not the next quarter — the
features that would push this from "a nice personal-AI hobby project" to
"the thing my friends genuinely envy". Each section has a motivation, a
shipping definition (what "done" looks like), and a real implementation
spine. Anything labelled *Pi-friendly* should still work on a Raspberry Pi
4 / 5; anything else assumes a Mac or beefier Linux box as master.

The ordering is roughly by impact-per-engineering-week, with the wildest
items at the end.

---

## 1. Ambient room awareness

**Motivation.** Today the assistant is reactive — it answers when you speak.
The most useful personal-AI products will be the ones that *know what's
going on around you* and quietly adapt. Lights dim when no one's home, the
morning briefing fires when you actually walk into the kitchen, music
pauses when someone enters the room and says your name. None of this works
without the assistant maintaining a coarse, privacy-preserving model of
who is where.

**What "done" looks like.** A `PresenceService` on master that exposes a
real-time stream of `{room, person, confidence, last_seen}` records, fed
by any combination of (a) cheap USB cameras running on-device person
detection, (b) BLE beacons from phones and watches, (c) the per-room
satellite mics from §2, and (d) explicit "I'm in the kitchen" voice
commands. The chat layer can ask "is anyone in the office?" and get a
straight answer; automations can subscribe to `presence_changed` over
the existing WebSocket bus.

**Implementation spine.**
1. Add `PresenceService.java` alongside `CameraService` with an in-memory
   ring buffer per room and a `Flow<PresenceEvent>` exposed over WS as
   `presence_update`.
2. Ship a small Python sidecar (`native/presence/`) running
   YOLOv8n-pose + a face-embedding model (Insightface buffalo_s — runs on
   Pi 5 at ~3 fps on CPU). Sidecar talks to master over a UNIX socket
   with a 32-byte protobuf per detection. **All embeddings stay local;
   never upload faces.**
3. Person enrollment lives in `Settings → PEOPLE`: each enrolled person
   gets a name, a colour, and a list of cosine-similar embeddings.
   Unknown faces are tagged `guest_<short_hash>` and auto-expire after
   72 h.
4. Phone presence via the existing browser clients — when a client's
   visibility changes or it disconnects/reconnects, raise a presence
   hint tagged with that device's owner.
5. New command `[CMD:WHO_IS_HERE]` returns the current snapshot; the LLM
   system prompt gets a one-line summary injected on every turn
   ("Currently home: Aryaman (office), guest_a1b (kitchen)").

**Pi-friendly?** Yes — Pi 5 + a $10 USB cam per room. Pi 4 needs the
nano model and ~2 fps. Camera frames never leave the master.

---

## 2. Satellite mic & speaker mesh

**Motivation.** One mic on one Pi is the fatal flaw of every voice
assistant project. Either you're shouting at the kitchen Pi from the
bedroom, or you bought four Echos and now Amazon owns your conversations.
A mesh of cheap, dumb satellites — each just a mic + speaker + Wi-Fi —
solves both: the assistant follows you between rooms, ducks audio in the
room you're leaving, and you keep ownership of every byte.

**What "done" looks like.** Walk from kitchen to office mid-conversation;
TTS hands off room-to-room within ~150 ms, mic input automatically picks
the closest satellite by RMS energy, and the assistant addresses the
correct person if voice ID (§4) is enabled.

**Implementation spine.**
1. Reference hardware: **ESP32-S3 + INMP441 mic + MAX98357 amp + small
   speaker** (~$12 BOM). Firmware in Rust via `esp-hal`; alternative
   path is a Raspberry Pi Zero 2 W running a tiny Go agent if you want
   wake-word on-device.
2. Each satellite advertises over mDNS as `_sentient-sat._tcp` with a
   declared `room` field. Master keeps a `SatelliteRegistry` in
   `WebServer.java`, heartbeated every 10 s.
3. Audio transport: **bidirectional Opus over WebRTC DataChannel**
   (not WebSocket — DataChannel gives us SCTP unordered/unreliable for
   audio frames at 20 ms cadence with <40 ms RTT on LAN). Re-use the
   WebRTC plumbing already planned for screen mirror.
4. Routing logic in a new `MicRouter`: pick the satellite with highest
   smoothed input RMS for the last 600 ms; switch hysteretically so a
   sneeze doesn't bounce the active mic.
5. TTS playback selection: by default play on the room the user is
   currently *in* (from PresenceService). For multi-room announcements
   ("kids, dinner!") add a `[CMD:BROADCAST:<message>]` tag.
6. Latency budget: < 250 ms wake-word-to-listening across mesh — tight,
   but doable since Vosk runs once on master, not per-satellite.

**Pi-friendly?** Master can stay on a Pi. Satellites are intentionally
cheaper and dumber than a Pi.

---

## 3. Speaker diarization & per-person voice ID

**Motivation.** "Add eggs to *my* shopping list" should add to *the
speaker's* list, not whoever last logged in. Same for "play my drive
playlist". Voice ID is also the cleanest authentication factor in a
shared house — no passwords, no phones, just say your name.

**What "done" looks like.** Every transcription emitted by `Listener.java`
is tagged with a `speaker_id`. The active LLM session is scoped per
speaker. Switching speakers mid-conversation transparently switches
profile, task list, calendar context, and TTS voice.

**Implementation spine.**
1. Replace bare Vosk with **Vosk + pyannote-audio's
   speaker-segmentation-3.0** (ONNX-quantised, ~80 MB, ~5× realtime on
   Pi 5). Run as a parallel pipeline so transcription latency doesn't
   regress.
2. Per-speaker embeddings stored encrypted in `~/.sentient_voices.json`
   under the existing vault key. Enrollment UI in `Settings → PEOPLE`
   asks each person to read three sentences (~15 s).
3. `Listener` emits `{text, speaker_id, confidence, start_ts, end_ts}`
   tuples. `confidence < 0.65` falls back to "unknown" rather than
   guessing.
4. `ProfileManager` becomes per-speaker: rename to `Profiles` with a
   `getActive(speakerId)` accessor. `user_profile.json` migrates to
   `profiles/<speakerId>.json`; a one-shot migration in the next boot
   shimmies the existing file into `profiles/owner.json`.
5. The LLM system prompt gets a `speaker:` line per turn so the model
   can ground "my tasks" correctly. The chat history stays per-speaker
   too — your kids shouldn't see your therapy notes.
6. Optional auth: tie voice ID to the existing shared-password gate —
   master can require a matching voice for any command that touches
   the credential vault.

**Pi-friendly?** Tight on Pi 4. Comfortable on Pi 5 / Mac.

---

## 4. Local LLM offline mode ✅ *shipped*

**Motivation.** OpenClaw + Groq is great until the wifi dies, the
provider rate-limits you, or you don't want a third party seeing the
prompt. A baked-in local fallback means the assistant **never goes
down**. It also unlocks honest privacy claims for the credential vault
and voice transcripts.

**What "done" looks like.** A toggle in `Settings → CHAT ENGINE`:
`Auto / Cloud / Local`. In Auto, the router tries OpenClaw → Groq →
Local in order, with a 1.5 s soft-timeout per hop. Local mode keeps
conversation history and tool-call shape identical to the cloud
engines, so commands like `[CMD:ADD_TASK]` still work.

**Implementation spine.**
1. Bundle **llama.cpp** via the existing Maven shade or a sibling
   `native/llamacpp/` directory; pull a prebuilt binary per OS at
   install time (the installer script already has the hooks).
2. Default model: **Qwen2.5-7B-Instruct Q4_K_M** (~4.5 GB, runs at
   ~7 tok/s on Pi 5 with NEON, ~30 tok/s on M-series Mac, ~80 tok/s
   on a 4070).
3. Wire a `LocalLlmService.java` that mirrors `GroqService`'s public
   surface — same `chat(history, attachments)` signature so the
   router can hot-swap. Use llama.cpp's HTTP server (`./server -m
   model.gguf`) so we don't need JNI.
4. Tool-calling: the command-tag protocol (`[CMD:...]`) already works
   via plain string output. For richer tool calls (Composio), expose a
   `tools_available:` block in the system prompt and parse JSON
   blobs the model emits — same shape as OpenAI tool-calls but
   string-based so any model can play.
5. Quantisation matrix shipped: tiny (1.5B Q4), medium (7B Q4),
   beefy (14B Q4). Wizard picks based on detected RAM.
6. Stretch: speculative decoding using a 0.5B draft model paired
   with the 7B target — 2× tok/s on M-series, "free" if you've
   already paid the RAM cost.

**Pi-friendly?** Tiny-tier on Pi 4. Medium-tier on Pi 5 (8 GB).

---

## 5. Long-running agent runtime

**Motivation.** Right now every chat turn is synchronous: you ask, the
LLM answers, done. The interesting frontier is **work that takes
hours**. "Spend tonight researching the best heat pump for my house and
have a comparison waiting in the morning." "Draft three different
op-eds on this topic and pick the best one." That's a real agent loop
with checkpointing, tool use, cost accounting, and a UI for inspecting
intermediate steps.

**What "done" looks like.** A new `AGENTS` tab. You give an agent a
goal in natural language; it shows you a live plan tree (with status
per step), a running cost meter, a "kill" button, and a final summary
artefact when done. You can switch tabs, close the laptop, walk away —
work continues on master.

**Implementation spine.**
1. New `AgentRuntime.java` with a SQLite store at
   `~/.sentient_agents.db`. Schema: `agents`, `steps`, `events`,
   `artefacts`. Steps are nodes in a DAG with `pending / running /
   done / failed / cancelled` states.
2. Planner/executor split: a planner LLM call decomposes the goal into
   a tree of steps; an executor LLM call runs each leaf step with
   full tool access. Re-planning is allowed when a step fails or
   surfaces unexpected info.
3. Tool surface: everything the chat layer already has — Composio MCP,
   Spotify, Tasks, Calendar, automation webhooks — plus a new
   `agent.write_artefact(name, body, mime)` for outputs.
4. Cost accounting: every LLM call writes `{tokens_in, tokens_out,
   provider, $cost_est}` to the events log. The agent self-aborts
   when a configurable per-agent budget is hit. Defaults: $1/agent,
   100k tokens, 6 hours wallclock.
5. Persistence across restarts: any agent in `running` state at
   shutdown is moved to `paused`; on boot the runtime offers to
   resume them.
6. UI: tree view of steps, click a step to see its prompt+response,
   tools used, and elapsed time. Final artefacts are listed below
   with download buttons and a "open in chat" action.
7. Stretch: **multi-agent collaboration** — a "team" template with
   pre-defined roles (researcher, writer, critic). Round-robin or
   debate-style turn-taking, capped at N rounds.

**Pi-friendly?** Runtime is. Whether the *agents* are depends on
which LLM backend they hit — local 7B is fine for shallow tasks,
deep research wants Sonnet-class.

---

## 6. Episodic memory & knowledge graph ✅ *episodic half shipped*

**Motivation.** A 20-message rolling history is a goldfish brain. A
genuine personal assistant should remember that your sister's wedding
is in June, that you've been trying to cut down coffee, that the last
six things you searched were about Rust async. The leap from
"chatbot" to "knows me" is mostly a memory system.

**What "done" looks like.** Every conversation, every email skimmed,
every doc shared with the assistant gets embedded and stored. The LLM
gets a "relevant memories" block injected per turn based on similarity
to the active context. A `MEMORY` tab shows the full graph, lets you
delete entries, and lets you ask questions like "when did I last
talk about heat pumps?".

**Implementation spine.**
1. **Vector store**: SQLite + `sqlite-vec` extension (no external
   service). One table per memory namespace (`chat`, `email`,
   `notes`, `artefacts`).
2. **Embedding model**: locally-run BGE-small-en-v1.5 (33 MB ONNX,
   <50 ms per embed on Pi 5).
3. Indexer threads: hooked into `WebServer.broadcast()` so every
   turn auto-embeds + stores. Pluggable indexers for new sources
   (Gmail via Composio, Drive, browser history via a future
   extension).
4. **Knowledge graph layer** on top of the vector store. Use an
   LLM extraction pass nightly: pull (subject, predicate, object)
   triples from the day's events; store in a `triples` table with
   provenance pointers back to source memories.
5. Retrieval at chat time: hybrid — top-k vector search +
   graph-walk from any entities mentioned in the user message.
   Inject as `## Relevant memories\n- ...` in the system prompt.
6. **Forgetting policy**: explicit `[CMD:FORGET:<topic>]`,
   automatic decay (memories not retrieved in 90 d drop priority),
   and a manual purge UI. PII detector flags memories that look
   like credentials — those never leave master, even to cloud LLMs.
7. Stretch: **timeline view** — scroll through your life as a
   vertical feed of remembered events, grouped by week.

**Pi-friendly?** Yes — embed model + sqlite-vec are tiny.

---

## 7. Predictive briefing & proactive nudges

**Motivation.** The assistant should occasionally speak first.
Morning brief on the way to the kitchen ("good morning — three
meetings today, your 10 a.m. with Tomás moved to 11, it's raining,
take the umbrella"). Anomaly nudge when your routine breaks ("you
usually finish standup by 9:45, it's 10:30 and you haven't moved —
all good?"). This is the difference between "tool" and "presence".

**What "done" looks like.** A `RoutineLearner` that builds a
probabilistic model of your daily/weekly patterns from the existing
event stream (calendar, tasks completed, presence, music history).
A `Briefing` template renders a contextual paragraph at chosen
triggers. A `Nudge` engine fires when the present state diverges
from prediction beyond a threshold.

**Implementation spine.**
1. **Event log**: every WS broadcast + every command executed is
   appended to `~/.sentient_events.ndjson` (rotated weekly). This
   becomes the training corpus.
2. **Pattern model**: nothing fancy — a Bayesian time-of-day model
   per event type (e.g., `task_completed`, `spotify_play`,
   `door_arrived`). Fit nightly in a 30 s job.
3. **Trigger engine**: cron-like rules registered as
   `BriefingTrigger { when: presence_change('kitchen'), template:
   'morning' }`. Templates are markdown with `{{slots}}` filled by
   data sources.
4. **Anomaly detector**: at every tick (30 s), compute likelihood of
   current state vs. learned distribution; if below 1st percentile
   for >15 min, fire a nudge. Rate-limit to 1 nudge / hour /
   category.
5. **User controls**: a `BRIEFINGS` settings panel to enable/mute
   specific triggers, change quiet hours, preview templates.
6. **Delivery**: TTS to the room the user is in (from §1+§2), plus
   a notification card in any open browser. Never barge in during
   a meeting (calendar-aware).
7. Stretch: **goal coaching** — register goals ("walk 6k steps",
   "no coffee after 2 p.m."), wire them to wearable data (§12),
   nudge accordingly.

**Pi-friendly?** Yes.

---

## 8. Smart home actuation (Home Assistant + Matter)

**Motivation.** Voice + LLM + smart home is the holy trinity, and
you have two of the three already. Adding the third means "Jarvis,
dim the lights and put on something focused" actually works.

**What "done" looks like.** A `SmartHomeService` that exposes
discovered devices to the LLM as Composio-style tools. Commands like
`[CMD:DEVICE:livingroom_lights:dim:30]` route to the right backend.
Routines can target devices.

**Implementation spine.**
1. **Primary backend**: Home Assistant via its WebSocket API.
   `~/.sentient_homeassistant.json` stores the long-lived access
   token + URL. Devices auto-discovered into a typed registry.
2. **Secondary backend**: direct Matter via `python-matter-server`
   sidecar, for users who don't want a full HA install.
3. **Tool surface**: each discovered device becomes a tool with a
   typed schema (`turn_on`, `turn_off`, `set_brightness(0-100)`,
   `set_color(rgb)`, `set_temperature(c)`, etc.). The LLM sees
   only friendly names + capabilities.
4. **Scenes**: "Movie night" → multiple device calls in parallel.
   Authoring via natural language ("when I say movie night, dim
   the lounge to 20 and play X") which writes a YAML routine to
   `~/.sentient_routines/`.
5. **Safety rails**: locks, garage doors, alarms require an extra
   confirmation step (voice ID match from §3, or shared password
   in the open browser). No "Jarvis unlock the front door" without
   it.
6. **Energy view**: pull cumulative consumption per device daily;
   surface in a new `ENERGY` panel.

**Pi-friendly?** Yes — HA itself is happy on a Pi.

---

## 9. Multi-master mesh (CRDT sync)

**Motivation.** Today there's one master, full stop. If it's down,
you're stuck. If you split your life across home Pi and work laptop,
state diverges. A federated mesh of masters that converge state via
CRDTs gives you redundancy *and* per-location specialisation
("home master controls the lights; work master holds the work
calendar; both see the same memory graph").

**What "done" looks like.** Spinning up a second master and pairing
it (single QR scan) makes the two devices a peer set. State edits on
either propagate within seconds. Browsers connect to whichever is
reachable; failover is transparent.

**Implementation spine.**
1. Replace plain `ProfileManager` writes with **Automerge** documents
   (Java port via `automerge-java`). Each profile/document gets a
   stable ID + per-node actor ID.
2. **Sync protocol**: WebSocket between masters carrying Automerge
   sync messages. Use mDNS discovery on LAN, Tailscale node names
   beyond it.
3. **Conflict semantics**: last-writer-wins is fine for most fields;
   task lists are sets (add/remove are commutative); chat history
   is per-master append-only (no conflicts possible).
4. **Pairing UX**: `Settings → MESH → Add Peer` shows a 6-word
   pairing phrase + a 60 s window. The other master joins by typing
   it. Under the hood it's an authenticated Noise handshake that
   exchanges per-peer signing keys.
5. **Browser routing**: client gets a `peers: [{name, url}]` list
   on first connect; if its preferred peer is unreachable, it
   tries the next one. WS messages tagged with `origin_master_id`
   so duplicates can be deduped on reconnect.
6. **State that should NOT replicate**: API keys, vault, OAuth
   tokens. Those stay strictly per-master and the peer model
   acknowledges that explicitly.

**Pi-friendly?** Yes.

---

## 10. Real-time multi-party translation

**Motivation.** Family with a non-native-English speaker, hosting an
exchange student, video call with someone in another language — the
assistant can be the simultaneous interpreter, with per-listener
delivery (the right TTS plays in the right room from §2).

**What "done" looks like.** Toggle `TRANSLATE` mode in VOICE; pick
target languages per speaker / per room. Everyone speaks their own
language, hears everyone else in theirs. Latency target: <2 s
end-to-end.

**Implementation spine.**
1. **STT**: drop Vosk for translation sessions, swap in
   **Whisper-small.en** for English, multilingual Whisper-base for
   other source langs. Run on master via whisper.cpp.
2. **MT layer**: tiny NLLB-200-distilled-600M (ONNX, ~1.2 GB) for
   the actual translation step. Cheap enough to run per chunk.
3. **TTS per language**: bundle Piper voices for the top 8
   languages (~30 MB each).
4. **Chunking**: stream STT at 250 ms windows with VAD-driven
   sentence boundaries; translate + TTS at sentence boundary, not
   per word, otherwise you get gibberish ordering.
5. **Routing**: speaker ID (§3) determines source language;
   per-listener preferred language determines TTS voice. Output
   sent only to satellites in rooms NOT containing that speaker
   (otherwise echo).
6. Stretch: **whisper mode** — translated output goes only to one
   listener's earbud (via their phone's open browser using WebRTC
   audio).

**Pi-friendly?** Tight. Realistically wants a Mac or N100 mini-PC
for sub-2-s latency.

---

## 11. Plugin SDK + community marketplace

**Motivation.** The assistant grows faster if other people can extend
it. Composio is great but generic; a first-class plugin SDK lets
people ship per-house features (recipe scrapers, school portals,
custom dashboards) without forking.

**What "done" looks like.** A `plugins/` directory under master.
Each plugin is a JAR (Java) or a folder with a manifest (Python /
Node). Master discovers them at boot, sandboxes them, and offers
their tools to the LLM. A community index lists installable plugins;
one-click install from the UI.

**Implementation spine.**
1. **Manifest**: `plugin.toml` declaring name, version, entrypoint,
   declared capabilities (`tools`, `panels`, `triggers`,
   `webhooks`), required permissions (`net`, `fs:~/Documents`,
   `vault:read:X`).
2. **JVM plugins**: load via separate ClassLoader; communicate over
   a `PluginBus` interface. **Non-JVM plugins**: WASM runtime
   (Wasmtime / Chicory) — single sandbox for Python (via
   Pyodide-WASM), JS, Rust, Go.
3. **Capability-based permissions**: every plugin call goes through
   a permission gate. First time a plugin asks for `net:openai.com`
   the user gets a prompt. Decisions cached per plugin version.
4. **Panel API**: plugins can register a UI panel — they ship a
   single-file HTML/JS that runs in an iframe inside the main app,
   with a postMessage RPC to master.
5. **Marketplace**: a static GitHub Pages site backed by a
   `plugins.json` index. Master pulls it; install = fetch + verify
   signature + extract to `plugins/<name>/`. Signing keys per
   author; revocation list checked on install.
6. **First-party reference plugins** to validate the API: weather,
   RSS digest, Hacker News briefing, recipe extractor, GitHub
   issue triage.

**Pi-friendly?** Plugins are user-controlled; SDK itself is fine.

---

## 12. Cognitive coach (spaced repetition + Socratic tutor)

**Motivation.** Most "AI assistants" make you smarter only by saving
clicks. Sentient should make you smarter *for real*. Two features:
auto-generated flashcards from things you read/watch/learn, and a
tutor mode that asks you questions instead of just answering yours.

**What "done" looks like.** A `LEARN` panel. Drop a PDF, paste a
URL, or just say "I want to learn X" — the assistant generates
SM-2-graded flashcards. A daily 5-minute review prompt fires from
the briefing engine. Tutor mode is a chat preset that refuses to
give answers and instead Socratically prods.

**Implementation spine.**
1. **Card generation**: LLM call over chunked source material with
   a tight system prompt — "extract atomic facts as Q/A pairs;
   one fact per card; cloze deletions where natural". Validate
   each card with a second LLM pass (drop ambiguous ones).
2. **SRS engine**: textbook SM-2 with optional FSRS upgrade.
   Store in `~/.sentient_cards.db` (SQLite). Per-user (from §3).
3. **Review UI**: a focused full-screen mode. Big card, three
   buttons (again / good / easy). Targets ~5 min / day.
4. **Tutor preset**: system prompt: *"You are a Socratic tutor.
   Never give answers directly. Ask one question at a time. Probe
   the user's reasoning. If they're stuck after three exchanges,
   give the smallest hint that unblocks them."* Wired to a
   `Mode → TUTOR` toggle in the chat panel.
5. **Surface area**: cards generated from any artefact in the
   memory graph (§6), automatically tagged with source. Browse by
   tag, deck, or recency.
6. **Voice-first review**: in VOICE mode, the assistant reads the
   question, listens for your spoken answer, grades it with an
   LLM call.

**Pi-friendly?** Yes.

---

## 13. AR/XR companion

**Motivation.** Vision Pro and Quest 3 are now common enough that
"my assistant has a body in mixed reality" is a real feature, not a
demo. A floating orb avatar that turns to face you, shows
transcripts as a halo, projects the current task list onto your
wall — this is the kind of thing that converts skeptics.

**What "done" looks like.** Open the existing web UI in WebXR mode
on a Vision Pro / Quest browser; the chat panel becomes a floating
3D avatar with the same orb you have in VOICE today, anchored in
your room. Hand-tracking lets you "pin" panels to walls.

**Implementation spine.**
1. **WebXR mode**: a new entry point `index.html?xr=1` loads
   **three.js + @react-three/xr** (or vanilla WebXR if we're
   keeping the no-framework rule). Detect WebXR support, fall back
   to flat UI otherwise.
2. **Avatar**: port the existing orb shader (CSS today) to a GLSL
   fragment shader on a 3D sphere. Same state machine
   (idle/listening/thinking/speaking) drives it.
3. **Spatial anchors**: panels are 2D HTML rendered to a texture
   via `<foreignObject>` → canvas → `THREE.CanvasTexture`. Each
   panel has a world transform stored per-user in profile.
4. **Hand tracking**: pinch + drag to move panels; pinch + pull
   to scale; two-pinch to dismiss. Accessibility: every gesture
   has a voice-command equivalent.
5. **Audio**: spatial — TTS appears to come from the avatar's
   position, mic input gain biased toward the user's head pose.
6. Stretch: **passthrough HUD** — call out items in your field of
   view by name ("that's the heat pump you bookmarked last week").
   Uses the same vision pipeline as §1 but on the headset's
   passthrough stream.

**Pi-friendly?** Headset does the rendering; master just streams
WebRTC + WS. Pi master is fine.

---

## 14. Conversation git (replay, branch, audit)

**Motivation.** LLM conversations are write-once and forgettable
today. Real engineering work wants the same toolchain as code:
branching to try a different path, replaying with a different model,
diffing two completions, blame-tracing why the AI made a claim.

**What "done" looks like.** Every message is a content-addressed
node in a DAG. The chat UI has a "branch from here" button,
a "rewind to here" button, and a "compare branches" view. The audit
log shows the full prompt + tools used for any past response.

**Implementation spine.**
1. **Storage**: replace the current `chat_history: List` with a
   Merkle DAG. Each node: `{id, parent_ids, role, content,
   model, tools_used, ts, attachments}`. Stored in
   `~/.sentient_chat.db`.
2. **Active head pointer**: per session, points to the leaf.
   Branching = creating a new child off any node and moving the
   head there. The old head still exists; switch back any time.
3. **Replay**: any node can be re-executed with a different model
   or tool config; result becomes a sibling node. Cheap to A/B.
4. **Diff view**: side-by-side render of two leaves with shared
   ancestor highlighted. Useful for "Sonnet vs. Opus on this
   question".
5. **Audit**: clicking any AI message expands to show the full
   prompt that produced it (system prompt + history + memory
   injections + tool call traces), the model name, and the exact
   provider response with timings.
6. **Export**: a branch as Markdown, a thread as JSON, the whole
   DAG as a Graphviz file.

**Pi-friendly?** Yes — pure bookkeeping.

---

## 15. Privacy egress firewall ✅ *shipped*

**Motivation.** Even with a local LLM, the assistant talks to
Google, Spotify, Composio, OpenClaw providers. A user should be
able to *see and constrain* every byte leaving master. This is the
feature that lets you give the assistant to your privacy-paranoid
sibling and have them actually use it.

**What "done" looks like.** A `PRIVACY` settings tab listing every
outbound destination, total bytes today, last call time, and a
toggle per destination. A "panic" mode kills all egress except a
declared local-LLM endpoint.

**Implementation spine.**
1. Route **all** outbound HTTP through a single
   `EgressClient.java` (already mostly the case — finish the
   refactor). Every call tagged with `{destination, purpose,
   data_classes_sent}` enums.
2. Per-destination toggles persisted in
   `~/.sentient_egress.json`. Disabled destinations return
   `EgressDeniedException` immediately; callers get a typed
   error.
3. **Live log**: ring buffer of last 500 calls in memory,
   visible in the UI with redaction (no body content, just
   metadata).
4. **Data-class redaction**: callers declare what classes of
   data are in the payload (`chat_text`, `email_subject`,
   `voice_transcript`, etc.). The firewall can be configured
   to strip or block by data class — e.g., "voice transcripts
   never leave master".
5. **DNS-layer enforcement**: optionally install a tiny local
   DNS resolver (`dnsmasq`-style) that blackholes any
   destination not on the allowlist — belt-and-braces against
   accidental egress from a misbehaving library or plugin.
6. **Panic toggle**: keyboard shortcut + voice command
   ("Jarvis, go private"). Switches to local LLM, blocks all
   other egress, pauses cloud sync.

**Pi-friendly?** Yes.

---

## Out of scope (deliberately)

A few obvious-sounding additions are *not* on this list because they
don't survive a second look:

- **Email triage / auto-reply.** Tempting, but the failure mode (AI
  replies on your behalf and gets it wrong with a real human) is
  bad enough that it should stay a manual-confirmation feature
  under existing Composio Gmail. No autopilot.
- **A native mobile app.** The PWA path is good enough and one less
  thing to maintain. iOS Safari + Add To Home Screen covers 90 %.
- **A LangChain / LlamaIndex dependency.** The internal abstractions
  here are already cleaner than those frameworks; adding them would
  be regression.
- **Cloud-hosted offering.** This project's value is *that it runs on
  your hardware*. A SaaS spin-off would dilute the thesis.

---

## How to pick what to ship next

If you can only do one thing from this document: **§4 Local LLM
offline mode**. It's the smallest in scope, eliminates the project's
biggest reliability cliff, and unlocks honest privacy claims that
make several later items credible.

If you can do two: §4 + **§6 Episodic memory**. Memory is the
feature that makes everyone who tries the assistant go "oh, this
is different".

If you can do three: §4 + §6 + **§9 Multi-master mesh**. Once
you've got memory worth protecting, you need redundancy worth
trusting.

Everything else is downstream of those three.
