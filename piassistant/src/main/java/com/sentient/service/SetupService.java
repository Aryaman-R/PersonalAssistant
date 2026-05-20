package com.sentient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Backs the in-UI setup wizard. Prereq probes, .env reads/writes, installer
 * runners for OpenClaw / Vosk / Tailscale / native helper.
 *
 * <p>Every long-running step takes a {@link ProgressSink} that the caller wires
 * to a WebSocket broadcast — that way the UI shows live progress, not a frozen
 * spinner.
 *
 * <p>All shell-outs use {@code ProcessBuilder} with argv arrays. No {@code
 * bash -c "$user_input"} — installer URLs are hard-coded constants.
 */
public class SetupService {

    public interface ProgressSink {
        /**
         * Emit a progress line. {@code phase} is a short tag like "openclaw",
         * "vosk", "tailscale". {@code level} is "info" / "ok" / "warn" / "err".
         */
        void emit(String phase, String level, String line);

        /** Signal the phase ended. {@code success} is the final outcome. */
        void done(String phase, boolean success, String summary);
    }

    /** No-op sink for synchronous probes. */
    public static final ProgressSink SILENT = new ProgressSink() {
        @Override public void emit(String phase, String level, String line) {}
        @Override public void done(String phase, boolean success, String summary) {}
    };

    private static final String OPENCLAW_INSTALL_URL  = "https://openclaw.ai/install.sh";
    private static final String TAILSCALE_INSTALL_URL = "https://tailscale.com/install.sh";
    private static final String VOSK_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String VOSK_MODEL_NAME = "vosk-model-small-en-us-0.15";

    /**
     * Whitelisted .env keys, in the order the wizard surfaces them. We keep a
     * LinkedHashSet so iteration is deterministic — the UI relies on this.
     */
    private static final Set<String> ENV_WHITELIST;
    static {
        ENV_WHITELIST = new LinkedHashSet<>();
        ENV_WHITELIST.add("GROQ_API_KEY");
        ENV_WHITELIST.add("OPENAI_API_KEY");
        ENV_WHITELIST.add("GEMINI_API_KEY");
        ENV_WHITELIST.add("SPOTIFY_CLIENT_ID");
        ENV_WHITELIST.add("SPOTIFY_CLIENT_SECRET");
        ENV_WHITELIST.add("GOOGLE_CLIENT_ID");
        ENV_WHITELIST.add("GOOGLE_CLIENT_SECRET");
        ENV_WHITELIST.add("VOSK_MODEL_PATH");
        ENV_WHITELIST.add("AUTOMATION_API_KEY");
    }

    private final Path repoRoot;
    private final Path envFile;
    private final OpenClawConfigManager openClawConfig;

    public SetupService(OpenClawConfigManager openClawConfig) {
        this.openClawConfig = openClawConfig;
        this.repoRoot = locateRepoRoot();
        this.envFile = repoRoot.resolve(".env");
    }

    // ── State snapshot ──────────────────────────────────

    /**
     * Has the user finished setup? We look for a few signals:
     * - {@code ~/.sentient_setup.json} with {@code completed: true}, OR
     * - a non-default {@code .env} (at least one whitelisted key set), OR
     * - OpenClaw already configured with an API key.
     *
     * The wizard auto-shows on a first launch only — once any of these is
     * true, we trust the user can find it from Settings.
     */
    public JsonObject state() {
        JsonObject out = new JsonObject();
        boolean envHasRealKey = envHasAnyWhitelistedKey();
        boolean openclawConfigured = openClawConfig.loadConfig().has("secrets");
        boolean markerSet = Files.exists(setupMarker());
        boolean firstRun = !envHasRealKey && !openclawConfigured && !markerSet;
        out.addProperty("firstRun", firstRun);
        out.addProperty("envHasKey", envHasRealKey);
        out.addProperty("openclawConfigured", openclawConfigured);
        out.addProperty("setupMarker", markerSet);
        out.addProperty("repoRoot", repoRoot.toString());
        out.addProperty("envPath", envFile.toString());
        out.addProperty("os", osTag());
        return out;
    }

    /** Mark setup as complete so the wizard doesn't auto-show again. */
    public void markComplete() {
        try {
            Files.writeString(setupMarker(), "{\"completed\":true}\n");
        } catch (IOException e) {
            System.err.println("[Setup] Could not write marker: " + e.getMessage());
        }
    }

    private static Path setupMarker() {
        return Paths.get(System.getProperty("user.home"), ".sentient_setup.json");
    }

    // ── Prerequisite probes ─────────────────────────────

    /**
     * Probe Java, Maven, OpenClaw, Vosk, Tailscale, and the native helper. Each
     * tool's entry has {{installed, version?, path?, note?}}.
     */
    public JsonObject prereqs() {
        JsonObject out = new JsonObject();
        out.add("java", probeJava());
        out.add("maven", probeMaven());
        out.add("openclaw", probeOpenClaw());
        out.add("vosk", probeVosk());
        out.add("tailscale", probeTailscale());
        out.add("helper", probeHelper());
        out.add("os", osInfo());
        return out;
    }

    private JsonObject probeJava() {
        JsonObject o = new JsonObject();
        String home = System.getProperty("java.home");
        String version = System.getProperty("java.version", "");
        o.addProperty("installed", true);
        o.addProperty("version", version);
        o.addProperty("path", home);
        int major = parseJavaMajor(version);
        o.addProperty("ok", major >= 17);
        if (major < 17) o.addProperty("note", "Java 17 or higher required; found " + version + ".");
        return o;
    }

    private JsonObject probeMaven() {
        ProcessResult r = runQuick("mvn", "-v");
        JsonObject o = new JsonObject();
        boolean present = r.exitCode == 0 && r.stdout.contains("Apache Maven");
        o.addProperty("installed", present);
        if (present) {
            String first = r.stdout.split("\n", 2)[0].trim();
            o.addProperty("version", first);
            o.addProperty("ok", true);
        } else {
            o.addProperty("ok", false);
            o.addProperty("note", "Maven only needed to rebuild from source. The shipped jar runs without it.");
        }
        return o;
    }

    private JsonObject probeOpenClaw() {
        JsonObject o = new JsonObject();
        String bin = openClawConfig.findOpenClawBinary();
        o.addProperty("installed", bin != null);
        if (bin != null) {
            o.addProperty("path", bin);
            ProcessResult r = runQuick(bin, "--version");
            if (r.exitCode == 0) o.addProperty("version", r.stdout.split("\n", 2)[0].trim());
            o.addProperty("ok", true);
        } else {
            o.addProperty("ok", false);
            o.addProperty("note", "Install from the wizard or run `curl -fsSL https://openclaw.ai/install.sh | bash`.");
        }
        return o;
    }

    private JsonObject probeVosk() {
        JsonObject o = new JsonObject();
        String configured = com.sentient.util.EnvLoader.get("VOSK_MODEL_PATH");
        Path modelPath = configured != null && !configured.isBlank()
                ? Paths.get(configured)
                : repoRoot.resolve(VOSK_MODEL_NAME);
        boolean present = Files.isDirectory(modelPath) && Files.exists(modelPath.resolve("am"));
        o.addProperty("installed", present);
        if (present) {
            o.addProperty("path", modelPath.toString());
            o.addProperty("ok", true);
        } else {
            o.addProperty("ok", false);
            o.addProperty("note", "Wake-word listener is optional. Click DOWNLOAD to fetch the ~40MB model.");
        }
        return o;
    }

    private JsonObject probeTailscale() {
        JsonObject o = new JsonObject();
        TailscaleService ts = new TailscaleService();
        boolean present = ts.isInstalled();
        o.addProperty("installed", present);
        if (present) {
            o.addProperty("path", ts.findBinary());
            o.addProperty("ok", true);
            // Bubble up logged-in status so the wizard can branch on it.
            JsonObject status = ts.status(7070);
            o.addProperty("loggedIn", status.has("loggedIn") && status.get("loggedIn").getAsBoolean());
            if (status.has("dnsName")) o.addProperty("dnsName", status.get("dnsName").getAsString());
        } else {
            o.addProperty("ok", false);
            o.addProperty("note", "Optional — only needed for tailnet/Funnel remote access.");
        }
        return o;
    }

    private JsonObject probeHelper() {
        JsonObject o = new JsonObject();
        Path bin = Paths.get("/usr/local/bin/sentient-helper");
        boolean present = Files.isExecutable(bin);
        o.addProperty("installed", present);
        if (present) {
            o.addProperty("path", bin.toString());
            o.addProperty("ok", true);
        } else {
            o.addProperty("ok", false);
            o.addProperty("note", "Optional — needed only if the AI should control this device at the OS level.");
        }
        o.addProperty("supportedOS", osTag().equals("macos") || osTag().equals("windows"));
        return o;
    }

    private JsonObject osInfo() {
        JsonObject o = new JsonObject();
        o.addProperty("tag", osTag());
        o.addProperty("name", System.getProperty("os.name", ""));
        o.addProperty("arch", System.getProperty("os.arch", ""));
        return o;
    }

    // ── .env read / write ───────────────────────────────

    /**
     * Return a JSON object with each whitelisted key plus a {@code present}
     * boolean. Never returns the values themselves.
     */
    public JsonObject envStatus() {
        Map<String, String> values = readEnvFile();
        JsonObject out = new JsonObject();
        JsonArray keys = new JsonArray();
        for (String key : ENV_WHITELIST) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", key);
            String v = values.get(key);
            entry.addProperty("present", v != null && !v.isBlank() && !looksLikePlaceholder(v));
            keys.add(entry);
        }
        out.add("keys", keys);
        out.addProperty("path", envFile.toString());
        out.addProperty("exists", Files.exists(envFile));
        return out;
    }

    public void setEnvValue(String key, String value) throws IOException {
        if (!ENV_WHITELIST.contains(key)) {
            throw new IllegalArgumentException("Key not allowed: " + key);
        }
        ensureEnvFileExists();
        Map<String, String> values = readEnvFile();
        values.put(key, value == null ? "" : value);
        writeEnvFile(values);
    }

    public void removeEnvValue(String key) throws IOException {
        if (!ENV_WHITELIST.contains(key)) {
            throw new IllegalArgumentException("Key not allowed: " + key);
        }
        ensureEnvFileExists();
        Map<String, String> values = readEnvFile();
        if (values.remove(key) != null) writeEnvFile(values);
    }

    private boolean envHasAnyWhitelistedKey() {
        Map<String, String> values = readEnvFile();
        for (String key : ENV_WHITELIST) {
            String v = values.get(key);
            if (v != null && !v.isBlank() && !looksLikePlaceholder(v)) return true;
        }
        return false;
    }

    private static boolean looksLikePlaceholder(String v) {
        if (v == null) return true;
        String s = v.trim();
        if (s.isEmpty()) return true;
        if (s.startsWith("your_")) return true;
        if (s.startsWith("/path/to/")) return true;
        return false;
    }

    private void ensureEnvFileExists() throws IOException {
        if (Files.exists(envFile)) return;
        Path example = repoRoot.resolve(".env.example");
        if (Files.exists(example)) {
            Files.copy(example, envFile);
        } else {
            Files.writeString(envFile, "# Sentient Assistant .env\n");
        }
    }

    private Map<String, String> readEnvFile() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(envFile)) return out;
        try (BufferedReader br = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String k = trimmed.substring(0, eq).trim();
                String v = trimmed.substring(eq + 1).trim();
                out.put(k, v);
            }
        } catch (IOException e) {
            System.err.println("[Setup] Failed to read .env: " + e.getMessage());
        }
        return out;
    }

    /**
     * Rewrite the .env so it exactly reflects {@code updated} for whitelisted
     * keys, while preserving every comment line and every non-whitelisted key.
     *
     * <p>A whitelisted key absent from {@code updated} is treated as "the user
     * removed it" and its line is dropped. A whitelisted key present in
     * {@code updated} replaces any existing line and is appended if missing.
     */
    private void writeEnvFile(Map<String, String> updated) throws IOException {
        StringBuilder out = new StringBuilder();
        Map<String, Boolean> written = new HashMap<>();
        for (String key : updated.keySet()) written.put(key, false);

        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    out.append(line).append('\n');
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    out.append(line).append('\n');
                    continue;
                }
                String k = trimmed.substring(0, eq).trim();
                if (updated.containsKey(k)) {
                    out.append(k).append('=').append(updated.get(k)).append('\n');
                    written.put(k, true);
                } else if (ENV_WHITELIST.contains(k)) {
                    // Whitelisted key not in `updated` means: user removed it. Drop the line.
                    continue;
                } else {
                    // Non-whitelisted key — preserve as-is.
                    out.append(line).append('\n');
                }
            }
        }
        for (Map.Entry<String, String> e : updated.entrySet()) {
            if (!Boolean.TRUE.equals(written.get(e.getKey()))) {
                out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        Path tmp = envFile.resolveSibling(".env.tmp");
        Files.writeString(tmp, out.toString());
        Files.move(tmp, envFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ── Installer runners ───────────────────────────────

    /**
     * Pipe {@code curl -fsSL <url>} into {@code sh} via {@link ProcessBuilder}.
     * We never pass user-controlled URLs here — only OPENCLAW_INSTALL_URL and
     * TAILSCALE_INSTALL_URL constants above.
     */
    public CompletableFuture<Boolean> installOpenClaw(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            sink.emit("openclaw", "info", "Downloading installer from " + OPENCLAW_INSTALL_URL + " …");
            if (openClawConfig.isInstalled()) {
                sink.emit("openclaw", "ok", "OpenClaw is already installed at "
                        + openClawConfig.findOpenClawBinary() + ".");
                sink.done("openclaw", true, "already installed");
                return true;
            }
            int code = runStreaming(sink, "openclaw",
                    "bash", "-c", "curl --proto =https --tlsv1.2 -fsSL "
                            + OPENCLAW_INSTALL_URL + " | bash");
            boolean ok = code == 0 && openClawConfig.isInstalled();
            sink.done("openclaw", ok, ok
                    ? "OpenClaw installed at " + openClawConfig.findOpenClawBinary()
                    : "Install failed (exit " + code + "). Try the manual command in the docs.");
            return ok;
        });
    }

    public CompletableFuture<Boolean> startOpenClawGateway(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            String bin = openClawConfig.findOpenClawBinary();
            if (bin == null) {
                sink.done("openclaw_gateway", false, "OpenClaw binary not found.");
                return false;
            }
            sink.emit("openclaw_gateway", "info", "Starting gateway …");
            int code = runStreaming(sink, "openclaw_gateway", bin, "gateway", "start");
            boolean ok = code == 0;
            sink.done("openclaw_gateway", ok, ok ? "Gateway started." : "Failed to start gateway.");
            return ok;
        });
    }

    public CompletableFuture<Boolean> installTailscale(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            TailscaleService ts = new TailscaleService();
            if (ts.isInstalled()) {
                sink.emit("tailscale", "ok", "Tailscale already installed at " + ts.findBinary() + ".");
                sink.done("tailscale", true, "already installed");
                return true;
            }
            String os = osTag();
            if (os.equals("windows")) {
                sink.emit("tailscale", "warn",
                        "Windows: download Tailscale from https://tailscale.com/download/windows and run the .msi.");
                sink.done("tailscale", false, "manual install required on Windows");
                return false;
            }
            sink.emit("tailscale", "info", "Downloading installer from " + TAILSCALE_INSTALL_URL + " …");
            int code = runStreaming(sink, "tailscale",
                    "bash", "-c", "curl --proto =https --tlsv1.2 -fsSL "
                            + TAILSCALE_INSTALL_URL + " | sh");
            boolean ok = code == 0 && ts.isInstalled();
            sink.done("tailscale", ok, ok
                    ? "Installed. Click LOGIN to authenticate."
                    : "Install failed (exit " + code + "). On macOS, install from the App Store instead.");
            return ok;
        });
    }

    /**
     * Run `tailscale up`. This pops a browser window with an auth URL — on a
     * headless server the URL is also printed to stdout, which we forward via
     * the sink so the user can copy it.
     */
    public CompletableFuture<Boolean> tailscaleUp(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            TailscaleService ts = new TailscaleService();
            String bin = ts.findBinary();
            if (bin == null) {
                sink.done("tailscale_up", false, "Tailscale binary not found.");
                return false;
            }
            sink.emit("tailscale_up", "info", "Running `" + bin + " up` …");
            // sudo may be required on Linux. We run without and let the user
            // know if it needs root.
            int code = runStreaming(sink, "tailscale_up", bin, "up");
            if (code != 0) {
                sink.emit("tailscale_up", "warn",
                        "May need root. Try in a terminal:  sudo " + bin + " up");
            }
            boolean ok = code == 0;
            sink.done("tailscale_up", ok, ok ? "Logged in." : "Login failed.");
            return ok;
        });
    }

    public CompletableFuture<Boolean> downloadVoskModel(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            Path target = repoRoot.resolve(VOSK_MODEL_NAME);
            Path zip = repoRoot.resolve(VOSK_MODEL_NAME + ".zip");
            if (Files.isDirectory(target) && Files.exists(target.resolve("am"))) {
                sink.emit("vosk", "ok", "Vosk model already at " + target);
                trySetEnv("VOSK_MODEL_PATH", target.toString(), sink);
                sink.done("vosk", true, "already downloaded");
                return true;
            }
            try {
                sink.emit("vosk", "info", "Downloading " + VOSK_MODEL_URL + " (~40 MB) …");
                downloadWithProgress(VOSK_MODEL_URL, zip, sink, "vosk");
                sink.emit("vosk", "info", "Unzipping into " + target + " …");
                unzip(zip, repoRoot);
                Files.deleteIfExists(zip);
                if (!Files.isDirectory(target)) {
                    sink.done("vosk", false, "Unzip did not produce expected directory: " + target);
                    return false;
                }
                trySetEnv("VOSK_MODEL_PATH", target.toString(), sink);
                sink.done("vosk", true, "Vosk model installed at " + target);
                return true;
            } catch (Exception e) {
                sink.emit("vosk", "err", "Download failed: " + e.getMessage());
                sink.done("vosk", false, e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> installHelper(ProgressSink sink) {
        return CompletableFuture.supplyAsync(() -> {
            String os = osTag();
            if (os.equals("macos")) {
                Path src = repoRoot.resolve("native").resolve("macos");
                if (!Files.isDirectory(src)) {
                    sink.done("helper", false, "Helper source not found at " + src);
                    return false;
                }
                sink.emit("helper", "info", "Building helper (swift build -c release) …");
                int rc = runStreaming(sink, "helper",
                        new String[]{"swift", "build", "-c", "release"}, src.toFile());
                if (rc != 0) {
                    sink.done("helper", false, "swift build failed (exit " + rc + ").");
                    return false;
                }
                Path built = src.resolve(".build/release/SentientHelper");
                if (!Files.exists(built)) {
                    sink.done("helper", false, "Built binary not found at " + built);
                    return false;
                }
                sink.emit("helper", "info", "Installing to /usr/local/bin/sentient-helper …");
                // Try without sudo first (works on dev machines where /usr/local/bin is user-owned).
                int cp = runStreaming(sink, "helper",
                        "bash", "-c", "cp '" + built + "' /usr/local/bin/sentient-helper "
                                + "&& chmod +x /usr/local/bin/sentient-helper");
                if (cp != 0) {
                    sink.emit("helper", "warn",
                            "Could not write to /usr/local/bin. In a terminal run:  "
                            + "sudo cp " + built + " /usr/local/bin/sentient-helper");
                    sink.done("helper", false, "needs sudo to copy");
                    return false;
                }
                sink.emit("helper", "ok", "Installed. Next: System Settings → Privacy & Security → Accessibility → add "
                        + "/usr/local/bin/sentient-helper.");
                sink.done("helper", true, "Installed at /usr/local/bin/sentient-helper");
                return true;
            }
            if (os.equals("windows")) {
                Path src = repoRoot.resolve("native").resolve("windows").resolve("SentientHelper");
                if (!Files.isDirectory(src)) {
                    sink.done("helper", false, "Helper source not found at " + src);
                    return false;
                }
                sink.emit("helper", "info", "Publishing helper (dotnet publish -c Release) …");
                int rc = runStreaming(sink, "helper",
                        new String[]{"dotnet", "publish", "-c", "Release"}, src.toFile());
                boolean ok = rc == 0;
                sink.done("helper", ok, ok
                        ? "Helper built at native\\windows\\SentientHelper\\bin\\Release\\net8.0-windows\\win-x64\\publish\\sentient-helper.exe"
                        : "dotnet publish failed (exit " + rc + "). Install .NET 8 SDK and retry.");
                return ok;
            }
            sink.done("helper", false, "Native helper not available on this OS (" + os + ").");
            return false;
        });
    }

    // ── Helpers ─────────────────────────────────────────

    private void trySetEnv(String key, String value, ProgressSink sink) {
        try {
            setEnvValue(key, value);
            sink.emit("env", "ok", "Saved " + key + " to .env.");
        } catch (Exception e) {
            sink.emit("env", "warn", "Could not write " + key + " to .env: " + e.getMessage());
        }
    }

    private int runStreaming(ProgressSink sink, String phase, String... cmd) {
        return runStreaming(sink, phase, cmd, null);
    }

    private int runStreaming(ProgressSink sink, String phase, String[] cmd, java.io.File workdir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workdir != null) pb.directory(workdir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sink.emit(phase, "info", line);
                }
            }
            return p.waitFor();
        } catch (Exception e) {
            sink.emit(phase, "err", "Process failed: " + e.getMessage());
            return -1;
        }
    }

    private ProcessResult runQuick(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new ProcessResult(code, out, "");
        } catch (Exception e) {
            return new ProcessResult(-1, "", e.getMessage() == null ? "exception" : e.getMessage());
        }
    }

    private void downloadWithProgress(String urlStr, Path target, ProgressSink sink, String phase) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        int total = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int pct = (int) (read * 100L / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        sink.emit(phase, "info", "downloaded " + pct + "% (" + (read / 1024) + " KB)");
                        lastPct = pct;
                    }
                }
            }
        }
    }

    private void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = destDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(destDir)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static int parseJavaMajor(String version) {
        if (version == null || version.isEmpty()) return 0;
        String[] parts = version.split("\\.");
        try {
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) return Integer.parseInt(parts[1]); // 1.8 → 8
            return first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "macos" / "linux" / "windows" / "other". */
    public static String osTag() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        return "other";
    }

    /** Walk up from the jar location / cwd until we find a .env.example. */
    private Path locateRepoRoot() {
        // Prefer the parent of the running jar / target dir.
        List<Path> candidates = Arrays.asList(
                Paths.get(System.getProperty("user.dir")),
                Paths.get(System.getProperty("user.dir")).getParent(),
                Paths.get(System.getProperty("user.dir")).resolve("..").normalize());
        for (Path c : candidates) {
            if (c == null) continue;
            for (int i = 0; i < 4 && c != null; i++) {
                if (Files.exists(c.resolve(".env.example"))) return c;
                c = c.getParent();
            }
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static class ProcessResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        ProcessResult(int c, String o, String e) { exitCode = c; stdout = o; stderr = e; }
    }
}
