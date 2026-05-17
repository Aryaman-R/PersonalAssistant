# SentientHelper (macOS)

Native bridge that lets the Sentient master execute OS-level actions on a Mac —
typing text, clicking coordinates, launching apps, sending key combos, opening
URLs. The browser can't do these because the web sandbox forbids
keystroke/click injection.

It connects to the master's `/helper` WebSocket and registers itself under a
device name. The master's command dispatch routes `[CMD:TYPE_TEXT:…]`,
`[CMD:CLICK_AT:…]`, `[CMD:LAUNCH_APP:…]`, `[CMD:KEY_COMBO:…]` to this helper.

## Build

```bash
cd native/macos
swift build -c release
cp .build/release/SentientHelper /usr/local/bin/sentient-helper
```

## Run

```bash
# Token = your shared password's issued token from the master.
sentient-helper --host localhost:7070 \
                --token "$SENTIENT_TOKEN" \
                --name "My Mac"
```

Common flags:

| Flag | Meaning |
|---|---|
| `--host HOST:PORT` | Master address. Default `localhost:7070`. |
| `--token TOKEN` | Auth token issued by the master. Read from `SENTIENT_TOKEN` env var if omitted. |
| `--name NAME` | Display name surfaced in the master's device registry. |
| `--insecure` | Force plain ws:// even for non-localhost hosts. |

## Permissions

macOS gates keystroke/click injection behind Accessibility:

1. Run the helper once — it prints a warning if permission is missing.
2. Open **System Settings → Privacy & Security → Accessibility**.
3. Click **+** and add `/usr/local/bin/sentient-helper` (or wherever you put it).
4. Toggle the row on.

For launching apps via Apple Events, macOS will pop a one-time TCC prompt the
first time the helper opens a specific target app.

## Run at login

The simplest setup is a LaunchAgent. Drop this into
`~/Library/LaunchAgents/com.sentient.helper.plist`:

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
    <key>StandardErrorPath</key><string>/tmp/sentient-helper.err</string>
    <key>StandardOutPath</key><string>/tmp/sentient-helper.log</string>
</dict></plist>
```

Then `launchctl load -w ~/Library/LaunchAgents/com.sentient.helper.plist`.

## Protocol

Inbound messages from the master are JSON objects of the form:

```json
{ "type": "remote_action", "action": "TYPE_TEXT", "text": "hello world" }
{ "type": "remote_action", "action": "CLICK_AT", "x": 500, "y": 300 }
{ "type": "remote_action", "action": "LAUNCH_APP", "bundleId": "com.anthropic.claude" }
{ "type": "remote_action", "action": "KEY_COMBO", "keys": ["cmd", "space"] }
{ "type": "remote_action", "action": "OPEN_URL", "url": "https://example.com" }
```

The helper replies with `{"type":"action_result","action":<name>,"success":<bool>,"detail":<string>}`.

On connect the helper sends `{"type":"register_helper","name":...,"platform":"macOS","capabilities":[...]}`
so the master can list it in the device registry.
