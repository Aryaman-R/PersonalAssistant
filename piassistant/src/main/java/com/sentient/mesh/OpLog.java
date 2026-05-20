package com.sentient.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Append-only operation log persisted as newline-delimited JSON at
 * {@code ~/.sentient_mesh_oplog.ndjson}.
 *
 * <p>Each line is one {@link Op} serialized by {@link Op#toJsonString()}.
 * The file is the durable source of truth for the mesh; rebuilding the
 * materialized state is "read every line, call {@link ReplicatedState#apply(Op)}".
 *
 * <p>Writes are line-buffered + {@code fsync()}'d on every {@link #append}.
 * That's the simplest correct option — a crash mid-write only loses the
 * trailing line if it was incomplete (NDJSON is robust to partial last lines;
 * {@link #read(Consumer)} skips any line that fails to parse).
 *
 * <p>Per-line dedup uses an in-memory {@link Set} of op ids loaded on
 * {@link #open()}. Future phases can spill this to a side-file when the log
 * grows. For Phase 1 the working assumption is &lt;100k ops — trivially fits.
 *
 * <p>Rotation is deliberately not implemented in Phase 1. The largest single
 * profile change costs ~200 bytes; even at 100 changes/day for a year that's
 * ~7 MB. Snapshot-and-truncate lands when we wire the snapshot path in
 * {@code ProfileManager} (Phase 1 follow-up commit).
 */
public final class OpLog {

    private static final Path DEFAULT_PATH = Paths.get(
            System.getProperty("user.home"), ".sentient_mesh_oplog.ndjson");

    private final Path file;
    private final Object writeLock = new Object();
    private final Set<String> seenIds = new HashSet<>();

    public OpLog() { this(DEFAULT_PATH); }

    /** Construct with an explicit path. Useful for tests + paired-master demos. */
    public OpLog(Path file) {
        this.file = file;
    }

    public Path path() { return file; }

    /**
     * Initialize on first use: create the file if absent, then populate the
     * in-memory dedup set by scanning every existing line. Safe to call again
     * — subsequent calls just no-op the create.
     */
    public synchronized void open() throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createFile(file);
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) { /* Windows or unsupported FS */ }
        }
        seenIds.clear();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    JsonObject o = JsonParser.parseString(trimmed).getAsJsonObject();
                    if (o.has("id")) seenIds.add(o.get("id").getAsString());
                } catch (Exception ignored) {
                    /* malformed last line is fine — we skip and move on */
                }
            }
        }
    }

    /**
     * Append an op iff not already in the log. Returns {@code true} when the
     * op was newly written (caller should gossip), {@code false} when already
     * known (idempotent replay).
     */
    public boolean append(Op op) throws IOException {
        if (op == null) throw new IllegalArgumentException("op required");
        synchronized (writeLock) {
            if (!seenIds.add(op.id)) return false;
            byte[] bytes = (op.toJsonString() + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.SYNC);
            return true;
        }
    }

    /**
     * Drive the supplied consumer once per op in the log, in append order.
     * Lines that fail to parse are skipped + logged to stderr — won't abort
     * the read (the trailing line after an OOM-kill is a known case).
     */
    public synchronized void read(Consumer<Op> consumer) throws IOException {
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    consumer.accept(Op.fromJsonString(trimmed));
                } catch (Exception e) {
                    System.err.println("[OpLog] skipping malformed line " + lineNo + ": " + e.getMessage());
                }
            }
        }
    }

    /** Snapshot all ops in order. For small logs only — Phase 2 prefers streaming via {@link #read}. */
    public synchronized List<Op> snapshot() throws IOException {
        List<Op> out = new ArrayList<>();
        read(out::add);
        return out;
    }

    public synchronized boolean contains(String opId) { return seenIds.contains(opId); }

    public synchronized int size() { return seenIds.size(); }
}
