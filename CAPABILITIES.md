# Sentient Assistant — Capabilities Reference

The **Sentient Assistant** is a voice-first personal AI assistant that runs as a persistent service on always-on hardware (Raspberry Pi, Mac, or Linux). It is accessible from any browser on the LAN or internet, and is designed for minimal latency, multi-device reach, and offline-fallback resilience.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────┐
│  MASTER DEVICE (Pi / Mac / Linux)                       │
│                                                          │
│  Java App (Javalin on :7070)                            │
│  ├─ WebServer         REST + WebSocket orchestrator     │
│  ├─ OpenClawService   Primary LLM brain                 │
│  ├─ GroqService       Fallback LLM brain                │
│  ├─ Listener          Vosk wake-word + transcription    │
│  ├─ TextToSpeech      Piper TTS synthesis               │
│  ├─ SpotifyService    OAuth + playback control          │
│  ├─ GoogleTasksService   Tasks sync                     │
│  ├─ GoogleCalendarService  Calendar sync                │
│  ├─ AutomationService    Webhook dispatcher             │
│  ├─ TailscaleService     Funnel for public URL          │
│  ├─ AuthService          Shared password + tokens       │
│  ├─ CredentialVault      AES-256-GCM encrypted secrets  │
│  ├─ ProfileManager       user_profile.json              │
│  └─ CameraService        OpenCV for vision              │
│                                                          │
│  Persistent State Files:                                │
│  • user_profile.json      tasks, habits, settings       │
│  • ~/.sentient_auth.json  password hash + device tokens │
│  • ~/.sentient_vault.json encrypted credential store    │
│  • ~/.sentient_spotify_token                            │
│  • ~/.sentient_google_tasks_token                       │
│  • ~/.sentient_openclaw_connection.json                 │
│  • ~/.config/openclaw/openclaw.json5                    │
└─────────────────────────────────────────────────────────┘
                          │
         ┌────────────────┼────────────────┐
    ┌─────────┐      ┌─────────┐     ┌─────────┐
    │ Browser │      │ Browser │     │ Browser │
    │ (Phone) │      │(Laptop) │     │(Tablet) │
    └─────────┘      └─────────┘     └─────────┘
```

---

## AI Brain

### Dual-Engine Design

**Groq (built-in fallback)**
- Three-tier routing: Router model (llama-3.1-8b-instant) classifies each prompt as CHAT or THINK
- CHAT path: llama-3.3-70b-versatile — conversational replies, command emission
- THINK path: qwen/qwen3-32b — math, logic, reasoning-heavy queries
- VISION path: meta-llama/llama-4-scout-17b-16e-instruct — image + text analysis
- 20-message rolling conversation history per session
- PDF, text, and image attachment parsing

**OpenClaw (local gateway, primary)**
- Any provider via single gateway: Anthropic, OpenAI, Google, Groq, OpenRouter, xAI, DeepSeek, custom endpoints
- MCP tool integrations via Composio: GitHub, Gmail, Slack, Notion, Jira, Linear, and others
- Separate conversation history from Groq
- Runtime provider and model switch without restart
- Reads/writes `~/.config/openclaw/openclaw.json5`; shells out to `openclaw` CLI for status and restarts

---

## Voice I/O

**Wake-word detection (Vosk, server-side)**
- Always-on background thread listening for "Jarvis" and phonetic variants (jarvi, jervis, harvis, charvis)
- Auto-sends after 2+ seconds of silence post-wake
- Gated by `paused` flag; mutes itself during TTS playback to prevent feedback loops

**Text-to-speech (Piper ONNX)**
- Real-time synthesis, word-by-word streaming to connected browsers
- Chorus + Voice objects cached after first load (~2–3s); subsequent responses instantaneous
- Strips markdown before synthesis

**Browser mic modes**
- Push-to-talk (HOME tab): hold to speak
- Always-on orb (VOICE tab): continuous listening with animated avatar
- Barge-in supported: speaking during TTS interrupts playback

---

## Tasks & Calendar

**Google Tasks**
- Bi-directional sync: local edits pushed to Google; Google changes pulled on load
- Multi-list support with local profile tracking of Google IDs
- Create, complete, and delete tasks; due dates supported
- Token stored at `~/.sentient_google_tasks_token`; auto-refreshed on 401

**Google Calendar**
- List events in 30-day horizon from primary calendar
- Create and delete events
- Shared OAuth token file with Tasks
- Re-authentication prompt if refresh token is invalid

**Local profile state**
- Tasks, commitments, alarms, and events persisted in `user_profile.json`
- Auto-saved on every mutation via ProfileManager singleton

---

## Music Control

**Spotify**
- Full OAuth flow (auth code + refresh token), token stored at `~/.sentient_spotify_token`
- Playback control: play, pause, skip, seek, volume, shuffle, repeat
- Queue management and playlist CRUD
- Library browsing: Liked Songs, Recently Played (fallback when playlist endpoints are locked)
- AI DJ: LLM generates mood-based search queries (e.g., "90s hip-hop with summer vibes")
- In-browser Spotify Web Playback SDK integration

---

## Automation & Webhooks

- Environment-configured webhook URLs (`AUTOMATION_WEBHOOK_*` in `.env`) + runtime-registered webhooks
- AI emits `[CMD:AUTOMATE:name]` → server HTTP POSTs to matching URL
- Optional bearer token via `AUTOMATION_API_KEY`
- Enables integration with smart home devices, IFTTT, custom services, etc.

---

## Security & Auth

**Device authentication**
- Shared password hashed with SHA-256 + random salt
- Per-device opaque tokens (~256-bit), stored in browser `localStorage`
- Constant-time comparison to prevent timing attacks
- Token persistence across sessions in `~/.sentient_auth.json`
- Auth-free mode available for closed LAN deployments

**Credential vault**
- AES-256-GCM encryption with per-machine key at `~/.sentient_vault_key`
- Two secret types:
  - **Env vars** — flat name/value pairs for use in automations and tools
  - **Service logins** — URL + username + password + notes
- Passwords masked in all API responses; raw value delivered only via one-shot WebSocket `autofill_request`
- Synced in real time to all logged-in browsers; single source of truth on master

**Network access tiers**
- LAN: `http://<master-ip>:7070`
- Tailnet (private): `http://<tailscale-hostname>:7070`
- Public internet: Tailscale Funnel HTTPS URL (requires password login)

---

## Multi-Device Support

- Every connected browser registers itself in a device registry (platform, capabilities)
- WebSocket-based connection with auto-reconnect and keep-alive pings
- Screen snapshot capture via `getDisplayMedia()` → one-frame JPEG relayed to other devices
- `[CMD:VIEW_DEVICE:name]` planned for full vision + remote control workflows

---

## Command System

The AI emits structured tags that the server intercepts and executes:

| Command | Effect |
|---|---|
| `[CMD:ADD_TASK:title\|desc\|date]` | Creates task in active list |
| `[CMD:SET_TIMER:45]` | Starts focus timer |
| `[CMD:SWITCH_HOME]` / `[CMD:SWITCH_TASKS]` | Navigates UI panel |
| `[CMD:AUTOMATE:webhook_name]` | Fires registered webhook |
| `[CMD:CONTINUE_CONVERSATION]` | Auto-restarts microphone |
| `[CMD:USE_CREDENTIAL:service]` | Triggers autofill overlay |
| `[CMD:VIEW_DEVICE:name]` | Requests screen snapshot from device |

---

## Frontend (Web UI)

Single-page app in vanilla JavaScript (~3000 LOC, no framework). Seven panels:

| Panel | Contents |
|---|---|
| HOME | Chat area, file attachment (image/PDF/text), push-to-talk |
| VOICE | Always-on mic with animated orb avatar, voice transcript |
| STUDY | Pomodoro focus timer, configurable intervals |
| TASKS | Multi-list task manager with Google Tasks sync |
| CALENDAR | Month grid with event dots, create/delete events |
| SLEEP | Sleep mode clock, meditation timer |
| SPOTIFY | Playlist browser, playback controls, AI DJ |
| SETTINGS | OpenClaw config, Skills, Master URL, audio, integrations |

**Notable UI features**
- Panel partition manager: smart 2D grid (1→full, 2→columns, 3→L-shape, 4→2×2, 5→3+2)
- Word-by-word streaming chat with markdown rendering (marked library)
- Auth token interceptor on all `fetch()` calls; redirects to `/login` on 401
- All preferences persisted in `localStorage`

---

## Build & Deployment

**Requirements**: Java 17, Maven

```bash
mvn package
java -jar target/sentient-assistant-1.0-SNAPSHOT.jar
# Opens http://localhost:7070 automatically
```

**Configuration layers** (in priority order):
1. `.env` file — API keys, model paths, webhook URLs
2. `~/.sentient_*` JSON files — auth, vault, OAuth tokens, OpenClaw connection
3. `user_profile.json` — tasks, habits, preferences, settings
4. `~/.config/openclaw/openclaw.json5` — provider config, Composio skill keys

**First-time setup**:
1. Copy `.env.example` → `.env` and fill in API keys
2. Run the app and open `http://localhost:7070`
3. SETTINGS → Integrations → Connect Spotify, Google Tasks, Google Calendar
4. (Optional) SETTINGS → OpenClaw → add provider, set model, enable Composio skills

---

## Known Issues

1. SETTINGS → Google Calendar link uses a wrong endpoint path
2. `models.json` is orphaned — no code reads it currently
3. JavaFX `DashboardView` is dead code; fat JAR includes unnecessary dependencies
4. `user_profile.json` exists in both repo root and `piassistant/` directory (duplicate)
5. Auto-browser-open is incomplete on Windows
6. Remote Master URL has no UI field yet; non-LAN clients must configure manually

---

## Planned / In Progress

**Phase 2 (Raspberry Pi portability)**
- Piper TTS piping strategy for Pi 3B+ performance
- Vosk + Picovoice wake-word threading
- Offline fallback via Ollama
- USB camera vision (on-demand, not continuous)

**Phase 3 (OpenClaw + remote control)**
- Full OpenClaw config UI with provider picker and Skills menu
- Master Server URL setting for remote clients
- Native OS-level remote control (Swift / C# / xdotool)
- WebRTC live screen mirroring (lower latency than snapshots)
- Voice command wiring for auto-snapshot: "what's on my Mac?"
