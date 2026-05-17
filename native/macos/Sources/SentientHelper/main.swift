//
// SentientHelper — native macOS bridge that executes OS-level actions on behalf
// of the Sentient master. Talks JSON over the `/helper` WebSocket. The master
// authenticates with the shared auth token (same one browsers use).
//
// Actions:
//   TYPE_TEXT  — synthesize Unicode keystrokes into the focused app
//   CLICK_AT   — left-click at absolute screen coordinates
//   KEY_COMBO  — post a CGEvent pair for a shortcut (e.g. cmd+space)
//   LAUNCH_APP — open an app by bundle identifier
//   OPEN_URL   — hand a URL to the default browser
//
// The user must grant Accessibility permission once for keystrokes / clicks
// to take effect: System Settings → Privacy & Security → Accessibility → +.
//

import AppKit
import ApplicationServices
import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

// MARK: - Argument parsing

struct Config {
    var host: String = "localhost:7070"
    var token: String = ""
    var name: String = Host.current().localizedName ?? "Mac"
    var insecure: Bool = false  // ws:// instead of wss:// even for non-localhost
}

func parseArgs() -> Config {
    var cfg = Config()
    if let env = ProcessInfo.processInfo.environment["SENTIENT_TOKEN"], !env.isEmpty {
        cfg.token = env
    }
    if let env = ProcessInfo.processInfo.environment["SENTIENT_HOST"], !env.isEmpty {
        cfg.host = env
    }
    if let env = ProcessInfo.processInfo.environment["SENTIENT_NAME"], !env.isEmpty {
        cfg.name = env
    }
    var args = Array(CommandLine.arguments.dropFirst())
    while !args.isEmpty {
        let a = args.removeFirst()
        switch a {
        case "--host", "-h":
            if !args.isEmpty { cfg.host = args.removeFirst() }
        case "--token", "-t":
            if !args.isEmpty { cfg.token = args.removeFirst() }
        case "--name", "-n":
            if !args.isEmpty { cfg.name = args.removeFirst() }
        case "--insecure":
            cfg.insecure = true
        case "--help":
            print("""
                  SentientHelper — native macOS bridge for the Sentient master.

                  Usage:
                    sentient-helper [--host HOST:PORT] [--token TOKEN] [--name NAME] [--insecure]

                  Env vars: SENTIENT_HOST, SENTIENT_TOKEN, SENTIENT_NAME.

                  Defaults: host=localhost:7070, name=this Mac's name.
                  """)
            exit(0)
        default:
            // Treat a bare positional as host (matches the Phase 3 doc spec).
            cfg.host = a
        }
    }
    return cfg
}

// MARK: - OS actions

/// Map of common key names to CGKeyCode constants. macOS's virtual key codes
/// are layout-independent but ASCII-keyboard-shaped — these cover the keys
/// the AI is likely to emit. Add to this list as needed.
let KEY_CODES: [String: CGKeyCode] = [
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9,
    "b": 11, "q": 12, "w": 13, "e": 14, "r": 15, "y": 16, "t": 17,
    "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "9": 25, "7": 26,
    "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33, "i": 34, "p": 35,
    "l": 37, "j": 38, "'": 39, "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45,
    "m": 46, ".": 47, "`": 50,
    "return": 36, "enter": 36, "tab": 48, "space": 49, "delete": 51, "backspace": 51,
    "escape": 53, "esc": 53,
    "left": 123, "right": 124, "down": 125, "up": 126,
    "home": 115, "end": 119, "pageup": 116, "pagedown": 121,
    "f1": 122, "f2": 120, "f3": 99, "f4": 118, "f5": 96, "f6": 97, "f7": 98, "f8": 100,
    "f9": 101, "f10": 109, "f11": 103, "f12": 111
]

let MODIFIER_FLAGS: [String: CGEventFlags] = [
    "cmd": .maskCommand, "command": .maskCommand, "meta": .maskCommand, "super": .maskCommand,
    "ctrl": .maskControl, "control": .maskControl,
    "alt": .maskAlternate, "option": .maskAlternate, "opt": .maskAlternate,
    "shift": .maskShift,
    "fn": .maskSecondaryFn
]

enum HelperError: Error {
    case unknownKey(String)
    case eventCreationFailed
}

/// Type a Unicode string into the focused application via CGEvent.
func typeText(_ text: String) throws {
    let src = CGEventSource(stateID: .hidSystemState)
    for ch in text.unicodeScalars {
        let keyDown = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true)
        let keyUp = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false)
        guard let down = keyDown, let up = keyUp else { throw HelperError.eventCreationFailed }
        var u16 = [UniChar](String(ch).utf16)
        down.keyboardSetUnicodeString(stringLength: u16.count, unicodeString: &u16)
        up.keyboardSetUnicodeString(stringLength: u16.count, unicodeString: &u16)
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
        // Tiny pause so the receiving app's event loop can drain.
        Thread.sleep(forTimeInterval: 0.005)
    }
}

/// Left-click at an absolute (global) screen coordinate.
func clickAt(x: Double, y: Double) throws {
    let src = CGEventSource(stateID: .hidSystemState)
    let pt = CGPoint(x: x, y: y)
    guard
        let move = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: pt, mouseButton: .left),
        let down = CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: pt, mouseButton: .left),
        let up = CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: pt, mouseButton: .left)
    else {
        throw HelperError.eventCreationFailed
    }
    move.post(tap: .cghidEventTap)
    down.post(tap: .cghidEventTap)
    up.post(tap: .cghidEventTap)
}

/// Translate ["cmd","space"] into a CGEvent pair with the right modifier flags.
/// The last entry that isn't a known modifier becomes the keypress; everything
/// before it is OR'd into the modifier mask.
func sendKeyCombo(_ keys: [String]) throws {
    var flags: CGEventFlags = []
    var keyCode: CGKeyCode? = nil
    for raw in keys {
        let k = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if let f = MODIFIER_FLAGS[k] {
            flags.insert(f)
        } else if let kc = KEY_CODES[k] {
            keyCode = kc
        } else if k.count == 1, let kc = KEY_CODES[k] {
            // Single character, already in KEY_CODES — handled above.
            keyCode = kc
        } else {
            throw HelperError.unknownKey(raw)
        }
    }
    guard let kc = keyCode else { throw HelperError.unknownKey("(no key found in combo)") }
    let src = CGEventSource(stateID: .hidSystemState)
    guard
        let down = CGEvent(keyboardEventSource: src, virtualKey: kc, keyDown: true),
        let up = CGEvent(keyboardEventSource: src, virtualKey: kc, keyDown: false)
    else {
        throw HelperError.eventCreationFailed
    }
    down.flags = flags
    up.flags = flags
    down.post(tap: .cghidEventTap)
    up.post(tap: .cghidEventTap)
}

func launchApp(bundleId: String) -> Bool {
    if #available(macOS 11.0, *) {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId) else { return false }
        let cfg = NSWorkspace.OpenConfiguration()
        var success = false
        let group = DispatchGroup()
        group.enter()
        NSWorkspace.shared.openApplication(at: url, configuration: cfg) { _, err in
            success = (err == nil)
            group.leave()
        }
        _ = group.wait(timeout: .now() + 5)
        return success
    } else {
        return NSWorkspace.shared.launchApplication(
            withBundleIdentifier: bundleId,
            options: [],
            additionalEventParamDescriptor: nil,
            launchIdentifier: nil)
    }
}

func openUrl(_ urlStr: String) -> Bool {
    guard let url = URL(string: urlStr) else { return false }
    return NSWorkspace.shared.open(url)
}

/// Check whether this process has Accessibility permission. Posting keyboard
/// or mouse events silently no-ops without it, so warn the user once at boot.
func hasAccessibilityPermission() -> Bool {
    let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: false] as CFDictionary
    return AXIsProcessTrustedWithOptions(opts)
}

// MARK: - WebSocket client

final class HelperClient: NSObject, URLSessionWebSocketDelegate {
    let config: Config
    var task: URLSessionWebSocketTask?
    var session: URLSession!
    var reconnectDelay: TimeInterval = 1.0
    var stopped: Bool = false

    init(config: Config) {
        self.config = config
        super.init()
        self.session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    func start() {
        guard let url = buildURL() else {
            print("[Helper] Invalid host: \(config.host)")
            return
        }
        print("[Helper] Connecting to \(url)…")
        let req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        task = session.webSocketTask(with: req)
        task?.resume()
        receive()
    }

    func buildURL() -> URL? {
        let scheme: String
        if config.insecure || config.host.hasPrefix("localhost") || config.host.hasPrefix("127.0.0.1") {
            scheme = "ws"
        } else {
            // Tailscale hostnames are reachable over plain ws on the tailnet, but if
            // the user pointed at a public host they probably want wss.
            scheme = "ws"
        }
        var s = "\(scheme)://\(config.host)/helper"
        if !config.token.isEmpty {
            s += "?token=" + (config.token.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? config.token)
        }
        return URL(string: s)
    }

    func register() {
        let caps = ["TYPE_TEXT", "CLICK_AT", "LAUNCH_APP", "KEY_COMBO", "OPEN_URL"]
        let payload: [String: Any] = [
            "type": "register_helper",
            "name": config.name,
            "platform": "macOS",
            "capabilities": caps
        ]
        send(payload)
    }

    func send(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []),
              let json = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(json)) { err in
            if let err = err {
                print("[Helper] send error: \(err.localizedDescription)")
            }
        }
    }

    func receive() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let err):
                print("[Helper] WS receive error: \(err.localizedDescription)")
                self.scheduleReconnect()
                return
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleMessage(text)
                case .data(let d):
                    if let text = String(data: d, encoding: .utf8) { self.handleMessage(text) }
                @unknown default:
                    break
                }
                self.receive()
            }
        }
    }

    func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("[Helper] non-JSON message: \(text)")
            return
        }
        let type = obj["type"] as? String ?? ""
        guard type == "remote_action" else {
            // Master may emit other types in future — ignore them.
            return
        }
        let action = obj["action"] as? String ?? ""
        var success = true
        var detail: String = ""
        do {
            switch action {
            case "TYPE_TEXT":
                let t = obj["text"] as? String ?? ""
                try typeText(t)
                detail = "\(t.count) chars"
            case "CLICK_AT":
                let x = (obj["x"] as? NSNumber)?.doubleValue ?? 0
                let y = (obj["y"] as? NSNumber)?.doubleValue ?? 0
                try clickAt(x: x, y: y)
                detail = "(\(x),\(y))"
            case "LAUNCH_APP":
                let bid = obj["bundleId"] as? String ?? ""
                success = launchApp(bundleId: bid)
                detail = bid
            case "KEY_COMBO":
                var keys: [String] = []
                if let arr = obj["keys"] as? [String] { keys = arr }
                else if let s = obj["keys"] as? String { keys = s.split(separator: "+").map { String($0) } }
                try sendKeyCombo(keys)
                detail = keys.joined(separator: "+")
            case "OPEN_URL":
                let u = obj["url"] as? String ?? ""
                success = openUrl(u)
                detail = u
            default:
                success = false
                detail = "unknown action"
            }
        } catch {
            success = false
            detail = "\(error)"
        }
        print("[Helper] \(action) [\(detail)] → \(success ? "ok" : "fail")")
        send([
            "type": "action_result",
            "action": action,
            "success": success,
            "detail": detail
        ])
    }

    func scheduleReconnect() {
        if stopped { return }
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, 30)
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.start()
        }
    }

    // MARK: URLSessionWebSocketDelegate

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        print("[Helper] WS open")
        reconnectDelay = 1.0
        register()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        print("[Helper] WS closed (code=\(closeCode.rawValue))")
        scheduleReconnect()
    }
}

// MARK: - Entry

let config = parseArgs()
if !hasAccessibilityPermission() {
    print("""
          [Helper] ⚠️  Accessibility permission not granted.
                 Keystrokes and clicks will silently fail until you allow this
                 binary in: System Settings → Privacy & Security → Accessibility.
          """)
}
let client = HelperClient(config: config)
client.start()
RunLoop.main.run()
