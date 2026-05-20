package com.sentient.mesh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sentient.util.ProfileManager;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * End-to-end smoke for {@link ProfileManager}. Exercises migration from a
 * legacy {@code user_profile.json}, mutations through the public API,
 * survival across a "restart" (singleton reset), and the back-fill that the
 * reconcile() pass does for direct field mutations.
 *
 * <p>Run with: {@code java -Duser.home=<temp> -cp <cp> com.sentient.mesh.ProfileManagerScenario}.
 * Most callers go through {@code run_profilemgr_scenario.sh} (added in this
 * same commit), which sets up + tears down a sandboxed home dir for us.
 *
 * <p>This is intentionally <em>not</em> part of {@link MeshSelfTest} —
 * {@code MeshSelfTest} runs the CRDT primitives in-process and never touches
 * {@code user.home}. The ProfileManager scenario does, so isolate it.
 */
public final class ProfileManagerScenario {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(System.getProperty("user.home"));
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path legacyProfile = cwd.resolve("user_profile.json");
        Path oplog = home.resolve(".sentient_mesh_oplog.ndjson");
        Path masterIdFile = home.resolve(".sentient_master_id");

        // Pre-flight: tear down anything in the temp paths so we start clean.
        Files.deleteIfExists(oplog);
        Files.deleteIfExists(masterIdFile);
        Files.deleteIfExists(legacyProfile);

        // ── 1. Write a legacy user_profile.json with rich content ──
        writeLegacyProfile(legacyProfile);

        // ── 2. Fresh boot: ProfileManager should migrate into op log ──
        resetSingleton();
        ProfileManager pm = ProfileManager.getInstance();
        ProfileManager.UserProfile p = pm.getUserProfile();
        require("Aryaman".equals(p.username), "migrated username");
        require(p.habits.contains("running"), "migrated habit");
        require(!p.taskLists.isEmpty(), "migrated task lists");
        ProfileManager.TaskList firstList = p.taskLists.get(0);
        require("Inbox".equals(firstList.name), "migrated list name");
        require(firstList.items.size() == 2, "migrated list size");
        require(p.events.size() == 1, "migrated event");
        require(p.events.get(0).id.equals("ev-123"), "preserved event id");

        // ── 3. Op log must contain ops after migration ──
        require(Files.exists(oplog), "op log was created");
        long lines = Files.lines(oplog).count();
        require(lines > 0, "op log non-empty after migration");
        System.out.println("[Scenario] migration wrote " + lines + " ops");

        // ── 4. Public-API mutations ──
        pm.addHabit("reading");
        pm.addTaskToList("Inbox", "Email Tomas", "about heat pump", "");
        pm.removeHabit("running");
        p = pm.getUserProfile();
        require(p.habits.contains("reading"), "added habit visible");
        require(!p.habits.contains("running"), "removed habit gone");
        require(p.taskLists.get(0).items.size() == 3, "task added");

        // ── 5. Direct-mutation pattern (legacy: getUserProfile().X = ...) ──
        ProfileManager.TaskItem item = p.taskLists.get(0).items.stream()
                .filter(t -> "Email Tomas".equals(t.title)).findFirst().orElse(null);
        require(item != null, "found just-added item");
        item.googleId = "g-789";
        item.googleListId = "gl-456";
        pm.saveProfile();
        // Reconcile should have picked up the direct mutation — verify via CRDT view.
        String basePath = "taskLists." + item._listId + ".items." + item._itemId;
        require("g-789".equals(pm.replicatedDoc().state().lwwString(basePath + ".googleId", "")),
                "reconcile picked up direct googleId mutation");
        require("gl-456".equals(pm.replicatedDoc().state().lwwString(basePath + ".googleListId", "")),
                "reconcile picked up direct googleListId mutation");

        // ── 6. "Restart" — drop singleton, re-init, expect same state ──
        long opsBeforeRestart = Files.lines(oplog).count();
        resetSingleton();
        ProfileManager pm2 = ProfileManager.getInstance();
        ProfileManager.UserProfile p2 = pm2.getUserProfile();
        require("Aryaman".equals(p2.username), "restart: username");
        require(p2.habits.contains("reading"), "restart: added habit survives");
        require(!p2.habits.contains("running"), "restart: removed habit stays gone");
        require(p2.taskLists.get(0).items.size() == 3, "restart: task count");
        ProfileManager.TaskItem persistedItem = p2.taskLists.get(0).items.stream()
                .filter(t -> "Email Tomas".equals(t.title)).findFirst().orElse(null);
        require(persistedItem != null, "restart: just-added item still there");
        require("g-789".equals(persistedItem.googleId), "restart: googleId survives");
        require("gl-456".equals(persistedItem.googleListId), "restart: googleListId survives");
        require(p2.events.size() == 1, "restart: events count");

        // No new ops on restart (we just replay; nothing new is emitted unless
        // the cache itself diverges from the log, which it shouldn't).
        long opsAfterRestart = Files.lines(oplog).count();
        require(opsAfterRestart == opsBeforeRestart, "restart did not append ops");

        // ── 7. Master id is stable across restarts ──
        String id1 = pm2.masterId();
        resetSingleton();
        ProfileManager pm3 = ProfileManager.getInstance();
        require(id1.equals(pm3.masterId()), "master id stable across restarts");

        System.out.println("[Scenario] all checks PASSED");
    }

    // ── helpers ─────────────────────────────────────────

    private static void writeLegacyProfile(Path file) throws IOException {
        ProfileManager.UserProfile p = new ProfileManager.UserProfile();
        p.username = "Aryaman";
        p.habits.add("running");
        p.habits.add("writing");
        p.preferences.add("dark mode");
        p.restriction_mode = false;

        ProfileManager.TaskList tl = new ProfileManager.TaskList();
        tl.name = "Inbox";
        ProfileManager.TaskItem t1 = new ProfileManager.TaskItem();
        t1.title = "Buy eggs"; t1.dueDate = "2026-05-21";
        ProfileManager.TaskItem t2 = new ProfileManager.TaskItem();
        t2.title = "Call dentist";
        tl.items.add(t1);
        tl.items.add(t2);
        p.taskLists.add(tl);

        ProfileManager.EventItem ev = new ProfileManager.EventItem();
        ev.id = "ev-123";
        ev.title = "Standup";
        ev.start = "2026-05-22T09:00:00-04:00";
        ev.end = "2026-05-22T09:30:00-04:00";
        p.events.add(ev);

        try (Writer w = new FileWriter(file.toFile())) {
            GSON.toJson(p, w);
        }
    }

    /** Reflective reset of the {@link ProfileManager} singleton — test-only. */
    private static void resetSingleton() throws Exception {
        Field f = ProfileManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
