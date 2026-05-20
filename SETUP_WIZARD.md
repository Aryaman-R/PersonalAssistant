# Setup Wizard — design + implementation plan

This document is the single source of truth for how a brand-new, non-technical
user gets from a fresh download to a working Sentient Assistant **without ever
touching a terminal**.

The goal is to feel like a polished, out-of-the-box product:

- One command (or one downloaded file) to get the server running.
- A browser opens automatically into a guided UI wizard.
- The wizard installs OpenClaw, downloads the wake-word model, optionally wires
  Tailscale, installs the native helper, and walks the user through every
  integration.
- Anyone — including someone who has never run `git clone` before — should be
  able to finish setup in 5–10 minutes.

This is a living plan. Anything we change while building should be reflected
here.

## 0. Decisions made (from clarification round)

1. **Entry point**: one-line install scripts (`install.sh` for macOS/Linux,
   `install.ps1` for Windows). They install Java/Maven if missing, clone the
   repo, build, launch, and open the browser. Native installers (.dmg/.exe) are
   left as a future follow-up.
2. **OpenClaw**: the Java server runs the install script itself when the user
   clicks "INSTALL OPENCLAW" in the wizard. Progress is streamed to the UI. No
   terminal involvement on the user side.
3. **Other terminal steps converted to UI**: Vosk wake-word model download,
   Tailscale install + login + Funnel toggle, `.env` API key entry, and the
   native macOS / Windows helper installer.
4. **Re-entry policy**: wizard auto-shows once on first launch. After
   completion, it's reachable from `Settings → SETUP WIZARD → RE-RUN`.
5. **Wake-word alternatives**: at the end of the wizard work, evaluate Picovoice
   Porcupine (small, accurate, but per-device license) and OpenWakeWord
   (Apache 2.0, ONNX, on-CPU) as alternatives to Vosk. Plan only for the moment;
   actual swap is a follow-up unless trivial.

## 1. User personas

| Persona | Constraints we design for |
|---|---|
| **Brand-new non-technical user** | Has never used a terminal, doesn't know what an API key is, doesn't know the difference between LAN and Tailscale. |
| **Power user** | Knows what they're doing, wants the manual flow available, doesn't want the wizard nagging them every launch. |
| **Headless Pi user** | No display on the server machine. Reaches the wizard from a laptop browser. |

The wizard must serve all three. The non-technical user gets the rich
hand-holding flow by default; the power user can skip every step; the Pi user
can complete the wizard from a different device.

## 2. Hosting methods presented to the user

Right at the top of the wizard, after the welcome screen, the user picks **how
they want to host**. Each option is a card with a short blurb and pros/cons.

| Option | Description | Best for |
|---|---|---|
| **Just my computer** | Master + browser on the same machine. Easiest. | Trying it out, single-user laptop install. |
| **One device on my home network** | Always-on master (Pi/Mac mini/PC), every device on my LAN connects. | Households, multi-room setups. |
| **My private network with Tailscale** | Same as LAN but reachable from anywhere via your Tailscale tailnet. Private. | Travelers, multi-location users. |
| **Public URL with Tailscale Funnel** | Permanent HTTPS URL anyone can reach. Requires shared-password login. | Sharing access from off-network without VPN setup. |

After the user picks, the wizard branches:

- **Just my computer**: skips network/remote-access steps entirely.
- **LAN**: shows the local IP so they know what URL to bookmark on other devices.
- **Tailnet**: walks the user through `tailscale up` (a UI button — see §5d).
- **Funnel**: forces them to set a password first, then enables Funnel, then
  shows the public URL.

The user can always change their mind from Settings later.

## 3. Wizard architecture

### 3.1 Frontend

A single modal overlay rendered by `app.js` over the existing UI. Lives at the
top of `index.html` (so it can cover the rest of the page) and is hidden until
either:

- `localStorage.sentient_wizardDone` is missing **and** the server reports
  `firstRun: true` (no `.env` set, no profile written), **or**
- The user clicks `Settings → SETUP WIZARD → RE-RUN`.

State machine: `welcome → host-mode → prereqs → engine-pick → openclaw-install
→ provider-key → password → env-keys → integrations → device-name → done`.

The wizard maintains its state in `localStorage.sentient_wizardState` so a
refresh doesn't lose progress.

Branching:
- `host-mode=just-me`  → skip tailscale, skip password (offered later anyway).
- `host-mode=lan`      → skip tailscale, recommend password.
- `host-mode=tailnet`  → tailscale install + up step inserted.
- `host-mode=funnel`   → tailscale + Funnel + force password.

Every step has:
- A clear single-line title.
- A short explanation in plain English (no jargon without a "what's this?" link).
- Either a direct action button, an input + save, or a "skip" link.
- A live status line (success / pending / error) that the user can act on.

### 3.2 Backend

New `SetupService` Java class. New REST endpoints under `/api/setup/*`:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/setup/state` | GET | Snapshot: is this a first launch? what's installed? which keys are set? which integrations connected? |
| `/api/setup/prereqs` | GET | Probes Java, Maven, OpenClaw, Vosk, Tailscale, helper. Returns version + status per tool. |
| `/api/setup/env` | GET / POST | Read/write `.env` entries (whitelisted keys only). |
| `/api/setup/install/openclaw` | POST | Runs the OpenClaw install script as a subprocess. Streams progress over the existing `/ws` as `setup_progress` messages. |
| `/api/setup/install/vosk` | POST | Downloads `vosk-model-small-en-us-0.15`, unzips into a known path, updates `VOSK_MODEL_PATH`. |
| `/api/setup/install/tailscale` | POST | Runs Tailscale install script. Streams. Returns URL to call `tailscale up`. |
| `/api/setup/install/helper` | POST | Builds and installs the native helper for the master's OS. (macOS: swift build; Windows: dotnet publish; Linux: no-op.) |
| `/api/setup/finish` | POST | Marks setup complete so we don't show the wizard again. |

All install endpoints must:
- Refuse if `auth.required` and the request is unauthenticated (existing gate).
- Spawn the subprocess as a child of the server, never via the shell directly
  with user-controlled args. (No `bash -c "$user_input"`.)
- Stream both stdout and stderr to a per-request id WS broadcast.
- Be idempotent: re-running them when already installed is a no-op success.

### 3.3 Auth bootstrap

The wizard needs to be reachable on the very first launch, when no password
exists. The existing auth gate already handles "no password set → no
gate". After the wizard sets a password, subsequent calls carry the token.

If the user pins a remote master (different device runs the server, this device
just opens the URL), the wizard still works because the existing `api(...)`
wrapper routes everything to the master host.

### 3.4 .env writing

`.env` lives at the repo root. New `EnvWriter` class reads, mutates one key at
a time (preserving comments and unknown keys), and writes the file atomically
(write to `.env.tmp`, rename). A whitelist keeps random keys out:

```
GROQ_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY,
SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET,
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
VOSK_MODEL_PATH, AUTOMATION_API_KEY
```

`AUTOMATION_WEBHOOK_*` names are accepted via a separate endpoint that takes
the suffix and the URL.

After a write, the running services don't re-read the .env automatically; the
wizard surfaces a "you'll need to restart the server for these to take effect"
banner with a "RESTART NOW" button that calls a new `/api/setup/restart`
endpoint (which does a clean shutdown and lets the launcher script re-spawn).

## 4. Step-by-step wizard flow (user view)

### Step 0 — Welcome
"Welcome to Sentient Assistant. We'll set everything up in about 5 minutes."
Two buttons: **GET STARTED** / **I'll do it myself (skip)**.

### Step 1 — How do you want to host this?
Four cards (see §2). The user picks one.

### Step 2 — Quick health check
The wizard probes:
- Java version (must be 17+).
- Maven (only required for rebuilds; we already shipped the jar).
- OS + arch (so the helper installer picks the right binary).
- Internet reachability.
Anything that's missing is flagged with a 1-click fix where possible.

### Step 3 — Pick a chat engine
Two cards: **Groq (easiest, single provider)** or **OpenClaw (choose any
provider, multi-LLM, MCP tools)**. After picking:

- Groq path → straight to step 5 (paste API key, link to console.groq.com).
- OpenClaw path → step 4.

### Step 4 — Install OpenClaw (if picked)
- "We'll install the OpenClaw gateway on this device. It runs in the background
  and lets the assistant talk to any LLM you choose. Click INSTALL to start."
- The server runs the install script. Progress streams into a live log box.
- On success, badge flips to ✓ INSTALLED.
- Then: provider picker (Anthropic/OpenAI/etc.), API key input, link to where
  to get each key, optional gateway auth token.
- Click **SAVE & START GATEWAY**. The wizard tests one round-trip; if it works,
  green check, otherwise human-readable error with a "retry" button.

### Step 5 — Set a device password
- For LAN: recommended.
- For Tailnet: recommended.
- For Funnel: **required** before proceeding.
- For Just-me: optional, can defer.
- Input + confirm. Min 6 chars. Server hashes + stores it; UI gets a new token.

### Step 6 — Optional API keys
- One row per key (Groq, OpenAI, Gemini, Spotify, Google).
- Each row has: label, "what's this for" tooltip, "where to get it" link,
  password-masked input, save button, status line.
- "Skip — I'll add later" is always available. Each integration's features will
  degrade gracefully if its key is missing (already true in the codebase).

### Step 7 — Connect integrations
- Tasks/Calendar (Google) — popup OAuth (existing flow).
- Spotify — popup OAuth.
- Composio — paste consumer key, pick toolkits.
- Each one shows ✓ when complete; "skip" is fine.

### Step 8 — Remote access (if not Just-me)
Branches on the chosen host mode:

- **LAN**: shows `http://<your-ip>:7070`, "Bookmark this on your phone/tablet."
- **Tailnet**: install/login Tailscale via UI buttons; shows
  `http://<machine>.tailnet.ts.net:7070`.
- **Funnel**: enables Funnel via existing endpoint; shows the `*.ts.net` URL.

### Step 9 — Voice (optional)
- "Want voice control? We'll download the wake-word model (~40 MB)."
- Button: **DOWNLOAD VOSK MODEL**. Server fetches + unzips, sets
  `VOSK_MODEL_PATH`. Status line.
- Skip = silent. Wake mode still works in Chromium via browser SpeechRecognition.

### Step 10 — Native helper (optional, OS-aware)
- macOS: "Install the helper so the AI can control this Mac" → server builds
  `swift build -c release` and copies to `/usr/local/bin/sentient-helper`.
  Then shows a one-time Accessibility-permission instruction with a button that
  jumps straight to System Settings → Accessibility (`x-apple.systempreferences:...`).
- Windows: builds the .NET helper, copies it, registers Task Scheduler entry.
- Linux: skipped (no helper exists yet).

### Step 11 — Name this device
- Input for "This device's name" (e.g. "My Laptop"). Saved in localStorage and
  broadcast via `register_device` WS message — exactly the existing flow.

### Step 12 — Done
- Summary: ✓ each thing that worked, ⚠ each thing that was skipped (with a
  one-click way to come back to it).
- **OPEN APP** closes the wizard and reveals the main UI.

## 5. Implementation order (and what each touch costs)

| # | Change | Files | Cost |
|---|---|---|---|
| 1 | New `SetupService.java` with all the heavy lifting (prereq probes, env writer, install runners). | `service/SetupService.java` (new) | M |
| 2 | New `/api/setup/*` REST endpoints in `WebServer.java`. | `WebServer.java` | S |
| 3 | New WS message type `setup_progress` and broadcaster. | `WebServer.java` | XS |
| 4 | Wizard modal HTML. | `index.html` | M |
| 5 | Wizard JS (state machine, step renderers, REST calls, WS subscribe). | `app.js` (or new `wizard.js` injected) | L |
| 6 | Wizard CSS. | `styles.css` | M |
| 7 | `install.sh` for macOS/Linux (curl-pipe-able). | `install.sh` (new top-level) | M |
| 8 | `install.ps1` for Windows. | `install.ps1` (new top-level) | M |
| 9 | Update README + RUNNING to lead with one-line install. | `README.md`, `RUNNING.md` | S |
| 10 | (Stretch) Evaluate Porcupine / OpenWakeWord swap notes. | doc only | XS |

## 6. Security review

- All install endpoints are gated by the auth middleware. On first launch (no
  password set) the gate is open, which is correct — the wizard runs.
- Arguments to subprocesses are passed via argv arrays, never `bash -c`.
- The OpenClaw install script (and Tailscale's) are external resources we
  download. We use `curl --proto '=https' --tlsv1.2 -fsSL` so MITM downgrade is
  blocked. We show the URL to the user before piping it to bash and offer a
  "show me the script first" link.
- `.env` writes go through a whitelist. A malicious browser request can't add
  arbitrary keys.
- The native helper install on macOS/Windows runs as the local user only —
  never `sudo` on its own. If sudo is needed (`/usr/local/bin` copy), we
  surface that to the user and ask them to run a short prompt themselves.
- The setup-state endpoint returns *whether* keys are present, never their
  values.

## 6.5 Wake-word alternatives (follow-up evaluation)

The wizard ships Vosk because it's already integrated. Two alternatives worth
considering for a future iteration:

### Picovoice Porcupine
- **Pros**: 97%+ accuracy with very low false-positive rates, ~50 KB models,
  instant custom wake words via web tool, ready React-Native / Java / Python
  SDKs, very low CPU.
- **Cons**: AGPL or per-seat commercial license. Free tier is for personal
  use and prototypes only — shipping it inside an open-source assistant the
  user runs themselves is fine for personal builds, but cannot be redistributed
  as a hosted service without a commercial agreement.
- **Verdict**: best fit if the project stays personal-use, since it's the
  best-of-class accuracy at the lowest cost. Custom wake words ("Sentient")
  would be trivial.

### OpenWakeWord
- **Pros**: Apache 2.0 license, ONNX models, no API key, runs on CPU
  (5–6× lower CPU on Pi than Vosk). Pre-trained models for common keywords
  like "hey jarvis" and "alexa". Pip-installable for Python; ONNX models can
  also be run via the `onnxruntime` Java package — which we already depend on
  for Piper TTS.
- **Cons**: Pre-trained custom keywords (like "Sentient") require training
  your own model with at least a few hours of data, or paying the maintainer.
  Default models are a bit less accurate than Porcupine in noisy conditions.
- **Verdict**: best fit for an open-source distribution. Since we already
  bundle `onnxruntime`, swapping the JNI Vosk bridge for an ONNX `MelSpec →
  embedding → keyword` pipeline is plausible. Estimated effort: ~2–3 days,
  most of it in audio buffering + the keyword classifier inference.

### Decision

Defer the swap. Vosk works, the licensing is unambiguous (Apache 2.0), and the
Setup Wizard reduces the friction of the existing setup (one-click model
download). Re-evaluate when (a) a user complains about wake-word false fires,
or (b) we add a "custom wake word" feature.

If we do swap, the wizard step `voice` doesn't need any change — it'd just
download a different model on click.

## 7. Out of scope (for this branch)

- Native installers (.dmg / .exe / .deb). The one-line script is good enough,
  and a real installer needs code signing + release infra.
- Auto-update.
- Wake-word swap from Vosk to Porcupine/OpenWakeWord. Doc the trade-offs;
  actual swap is a follow-up.
- Multi-user accounts. The single shared-password model stays.

## 8. Acceptance — how we know this is done

1. From a completely blank Ubuntu VM (no Java, no Maven, no clone), running
   the one-line install script gets to "the wizard is open in my browser" in
   under 5 minutes on a normal connection.
2. From the wizard, a non-technical user can:
   - Install OpenClaw (or pick Groq), paste one API key, set a password, and
     have a working chat.
   - Optionally connect Google / Spotify / Composio via OAuth popups.
   - Optionally enable Tailscale Funnel and get a public URL.
   - Optionally install voice + the native helper.
3. Re-running the wizard finds and respects existing config (doesn't
   double-install OpenClaw, doesn't re-prompt for keys already set).
4. After completion, the wizard does not appear on subsequent launches unless
   re-invoked from Settings.
5. Every commit credits only `AryamanR` (no Co-Authored-By footers).
