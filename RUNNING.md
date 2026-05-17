# Running Sentient Assistant

The full, end-to-end guide: zero-to-working master, plus client browsers, optional
remote-access, optional OpenClaw + Composio, and the new Phase 3 features
(VIEW_DEVICE, live mirror, native macOS helper).

If you only want the 30-second story, read [`README.md`](README.md). For
provider-level OpenClaw / Composio detail, read [`SETUP_OPENCLAW.md`](SETUP_OPENCLAW.md).
For the master/client architecture, read [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## 1. What you'll have when this is done

- A **master process** running on one always-on machine (Raspberry Pi, Mac mini,
  Linux server, your laptop) that:
  - Serves the web UI at `http://<master>:7070`
  - Holds your `.env`, `user_profile.json`, OpenClaw config, OAuth tokens
  - Owns the Vosk wake-word listener + Piper TTS
- **Browser clients** (any device, any OS) that:
  - Connect to the master over WebSocket
  - Render the UI, capture mic input, share their screen on request
- (Optional) **OpenClaw gateway** beside the master, fanning chats out to
  Anthropic / OpenAI / Google / Groq / OpenRouter / xAI / DeepSeek / Moonshot
  or your own custom endpoint.
- (Optional) **Composio MCP** for tools like GitHub, Gmail, Slack, Notion, Linear.
- (Optional, macOS) A **native helper** on each Mac that the AI can drive to
  type text, click, launch apps, send key combos.
- (Optional) **Tailscale** so clients off the LAN can still reach the master,
  with **Tailscale Funnel** if you want a public URL.

---

## 2. Prerequisites

### Master device

| Tool | Why | Install |
|---|---|---|
| **Java 17+** | Runs the server | `sudo apt install openjdk-21-jdk` / `brew install openjdk@21` |
| **Maven 3.6+** | Builds the fat jar | `sudo apt install maven` / `brew install maven` |
| **Vosk model** *(optional but recommended)* | Server-side wake-word listener | Download `vosk-model-small-en-us-0.15` from <https://alphacephei.com/vosk/models> and unzip somewhere stable |
| **Piper voice** | Already vendored — no install | — |
| **Tailscale CLI** *(optional)* | LAN/internet remote-access | <https://tailscale.com/download> |
| **OpenClaw CLI** *(optional)* | Multi-provider LLM gateway | `curl -fsSL https://openclaw.ai/install.sh \| bash` |

### Client devices

Just a recent Chromium-based browser (Chrome, Edge, Brave, Arc). Safari and
Firefox work but `SpeechRecognition` is unreliable there — those tabs lean on
the server's Vosk listener instead.

### Optional — Mac that will be controlled by the AI

| Tool | Why |
|---|---|
| **Xcode CLT** (`xcode-select --install`) | Builds the native helper |
| **Swift 5.7+** (bundled with Xcode CLT) | Same |

---

## 3. Get the code

```bash
git clone https://github.com/Aryaman-R/SentientAssistant.git
cd SentientAssistant
```

---

## 4. Configure `.env`

There are two paths. **Either works** — pick one.

### Path A — `./setup.sh` (interactive)

```bash
chmod +x setup.sh
./setup.sh
```

This will:
1. Verify Java + Maven are installed.
2. Copy `.env.example` → `.env` if needed.
3. Prompt for each API key (skip with Enter; you can add later).
4. Remind you which OAuth redirect URIs to whitelist.
5. Run `mvn -DskipTests package` to produce the fat jar.

### Path B — manual

```bash
cp .env.example .env
$EDITOR .env
```

Fill what applies. Every key is independently optional — features just degrade
when their key is missing.

| Key | What breaks without it |
|---|---|
| `GROQ_API_KEY` | Built-in Groq chat path. If you're using OpenClaw exclusively, leave blank. |
| `OPENAI_API_KEY` | Optional vision / Whisper transcription path. |
| `GEMINI_API_KEY` | Camera vision analysis. |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | Spotify panel. Redirect URI: `http://127.0.0.1:7070/api/spotify/callback`. |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Tasks + Calendar panels. Redirect URI: `http://127.0.0.1:7070/api/tasks/google/callback`. Enable **Google Tasks API** *and* **Google Calendar API** on the same OAuth client. |
| `VOSK_MODEL_PATH` | Server-side wake-word. The browser still does its own SR. |
| `AUTOMATION_WEBHOOK_<NAME>=<url>` | `[CMD:AUTOMATE:name]` triggers. Add one per webhook. |
| `AUTOMATION_API_KEY` | Adds `Authorization: Bearer …` to every webhook call. |

---

## 5. Build & run the master

```bash
cd piassistant
mvn -DskipTests package    # → target/sentient-assistant-1.0-SNAPSHOT.jar
java -jar target/sentient-assistant-1.0-SNAPSHOT.jar
```

You should see:

```
[WebServer] Running at http://localhost:7070
[WebServer] Listener started.
```

The Javalin server binds `0.0.0.0:7070`, so any device on the LAN can reach
`http://<master-ip>:7070` immediately. The master also tries to open your
default browser to `http://localhost:7070`.

---

## 6. First-launch checklist (in the web UI)

1. **Set a device login password** — `Settings → DEVICE LOGIN → SET PASSWORD`.
   Until you do this the app is open to anyone on the network. Once set, every
   browser tab has to log in once; the token is stored in `localStorage`.
2. **Name this browser** — `Settings → DEVICES → This device's name` →
   `SAVE NAME`. The name shows up in every other tab's device list and is the
   handle the AI uses for `[CMD:VIEW_DEVICE:Name]`, `[CMD:REMOTE_OPEN:Name|…]`.
3. **Pick a chat engine** — `Settings → Chat Engine` dropdown:
   - `Groq` — uses `GROQ_API_KEY` directly. Cheapest, fastest, no extra setup.
   - `OpenClaw (local)` — needs OpenClaw running and a provider configured.
     See [`SETUP_OPENCLAW.md`](SETUP_OPENCLAW.md) for the full walkthrough.
4. **(Optional) Connect integrations** — Spotify / Google Tasks / Google Calendar
   buttons. Each one pops an OAuth window against the master host. Polling kicks
   in automatically; the panel switches to its normal view once auth lands.

You're live.

---

## 7. Optional setup paths

### 7a. OpenClaw + multi-provider LLM

Short version:

```bash
curl -fsSL https://openclaw.ai/install.sh | bash
openclaw gateway start
openclaw gateway status        # confirm port + provider
```

Then in `Settings → OPENCLAW`:
- Pick a provider (Anthropic / OpenAI / Google / Groq / OpenRouter / …).
- Paste the API key.
- (Optional) Set a gateway auth token to require a bearer on every gateway call.
- Click **SAVE & RESTART GATEWAY** — the badge flips to `ONLINE`.

Long version (custom providers, remote-OpenClaw, MCP wiring):
[`SETUP_OPENCLAW.md`](SETUP_OPENCLAW.md).

### 7b. Composio (tools the AI can call)

In `Settings → SKILLS · COMPOSIO`:
1. Paste your Composio **consumer key** (`https://app.composio.dev/`).
2. Tick the toolkits you want exposed (GitHub, Gmail, Slack, Notion, …).
3. Click **SAVE** — the app writes the MCP entry to `~/.config/openclaw/openclaw.json5`
   and restarts the gateway. New tools show up in the AI's call surface.

### 7c. Tailscale + Funnel (private and public remote access)

```bash
# On the master
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up

# On any client
sudo tailscale up
# Now `http://<master-tailscale-name>:7070` works from anywhere on the tailnet.
```

For a **public** URL (so a phone off the tailnet can still hit it):

1. Set a device password first — `Settings → DEVICE LOGIN`.
2. `Settings → REMOTE ACCESS → ENABLE FUNNEL`. The app shells out to
   `tailscale funnel`. You'll see a public `https://<machine>.<tailnet>.ts.net`.

### 7d. Pinning the master server from a client

Open the UI on a *non*-master device. In `Settings → MASTER SERVER URL`, paste
your master's address (e.g. `100.64.1.5:7070` or `mac-mini.tailnet.ts.net`).
Reload. Every WS + REST call now goes to that host. This is also what makes
the Spotify / Google / Calendar OAuth windows open against the correct host
(Phase 3 / Track 6 fix — they all run through the `api(...)` wrapper now).

### 7e. Credential vault (master-side env vars + service logins)

`Settings → CREDENTIAL VAULT` lets you:
- Store env-var values (the AI sees names only, never values — they're injected
  by the master process at use time).
- Store full service logins (URL + username + password). The AI can ask the
  master to fire `[CMD:USE_CREDENTIAL:name]`; the master sends an
  `autofill_request` directly to the browser. The plaintext password never
  passes through the model.

Every mutation broadcasts `vault_updated` so every connected device refetches.

---

## 8. The Phase 3 features (cross-device control)

The whole feature set is documented in detail in
[`Phase3.md`](Phase3.md#how-to-test--see-each-feature). One-line recipes:

| Feature | Try it |
|---|---|
| **`VIEW_DEVICE`** — AI screenshots a device | Open the UI on two devices, name them. Say *"What's on my Laptop?"* — the Laptop browser pops a screen-share prompt; the frame goes straight into vision. |
| **WebRTC live mirror** | Settings → DEVICES → `LIVE` next to a device. Receiver consents to live-share; sender gets a viewer window. |
| **`REMOTE_OPEN`** | *"Open anthropic.com on my Phone."* The Phone consents (or has consented), the URL opens in a new tab. |
| **Per-action consent** | Every novel `(action, sender)` pair pops a modal once. Tick "Always allow" → silent thereafter. Revoke at `Settings → PERMISSIONS`. |
| **macOS helper** | See §9 below. Then say *"Launch the bundle id `com.apple.Safari` on My Mac."* |

---

## 9. Native macOS helper (optional)

The browser sandbox can't inject OS keystrokes or move the mouse — for that
each Mac you want the AI to drive needs a tiny native bridge.

### 9a. Build

```bash
cd native/macos
swift build -c release
sudo cp .build/release/SentientHelper /usr/local/bin/sentient-helper
```

### 9b. Grant Accessibility permission

1. Run `sentient-helper --help` once to register the binary with the OS.
2. **System Settings → Privacy & Security → Accessibility → +** →
   pick `/usr/local/bin/sentient-helper`. Toggle on.
3. Without this, keystrokes and clicks silently no-op; the helper prints a
   warning on boot when permission is missing.

### 9c. Get the device token

Log in to the web UI on the Mac you're setting up. DevTools → Application →
Local Storage → copy `sentient_token`. (Or skip auth altogether if the master
has no password set — but you should set one.)

### 9d. Run

```bash
SENTIENT_TOKEN=<paste-the-token> \
  sentient-helper --host <master>:7070 --name "My Mac"
```

The master logs `[Helper] connected: <sessionId>`. The Mac now appears as a
control target for `TYPE_TEXT`, `CLICK_AT`, `LAUNCH_APP`, `KEY_COMBO`, `OPEN_URL`.

### 9e. Run at login

Drop `~/Library/LaunchAgents/com.sentient.helper.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>Label</key><string>com.sentient.helper</string>
    <key>ProgramArguments</key><array>
        <string>/usr/local/bin/sentient-helper</string>
        <string>--host</string><string>YOUR-MASTER:7070</string>
        <string>--name</string><string>My Mac</string>
    </array>
    <key>EnvironmentVariables</key><dict>
        <key>SENTIENT_TOKEN</key><string>YOUR-TOKEN</string>
    </dict>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>/tmp/sentient-helper.log</string>
    <key>StandardErrorPath</key><string>/tmp/sentient-helper.err</string>
</dict></plist>
```

```bash
launchctl load -w ~/Library/LaunchAgents/com.sentient.helper.plist
```

Full helper protocol + flags in [`native/macos/README.md`](native/macos/README.md).

---

## 10. Daily commands

```bash
# Build (fat jar lives at piassistant/target/sentient-assistant-1.0-SNAPSHOT.jar)
cd piassistant && mvn -DskipTests package

# Run (foreground)
java -jar target/sentient-assistant-1.0-SNAPSHOT.jar

# Run (background — recommended for an always-on master)
nohup java -jar target/sentient-assistant-1.0-SNAPSHOT.jar > sentient.log 2>&1 &

# Sanity-check the frontend before rebuilding
node --check src/main/resources/web/app.js

# Stop the background instance
pkill -f sentient-assistant-1.0-SNAPSHOT.jar
```

### Running as a systemd service (Linux master)

```ini
# /etc/systemd/system/sentient.service
[Unit]
Description=Sentient Assistant
After=network.target

[Service]
User=pi
WorkingDirectory=/home/pi/SentientAssistant/piassistant
ExecStart=/usr/bin/java -jar target/sentient-assistant-1.0-SNAPSHOT.jar
Restart=on-failure
EnvironmentFile=/home/pi/SentientAssistant/.env

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now sentient.service
journalctl -u sentient.service -f
```

---

## 11. Troubleshooting

| Symptom | Probable cause / fix |
|---|---|
| `ctx.json(...)` 500s on every REST call | The Gson `jsonMapper(...)` block in `WebServer.java` was removed. It's load-bearing — restore it. |
| Spotify panel shows "needs extended quota" yellow banner | Spotify dev-mode locks `/playlists/{id}/tracks` and a few others. Either request extended quota in their dashboard or stick to the LIBRARY tab. |
| Login page bounces in a loop | Auth token is stale. Clear `sentient_token` from localStorage and re-login. |
| OAuth window opens against `localhost` instead of the master IP | `Settings → MASTER SERVER URL` isn't set on the client. Set it to the master's address and reload. |
| Wake word "Jarvis" doesn't trigger | Set `VOSK_MODEL_PATH`. The browser SR will still work in Chromium, but Vosk is more reliable. |
| Calendar / Tasks OAuth fails with `redirect_uri_mismatch` | Add **`http://127.0.0.1:7070/api/tasks/google/callback`** to the OAuth client's Authorized redirect URIs in Google Cloud Console. Tasks and Calendar share this single URI. |
| `[CMD:VIEW_DEVICE:Name]` returns "is not connected" | The named device's tab isn't open / registered. Check `Settings → DEVICES` — it should list the device with the exact name. |
| Live mirror viewer is black / never shows frames | (a) Pop-ups blocked — allow them. (b) Tailnet not connecting peers — for now run on the same tailnet; STUN/TURN isn't configured. |
| macOS helper does nothing when AI says "type X" | Accessibility permission missing. Re-grant it under System Settings; quit and re-run the helper afterwards. |
| Helper exits with `WS error: The operation couldn't be completed` | Wrong token or wrong host. Re-copy `sentient_token` from the master's web UI. |
| `vault_updated` doesn't sync across devices | The receiving tab's WS reconnect dropped. Check the connection indicator in the top-right corner. |

---

## 12. Where to go next

- [`Phase3.md`](Phase3.md) — full Phase 3 design and test walkthrough.
- [`SETUP_OPENCLAW.md`](SETUP_OPENCLAW.md) — provider configuration, remote OpenClaw, MCP wiring.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — module map, WS contract, REST surface.
- [`CAPABILITIES.md`](CAPABILITIES.md) — feature surface area (every command tag the AI knows).
- [`DEVICE_CONTROL.md`](DEVICE_CONTROL.md) — original cross-device control design notes.
- [`native/macos/README.md`](native/macos/README.md) — helper internals + LaunchAgent.
