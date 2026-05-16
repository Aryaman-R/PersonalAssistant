# OpenClaw setup & remote-access guide

This is the operator's reference for getting **OpenClaw**, **Tailscale Funnel**, and
**device-login auth** wired up on a Sentient Assistant master device. Read it once
when you first set up the machine; everything else lives in `Settings` inside the
app.

---

## 1. Why OpenClaw?

OpenClaw is a local agent harness that exposes an **OpenAI-compatible chat
endpoint** at `http://127.0.0.1:18789/v1/chat/completions` and fans the request out
to any provider you configure (Anthropic, OpenAI, Google, Groq, OpenRouter, xAI,
DeepSeek, Moonshot, or a custom OpenAI-compatible URL). It also speaks the **MCP**
tool protocol, which is how Composio's hundreds of integrations show up to the LLM.

The Sentient Assistant talks to OpenClaw exactly like it talks to Groq, so you can
swap providers at runtime from `Settings → Chat Engine` without restarting the app.

## 2. Install OpenClaw on the master

```bash
curl -fsSL https://openclaw.ai/install.sh | bash
```

The installer drops a binary at one of:

- `/usr/local/bin/openclaw`
- `/opt/homebrew/bin/openclaw` (macOS, Apple Silicon Homebrew)
- `~/.openclaw/bin/openclaw`
- `~/.local/bin/openclaw`

Verify it's on PATH:

```bash
openclaw --version
```

Start the gateway daemon:

```bash
openclaw gateway start          # runs in background
openclaw gateway status         # shows port + active provider
```

The gateway listens on **`127.0.0.1:18789`** by default. The Sentient master
process hits that URL directly.

## 3. Configure a provider from the app

Open `Settings → OPENCLAW` in the Sentient UI:

1. **Provider** — pick Anthropic / OpenAI / Google / Groq / OpenRouter / xAI /
   DeepSeek / Moonshot / Custom.
2. **API Key** — paste the provider's API key. It's written to your local
   `~/.config/openclaw/openclaw.json5` under `secrets.<PROVIDER>_API_KEY`. Nothing
   leaves the master device.
3. **Model** — leave blank to use the suggested default, or type the exact model
   id you want (e.g. `claude-sonnet-4-5`, `gpt-4o`, `llama-3.3-70b-versatile`).
4. **Gateway Auth Token** — optional. Set this to require a bearer token on every
   call to the gateway. If you set it here, the Sentient app sends it as
   `Authorization: Bearer <token>` automatically.
5. Click **SAVE & RESTART GATEWAY**. The badge flips to `ONLINE`.
6. Set `Settings → Chat Engine` to **OpenClaw (local)**. Chats now route through it.

### What gets written

The UI updates `~/.config/openclaw/openclaw.json5`. Highlights:

```json5
{
  // Provider API key
  secrets: {
    ANTHROPIC_API_KEY: "...",
  },
  // Default model for chat
  gateway: {
    defaultModel: "anthropic/claude-sonnet-4-5",
    // Bearer token clients must send. Empty/missing = no auth.
    auth: { token: "your-shared-secret-here" }
  }
}
```

The previous file is backed up to `openclaw.json5.bak` before every write.

### Custom (OpenAI-compatible) providers

For any URL that speaks the OpenAI chat completions protocol (e.g. self-hosted
vLLM, LM Studio, Together AI):

- Provider: `Custom (OpenAI-compatible)`
- Base URL: e.g. `https://api.together.xyz/v1` or `http://192.168.1.10:8000/v1`
- API Key: bearer token for that endpoint
- Model: the exact model id the endpoint exposes

This writes a `models.providers.custom` block plus the necessary
`request.allowPrivateNetwork = true` flag when the URL isn't localhost.

## 4. Composio (skills)

`Settings → SKILLS · COMPOSIO` lets you connect Composio's MCP catalogue.

1. Paste your **Consumer Key** from
   [dashboard.composio.dev](https://dashboard.composio.dev/).
2. Toggle the toolkits you want exposed (GitHub, Gmail, Slack, Notion, …).
3. Click **SAVE SKILLS**. OpenClaw restarts and the tools become available to
   the LLM through MCP.

## 5. Connecting to a **remote** OpenClaw gateway

You don't have to run OpenClaw on the same machine that runs Sentient. You can
point Sentient at an OpenClaw instance on a **VPS** or another device on your
tailnet.

On the **remote host** (the one running OpenClaw):

1. Install OpenClaw and start the gateway as above.
2. **Bind to a public-ish interface**: by default the gateway listens on
   `127.0.0.1` only. Edit `~/.config/openclaw/openclaw.json5`:
   ```json5
   { gateway: { host: "0.0.0.0", port: 18789, auth: { token: "PUT-A-LONG-SECRET-HERE" } } }
   ```
   Restart: `openclaw gateway restart`.
3. **Open the port** (or, recommended, leave it firewalled and use Tailscale —
   then the URL is `http://<remote-tailscale-name>:18789` and only your tailnet
   can reach it).
4. **Always set `auth.token`** when binding to anything other than `127.0.0.1`.

On the **Sentient master** (the one running this app):

1. `Settings → OPENCLAW` → enable **Use Remote Gateway**.
2. **Remote URL**: `http://<remote-host>:18789` (or `https://` if you've put it
   behind TLS).
3. **Remote Auth Token**: the token you set in step 2 above.
4. **SAVE CONNECTION**. The status badge becomes
   `ONLINE · REMOTE (http://...)`.

The local gateway, if you have one, stays untouched — you can toggle back any
time. The toggle is persisted to `~/.sentient_openclaw_connection.json` on the
master, so it survives restarts.

## 6. Tailscale Funnel (public URL for the web UI)

Tailscale Funnel exposes the master's port 7070 on a permanent HTTPS URL like
`https://your-pi.tail-net.ts.net`. No router/port-forwarding required.

> **Important:** Funnel publishes the URL to the **public internet**. Set a
> shared-password login (next section) **before** turning Funnel on.

### Install Tailscale on the master

```bash
# Linux
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
# macOS
brew install tailscale && sudo tailscaled install-system-daemon
sudo tailscale up
```

### Enable Funnel from the app

`Settings → REMOTE ACCESS · TAILSCALE FUNNEL`:

- Click **ENABLE FUNNEL**. The app shells out to:
  ```
  tailscale funnel --bg --https=443 7070
  ```
- The public URL appears underneath the badge. Bookmark it on each device.
- Click **DISABLE FUNNEL** to take it down (`tailscale funnel reset`).

### What if Tailscale isn't installed?

The app will say `NOT INSTALLED` and link to
[tailscale.com/download](https://tailscale.com/download). Nothing else breaks —
the Funnel UI just stays inert.

### Tailnet (private) URL

Even without Funnel, every device on your tailnet can reach the master at
`http://<master-tailscale-name>:7070`. That's the safest mode if you don't need
public access — only your devices can connect.

## 7. Device login (shared password)

`Settings → DEVICE LOGIN`:

- Set a **shared password** (minimum 6 characters). All previously-logged-in
  devices are immediately signed out and must re-enter the password.
- The password is **never sent to any external service**. It's hashed with a
  random salt and stored at `~/.sentient_auth.json` on the master only.
- Each successful login issues a random 256-bit token that the device stores in
  `localStorage`. The token is sent on every API call (`X-Sentient-Token` header)
  and every WebSocket connect (`?token=…` query parameter).
- **DISABLE LOGIN** clears the password and reverts to LAN-trust mode.
- **LOG OUT THIS DEVICE** invalidates this device's token only.

### Bypass paths

These routes work without a token (so OAuth + login can complete):

- `/`, `/login`, `/login.html`, `/favicon.ico`
- `/api/auth/status`, `/api/auth/login`
- `/api/spotify/callback`, `/api/tasks/google/callback`

## 8. The credential vault (sync across instances)

`Settings → VAULT · CREDENTIALS & ENV VARS` is the single source of truth for
two kinds of secrets:

1. **Service logins** — `name + URL + username + password + notes`. Passwords
   are encrypted at rest with AES-256-GCM (key at `~/.sentient_vault_key`,
   mode `0600`). The REST API returns them masked. The plaintext only flows
   over a one-shot WebSocket `autofill_request` message when you (or the AI)
   triggers `USE`.
2. **Environment variables** — flat name/value pairs that automation and
   subprocess-spawning tools can consume. Values come back unmasked to the
   authenticated UI because that's how env vars get used.

### Sync

The vault lives on the master only. Every browser that connects pulls from
the same `/api/vault/*` endpoints, so every logged-in device sees the same
list. After any mutation the master broadcasts `{type:"vault_updated"}` over
WebSocket so other tabs re-fetch immediately — no refresh needed.

### How the AI uses credentials without seeing them

The system prompt for both Groq and OpenClaw lists the *names* of saved
credentials (e.g. `gmail, amazon, github`). The AI never receives the
passwords. When the assistant decides to sign you in, it emits:

```
[CMD:USE_CREDENTIAL:gmail]
```

The master intercepts that, looks up the credential, and broadcasts an
`autofill_request` WebSocket message to your browsers. The currently-visible
tab opens an in-app overlay with the URL + click-to-copy buttons for username
and password. (Browsers block cross-origin form fill, so the actual paste is
one click — see `DEVICE_CONTROL.md` for the path to fully-automatic injection
via a native helper.)

### Env-var consumption

Today the env vars are stored and synced but not yet auto-injected into
OpenClaw's config block or the automation webhooks. Adding the `{{VAR_NAME}}`
substitution path in `AutomationService` and `OpenClawConfigManager` is a
short follow-up — see `CredentialVault.getEnvVar`.

### Why store secrets on the master rather than a hosted vault?

- The master is already trusted with every API key and OAuth refresh token.
- No third-party dependency or rate limit.
- Browser-side `localStorage` is the wrong place because it doesn't sync
  cross-device, and a public Funnel URL means it would be exposed to anyone
  with the password.

## 9. End-to-end checklist

A fresh master device, fully wired:

- [ ] `openclaw gateway start` runs at boot (e.g. systemd unit).
- [ ] Settings → OpenClaw: provider chosen + model set + key saved.
- [ ] Gateway auth token set on `gateway.auth.token`, matching the Sentient setting.
- [ ] Settings → Device Login: shared password set.
- [ ] Settings → Tailscale Funnel: enabled if you want public access.
- [ ] Each personal device opens the Funnel URL (or tailnet URL), logs in once
      with the shared password, and stores its token.

That's it. From any of those devices you should be able to chat, run skills via
Composio, and control Spotify/Tasks/Calendar even when you're not on the LAN.

## 10. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Badge says `NOT INSTALLED` | `openclaw` binary isn't on the master's `PATH`. Run the install script and re-check `which openclaw`. |
| Badge says `INSTALLED · OFFLINE` | Binary is there but `openclaw gateway start` hasn't been run, or it crashed. Check `openclaw gateway status`. |
| Chats hang then say "OpenClaw gateway rejected the request" | The gateway is running but `gateway.auth.token` doesn't match the **Gateway Auth Token** in Sentient settings. Make sure both sides have the same value. |
| Remote badge says `REMOTE UNREACHABLE` | Wrong URL, firewall blocks the port, or the remote gateway isn't bound to `0.0.0.0`. Try `curl http://<remote>:18789/v1/models -H "Authorization: Bearer <token>"` from the master. |
| Funnel button does nothing | Tailscale isn't logged in. Run `tailscale up` on the master, then refresh. |
| Login screen loops | Token mismatch with the server. Clear `localStorage.sentient_token` and try again. |
| Vault entries gone after restart | `~/.sentient_vault_key` was deleted or replaced. The encrypted blob in `~/.sentient_vault.json` can no longer be decoded — restore both files together or re-enter the vault contents. |
| Auto-fill overlay never shows up | Master couldn't deliver the WS message — check that the calling tab is connected (online indicator in the sidebar), and that the token in `localStorage` is still valid. |
