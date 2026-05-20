package com.sentient.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentient.mesh.MasterId;
import com.sentient.mesh.OpLog;
import com.sentient.mesh.ReplicatedDoc;
import com.sentient.mesh.ReplicatedState;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Owner of the user's replicated profile state.
 *
 * <p>As of STRETCH §9 (multi-master mesh, Phase 1), the on-disk truth is the
 * mesh op log at {@code ~/.sentient_mesh_oplog.ndjson}. The legacy
 * {@code user_profile.json} sitting next to the JAR is now a periodically-
 * regenerated <em>snapshot</em> kept for tools that scrape the file directly
 * — it is no longer source of truth.
 *
 * <p>The class still exposes the original (pre-mesh) API verbatim — every
 * caller in the repo continues to work with no source changes. Three patterns
 * are supported:
 *
 * <ol>
 *   <li><b>Explicit setter</b>: {@code pm.addHabit("running")} — generates a
 *       CRDT op, applies it locally, persists to the op log, mirrors into the
 *       cached {@link UserProfile}, and (after Phase 2) gossips to peers.</li>
 *   <li><b>Direct field mutation + saveProfile</b>: legacy pattern
 *       {@code pm.getUserProfile().events.find(...).title = "new"; pm.saveProfile();}
 *       The {@link #saveProfile()} call walks the cached state, diffs against
 *       the CRDT view, and emits any missing ops. Catches every "leak" that
 *       didn't route through the explicit setters.</li>
 *   <li><b>Remote ops</b>: Phase 2's mesh layer pushes peer ops in via
 *       {@link #applyRemoteOp(com.sentient.mesh.Op)}. The cache is then
 *       re-materialized from the CRDT state — any uncommitted direct edits
 *       on the cache are intentionally discarded (they should have been
 *       saveProfile()'d).</li>
 * </ol>
 *
 * <p>On first boot after the §9 migration, an existing
 * {@code user_profile.json} is read and a one-shot {@code reconcile()} pass
 * pours every field into the op log. From then on the op log wins.
 */
public class ProfileManager {

    private static final String PROFILE_PATH = "user_profile.json";
    private static ProfileManager instance;

    private final Gson gson;
    private final ReplicatedDoc doc;
    private final String masterId;
    private UserProfile cached;

    private ProfileManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.masterId = MasterId.load();
        OpLog log = new OpLog();
        this.doc = new ReplicatedDoc(masterId, log);
        try {
            doc.load();
        } catch (IOException e) {
            System.err.println("[ProfileManager] op log load failed: " + e.getMessage());
        }

        // Bootstrap the cache from current CRDT state.
        this.cached = materialize();

        // Legacy → mesh one-shot migration: if the op log was empty but the
        // legacy snapshot exists, slurp it in via reconcile().
        boolean migrated = false;
        if (log.size() == 0 && Files.exists(Paths.get(PROFILE_PATH))) {
            migrated = loadLegacySnapshotIntoCache();
        }

        // Make sure we always have at least one task list — preserves the
        // legacy invariant that {@code taskLists.get(0)} works.
        if (cached.taskLists == null) cached.taskLists = new ArrayList<>();
        if (cached.taskLists.isEmpty()) {
            TaskList defaultList = new TaskList();
            defaultList.name = "My Tasks";
            cached.taskLists.add(defaultList);
            migrated = true;
        }

        // If we changed anything during bootstrap, flush ops + snapshot.
        if (migrated) {
            reconcile();
            writeSnapshot();
        }
    }

    public static synchronized ProfileManager getInstance() {
        if (instance == null) instance = new ProfileManager();
        return instance;
    }

    /** Mesh layer hook — pass remote ops here. */
    public synchronized void applyRemoteOp(com.sentient.mesh.Op op) {
        if (op == null) return;
        boolean changed = doc.applyRemote(op);
        if (changed) {
            cached = materialize();
            writeSnapshot();
        }
    }

    /** For Phase 2's MeshService: subscribe to ops the doc emits. */
    public ReplicatedDoc replicatedDoc() { return doc; }
    public String masterId() { return masterId; }

    // ── Boot / migration ────────────────────────────────

    private boolean loadLegacySnapshotIntoCache() {
        try {
            String raw = Files.readString(Paths.get(PROFILE_PATH));
            UserProfile legacy = gson.fromJson(raw, UserProfile.class);
            if (legacy == null) return false;

            JsonObject rawJson = JsonParser.parseString(raw).getAsJsonObject();
            // Sub-migration: old flat "tasks" array → taskLists[0].items
            if (rawJson.has("tasks") && rawJson.get("tasks").isJsonArray()
                    && (legacy.taskLists == null || legacy.taskLists.isEmpty())) {
                JsonArray oldTasks = rawJson.getAsJsonArray("tasks");
                if (oldTasks.size() > 0) {
                    TaskList defaultList = new TaskList();
                    defaultList.name = "My Tasks";
                    for (JsonElement elem : oldTasks) {
                        JsonObject t = elem.getAsJsonObject();
                        TaskItem item = new TaskItem();
                        item.title = t.has("title") ? t.get("title").getAsString() : "";
                        item.description = t.has("description") ? t.get("description").getAsString() : "";
                        item.dueDate = t.has("dueDate") ? t.get("dueDate").getAsString() : "";
                        if (!item.title.isEmpty()) defaultList.items.add(item);
                    }
                    if (legacy.taskLists == null) legacy.taskLists = new ArrayList<>();
                    legacy.taskLists.add(defaultList);
                    System.out.println("[ProfileManager] Migrated legacy flat tasks → 'My Tasks' list.");
                }
            }

            cached = legacy;
            System.out.println("[ProfileManager] Legacy user_profile.json → mesh op log migration in progress.");
            return true;
        } catch (Exception e) {
            System.err.println("[ProfileManager] legacy snapshot load failed: " + e.getMessage());
            return false;
        }
    }

    // ── Public API: getters ────────────────────────────

    public synchronized UserProfile getUserProfile() { return cached; }

    public synchronized List<TaskList> getTaskLists() { return cached.taskLists; }

    public synchronized List<TaskItem> getAllTasks() {
        List<TaskItem> all = new ArrayList<>();
        for (TaskList tl : cached.taskLists) all.addAll(tl.items);
        return all;
    }

    public synchronized TaskItem findTask(String listName, String title) {
        if (listName == null || title == null) return null;
        for (TaskList tl : cached.taskLists) {
            if (tl.name.equalsIgnoreCase(listName.trim())) {
                for (TaskItem t : tl.items) {
                    if (t.title.equalsIgnoreCase(title.trim())) return t;
                }
            }
        }
        return null;
    }

    public synchronized TaskItem findTaskAnywhere(String title) {
        if (title == null) return null;
        for (TaskList tl : cached.taskLists) {
            for (TaskItem t : tl.items) {
                if (t.title.equalsIgnoreCase(title.trim())) return t;
            }
        }
        return null;
    }

    public synchronized String listNameForTask(TaskItem item) {
        for (TaskList tl : cached.taskLists) {
            if (tl.items.contains(item)) return tl.name;
        }
        return null;
    }

    // ── Public API: setters (each ends in save) ────────

    public synchronized void setUsername(String name) {
        cached.username = name;
        saveProfile();
    }

    public synchronized void setRestrictionMode(boolean enabled) {
        cached.restriction_mode = enabled;
        saveProfile();
    }

    public synchronized void addHabit(String habit) { addIgnoreCase(cached.habits, habit); saveProfile(); }
    public synchronized void removeHabit(String habit) { removeIgnoreCase(cached.habits, habit); saveProfile(); }

    public synchronized void addPreference(String pref) { addIgnoreCase(cached.preferences, pref); saveProfile(); }
    public synchronized void removePreference(String pref) { removeIgnoreCase(cached.preferences, pref); saveProfile(); }

    public synchronized void addDislike(String dislike) { addIgnoreCase(cached.dislikes, dislike); saveProfile(); }
    public synchronized void removeDislike(String dislike) { removeIgnoreCase(cached.dislikes, dislike); saveProfile(); }

    public synchronized void addGoal(String goal) { addIgnoreCase(cached.goals, goal); saveProfile(); }
    public synchronized void removeGoal(String goal) { removeIgnoreCase(cached.goals, goal); saveProfile(); }

    public synchronized void addNickname(String nick) { addIgnoreCase(cached.nicknames, nick); saveProfile(); }
    public synchronized void removeNickname(String nick) { removeIgnoreCase(cached.nicknames, nick); saveProfile(); }

    public synchronized void addNote(String note) { addIgnoreCase(cached.notes, note); saveProfile(); }
    public synchronized void removeNote(String note) { removeIgnoreCase(cached.notes, note); saveProfile(); }

    public synchronized void addCommitment(String c) { addIgnoreCase(cached.commitments, c); saveProfile(); }
    public synchronized void removeCommitment(String c) { removeIgnoreCase(cached.commitments, c); saveProfile(); }

    public synchronized void addAlarm(String time) {
        // Alarms are exact-match (not case-folded) in the legacy code.
        String t = time == null ? "" : time.trim();
        if (!t.isEmpty() && !cached.alarms.contains(t)) {
            cached.alarms.add(t);
            saveProfile();
        }
    }

    public synchronized void removeAlarm(String time) {
        if (cached.alarms.removeIf(a -> a.equalsIgnoreCase(time == null ? "" : time.trim()))) saveProfile();
    }

    // ── Task lists ─────────────────────────────────────

    public synchronized void addTaskList(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return;
        if (cached.taskLists.stream().anyMatch(tl -> tl.name.equalsIgnoreCase(trimmed))) return;
        TaskList list = new TaskList();
        list.name = trimmed;
        cached.taskLists.add(list);
        saveProfile();
    }

    public synchronized void removeTaskList(String name) {
        if (name == null) return;
        if (cached.taskLists.removeIf(tl -> tl.name.equalsIgnoreCase(name.trim()))) saveProfile();
    }

    public synchronized void addTaskToList(String listName, String title, String description, String dueDate) {
        String trimmedTitle = title == null ? "" : title.trim();
        if (trimmedTitle.isEmpty()) return;
        for (TaskList tl : cached.taskLists) {
            if (tl.name.equalsIgnoreCase(listName == null ? "" : listName.trim())) {
                if (tl.items.stream().noneMatch(t -> t.title.equalsIgnoreCase(trimmedTitle))) {
                    TaskItem item = new TaskItem();
                    item.title = trimmedTitle;
                    item.description = description == null ? "" : description.trim();
                    item.dueDate = dueDate == null ? "" : dueDate.trim();
                    tl.items.add(item);
                    saveProfile();
                }
                return;
            }
        }
    }

    public synchronized void removeTaskFromList(String listName, String title) {
        for (TaskList tl : cached.taskLists) {
            if (tl.name.equalsIgnoreCase(listName == null ? "" : listName.trim())) {
                if (tl.items.removeIf(t -> t.title.equalsIgnoreCase(title == null ? "" : title.trim()))) {
                    saveProfile();
                }
                return;
            }
        }
    }

    public synchronized void addTask(String title, String description, String dueDate) {
        if (cached.taskLists.isEmpty()) {
            TaskList l = new TaskList();
            l.name = "My Tasks";
            cached.taskLists.add(l);
        }
        addTaskToList(cached.taskLists.get(0).name, title, description, dueDate);
    }

    public synchronized void removeTask(String title) {
        boolean changed = false;
        for (TaskList tl : cached.taskLists) {
            if (tl.items.removeIf(t -> t.title.equalsIgnoreCase(title == null ? "" : title.trim()))) {
                changed = true;
            }
        }
        if (changed) saveProfile();
    }

    // ── Events ─────────────────────────────────────────

    public synchronized void addEvent(EventItem event) {
        if (event == null) return;
        if (event.id == null || event.id.isEmpty()) event.id = UUID.randomUUID().toString();
        cached.events.add(event);
        saveProfile();
    }

    public synchronized void removeEvent(String id) {
        if (id == null) return;
        if (cached.events.removeIf(e -> id.equals(e.id))) saveProfile();
    }

    /**
     * Mesh-friendly event update. Replaces the legacy pattern of mutating an
     * EventItem field directly + calling saveProfile() — that still works (the
     * reconcile pass picks it up) but using this method emits more targeted ops.
     */
    public synchronized boolean updateEvent(String id, String title, String description, String start, String end) {
        for (EventItem ev : cached.events) {
            if (id.equals(ev.id)) {
                if (title != null) ev.title = title;
                if (description != null) ev.description = description;
                if (start != null) {
                    ev.start = start;
                    ev.allDay = start.length() <= 10;
                }
                if (end != null) ev.end = end;
                saveProfile();
                return true;
            }
        }
        return false;
    }

    // ── Snapshot persistence ───────────────────────────

    /**
     * Reconcile the cached state against the CRDT, emitting ops for any
     * divergence; then write {@code user_profile.json} as a snapshot.
     *
     * <p>Most existing callers already invoke this after a direct mutation
     * (the pre-mesh pattern). The reconcile step is what makes those legacy
     * mutations participate in the mesh — without it they would diverge.
     */
    public synchronized void saveProfile() {
        reconcile();
        writeSnapshot();
    }

    private void writeSnapshot() {
        try (Writer w = new FileWriter(PROFILE_PATH)) {
            gson.toJson(cached, w);
        } catch (IOException e) {
            System.err.println("[ProfileManager] snapshot write failed: " + e.getMessage());
        }
    }

    // ── Reconciliation: cache → CRDT diff ──────────────

    private void reconcile() {
        ReplicatedState s = doc.state();

        // Scalars
        reconcileLwwString("profile.username", cached.username, "Owner");
        reconcileLwwBool("profile.restriction_mode", cached.restriction_mode);

        // Plain string OR-Sets
        reconcileStringOrset("profile.habits", cached.habits);
        reconcileStringOrset("profile.preferences", cached.preferences);
        reconcileStringOrset("profile.dislikes", cached.dislikes);
        reconcileStringOrset("profile.goals", cached.goals);
        reconcileStringOrset("profile.nicknames", cached.nicknames);
        reconcileStringOrset("profile.notes", cached.notes);
        reconcileStringOrset("profile.commitments", cached.commitments);
        reconcileStringOrset("profile.alarms", cached.alarms);
        reconcileStringOrset("profile.past_conversations_summary", cached.past_conversations_summary);

        // Task lists
        reconcileTaskLists();

        // Events
        reconcileEvents();
    }

    private void reconcileLwwString(String path, String value, String def) {
        String cur = doc.state().lwwString(path, def);
        String want = value == null ? def : value;
        if (!java.util.Objects.equals(cur, want)) doc.setLwwString(path, want);
    }

    private void reconcileLwwBool(String path, boolean value) {
        boolean cur = doc.state().lwwBool(path, false);
        if (cur != value) doc.setLwwBool(path, value);
    }

    private void reconcileStringOrset(String path, List<String> cacheList) {
        if (cacheList == null) return;
        Set<String> cacheMembers = new LinkedHashSet<>(cacheList);
        Set<String> stateMembers = doc.state().orsetMembers(path);
        // Case-insensitive comparison to match legacy semantics: a cache entry
        // "Running" and a state entry "running" are the same element.
        Set<String> cacheLower = lower(cacheMembers);
        Set<String> stateLower = lower(stateMembers);
        for (String el : cacheMembers) {
            if (!stateLower.contains(el.toLowerCase())) doc.addToSet(path, el);
        }
        for (String el : stateMembers) {
            if (!cacheLower.contains(el.toLowerCase())) doc.removeFromSet(path, el);
        }
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new HashSet<>(in.size());
        for (String s : in) out.add(s == null ? "" : s.toLowerCase());
        return out;
    }

    private void reconcileTaskLists() {
        // The OR-Set member is the list id (transient _id on TaskList). Generate
        // one for any list that doesn't have it yet.
        Set<String> wantIds = new LinkedHashSet<>();
        for (TaskList tl : cached.taskLists) {
            if (tl._id == null || tl._id.isEmpty()) tl._id = UUID.randomUUID().toString();
            wantIds.add(tl._id);
            reconcileLwwString("taskLists." + tl._id + ".name", tl.name, "");
            reconcileTaskItems(tl);
        }
        Set<String> have = doc.state().orsetMembers("taskLists");
        for (String id : wantIds) if (!have.contains(id)) doc.addToSet("taskLists", id);
        for (String id : have) if (!wantIds.contains(id)) doc.removeFromSet("taskLists", id);
    }

    private void reconcileTaskItems(TaskList tl) {
        String listPath = "taskLists." + tl._id + ".items";
        Set<String> wantIds = new LinkedHashSet<>();
        for (TaskItem t : tl.items) {
            if (t._itemId == null || t._itemId.isEmpty()) t._itemId = UUID.randomUUID().toString();
            t._listId = tl._id;
            wantIds.add(t._itemId);
            String basePath = listPath + "." + t._itemId;
            reconcileLwwString(basePath + ".title", t.title, "");
            reconcileLwwString(basePath + ".description", t.description, "");
            reconcileLwwString(basePath + ".dueDate", t.dueDate, "");
            reconcileLwwString(basePath + ".googleId", t.googleId, "");
            reconcileLwwString(basePath + ".googleListId", t.googleListId, "");
            reconcileLwwBool(basePath + ".completed", t.completed);
        }
        Set<String> have = doc.state().orsetMembers(listPath);
        for (String id : wantIds) if (!have.contains(id)) doc.addToSet(listPath, id);
        for (String id : have) if (!wantIds.contains(id)) doc.removeFromSet(listPath, id);
    }

    private void reconcileEvents() {
        Set<String> wantIds = new LinkedHashSet<>();
        for (EventItem ev : cached.events) {
            if (ev.id == null || ev.id.isEmpty()) ev.id = UUID.randomUUID().toString();
            wantIds.add(ev.id);
            String base = "events." + ev.id;
            reconcileLwwString(base + ".title", ev.title, "");
            reconcileLwwString(base + ".description", ev.description, "");
            reconcileLwwString(base + ".start", ev.start, "");
            reconcileLwwString(base + ".end", ev.end, "");
            reconcileLwwBool(base + ".allDay", ev.allDay);
        }
        Set<String> have = doc.state().orsetMembers("events");
        for (String id : wantIds) if (!have.contains(id)) doc.addToSet("events", id);
        for (String id : have) if (!wantIds.contains(id)) doc.removeFromSet("events", id);
    }

    // ── Materialize CRDT state → UserProfile ───────────

    private UserProfile materialize() {
        ReplicatedState s = doc.state();
        UserProfile p = new UserProfile();
        p.username = s.lwwString("profile.username", "Owner");
        p.restriction_mode = s.lwwBool("profile.restriction_mode", false);
        p.habits = new ArrayList<>(s.orsetMembers("profile.habits"));
        p.preferences = new ArrayList<>(s.orsetMembers("profile.preferences"));
        p.dislikes = new ArrayList<>(s.orsetMembers("profile.dislikes"));
        p.goals = new ArrayList<>(s.orsetMembers("profile.goals"));
        p.nicknames = new ArrayList<>(s.orsetMembers("profile.nicknames"));
        p.notes = new ArrayList<>(s.orsetMembers("profile.notes"));
        p.commitments = new ArrayList<>(s.orsetMembers("profile.commitments"));
        p.alarms = new ArrayList<>(s.orsetMembers("profile.alarms"));
        p.past_conversations_summary = new ArrayList<>(s.orsetMembers("profile.past_conversations_summary"));

        p.taskLists = new ArrayList<>();
        for (String listId : s.orsetMembers("taskLists")) {
            TaskList tl = new TaskList();
            tl._id = listId;
            tl.name = s.lwwString("taskLists." + listId + ".name", "");
            tl.items = new ArrayList<>();
            String itemsPath = "taskLists." + listId + ".items";
            for (String itemId : s.orsetMembers(itemsPath)) {
                TaskItem item = new TaskItem();
                item._listId = listId;
                item._itemId = itemId;
                String base = itemsPath + "." + itemId;
                item.title = s.lwwString(base + ".title", "");
                item.description = s.lwwString(base + ".description", "");
                item.dueDate = s.lwwString(base + ".dueDate", "");
                item.googleId = s.lwwString(base + ".googleId", "");
                item.googleListId = s.lwwString(base + ".googleListId", "");
                item.completed = s.lwwBool(base + ".completed", false);
                tl.items.add(item);
            }
            p.taskLists.add(tl);
        }

        p.events = new ArrayList<>();
        for (String eventId : s.orsetMembers("events")) {
            EventItem ev = new EventItem();
            ev.id = eventId;
            String base = "events." + eventId;
            ev.title = s.lwwString(base + ".title", "");
            ev.description = s.lwwString(base + ".description", "");
            ev.start = s.lwwString(base + ".start", "");
            ev.end = s.lwwString(base + ".end", "");
            ev.allDay = s.lwwBool(base + ".allDay", false);
            p.events.add(ev);
        }
        return p;
    }

    // ── Helpers ────────────────────────────────────────

    /** Case-insensitive add — duplicates by lowercased equality are dropped. */
    private static void addIgnoreCase(List<String> list, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        for (String existing : list) if (existing.equalsIgnoreCase(trimmed)) return;
        list.add(trimmed);
    }

    private static void removeIgnoreCase(List<String> list, String value) {
        if (value == null) return;
        String t = value.trim();
        list.removeIf(x -> x.equalsIgnoreCase(t));
    }

    /**
     * For tests that need to walk away from any in-memory cache + reload from
     * disk. Production code shouldn't call this.
     */
    public synchronized void reloadForTests() {
        cached = materialize();
    }

    // Test seam — let tests reset the singleton.
    static synchronized void resetForTests() { instance = null; }

    // ── Data classes (wire shape preserved) ────────────

    public static class TaskItem {
        public String title = "";
        public String description = "";
        public String dueDate = "";
        public String googleId = "";
        public String googleListId = "";
        public boolean completed = false;

        /** Internal mesh id — excluded from JSON via {@code transient}. */
        public transient String _itemId = "";
        public transient String _listId = "";
    }

    public static class TaskList {
        public String name = "";
        public List<TaskItem> items = new ArrayList<>();

        public transient String _id = "";
    }

    public static class EventItem {
        public String id = "";
        public String title = "";
        public String description = "";
        public String start = "";
        public String end = "";
        public boolean allDay = false;
    }

    public static class UserProfile {
        public String username = "Owner";
        public List<String> habits = new ArrayList<>();
        public List<String> preferences = new ArrayList<>();
        public List<String> dislikes = new ArrayList<>();
        public List<String> goals = new ArrayList<>();
        public List<String> nicknames = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        public boolean restriction_mode = false;
        public List<String> past_conversations_summary = new ArrayList<>();
        public List<TaskList> taskLists = new ArrayList<>();
        public List<String> commitments = new ArrayList<>();
        public List<String> alarms = new ArrayList<>();
        public List<EventItem> events = new ArrayList<>();
    }
}
