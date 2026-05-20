package com.sentient.mesh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * Stable per-master UUID, persisted at {@code ~/.sentient_master_id}. Generated
 * once on first boot and reused on every subsequent boot — that's what makes
 * a master a stable identity in the mesh.
 *
 * <p>Reuse rules:
 * <ul>
 *   <li>If the file exists and parses as a valid UUID, return it verbatim.</li>
 *   <li>If the file is missing, generate a fresh UUID, write it, set
 *       {@code 0600} perms, return it.</li>
 *   <li>If the file exists but is garbage, log + overwrite with a fresh UUID
 *       (the prior value can't be trusted as an origin tag anyway).</li>
 * </ul>
 *
 * <p>This UUID is also the {@code originMasterId} stamped on every op the
 * master emits — see {@link Op#origin} — and the HLC node id used by
 * {@link HlcClock}. Same value everywhere is intentional: that's what makes
 * "trace this state mutation back to its source master" work.
 */
public final class MasterId {

    private static final Path DEFAULT_PATH = Paths.get(
            System.getProperty("user.home"), ".sentient_master_id");

    private MasterId() {}

    public static String load() { return load(DEFAULT_PATH); }

    public static String load(Path path) {
        try {
            if (Files.exists(path)) {
                String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (isValidUuid(raw)) return raw;
                System.err.println("[MasterId] " + path + " contains invalid UUID, regenerating");
            }
            String fresh = UUID.randomUUID().toString();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, fresh, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) { /* non-POSIX */ }
            return fresh;
        } catch (IOException ioe) {
            // Fall back to an ephemeral UUID so the rest of the app still boots,
            // but warn loudly — without a stable ID the mesh can't dedupe.
            String ephemeral = UUID.randomUUID().toString();
            System.err.println("[MasterId] FATAL: could not persist master id ("
                    + ioe.getMessage() + "). Falling back to ephemeral: " + ephemeral);
            return ephemeral;
        }
    }

    static boolean isValidUuid(String s) {
        if (s == null || s.length() != 36) return false;
        try { UUID.fromString(s); return true; }
        catch (IllegalArgumentException e) { return false; }
    }
}
