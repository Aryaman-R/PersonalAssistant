# Cross-device screen access & remote control

This document explains:
1. **What ships in this iteration** — on-demand single-frame screen snapshot from any
   browser tab to any other browser tab, both logged into the same Sentient master.
2. **What was investigated but NOT built** — full continuous screen mirroring and
   true OS-level remote control (clicks, keystrokes, "open Claude").
3. **A concrete path** to finish the rest.

---

## 1. What works today

### Device registry
Every browser tab that connects to the master announces itself via a
`register_device` WebSocket message:

```jsonc
{
  "type": "register_device",
  "name": "Aryaman's Mac",       // editable in Settings → CONNECTED DEVICES
  "platform": "MacIntel",
  "capabilities": ["screen-capture-getDisplayMedia"]
}
```

The master keeps a session-scoped map in `WebServer.deviceMeta`, indexed by
WebSocket sessionId. On disconnect, the entry is removed. The list is exposed:

- via WebSocket — `{type:"device_list", devices:[...]}` broadcast after any change
- via REST — `GET /api/devices`

Users see this list under **Settings → CONNECTED DEVICES**.

### Single-frame snapshot

Click **VIEW SCREEN** next to a remote device. The flow:

```
Tab A                         Master (Java)                  Tab B
  │                                │                            │
  │  request_screen(target=B, id) ─┼─►                          │
  │                                │  capture_screen(id) ──────►│
  │                                │                            │ getDisplayMedia()
  │                                │                            │ user clicks "allow"
  │                                │  ◄── screen_frame(jpeg)    │
  │  ◄── screen_frame(jpeg) ───────┤                            │
  │                                │                            │
```

Tab B uses the browser's standard
[`navigator.mediaDevices.getDisplayMedia()`](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getDisplayMedia)
API, which prompts the user before capturing anything. After capture the stream is
stopped immediately; only one JPEG (~60% quality) flows over the WS.

Tab A renders the JPEG in a new window. This works on every modern desktop
browser. **iOS Safari** does not support `getDisplayMedia` — those devices remain
listed in the device registry but the **VIEW SCREEN** button will return an
empty frame.

### Feeding the snapshot to the assistant
A user can ask the assistant "look at my Mac's screen" today by:
1. Taking a snapshot of Mac → window opens with JPEG.
2. Dragging or pasting that JPEG into the chat → existing image upload path
   already feeds it to the vision model (Groq scout or OpenClaw's selected
   provider).

Wiring this to a single voice command (e.g. "Jarvis, what's on my Mac?")
requires adding a server-side command handler — see §3 below.

## 2. What we did NOT build, and why

### Continuous screen mirroring (low-latency live view)
A snapshot is one HTTP/WS message. A live mirror requires:
- **WebRTC** peer connections between browsers, or
- A relay through the master that handles tens of MB/sec of video.

WebRTC is the right answer — it stays browser-to-browser, no relay bandwidth,
sub-100ms latency. But it adds:
- A signalling channel (we can extend the existing WS),
- STUN/TURN configuration (Tailscale would make this trivial since both ends
  are already on the tailnet),
- Negotiation logic (offer/answer/ICE candidates),
- Permission UX (the receiving user still has to grant the *initial* prompt;
  once granted, WebRTC streams without further prompts).

Estimated implementation: ~400 LOC server, ~300 LOC frontend, plus testing on
each browser. Out of scope for this pass.

### True OS-level remote control (clicks, typed keystrokes, "open Claude")
This was the most ambitious ask. Web browsers are **deliberately sandboxed**:
JavaScript inside a tab cannot:
- Click on something outside that tab,
- Type into another application,
- Launch a different application.

There is no browser API that can do this. The user's clarification — "search
this on Google, open Claude" — needs **native helpers** running on each
controlled device.

Concrete architecture if you decide to build it:

```
Browser tab (Sentient UI)
        │  WS
        ▼
   Sentient master (Java)
        │  WS or local socket
        ▼
   "Sentient Agent" native helper (per OS)
        ├── macOS:    Swift CLI using Accessibility / CGEventPost / NSWorkspace   (shipped → native/macos)
        ├── Windows:  C# / .NET 8 using SendInput / ShellExecute                  (shipped → native/windows)
        ├── Linux/X:  xdotool / wmctrl
        ├── Linux/Wayland: wtype / ydotool
        └── Android:  ADB shell over USB, or a Tasker-bridge app
```

You'd publish the helper as a small signed binary the user runs once per
device. The helper would:
- Open a localhost WS back to the master (auth'd via a device-pairing code).
- Accept `remote_action` messages: `OPEN_URL`, `TYPE_TEXT`, `CLICK_AT`, etc.
- Refuse anything not in an allow-list configured by the user.

This is non-trivial. Roughly: 2–4 weeks of focused work, OS-by-OS.

### What the `remote_action` WS stub does today
The master already accepts and forwards `remote_action` messages between
sessions — but the receiving browser tab cannot inject anything outside itself.
Two safe, in-browser actions ARE implementable on top of this stub:
- **OPEN_URL** → receiving tab calls `window.open(url)` (subject to popup
  blocker; works inside the Sentient web app's origin).
- **SWITCH_PANEL** → already exists via the `[CMD:SWITCH_*]` mechanism.

Anything that touches the OS shell is gated on the native-helper work above.

## 3. Roadmap to "the assistant sees and controls my devices"

Order of operations if you want to finish this:

1. **Server command for snapshot-as-vision**: Add `[CMD:VIEW_DEVICE:<name>]` to
   the LLM system prompt. Server-side handler:
   - Looks up `deviceMeta` for that name.
   - Sends `capture_screen` to that session.
   - Awaits `screen_frame` (with a 30s timeout).
   - Pipes the JPEG into the next vision call automatically.
   This makes "look at my Mac's screen and tell me what's on it" work without
   the user manually dragging an image.

2. **WebRTC live mirror**: Promote the request/response pair to a peer
   connection. Reuse the device registry; add `webrtc_offer` / `webrtc_answer`
   / `ice_candidate` WS message types as a pure signalling relay. On a Tailnet,
   no TURN server is needed.

3. **Browser-safe `remote_action` actions**: Implement `OPEN_URL` and basic
   panel switching first — those work today without any native helper.

4. **Native helper for OS control**: This is the big one. macOS (Swift CLI
   gated on Accessibility permission) and Windows (.NET 8 console app using
   `SendInput` / `ShellExecute`, no permission prompt) both ship today —
   see `native/macos/` and `native/windows/`. Both speak the same wire
   protocol over `/helper`. Linux (X11 / Wayland) and mobile are still TBD.

5. **Consent model**: Build a per-action consent screen on the controlled
   device. Even with a native helper, every novel action category should
   prompt the user the first time and offer an "always allow" toggle scoped
   by sender device + action type. This is the single biggest reason
   commercial RDP-style tools have a poor reputation — they don't bother with
   per-action consent.

## 4. What you can demo today

| Scenario | Works? |
|---|---|
| See all my logged-in devices in Settings | ✅ |
| Click VIEW SCREEN on my phone from my laptop → see one snapshot | ✅ (Android Chrome OK; iOS Safari no) |
| Drag that snapshot into the chat, ask "what is this?" | ✅ (uses existing vision flow) |
| Continuous live mirror (multi-second video stream) | ❌ — needs WebRTC |
| "Jarvis, what's on my Mac right now?" — automatic | ❌ — needs the §3.1 command wiring |
| "Open Claude on my Mac" / "type X into the focused app" | ✅ on macOS + Windows with the helper installed (`native/macos/`, `native/windows/`) |

## 5. Security posture

- All screen-share requests cross the same WebSocket the user is already
  authenticated on (shared-password gate). A device that isn't logged in can't
  see or request anything.
- `getDisplayMedia` always prompts the user on the *captured* device — there
  is no silent capture.
- Captured frames are JPEG-encoded base64, relayed exactly once through the
  master, not stored.
- The `remote_action` WS stub deliberately has no OS-level effect. Until a
  native helper exists, the worst it can do is open URLs inside the Sentient
  web app.

## 6. References

- `WebServer.java`: `setupWebSocket()` — message routing (`register_device`,
  `request_screen`, `screen_frame`, `remote_action`).
- `app.js`: `registerDevice`, `requestScreenSnapshot`, `handleCaptureScreen`,
  `handleWSMessage` (`device_list` / `capture_screen` / `screen_frame` cases).
- `index.html`: `#settingsDevices` panel.
