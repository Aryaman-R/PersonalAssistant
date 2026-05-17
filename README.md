# Sentient Assistant

A personal AI assistant that runs on one device and is reachable from every screen in the house. Master-on-Pi (or any always-on machine), client in any browser. Voice-first when you want it, keyboard-first when you don't.

```
┌─────────────────────────────┐                ┌────────────────────────┐
│  Master device              │                │  Client device         │
│  (Pi / Mac / Linux box)     │                │  (any browser)         │
│                             │                │                        │
│  ┌─────────────────────┐    │   ws://:7070   │                        │
│  │  Java + Javalin     │◄───┼────────────────┤  index.html + app.js   │
│  │  + Vosk listener    │    │   http://:7070 │  + Spotify Web SDK     │
│  │  + Piper TTS        │    │                │                        │
│  │  + LLM client       │    │                └────────────────────────┘
│  └──────────┬──────────┘    │
│             │               │   ┌─── OpenClaw gateway (local, :18789)
│             ├───────────────┼───┤      └─ Anthropic / OpenAI / Google /
│             │               │   │         Groq / OpenRouter / xAI / …
│             │               │   ├─── Composio MCP (tools: GitHub, Gmail,
│             │               │   │     Slack, Notion, Calendar, Drive, …)
│             │               │   ├─── Spotify Web API
│             │               │   └─── Google Tasks + Calendar
│  user_profile.json (state)  │
└─────────────────────────────┘
```

## What it does

- **Chat with an LLM** via either a local **OpenClaw** install (any provider — Anthropic, OpenAI, Google, Groq, OpenRouter, xAI, DeepSeek, Moonshot, or a custom OpenAI-compatible endpoint) or the built-in **Groq** path. Engine toggle in Settings; fallback is automatic when the chosen engine is unreachable.
- **Voice in two modes**:
  - **HOME panel** — *push-to-talk*. Hold the mic button while you speak. Mic is otherwise silent.
  - **VOICE panel** — *always-listening avatar mode*. A pulsing orb in the centre reacts to states (idle / heard wake word / transcribing / thinking / speaking). Wake word: **"Jarvis"**. Both server-side Vosk and browser-side `SpeechRecognition` listen in parallel — whichever fires first wins. Barge-in works: speak over the bot to interrupt it.
- **Spotify**: browse + play your playlists, control playback, AI DJ that picks tracks from a mood description, and a LIBRARY tab showing Liked Songs + Recently Played (works even when Spotify's dev-mode policy locks the playlist-tracks endpoint).
- **Google Tasks + Calendar**: bi-directional sync. Adding a task in the UI immediately writes to Google; deleting a task removes it from the right Google list. Calendar events created in the UI hit your real Google Calendar.
- **Tools via Composio**: connect Composio's MCP catalogue (GitHub, Gmail, Slack, Notion, Linear, Jira, Drive, etc.) so the LLM can actually act on those tools.
- **Focus timer**, **task lists**, **commitments**, **calendar grid**, **alarms**, **automations** (configurable webhooks the AI can trigger with `[CMD:AUTOMATE:name]`).

## Quick start

```bash
# Clone
git clone https://github.com/<you>/RaspberryPi-Home-Assistant.git
cd RaspberryPi-Home-Assistant

# Configure
cp .env.example .env
$EDITOR .env           # fill in GROQ_API_KEY, SPOTIFY_*, GOOGLE_*

# Build
cd piassistant
mvn package            # produces target/sentient-assistant-1.0-SNAPSHOT.jar

# Run
java -jar target/sentient-assistant-1.0-SNAPSHOT.jar
```

Then open **`http://localhost:7070`**. The Javalin server binds `0.0.0.0:7070` by default, so any device on the LAN can reach it at `http://<master-ip>:7070`.

### Optional: install OpenClaw for multi-provider LLM support

```bash
curl -fsSL https://openclaw.ai/install.sh | bash
openclaw gateway start
```

In Settings → OPENCLAW pick a provider, paste your API key, hit **SAVE & RESTART GATEWAY**. The badge flips to `ONLINE`. Toggle the **Chat Engine** dropdown to "OpenClaw (local)".

The full setup guide — including **gateway auth tokens**, **connecting to a remote OpenClaw on a VPS**, **Tailscale Funnel** for a public URL, and the **shared-password login** — lives in [`SETUP_OPENCLAW.md`](SETUP_OPENCLAW.md). Read it before exposing the app outside your LAN.

### Remote access summary

| Mode | How |
|---|---|
| LAN | Open `http://<master-ip>:7070` from any device on the same network. |
| Tailnet (private) | Install Tailscale on every device. Open `http://<master-tailscale-name>:7070`. |
| Public internet | Enable Tailscale Funnel from `Settings → REMOTE ACCESS`. Set a shared password first under `Settings → DEVICE LOGIN`. |
| Remote OpenClaw | Run OpenClaw on a VPS/another device. In `Settings → OPENCLAW`, toggle **Use Remote Gateway** and paste the URL + auth token. |

## Configuration

### `.env` (required keys)

| Key | Purpose |
|---|---|
| `GROQ_API_KEY` | Built-in Groq path. Used for chat if OpenClaw isn't configured. |
| `OPENAI_API_KEY` | Vision/transcription fallback (optional). |
| `GEMINI_API_KEY` | Camera vision analysis (optional). |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | Spotify OAuth. Create app at `developer.spotify.com/dashboard`; redirect URI `http://localhost:7070/api/spotify/callback`. |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Tasks + Calendar OAuth. Google Cloud Console → enable Tasks + Calendar APIs → redirect URI `http://localhost:7070/api/tasks/google/callback`. |
| `VOSK_MODEL_PATH` | Path to Vosk model directory (default: `vosk-model-small-en-us-0.15`). Used for the server-side wake-word listener. |

### In-app settings

Click **SETTINGS** in the sidebar to configure:

- **Chat Engine**: Groq vs OpenClaw
- **OPENCLAW**: provider, API key, model, gateway auth token, restart button, live status badge
- **SKILLS · COMPOSIO**: paste consumer key, toggle which toolkits to expose
- **MASTER SERVER**: if you're a remote client, point at the master device's IP:port
- **AUDIO**: per-browser mic + speaker device pickers
- **INTEGRATIONS**: Connect Google Tasks, Connect Google Calendar, Connect Spotify

All settings persist in `localStorage` (browser-side) or write to `~/.config/openclaw/openclaw.json5` / `user_profile.json` (server-side).

## Operating modes

| Mode | Where | Mic behaviour |
|---|---|---|
| **Silent** | Most tabs | Mic is off entirely. Server Vosk listener is paused. |
| **Push-to-talk** | HOME tab | Mic active only while you hold the 🎤 button. |
| **Wake-word avatar** | VOICE tab + wake mode ON | Server Vosk + browser SR both listen for "Jarvis". On detection, transcription starts. Barge-in: speak during TTS to interrupt and re-transcribe. |

The server-side Vosk listener defaults to *paused* on boot. It only resumes when the browser explicitly turns on wake mode in the VOICE tab.

## Project layout

```
piassistant/
├─ src/main/java/com/sentient/
│  ├─ App.java                  ── entry point (boots WebServer)
│  ├─ Launcher.java             ── trivial wrapper for maven-shade
│  ├─ WebServer.java            ── Javalin server: WS + REST + chat orchestration
│  ├─ service/
│  │  ├─ GroqService.java       ── built-in LLM (Groq, dual-model routing)
│  │  ├─ OpenClawService.java   ── local OpenClaw gateway client
│  │  ├─ OpenClawConfigManager.java ── reads/writes ~/.config/openclaw/openclaw.json5
│  │  ├─ SpotifyService.java
│  │  ├─ GoogleTasksService.java
│  │  ├─ GoogleCalendarService.java
│  │  ├─ AutomationService.java ── webhook-based automations
│  │  ├─ Listener.java          ── Vosk wake-word + transcription
│  │  ├─ TextToSpeech.java      ── Piper / Chorus ONNX TTS
│  │  └─ CameraService.java
│  └─ util/
│     ├─ ProfileManager.java    ── user_profile.json singleton
│     └─ EnvLoader.java
├─ src/main/resources/web/
│  ├─ index.html                ── all panels in one document
│  ├─ app.js                    ── ~3000 LOC, no framework
│  └─ styles.css                ── dark monospace theme
├─ pom.xml                      ── Maven build (shade plugin → fat jar)

ARCHITECTURE.md                 ── deeper-dive architecture notes + known issues
reference.md                    ── Claude's working notes for this codebase
README.md                       ── this file
```

## Integrations: how each one is wired

- **OpenClaw**: HTTP client → `http://127.0.0.1:18789/v1/chat/completions` (OpenAI-compatible). Config written to `~/.config/openclaw/openclaw.json5`. Gateway restart shells out to `openclaw gateway restart`.
- **Composio**: registered as an MCP server entry inside `openclaw.json5` at `https://connect.composio.dev/mcp` with `x-consumer-api-key` header. OpenClaw discovers tools at startup.
- **Spotify**: `spotify-web-api-java` SDK for most calls; raw HTTP fallback for endpoints that the SDK doesn't parse cleanly. The `/api/spotify/status` endpoint probes `/v1/me` so the UI knows when the token is dead — not just a cached flag.
- **Google Tasks**: bi-directional sync. Local CRUD auto-pushes; pull mirrors every Google task list (not just one). Tasks store their Google ID + list ID so deletes target the right item.
- **Google Calendar**: shares the Google OAuth token file with Tasks. Re-authentication of Tasks auto-refreshes Calendar's tokens.

## Known limitations

- **Spotify dev-mode 403s**: Spotify locks `/v1/playlists/{id}/tracks`, recommendations, audio features, and a few others for apps without extended quota. The UI shows a yellow banner explaining this when it hits. The LIBRARY tab (Liked Songs + Recently Played) keeps working regardless. To unblock playlist tracks, submit your app at `developer.spotify.com/dashboard` for extended quota review.
- **Wake-word detection is best-effort**: Vosk's small EN model is the default; it accepts `jarvis`, `jervis`, `jarvi`, `harvis`, `charvis`. The browser-side `SpeechRecognition` is unreliable in Safari/Firefox — those browsers rely on the server-side Vosk listener (master device only).
- **Browser `SpeechRecognition` can't choose a mic device**: the mic-device dropdown in Settings claims the chosen device via a brief `getUserMedia` call right before recognition starts; most browsers then use that device. There's no formal API.
- **One mic, no multi-tenant**: if two clients connect and both activate wake mode, the master device's mic is shared. Barge-in is per-browser via `AnalyserNode`.

## Development

```bash
mvn package                  # rebuild fat jar
mvn -DskipTests compile      # quick compile-only verification
node --check src/main/resources/web/app.js   # JS syntax check
```

Frontend hot-reload isn't wired — the static files are bundled into the jar at build time, so an `app.js` change requires a `mvn package` to ship. (Running with `mvn javafx:run` instead serves from `src/main/resources/web/` directly during development.)

## Further reading

- **`RUNNING.md`** — end-to-end setup guide: prereqs, build, first launch, OAuth wiring, Tailscale, the macOS helper, troubleshooting.
- **`Phase3.md`** — cross-device control design + per-track test walkthrough (`VIEW_DEVICE`, WebRTC mirror, remote actions, consent, native helper).
- **`SETUP_OPENCLAW.md`** — OpenClaw provider config, remote-gateway mode, Composio MCP wiring, shared-password auth.
- **`ARCHITECTURE.md`** — module map, WebSocket message contract, REST surface, continuity issues.
- **`reference.md`** — short, no-nonsense reference used by Claude when working in this repo.

## License

See repo. Personal project — no warranty.
