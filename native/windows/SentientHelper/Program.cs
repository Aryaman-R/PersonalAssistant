//
// SentientHelper — native Windows bridge that executes OS-level actions on
// behalf of the Sentient master. Talks JSON over the `/helper` WebSocket,
// authenticating with the same shared auth token the browsers use.
//
// Actions:
//   TYPE_TEXT  — synthesize Unicode keystrokes into the focused app
//   CLICK_AT   — left-click at absolute screen coordinates
//   KEY_COMBO  — post a SendInput pair for a shortcut (e.g. ctrl+space)
//   LAUNCH_APP — open an app by exe name / full path / AppUserModelID
//   OPEN_URL   — hand a URL to the default browser
//
// Unlike macOS, Windows has no single "Accessibility" gate — SendInput works
// for any interactive user session. Running elevated is only required if you
// want to drive other elevated windows (UIPI restriction).
//

using System.Diagnostics;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace SentientHelper;

internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        // Per-monitor v2 DPI so CLICK_AT coordinates land where the caller expects
        // on high-DPI / multi-monitor setups. Best-effort: older builds of Win10
        // will silently ignore an unknown context handle.
        try { NativeMethods.SetProcessDpiAwarenessContext(NativeMethods.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2); }
        catch { /* not available — fine */ }

        var config = Config.Parse(args);
        if (config.ShowHelp)
        {
            PrintHelp();
            return 0;
        }

        var client = new HelperClient(config);
        var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

        await client.RunAsync(cts.Token);
        return 0;
    }

    private static void PrintHelp()
    {
        Console.WriteLine("""
            SentientHelper — native Windows bridge for the Sentient master.

            Usage:
              sentient-helper [--host HOST:PORT] [--token TOKEN] [--name NAME] [--insecure]

            Env vars: SENTIENT_HOST, SENTIENT_TOKEN, SENTIENT_NAME.

            Defaults: host=localhost:7070, name=this computer's name.
            """);
    }
}

// ---------- Config ---------------------------------------------------------

internal sealed class Config
{
    public string Host { get; set; } = "localhost:7070";
    public string Token { get; set; } = "";
    public string Name { get; set; } = Environment.MachineName;
    public bool Insecure { get; set; }
    public bool ShowHelp { get; set; }

    public static Config Parse(string[] args)
    {
        var cfg = new Config();
        if (Environment.GetEnvironmentVariable("SENTIENT_TOKEN") is { Length: > 0 } t) cfg.Token = t;
        if (Environment.GetEnvironmentVariable("SENTIENT_HOST") is { Length: > 0 } h) cfg.Host = h;
        if (Environment.GetEnvironmentVariable("SENTIENT_NAME") is { Length: > 0 } n) cfg.Name = n;

        for (var i = 0; i < args.Length; i++)
        {
            var a = args[i];
            string? Next() => (i + 1 < args.Length) ? args[++i] : null;
            switch (a)
            {
                case "--host" or "-h":
                    if (Next() is { } host) cfg.Host = host;
                    break;
                case "--token" or "-t":
                    if (Next() is { } tok) cfg.Token = tok;
                    break;
                case "--name" or "-n":
                    if (Next() is { } name) cfg.Name = name;
                    break;
                case "--insecure":
                    cfg.Insecure = true;
                    break;
                case "--help" or "/?":
                    cfg.ShowHelp = true;
                    break;
                default:
                    // Bare positional → treat as host (matches macOS helper).
                    cfg.Host = a;
                    break;
            }
        }
        return cfg;
    }
}

// ---------- WebSocket client ----------------------------------------------

internal sealed class HelperClient
{
    private readonly Config _config;
    private TimeSpan _reconnectDelay = TimeSpan.FromSeconds(1);

    public HelperClient(Config config) { _config = config; }

    public async Task RunAsync(CancellationToken stopToken)
    {
        while (!stopToken.IsCancellationRequested)
        {
            try
            {
                await ConnectAndPumpAsync(stopToken);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"[Helper] WS error: {ex.Message}");
            }

            if (stopToken.IsCancellationRequested) break;
            var delay = _reconnectDelay;
            _reconnectDelay = TimeSpan.FromSeconds(Math.Min(_reconnectDelay.TotalSeconds * 2, 30));
            Console.WriteLine($"[Helper] Reconnecting in {delay.TotalSeconds:0}s…");
            try { await Task.Delay(delay, stopToken); }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task ConnectAndPumpAsync(CancellationToken stopToken)
    {
        var url = BuildUri();
        Console.WriteLine($"[Helper] Connecting to {url}…");
        using var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
        await ws.ConnectAsync(url, stopToken);
        Console.WriteLine("[Helper] WS open");
        _reconnectDelay = TimeSpan.FromSeconds(1);

        await SendRegisterAsync(ws, stopToken);

        var buffer = new byte[64 * 1024];
        var pending = new MemoryStream();
        while (ws.State == WebSocketState.Open && !stopToken.IsCancellationRequested)
        {
            pending.SetLength(0);
            WebSocketReceiveResult result;
            do
            {
                result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), stopToken);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    Console.WriteLine($"[Helper] WS closed by peer (code={(int?)result.CloseStatus})");
                    return;
                }
                pending.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            var text = Encoding.UTF8.GetString(pending.GetBuffer(), 0, (int)pending.Length);
            await HandleMessageAsync(ws, text, stopToken);
        }
    }

    private Uri BuildUri()
    {
        // Same scheme policy as macOS helper: prefer ws://; --insecure or a
        // localhost host always uses ws://. For non-localhost hosts the user
        // can put the master behind Tailscale (still ws on the tailnet) or
        // upgrade this to wss:// here once a TLS cert is wired up.
        var scheme = "ws";
        var sb = new StringBuilder();
        sb.Append(scheme).Append("://").Append(_config.Host).Append("/helper");
        if (!string.IsNullOrEmpty(_config.Token))
        {
            sb.Append("?token=").Append(Uri.EscapeDataString(_config.Token));
        }
        return new Uri(sb.ToString());
    }

    private Task SendRegisterAsync(ClientWebSocket ws, CancellationToken ct)
    {
        var payload = new JsonObject
        {
            ["type"] = "register_helper",
            ["name"] = _config.Name,
            ["platform"] = "Windows",
            ["capabilities"] = new JsonArray("TYPE_TEXT", "CLICK_AT", "LAUNCH_APP", "KEY_COMBO", "OPEN_URL"),
        };
        return SendJsonAsync(ws, payload, ct);
    }

    private static async Task SendJsonAsync(ClientWebSocket ws, JsonObject obj, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(obj.ToJsonString());
        await ws.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, ct);
    }

    private static async Task HandleMessageAsync(ClientWebSocket ws, string text, CancellationToken ct)
    {
        JsonObject? msg;
        try { msg = JsonNode.Parse(text)?.AsObject(); }
        catch (JsonException)
        {
            Console.Error.WriteLine($"[Helper] non-JSON message: {text}");
            return;
        }
        if (msg is null) return;

        var type = msg["type"]?.GetValue<string>() ?? "";
        if (type != "remote_action") return;  // ignore unknown message types

        var action = msg["action"]?.GetValue<string>() ?? "";
        var success = true;
        var detail = "";
        try
        {
            switch (action)
            {
                case "TYPE_TEXT":
                {
                    var t = msg["text"]?.GetValue<string>() ?? "";
                    Actions.TypeText(t);
                    detail = $"{t.Length} chars";
                    break;
                }
                case "CLICK_AT":
                {
                    var x = msg["x"]?.GetValue<double>() ?? 0;
                    var y = msg["y"]?.GetValue<double>() ?? 0;
                    Actions.ClickAt((int)Math.Round(x), (int)Math.Round(y));
                    detail = $"({x},{y})";
                    break;
                }
                case "LAUNCH_APP":
                {
                    var bid = msg["bundleId"]?.GetValue<string>() ?? "";
                    success = Actions.LaunchApp(bid);
                    detail = bid;
                    break;
                }
                case "KEY_COMBO":
                {
                    var keys = ParseKeys(msg["keys"]);
                    Actions.SendKeyCombo(keys);
                    detail = string.Join("+", keys);
                    break;
                }
                case "OPEN_URL":
                {
                    var u = msg["url"]?.GetValue<string>() ?? "";
                    success = Actions.OpenUrl(u);
                    detail = u;
                    break;
                }
                default:
                    success = false;
                    detail = "unknown action";
                    break;
            }
        }
        catch (Exception ex)
        {
            success = false;
            detail = ex.Message;
        }

        Console.WriteLine($"[Helper] {action} [{detail}] → {(success ? "ok" : "fail")}");
        var reply = new JsonObject
        {
            ["type"] = "action_result",
            ["action"] = action,
            ["success"] = success,
            ["detail"] = detail,
        };
        await SendJsonAsync(ws, reply, ct);
    }

    private static List<string> ParseKeys(JsonNode? node)
    {
        if (node is JsonArray arr)
        {
            var list = new List<string>(arr.Count);
            foreach (var item in arr)
            {
                if (item is not null) list.Add(item.GetValue<string>());
            }
            return list;
        }
        if (node is JsonValue v && v.TryGetValue<string>(out var s))
        {
            return s.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
        }
        return new List<string>();
    }
}

// ---------- OS actions -----------------------------------------------------

internal static class Actions
{
    /// <summary>Type Unicode text into the focused window via SendInput.</summary>
    public static void TypeText(string text)
    {
        // SendInput's KEYEVENTF_UNICODE path takes a UTF-16 code unit per event.
        // For characters in the supplementary planes (emoji etc.) we let the
        // surrogate pair flow through as two consecutive events; Windows
        // reassembles them on the receiving side.
        var inputs = new List<NativeMethods.INPUT>(text.Length * 2);
        foreach (var ch in text)
        {
            inputs.Add(MakeKeyboardUnicode(ch, keyUp: false));
            inputs.Add(MakeKeyboardUnicode(ch, keyUp: true));
        }
        SendInputBatched(inputs);
    }

    /// <summary>Move cursor and left-click at absolute screen coordinates.</summary>
    public static void ClickAt(int x, int y)
    {
        NativeMethods.SetCursorPos(x, y);
        var inputs = new List<NativeMethods.INPUT>(2)
        {
            MakeMouse(NativeMethods.MOUSEEVENTF_LEFTDOWN),
            MakeMouse(NativeMethods.MOUSEEVENTF_LEFTUP),
        };
        SendInputBatched(inputs);
    }

    /// <summary>Translate ["ctrl","c"] into a SendInput sequence (modifiers held around the key).</summary>
    public static void SendKeyCombo(IReadOnlyList<string> keys)
    {
        var modifiers = new List<ushort>();
        ushort? keyVk = null;
        foreach (var raw in keys)
        {
            var k = raw.Trim().ToLowerInvariant();
            if (KeyMap.Modifiers.TryGetValue(k, out var mvk)) modifiers.Add(mvk);
            else if (KeyMap.Keys.TryGetValue(k, out var kvk)) keyVk = kvk;
            else if (k.Length == 1 && KeyMap.TryCharVk(k[0], out var cvk)) keyVk = cvk;
            else throw new InvalidOperationException($"unknown key: {raw}");
        }
        if (keyVk is null) throw new InvalidOperationException("no non-modifier key in combo");

        var inputs = new List<NativeMethods.INPUT>(modifiers.Count * 2 + 2);
        foreach (var m in modifiers) inputs.Add(MakeKeyboardVk(m, keyUp: false));
        inputs.Add(MakeKeyboardVk(keyVk.Value, keyUp: false));
        inputs.Add(MakeKeyboardVk(keyVk.Value, keyUp: true));
        // Release modifiers in reverse order — same order keyboards report.
        for (var i = modifiers.Count - 1; i >= 0; i--) inputs.Add(MakeKeyboardVk(modifiers[i], keyUp: true));
        SendInputBatched(inputs);
    }

    /// <summary>Launch an app: handles plain exe names, full paths, and AppUserModelIDs.</summary>
    public static bool LaunchApp(string bundleId)
    {
        if (string.IsNullOrWhiteSpace(bundleId)) return false;
        try
        {
            // AppUserModelIDs always contain '!' (e.g. "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App").
            // explorer.exe interprets shell:AppsFolder\<AUMID> as a launch verb for UWP / packaged apps.
            if (bundleId.Contains('!'))
            {
                var psi = new ProcessStartInfo("explorer.exe", $"shell:AppsFolder\\{bundleId}")
                {
                    UseShellExecute = true
                };
                using var p = Process.Start(psi);
                return p is not null;
            }
            else
            {
                var psi = new ProcessStartInfo(bundleId) { UseShellExecute = true };
                using var p = Process.Start(psi);
                return p is not null;
            }
        }
        catch
        {
            return false;
        }
    }

    public static bool OpenUrl(string url)
    {
        if (string.IsNullOrWhiteSpace(url)) return false;
        try
        {
            var psi = new ProcessStartInfo(url) { UseShellExecute = true };
            using var p = Process.Start(psi);
            return p is not null;
        }
        catch
        {
            return false;
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static NativeMethods.INPUT MakeKeyboardUnicode(char ch, bool keyUp)
    {
        var flags = NativeMethods.KEYEVENTF_UNICODE | (keyUp ? NativeMethods.KEYEVENTF_KEYUP : 0u);
        return new NativeMethods.INPUT
        {
            type = NativeMethods.INPUT_KEYBOARD,
            U = new NativeMethods.InputUnion
            {
                ki = new NativeMethods.KEYBDINPUT
                {
                    wVk = 0,
                    wScan = ch,
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero,
                }
            }
        };
    }

    private static NativeMethods.INPUT MakeKeyboardVk(ushort vk, bool keyUp)
    {
        var flags = keyUp ? NativeMethods.KEYEVENTF_KEYUP : 0u;
        // Extended-key flag is required for arrows, INS/DEL/HOME/END/PGUP/PGDN, etc.,
        // otherwise some apps see the wrong key (the numpad twin).
        if (KeyMap.ExtendedKeys.Contains(vk)) flags |= NativeMethods.KEYEVENTF_EXTENDEDKEY;
        return new NativeMethods.INPUT
        {
            type = NativeMethods.INPUT_KEYBOARD,
            U = new NativeMethods.InputUnion
            {
                ki = new NativeMethods.KEYBDINPUT
                {
                    wVk = vk,
                    wScan = (ushort)NativeMethods.MapVirtualKey(vk, NativeMethods.MAPVK_VK_TO_VSC),
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero,
                }
            }
        };
    }

    private static NativeMethods.INPUT MakeMouse(uint flags)
    {
        return new NativeMethods.INPUT
        {
            type = NativeMethods.INPUT_MOUSE,
            U = new NativeMethods.InputUnion
            {
                mi = new NativeMethods.MOUSEINPUT
                {
                    dx = 0, dy = 0, mouseData = 0,
                    dwFlags = flags, time = 0, dwExtraInfo = IntPtr.Zero,
                }
            }
        };
    }

    private static void SendInputBatched(List<NativeMethods.INPUT> inputs)
    {
        if (inputs.Count == 0) return;
        var arr = inputs.ToArray();
        var sent = NativeMethods.SendInput((uint)arr.Length, arr, Marshal.SizeOf<NativeMethods.INPUT>());
        if (sent != (uint)arr.Length)
        {
            var err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"SendInput partial: {sent}/{arr.Length} (err {err})");
        }
    }
}

// ---------- Key tables -----------------------------------------------------

internal static class KeyMap
{
    public static readonly Dictionary<string, ushort> Modifiers = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ctrl"] = 0x11, ["control"] = 0x11,
        ["alt"] = 0x12, ["option"] = 0x12, ["opt"] = 0x12,
        ["shift"] = 0x10,
        // macOS's "cmd"/"meta"/"super" → Windows key.
        ["win"] = 0x5B, ["super"] = 0x5B, ["cmd"] = 0x5B, ["command"] = 0x5B, ["meta"] = 0x5B,
    };

    public static readonly Dictionary<string, ushort> Keys = new(StringComparer.OrdinalIgnoreCase)
    {
        ["return"] = 0x0D, ["enter"] = 0x0D,
        ["tab"] = 0x09, ["space"] = 0x20,
        ["escape"] = 0x1B, ["esc"] = 0x1B,
        ["backspace"] = 0x08, ["delete"] = 0x2E,
        ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,
        ["home"] = 0x24, ["end"] = 0x23, ["pageup"] = 0x21, ["pagedown"] = 0x22, ["insert"] = 0x2D,
        ["f1"] = 0x70, ["f2"] = 0x71, ["f3"] = 0x72, ["f4"] = 0x73,
        ["f5"] = 0x74, ["f6"] = 0x75, ["f7"] = 0x76, ["f8"] = 0x77,
        ["f9"] = 0x78, ["f10"] = 0x79, ["f11"] = 0x7A, ["f12"] = 0x7B,
    };

    /// <summary>VKs that need the extended-key flag on SendInput.</summary>
    public static readonly HashSet<ushort> ExtendedKeys = new()
    {
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, // pgup, pgdn, end, home, arrows, ins, del
        0x5B, // left Win
    };

    public static bool TryCharVk(char c, out ushort vk)
    {
        if (c is >= 'a' and <= 'z') { vk = (ushort)(c - 'a' + 'A'); return true; }
        if (c is >= 'A' and <= 'Z') { vk = c; return true; }
        if (c is >= '0' and <= '9') { vk = c; return true; }
        vk = 0;
        return false;
    }
}

// ---------- P/Invoke -------------------------------------------------------

internal static class NativeMethods
{
    public const uint INPUT_MOUSE = 0;
    public const uint INPUT_KEYBOARD = 1;

    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const uint KEYEVENTF_UNICODE = 0x0004;
    public const uint KEYEVENTF_EXTENDEDKEY = 0x0001;

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;

    public const uint MAPVK_VK_TO_VSC = 0;

    public static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = new(-4);

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    public static extern uint MapVirtualKey(uint uCode, uint uMapType);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);
}
