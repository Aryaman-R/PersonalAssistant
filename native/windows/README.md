# SentientHelper (Windows)

Native bridge that lets the Sentient master execute OS-level actions on a
Windows PC — typing text, clicking coordinates, launching apps, sending key
combos, opening URLs. The browser can't do these because the web sandbox
forbids keystroke/click injection.

It connects to the master's `/helper` WebSocket and registers itself under a
device name. The master's command dispatch routes `[CMD:TYPE_TEXT:…]`,
`[CMD:CLICK_AT:…]`, `[CMD:LAUNCH_APP:…]`, `[CMD:KEY_COMBO:…]` to this helper.

This is the Windows counterpart to [`../macos/`](../macos/README.md) and
speaks exactly the same wire protocol.

## Build

Requires the **.NET 8 SDK** (`winget install Microsoft.DotNet.SDK.8`).

```powershell
cd native\windows\SentientHelper

# One-shot publish → a single self-contained sentient-helper.exe with no
# .NET runtime dependency. Output lands in bin\Release\net8.0-windows\win-x64\publish\
dotnet publish -c Release

# Or for a quick dev run that uses your locally-installed runtime:
dotnet run -- --host localhost:7070 --token "%SENTIENT_TOKEN%" --name "My PC"
```

Copy the published EXE wherever you'd like — `C:\Tools\sentient-helper.exe`
is a fine default. The file is fully self-contained; no installer needed.

## Run

```powershell
# Token = your shared password's issued token from the master.
sentient-helper --host localhost:7070 ^
                --token "%SENTIENT_TOKEN%" ^
                --name "My PC"
```

Common flags:

| Flag | Meaning |
|---|---|
| `--host HOST:PORT` | Master address. Default `localhost:7070`. |
| `--token TOKEN` | Auth token issued by the master. Read from `SENTIENT_TOKEN` env var if omitted. |
| `--name NAME` | Display name surfaced in the master's device registry. |
| `--insecure` | Force plain ws:// even for non-localhost hosts. |

## Permissions

Unlike macOS, Windows has **no Accessibility prompt** — `SendInput` works for
any process running in the interactive desktop session as soon as the user is
logged in. There are still two scenarios that need a heads-up:

1. **UAC / elevated windows.** A non-elevated helper cannot drive windows
   owned by a process running As Administrator (User Interface Privilege
   Isolation). If the AI needs to click inside an elevated app (Task Manager,
   regedit, an installer), launch the helper itself elevated — right-click
   `sentient-helper.exe` → **Run as administrator**, or check the elevated
   box in the Scheduled Task below.

2. **Windows Defender SmartScreen.** First run of an unsigned EXE will pop a
   "Windows protected your PC" warning. Click **More info → Run anyway**.
   Code-signing the binary removes this; that's out of scope here.

## Run at login (Task Scheduler)

Easiest path is a per-user logon task. Save the helper somewhere stable, then
register a task that runs it on every logon:

```powershell
schtasks /Create ^
  /TN "SentientHelper" ^
  /SC ONLOGON ^
  /TR "\"C:\Tools\sentient-helper.exe\" --host YOUR-MASTER:7070 --name \"My PC\"" ^
  /RL HIGHEST ^
  /F

# The helper reads SENTIENT_TOKEN from the user environment. Set it once:
setx SENTIENT_TOKEN "your-token-from-the-master"
```

To remove it later: `schtasks /Delete /TN "SentientHelper" /F`.

If you'd rather avoid the console window popping briefly on login, wrap the
EXE in a tiny VBScript launcher or publish with `<OutputType>WinExe</OutputType>` —
but the console output is genuinely useful while you're still debugging.

## Protocol

Inbound messages from the master are JSON objects of the form:

```json
{ "type": "remote_action", "action": "TYPE_TEXT", "text": "hello world" }
{ "type": "remote_action", "action": "CLICK_AT", "x": 500, "y": 300 }
{ "type": "remote_action", "action": "LAUNCH_APP", "bundleId": "notepad.exe" }
{ "type": "remote_action", "action": "KEY_COMBO", "keys": ["ctrl", "c"] }
{ "type": "remote_action", "action": "OPEN_URL", "url": "https://example.com" }
```

The helper replies with `{"type":"action_result","action":<name>,"success":<bool>,"detail":<string>}`.

On connect the helper sends
`{"type":"register_helper","name":...,"platform":"Windows","capabilities":[...]}`
so the master can list it in the device registry.

### Field notes (Windows specifics)

- **`bundleId`** (LAUNCH_APP). The macOS helper expects a bundle identifier;
  on Windows this field is interpreted as one of:
  - An executable name on `PATH` — `notepad.exe`, `code.exe`, `pwsh.exe`
  - A full path — `C:\Program Files\Anthropic\Claude\Claude.exe`
  - An AppUserModelID (UWP / MS Store apps), recognized by the `!` separator —
    e.g. `Microsoft.WindowsCalculator_8wekyb3d8bbwe!App`. The helper launches
    these via `explorer.exe shell:AppsFolder\<AUMID>`.
  - A URI scheme registered with Windows — `ms-settings:`, `mailto:foo@bar`,
    `slack://channel?...`
- **`keys`** (KEY_COMBO). Modifier names accept both Windows and macOS
  spellings so the same automation works cross-platform: `cmd` / `meta` /
  `super` are all aliases for the Windows key.
- **`x`/`y`** (CLICK_AT) are absolute virtual-screen pixel coordinates. The
  helper opts into Per-Monitor-V2 DPI awareness so coordinates land where the
  caller expects on high-DPI / mixed-DPI multi-monitor setups.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `[Helper] WS error: The remote party closed the WebSocket…` repeating | Wrong `--token`, or master isn't running. Check `http://HOST:PORT/` in a browser. |
| `LAUNCH_APP` returns `success:false` for a UWP app | Use the AppUserModelID (`Get-StartApps` in PowerShell lists them) — a plain exe name won't find packaged apps. |
| `CLICK_AT` lands a few hundred pixels off on a 4K monitor | Pre-Win10 build that doesn't support Per-Monitor-V2. Update Windows, or scale your coordinates by the display's DPI factor before sending. |
| Keys go to the wrong window | Whichever window has keyboard focus when SendInput fires receives the events. Use `LAUNCH_APP` first, give the app a brief moment to focus, then send your `TYPE_TEXT` / `KEY_COMBO`. |
| Nothing works in Task Manager / installer | UIPI — re-register the Scheduled Task with the elevated checkbox, or right-click the EXE → Run as administrator. |
