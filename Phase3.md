# Phase 3 — OpenClaw Integration & Remote Device Control

Implementation blueprint for the remaining Phase 3 work. Each track lists exactly which files to touch, what to add, and the call chain end-to-end.

---

## Current Status Audit

The following Phase 3 items are **already complete**:

| Item | Status |
|---|---|
| `OpenClawService.java` — gateway client, local/remote toggle, auth, history | ✅ Done |
| `OpenClawConfigManager.java` — provider config writes, Composio setup, gateway restart | ✅ Done |
| All `/api/openclaw/*` REST endpoints | ✅ Done |
| Engine toggle (`chatEngine` localStorage + WS `engine` field + `WebServer.handleChat` routing) | ✅ Done |
| OpenClaw provider config UI (dropdown, API key, model, custom URL, gateway token) | ✅ Done |
| Composio / Skills UI (consumer key, toolkit grid, save to `/api/openclaw/composio`) | ✅ Done |
| Remote gateway UI (`useRemote` toggle, remote URL + token) | ✅ Done |
| Master Server URL field in Settings (`sentient_masterHost` localStorage, used in `api()`) | ✅ Done |
| Single-frame screen snapshot (device registry, `request_screen`/`capture_screen`/`screen_frame` flow) | ✅ Done |
| `remote_action` relay in WebServer (forwards to target WS session) | ✅ Done |
| `AuthService` + `CredentialVault` | ✅ Done |

The following items are **not yet built** and make up the Phase 3 deliverable:

| Track | Item | Effort |
|---|---|---|
| 1 | `[CMD:VIEW_DEVICE:<name>]` — auto-snapshot → vision pipeline | ~80 LOC |
| 2 | WebRTC live screen mirror | ~700 LOC |
| 3 | Browser-safe `remote_action` on receiving device | ~30 LOC |
| 4 | Native OS helper — macOS first | 2–4 weeks |
| 5 | Per-action consent model | ~200 LOC |
| 6 | Blocking bug fixes | ~10 LOC |

---

## Track 1 — `[CMD:VIEW_DEVICE:<name>]` Auto-Snapshot → Vision

**Goal:** When the user says "Jarvis, what's on my Mac?" the assistant captures a screenshot from the named device and feeds it directly into the vision model, without the user dragging an image.

The command string is already in the OpenClawService system prompt. Only the server-side dispatch is missing.

### 1.1 Server — `WebServer.java`

**Where:** In the `handleChat` command dispatch loop, after line ~424 (the `for (String[] cmd : commands)` block).

**Add a `VIEW_DEVICE` case:**

```java
if ("VIEW_DEVICE".equals(cmd[0]) && cmd[1] != null) {
    String targetName = cmd[1].trim();

    // Find the WS session that registered under this device name.
    String targetSessionId = deviceMeta.entrySet().stream()
        .filter(e -> targetName.equalsIgnoreCase(
            e.getValue().has("name") ? e.getValue().get("name").getAsString() : ""))
        .map(Map.Entry::getKey)
        .findFirst().orElse(null);

    if (targetSessionId == null) {
        // Device not connected — tell the user.
        JsonObject warn = new JsonObject();
        warn.addProperty("type", "system");
        warn.addProperty("text", "Device '" + targetName + "' is not connected.");
        broadcast(warn);
        continue;
    }

    // Ask that device to capture its screen.
    String requestId = java.util.UUID.randomUUID().toString();
    JsonObject captureMsg = new JsonObject();
    captureMsg.addProperty("type", "capture_screen");
    captureMsg.addProperty("requestId", requestId);

    // Register a one-shot listener for the screen_frame reply.
    java.util.concurrent.CompletableFuture<String> frameFuture = new java.util.concurrent.CompletableFuture<>();
    pendingScreenCaptures.put(requestId, frameFuture);

    // Send capture request to target device.
    clients.stream()
        .filter(c -> requestId != null && c.getSessionId() != null
            && deviceMeta.containsKey(c.getSessionId())
            && c.getSessionId().equals(targetSessionId))
        .forEach(c -> c.send(captureMsg.toString()));

    // Wait up to 30s for the frame, then pipe it into vision.
    frameFuture.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .thenAccept(jpegBase64 -> {
            // Re-enter handleChat with the captured JPEG as the image payload.
            String visionPrompt = "The user asked: \"" + text + "\". Describe what you see on screen.";
            handleChat(visionPrompt, playOnServer, "THINK", jpegBase64, "screen.jpg", "image/jpeg", engine);
        })
        .exceptionally(ex -> {
            JsonObject timeoutMsg = new JsonObject();
            timeoutMsg.addProperty("type", "system");
            timeoutMsg.addProperty("text", "Screen capture from '" + targetName + "' timed out.");
            broadcast(timeoutMsg);
            return null;
        });
    continue;
}
```

**Add a pending captures map** near the top of `WebServer` (alongside `deviceMeta`):

```java
private final java.util.Map<String, java.util.concurrent.CompletableFuture<String>>
    pendingScreenCaptures = new ConcurrentHashMap<>();
```

**Wire `screen_frame` replies into the map** — in `setupWebSocket()`, inside the existing `"screen_frame"` case (~line 338):

```java
case "screen_frame": {
    // ... existing relay logic ...
    // NEW: also resolve any pending VIEW_DEVICE capture.
    String reqId = msgObj.has("requestId") ? msgObj.get("requestId").getAsString() : null;
    if (reqId != null) {
        var pending = pendingScreenCaptures.remove(reqId);
        if (pending != null && msgObj.has("imageData")) {
            pending.complete(msgObj.get("imageData").getAsString());
        }
    }
    break;
}
```

**Add `VIEW_DEVICE` to the system prompt** in `GroqService.java` as well (it's already in `OpenClawService.buildChatSystemPrompt()`). Find the commands section in `GroqService` and add:

```
- [CMD:VIEW_DEVICE:DeviceName] — capture a screenshot from a connected device and analyze it visually. Use the device names visible in the device registry.
```

### 1.2 No frontend changes needed

The existing `capture_screen` → `screen_frame` flow in `app.js` already handles this. The server-side roundtrip above reuses it transparently.

---

## Track 2 — WebRTC Live Screen Mirror

**Goal:** Replace the single-frame snapshot with a continuous live video stream between two browser tabs, peer-to-peer over the Tailnet, sub-100ms latency.

### 2.1 Server — `WebServer.java` (signalling relay only)

WebRTC is browser-to-browser; the server's only role is to relay three message types. Add these cases to the `setupWebSocket()` switch block (alongside the existing `request_screen` / `screen_frame` handlers):

```java
case "webrtc_offer":
case "webrtc_answer":
case "ice_candidate": {
    // Relay to the target session identified by targetSessionId in the payload.
    String targetId = msgObj.has("targetSessionId")
        ? msgObj.get("targetSessionId").getAsString() : null;
    if (targetId == null) break;
    clients.stream()
        .filter(c -> targetId.equals(c.getSessionId()))
        .forEach(c -> c.send(message));
    break;
}
```

That is the entire server change for WebRTC — ~15 LOC.

### 2.2 Frontend — `app.js`

Add these four functions (roughly 250 LOC total). Place them after the existing `requestScreenSnapshot` / `handleCaptureScreen` functions (~line 440).

**`startWebRTCStream(targetSessionId)`** — called on the *requester* (the device that wants to watch):

```javascript
async function startWebRTCStream(targetSessionId) {
    const pc = new RTCPeerConnection({ iceServers: [] }); // no TURN needed on Tailnet
    webrtcPeerConnections[targetSessionId] = pc;

    // Create a <video> element to show the remote stream.
    const w = window.open('', '_blank', 'width=960,height=600');
    const video = w.document.createElement('video');
    video.autoplay = true;
    video.style.cssText = 'width:100%;height:100%;background:#000';
    w.document.body.style.margin = '0';
    w.document.body.appendChild(video);

    pc.ontrack = e => { video.srcObject = e.streams[0]; };
    pc.onicecandidate = e => {
        if (!e.candidate) return;
        sendWS({ type: 'ice_candidate', targetSessionId,
                 candidate: e.candidate.toJSON(), fromSessionId: mySessionId });
    };

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    sendWS({ type: 'webrtc_offer', targetSessionId,
             sdp: offer.sdp, fromSessionId: mySessionId });
}
```

**`handleWebRTCOffer(msg)`** — called on the *capturer* (the device being watched):

```javascript
async function handleWebRTCOffer(msg) {
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
    const pc = new RTCPeerConnection({ iceServers: [] });
    webrtcPeerConnections[msg.fromSessionId] = pc;

    stream.getTracks().forEach(track => pc.addTrack(track, stream));

    pc.onicecandidate = e => {
        if (!e.candidate) return;
        sendWS({ type: 'ice_candidate', targetSessionId: msg.fromSessionId,
                 candidate: e.candidate.toJSON(), fromSessionId: mySessionId });
    };

    await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    sendWS({ type: 'webrtc_answer', targetSessionId: msg.fromSessionId,
             sdp: answer.sdp, fromSessionId: mySessionId });
}
```

**`handleWebRTCAnswer(msg)`** and **`handleICECandidate(msg)`**:

```javascript
async function handleWebRTCAnswer(msg) {
    const pc = webrtcPeerConnections[msg.fromSessionId];
    if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
}

async function handleICECandidate(msg) {
    const pc = webrtcPeerConnections[msg.fromSessionId];
    if (pc && msg.candidate) await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
}
```

Add a map at the top of `app.js`:

```javascript
const webrtcPeerConnections = {};
```

**Wire into `handleWSMessage`** — add cases alongside `capture_screen` / `screen_frame`:

```javascript
case 'webrtc_offer':    handleWebRTCOffer(data); break;
case 'webrtc_answer':   handleWebRTCAnswer(data); break;
case 'ice_candidate':   handleICECandidate(data); break;
```

**Add a "LIVE STREAM" button** to the device list in `index.html` alongside the existing "VIEW SCREEN" button:

```html
<button class="timer-btn" onclick="startWebRTCStream('${device.sessionId}')">LIVE</button>
```

Update the JS that renders the device list to pass `device.sessionId` into that call.

---

## Track 3 — Browser-Safe `remote_action` on the Receiving Device

The server already forwards `remote_action` messages. The receiving browser tab needs to act on them.

**File:** `app.js` — in `handleWSMessage`, find the `remote_action` case (currently it may only log). Replace it with:

```javascript
case 'remote_action': {
    const action = data.action;
    if (action === 'OPEN_URL' && data.url) {
        // Check consent before acting.
        if (hasConsent('OPEN_URL', data.fromDevice)) {
            window.open(data.url, '_blank', 'noopener');
        } else {
            showConsentPrompt('OPEN_URL', data.fromDevice, data.url,
                () => window.open(data.url, '_blank', 'noopener'));
        }
    } else if (action === 'SWITCH_PANEL' && data.panel) {
        showPanel(data.panel); // existing function
    }
    break;
}
```

**AI command wiring:** Add `[CMD:REMOTE_OPEN:DeviceName|https://url]` to the system prompt in both `OpenClawService.buildChatSystemPrompt()` and the equivalent section in `GroqService.java`. Handle it in `WebServer.java` alongside the other commands:

```java
if ("REMOTE_OPEN".equals(cmd[0]) && cmd[1] != null) {
    String[] parts = cmd[1].split("\\|", 2);
    if (parts.length == 2) {
        String targetDevice = parts[0].trim();
        String url = parts[1].trim();
        String targetId = lookupDeviceSession(targetDevice); // same helper as VIEW_DEVICE
        if (targetId != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "remote_action");
            msg.addProperty("action", "OPEN_URL");
            msg.addProperty("url", url);
            msg.addProperty("fromDevice", "master");
            forwardToSession(targetId, msg);
        }
    }
    continue;
}
```

Extract a reusable `lookupDeviceSession(String name)` helper to avoid duplicating the `deviceMeta` stream lookup from Track 1.

---

## Track 4 — Native OS Helper (macOS First)

**Goal:** Allow the AI to type text, click coordinates, launch apps, and send key combos on a user's Mac.

### 4.1 Protocol design

The helper speaks a simple JSON protocol over a WebSocket to the master (port 7071, separate from the browser WS). Each message is:

```json
{ "type": "remote_action", "action": "TYPE_TEXT", "text": "hello world" }
{ "type": "remote_action", "action": "CLICK_AT", "x": 500, "y": 300 }
{ "type": "remote_action", "action": "LAUNCH_APP", "bundleId": "com.anthropic.claude" }
{ "type": "remote_action", "action": "KEY_COMBO", "keys": ["cmd", "space"] }
{ "type": "remote_action", "action": "OPEN_URL", "url": "https://..." }
```

### 4.2 Server — `WebServer.java` — helper WebSocket endpoint

Add a second WS route at `/helper` that only native helpers connect to. This is separate from the browser WS so helper sessions aren't broadcast to browsers.

```java
// In setupWebSocket():
app.ws("/helper", ws -> {
    ws.onConnect(ctx -> {
        String token = ctx.queryParam("token");
        if (!auth.isValidToken(token)) { ctx.closeSession(1008, "Unauthorized"); return; }
        helperClients.put(ctx.getSessionId(), ctx);
        System.out.println("[Helper] connected: " + ctx.getSessionId());
    });
    ws.onClose(ctx -> helperClients.remove(ctx.getSessionId()));
    ws.onMessage(ctx -> {
        // Helpers only send status/ack messages back — log them.
        System.out.println("[Helper] " + ctx.message());
    });
});
```

Add `helperClients` map alongside `clients`.

### 4.3 macOS Swift CLI

Create a new directory `native/macos/` containing a Swift Package Manager project.

**`Sources/SentientHelper/main.swift`** — skeleton:

```swift
import Foundation
import AppKit
import WebKit

let masterHost = CommandLine.arguments.dropFirst().first ?? "localhost:7071"
let token = ProcessInfo.processInfo.environment["SENTIENT_TOKEN"] ?? ""

// Connect to master WS
var request = URLRequest(url: URL(string: "ws://\(masterHost)/helper?token=\(token)")!)
let session = URLSession(configuration: .default)
let task = session.webSocketTask(with: request)
task.resume()

func receive() {
    task.receive { result in
        switch result {
        case .success(let msg):
            if case .string(let text) = msg, let data = text.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                handleAction(json)
            }
            receive() // keep listening
        case .failure(let err):
            print("[Helper] WS error: \(err)")
        }
    }
}

func handleAction(_ msg: [String: Any]) {
    guard let action = msg["action"] as? String else { return }
    switch action {
    case "TYPE_TEXT":
        if let text = msg["text"] as? String { typeText(text) }
    case "CLICK_AT":
        if let x = msg["x"] as? CGFloat, let y = msg["y"] as? CGFloat {
            clickAt(CGPoint(x: x, y: y))
        }
    case "LAUNCH_APP":
        if let bundleId = msg["bundleId"] as? String {
            NSWorkspace.shared.launchApplication(withBundleIdentifier: bundleId,
                options: [], additionalEventParamDescriptor: nil, launchIdentifier: nil)
        }
    case "KEY_COMBO":
        if let keys = msg["keys"] as? [String] { sendKeyCombo(keys) }
    case "OPEN_URL":
        if let urlStr = msg["url"] as? String, let url = URL(string: urlStr) {
            NSWorkspace.shared.open(url)
        }
    default: break
    }
}

receive()
RunLoop.main.run() // keep alive
```

**`typeText(_:)`** uses `CGEvent(keyboardEventSource:virtualKey:keyDown:)` with a character loop, or `AXUIElementSetAttributeValue` for the focused element.

**`clickAt(_:)`** uses `CGEvent(mouseEventSource:mouseType:mouseCursorPosition:mouseButton:)`.

**`sendKeyCombo(_:)`** maps key names ("cmd", "shift", "space", etc.) to `CGKeyCode` constants and posts a CGEvent pair (keyDown + keyUp) with the appropriate modifier flags.

**Required entitlements** (`SentientHelper.entitlements`):

```xml
<key>com.apple.security.automation.apple-events</key><true/>
<key>com.apple.security.temporary-exception.apple-events</key>...
```

The user must grant Accessibility permission once: `System Preferences → Privacy & Security → Accessibility → add SentientHelper`.

**Build & install:**

```bash
cd native/macos
swift build -c release
cp .build/release/SentientHelper /usr/local/bin/sentient-helper
# Run at login:
sentient-helper localhost:7071 &
```

### 4.4 System prompt additions

Add to both `OpenClawService.buildChatSystemPrompt()` and `GroqService`:

```
- [CMD:TYPE_TEXT:DeviceName|text to type] — type text into the focused app on a device
- [CMD:LAUNCH_APP:DeviceName|bundleId] — launch a macOS app by bundle ID
- [CMD:KEY_COMBO:DeviceName|cmd+space] — send a key combination
- [CMD:CLICK_AT:DeviceName|x,y] — click at screen coordinates
Only use these when the user explicitly asks to control a specific device.
```

### 4.5 Server command dispatch

Add cases to the WebServer command dispatch loop (same block as `VIEW_DEVICE`):

```java
if (List.of("TYPE_TEXT","LAUNCH_APP","KEY_COMBO","CLICK_AT").contains(cmd[0]) && cmd[1] != null) {
    String[] parts = cmd[1].split("\\|", 2);
    if (parts.length == 2) {
        String targetDevice = parts[0].trim();
        String payload = parts[1].trim();
        String targetId = lookupHelperSession(targetDevice);
        if (targetId != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "remote_action");
            msg.addProperty("action", cmd[0]);
            // Parse payload per action type
            if ("TYPE_TEXT".equals(cmd[0])) msg.addProperty("text", payload);
            else if ("LAUNCH_APP".equals(cmd[0])) msg.addProperty("bundleId", payload);
            else if ("KEY_COMBO".equals(cmd[0])) msg.addProperty("keys", payload);
            else if ("CLICK_AT".equals(cmd[0])) {
                String[] xy = payload.split(",", 2);
                if (xy.length == 2) {
                    msg.addProperty("x", Double.parseDouble(xy[0].trim()));
                    msg.addProperty("y", Double.parseDouble(xy[1].trim()));
                }
            }
            forwardToHelper(targetId, msg);
        }
    }
    continue;
}
```

Add `lookupHelperSession(String name)` — same shape as `lookupDeviceSession` but searches `helperClients` map.

---

## Track 5 — Per-Action Consent Model

**Goal:** Every novel action category that targets a browser device must prompt the receiving user before executing. Users can grant "always allow" per (sender, action type) pair.

**File:** `app.js` — add ~200 LOC.

**Consent store** (persisted in `localStorage`):

```javascript
function consentKey(action, fromDevice) {
    return `sentient_consent_${action}_${fromDevice}`;
}
function hasConsent(action, fromDevice) {
    return localStorage.getItem(consentKey(action, fromDevice)) === 'granted';
}
function grantConsent(action, fromDevice) {
    localStorage.setItem(consentKey(action, fromDevice), 'granted');
}
function revokeConsent(action, fromDevice) {
    localStorage.removeItem(consentKey(action, fromDevice));
}
```

**Consent overlay** — a modal shown over the current panel:

```javascript
function showConsentPrompt(action, fromDevice, detail, onAllow) {
    const modal = document.createElement('div');
    modal.className = 'consent-modal';
    modal.innerHTML = `
        <div class="consent-box">
            <h3>Permission Request</h3>
            <p><strong>${fromDevice || 'master'}</strong> wants to: <em>${action}</em></p>
            <p class="consent-detail">${detail || ''}</p>
            <label><input type="checkbox" id="consentRemember"> Always allow this from ${fromDevice}</label>
            <div class="consent-buttons">
                <button id="consentAllow" class="timer-btn play-btn">ALLOW</button>
                <button id="consentDeny" class="timer-btn cancel-btn">DENY</button>
            </div>
        </div>`;
    document.body.appendChild(modal);

    modal.querySelector('#consentAllow').addEventListener('click', () => {
        if (modal.querySelector('#consentRemember').checked) grantConsent(action, fromDevice);
        modal.remove();
        onAllow();
    });
    modal.querySelector('#consentDeny').addEventListener('click', () => modal.remove());
}
```

**CSS** (add to `styles.css`):

```css
.consent-modal {
    position: fixed; inset: 0; background: rgba(0,0,0,0.7);
    display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.consent-box {
    background: var(--bg-secondary); border: 1px solid var(--border);
    border-radius: 12px; padding: 2rem; max-width: 420px; width: 90%;
}
.consent-detail { font-size: 0.85em; color: var(--text-secondary); word-break: break-all; }
.consent-buttons { display: flex; gap: 1rem; margin-top: 1.5rem; }
```

**Apply consent check** to every action handler in Track 3, and add a "Manage Permissions" section to Settings where users can see and revoke stored consent grants.

---

## Track 6 — Blocking Bug Fixes

These must be fixed before Phase 3 ships as they block the new flows.

### 6.1 Google Calendar auth endpoint (`app.js` line ~2388)

**Current (broken):**

```javascript
window.open('/api/calendar/google/auth', ...)
```

**Fix:**

```javascript
window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
```

The Tasks and Calendar OAuth share a single token file and a single auth endpoint (`/api/tasks/google/auth`). Matching the existing Spotify + Google Tasks link pattern, use `api(...)` to respect the `sentient_masterHost` override.

### 6.2 Duplicate `user_profile.json`

Delete `/Users/aryamanr/PersonalAssistant/user_profile.json` (the repo-root copy). The live one is at `piassistant/user_profile.json`. Add to `.gitignore`:

```
user_profile.json
```

---

## Implementation Order

Build in this sequence to ship incremental value with no regressions:

1. **Track 6** — Fix Google Calendar link and duplicate profile. Zero risk, unblocks testing.
2. **Track 3** — Browser-safe `remote_action` (`OPEN_URL` + `SWITCH_PANEL`). ~30 LOC, immediately useful.
3. **Track 1** — `VIEW_DEVICE` voice command → vision pipeline. High user value, server-only change.
4. **Track 5** — Consent model. Required before Track 4 ships; build it now so Track 3 already uses it.
5. **Track 2** — WebRTC live mirror. Larger but self-contained; device registry already exists.
6. **Track 4** — Native macOS helper. Separate project; can develop and test in parallel with Tracks 2–5.

---

## File Change Summary

| File | Track | Change |
|---|---|---|
| `WebServer.java` | 1 | Add `pendingScreenCaptures` map, `VIEW_DEVICE` command case, `screen_frame` → future resolution, `lookupDeviceSession()` helper |
| `WebServer.java` | 2 | Add `webrtc_offer` / `webrtc_answer` / `ice_candidate` relay cases (~15 LOC) |
| `WebServer.java` | 3 | Add `REMOTE_OPEN` command case, `forwardToSession()` helper |
| `WebServer.java` | 4 | Add `/helper` WS endpoint, `helperClients` map, OS-action command cases, `lookupHelperSession()`, `forwardToHelper()` |
| `GroqService.java` | 1, 3, 4 | Add `VIEW_DEVICE`, `REMOTE_OPEN`, OS-action commands to system prompt |
| `OpenClawService.java` | 3, 4 | Same system prompt additions (already has `VIEW_DEVICE`) |
| `app.js` | 2 | Add `webrtcPeerConnections` map, 4 WebRTC functions, WS case handlers, "LIVE" button in device list render |
| `app.js` | 3 | Handle `remote_action` WS message: `OPEN_URL` and `SWITCH_PANEL` |
| `app.js` | 5 | Add consent store functions, `showConsentPrompt()`, apply to all remote action handlers |
| `app.js` | 6 | Fix Google Calendar auth endpoint |
| `index.html` | 2 | Add "LIVE" button to device list item template |
| `index.html` | 5 | Add "Manage Permissions" section to Settings panel |
| `styles.css` | 5 | Add `.consent-modal` + `.consent-box` styles |
| `native/macos/` | 4 | New Swift PM project — `SentientHelper` CLI |
| `.gitignore` | 6 | Add `user_profile.json` |

---

## What Shipped (commit `cb95cdf`)

All six tracks are implemented and on `origin/main`. The Maven build is clean
(`mvn -DskipTests package` → `BUILD SUCCESS`); `app.js` passes `node --check`.

### Files touched

```
piassistant/src/main/java/com/sentient/WebServer.java               +275/-13
piassistant/src/main/java/com/sentient/service/GroqService.java       +7
piassistant/src/main/java/com/sentient/service/OpenClawService.java   +7
piassistant/src/main/resources/web/app.js                            +316
piassistant/src/main/resources/web/index.html                        +10
piassistant/src/main/resources/web/styles.css                        +48
native/macos/Package.swift                                            (new)
native/macos/Sources/SentientHelper/main.swift                        (new)
native/macos/SentientHelper.entitlements                              (new)
native/macos/README.md                                                (new)
native/macos/.gitignore                                               (new)
```

### Track-by-track outcome

| Track | Status | Notes |
|---|---|---|
| 1 — `VIEW_DEVICE` → vision | ✅ | Round-trip via `pendingScreenCaptures` + 30 s timeout, recurses into `handleChat` with the JPEG. |
| 2 — WebRTC live mirror | ✅ | `webrtc_offer/answer/ice_candidate` relay on server; `startWebRTCStream` / `handleWebRTCOffer` / answer / ICE on client; new `LIVE` button next to `VIEW SCREEN`. Consent-gated under `LIVE_STREAM`. |
| 3 — Browser-safe `remote_action` | ✅ | `OPEN_URL` and `SWITCH_PANEL` handlers; both gated by per-action consent. Server also relays `text`, `bundleId`, `keys`, `x`, `y`, and `fromDevice` to helpers. |
| 4 — Native macOS helper | ✅ | Swift PM project, `URLSessionWebSocketTask` client with reconnect, `CGEvent` typing/clicking/key combos, `NSWorkspace` launching, Accessibility-permission check at boot. Separate `/helper` WS so helpers don't pollute browser broadcasts. |
| 5 — Per-action consent | ✅ | `localStorage`-backed store, modal prompt, Settings → PERMISSIONS panel listing every grant with REVOKE buttons. |
| 6 — Bug fixes | ✅ | Calendar / Tasks / Spotify `window.open` URLs go through `api(...)` so they respect `sentient_masterHost`. `.gitignore` already excluded `user_profile.json`; no duplicate file present in the working tree. |

### Slight deviations from the blueprint

- Phase 3 doc called the receiver-side switch helper `showPanel(...)`; the
  function is named `openPanel(...)` in the codebase — I used the existing
  name. Same behavior.
- `screen_frame` already carried the captured frame in a field named `frame`
  (not `imageData` as the doc draft suggested). The Track 1 future resolution
  reads `frame` to match what the device actually sends.
- The doc's example `iceServers: []` is kept verbatim — works on Tailnet but
  will need a STUN entry if anyone ever runs this outside the tailnet.
- `KEY_COMBO` accepts `+`-separated key names on the wire (e.g.
  `cmd+space`). The helper splits and maps each token to either a CGEventFlag
  (`cmd`, `shift`, `alt`/`option`, `ctrl`, `fn`) or a virtual key code.

---

## How to test / see each feature

Everything below assumes:
- The master is running locally: `cd piassistant && mvn -DskipTests package && java -jar target/sentient-assistant-1.0-SNAPSHOT.jar` (or however you normally launch it).
- The web UI is open at `http://localhost:7070`.
- You're logged in and the WS shows `ONLINE`.

### 0. Smoke test — just confirm nothing regressed

1. Open the app, send a chat: "what time is it?". You should get a streamed reply.
2. Open Settings — confirm the new `PERMISSIONS` section appears under
   `DEVICES`. It should show the "No permissions granted yet…" hint.
3. Open the Spotify panel and click `RECONNECT SPOTIFY`. The OAuth window
   should open against whichever host `sentient_masterHost` points at (not
   hardcoded to `localhost`). Same check applies to Tasks/Calendar buttons.

### 1. `VIEW_DEVICE` — AI asks a device for its screen

1. Open the app in **two browser tabs** (or two devices on the same tailnet).
2. In Settings → DEVICES on each tab, set distinct device names (e.g. `Laptop`
   and `Phone`) and `SAVE NAME`. Both should appear in each tab's device list.
3. In the chat, ask: **"What's on my Laptop?"** (use the name you saved).
4. On the named device's tab, the browser will pop a screen-share consent
   dialog. Pick a window or screen and click "Share".
5. The frame round-trips to the server (you'll see no UI on the requester
   yet) and is fed straight into the vision model. The assistant streams back
   a description of what was on the captured screen.
6. Failure modes worth checking:
   - Decline the screen-share prompt → expect "Screen capture from 'Laptop'
     was declined." in chat.
   - Don't respond at all for 30 s → expect "...timed out."
   - Use a name that doesn't match any connected device → expect
     "Device 'Laptop' is not connected."

### 2. WebRTC live mirror

1. Same two-tab / two-device setup.
2. In tab A's Settings → DEVICES, click `LIVE` next to tab B's row.
3. Tab B should pop a consent modal: **"Share your screen live with …"**.
   Click `ALLOW`. (Optional: tick "Always allow…" to skip future prompts.)
4. The browser's screen-share picker appears on tab B. Choose a window/screen
   and click Share.
5. A new viewer window opens on tab A with the live video — should be
   sub-100 ms on a local tailnet.
6. Stop the stream by clicking macOS/Chrome's native "Stop sharing" pill OR
   by closing the viewer window — both should clean up the peer connection
   (no zombie connections in `webrtcPeerConnections`).
7. Re-test denying the consent → the offerer should see
   "The other device declined the live stream." in chat.

### 3. AI-driven remote actions (`REMOTE_OPEN`)

1. Two devices/tabs registered as `Laptop` and `Phone`.
2. From either tab's chat, type: **"Open anthropic.com on my Phone."**
3. On the `Phone` tab, a consent modal appears: *"master wants to: OPEN_URL.
   https://anthropic.com"*. Click `ALLOW` — the URL opens in a new tab.
4. Tick "Always allow" on the next request; subsequent
   "Open <url> on Phone" requests should pop the URL without prompting.
5. Revoke from Settings → PERMISSIONS → REVOKE; next request should prompt
   again.

### 4. Per-action consent store

1. Anywhere a remote action lands (Track 2/3 above), the modal offers
   "Always allow `<action>` from `<device>`".
2. After accepting once with the box ticked, the same (sender, action) pair
   becomes silent until you revoke.
3. Go to Settings → PERMISSIONS. Every stored grant appears with a `REVOKE`
   button; click it and the entry disappears. The localStorage key is
   `sentient_consent_<ACTION>_<from-device-lowercased>`.

### 5. Native macOS helper

You need a Mac with Xcode command-line tools (`swift --version`).

```bash
cd native/macos
swift build -c release
sudo cp .build/release/SentientHelper /usr/local/bin/sentient-helper

# Token = the one your master issued after login. Easiest way:
#   open the web UI's devtools → application → localStorage → sentient_token
SENTIENT_TOKEN=<your-token> sentient-helper \
    --host localhost:7070 \
    --name "My Mac"
```

First run will print a warning until you tick **System Settings → Privacy &
Security → Accessibility** for `sentient-helper`. After that:

1. In the master's chat: **"On My Mac, launch the bundle id `com.apple.Safari`."**
   Safari opens. (Backed by `[CMD:LAUNCH_APP:My Mac|com.apple.Safari]`.)
2. **"Type `Hello there` on My Mac."** → text appears in the focused app.
   (`[CMD:TYPE_TEXT:My Mac|Hello there]`)
3. **"Open Spotlight on My Mac."** → expect a `KEY_COMBO` with `cmd+space`.
4. **"Click 800, 600 on My Mac."** → mouse jumps and left-clicks.
5. Sever the master and observe the helper auto-reconnect with exponential
   backoff (1 s → 2 s → 4 s up to 30 s).
6. Inspect `[Helper] <ACTION> [...] → ok|fail` in the helper's stdout and
   `[Helper] <ACTION> → ok|fail` on the master's stdout for each round-trip.

### 6. Confirm Track 6 fixes

- In Settings, set `Master Server URL` to a *different* host on your tailnet
  (e.g. `100.x.y.z:7070`) and reload. Click `CONNECT GOOGLE CALENDAR`,
  `CONNECT GOOGLE TASKS`, and Spotify's `RECONNECT` — each should open the
  OAuth window against the *remote* host, not `localhost`.
- `git ls-files | grep user_profile.json` should be empty (it's git-ignored).

