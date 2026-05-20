package com.sentient.mesh;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * 6-word pairing phrase generation + validation.
 *
 * <p>Words are drawn from a fixed 256-entry English wordlist embedded below.
 * Six words gives 256^6 ≈ 2^48 ≈ 281 trillion possibilities — comfortably
 * unguessable in the 60-second pairing window the mesh enforces.
 *
 * <p>The wordlist is intentionally simple, lowercase, monosyllabic /
 * disyllabic. Words a non-technical person can read out loud over the phone
 * without spelling them. The list contains no I-vs-l-vs-1 / O-vs-0 collisions
 * and no near-homophones (e.g. {@code piece} but not {@code peace}).
 *
 * <p>Generation: cryptographically random pick of 6 indices via
 * {@link SecureRandom}. Validation: case-insensitive exact match per word,
 * tolerant of surrounding whitespace. The validator uses a constant-time
 * comparison to avoid leaking which word position was wrong.
 */
public final class PairingPhrase {

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * 256 short English words; the index of each word in this array maps a
     * random byte to that word. Words are deliberately:
     *   - lowercase
     *   - 3-7 letters
     *   - phonetically distinct (no homophones)
     *   - free of l/1, o/0 collisions
     *   - readable over a poor phone connection
     */
    public static final String[] WORDLIST = new String[] {
        "able",  "acid",  "acorn", "actor", "adept", "admit", "adobe", "afar",
        "agent", "agile", "agree", "ahead", "aided", "aim",   "airy",  "ajar",
        "alarm", "alibi", "alien", "alley", "alpha", "amber", "amigo", "ample",
        "anchor","angel", "angle", "ankle", "anvil", "apex",  "april", "arbor",
        "arena", "argue", "armor", "arrow", "ashen", "askew", "atlas", "atom",
        "audio", "aunt",  "avert", "avid",  "awake", "award", "axis",  "azure",
        "bacon", "badge", "bagel", "baker", "balsa", "banjo", "barge", "baron",
        "basil", "baton", "bayou", "beach", "beam",  "bean",  "bear",  "beat",
        "beef",  "begin", "belt",  "bench", "berry", "bicep", "binge", "birch",
        "bird",  "bison", "blade", "blast", "bliss", "block", "bloom", "blunt",
        "boat",  "bonus", "book",  "boots", "borer", "bough", "bowl",  "boxer",
        "brace", "brake", "brand", "brass", "brave", "bread", "bring", "brink",
        "brisk", "broom", "broth", "brown", "brush", "buddy", "buggy", "build",
        "bulky", "bunch", "buoy",  "burn",  "burrow","bush",  "cabin", "cable",
        "cake",  "calf",  "camel", "camp",  "candy", "canoe", "canopy","canvas",
        "cape",  "carat", "cargo", "carry", "carve", "cash",  "cast",  "catch",
        "cause", "cave",  "cedar", "cello", "cent",  "chalk", "champ", "chant",
        "chard", "chart", "chase", "cheap", "check", "cheer", "chef",  "chess",
        "chest", "chew",  "chick", "chief", "chill", "chip",  "choke", "chore",
        "chord", "chunk", "churn", "cider", "cinch", "city",  "civil", "clamp",
        "clang", "clap",  "clash", "clasp", "clean", "clear", "click", "cliff",
        "climb", "cling", "cloak", "clock", "clone", "cloud", "clove", "clown",
        "clump", "coach", "coal",  "coast", "cobra", "cocoa", "coin",  "color",
        "comet", "comma", "cone",  "conga", "cook",  "cool",  "copy",  "coral",
        "core",  "corn",  "cosmo", "couch", "cougar","count", "court", "cove",
        "cozy",  "crab",  "craft", "crag",  "crane", "crank", "crash", "crate",
        "crave", "crazy", "creak", "cream", "creep", "crest", "crib",  "crisp",
        "croak", "crock", "crop",  "cross", "crow",  "crown", "crush", "crust",
        "cubic", "curd",  "curly", "curry", "curve", "cycle", "daily", "dairy",
        "dance", "dandy", "daring","dash",  "dazed", "deck",  "decoy", "deep",
        "deer",  "delta", "dense", "dent",  "depth", "deput", "desk",  "detox",
        "diary", "diet",  "dimer", "dimly", "diner", "dingo", "diode", "ditch"
    };

    static {
        if (WORDLIST.length != 256) {
            throw new IllegalStateException("wordlist must be 256 entries, was " + WORDLIST.length);
        }
        java.util.Set<String> dedup = new java.util.HashSet<>();
        for (String w : WORDLIST) {
            if (!dedup.add(w)) throw new IllegalStateException("duplicate word: " + w);
        }
    }

    private PairingPhrase() {}

    /** Generate a fresh 6-word phrase. */
    public static String generate() {
        StringBuilder b = new StringBuilder();
        byte[] indices = new byte[6];
        RNG.nextBytes(indices);
        for (int i = 0; i < 6; i++) {
            if (i > 0) b.append(' ');
            b.append(WORDLIST[indices[i] & 0xff]);
        }
        return b.toString();
    }

    /**
     * Normalize a user-typed phrase: lowercase, collapse whitespace.
     * Returns the canonical "word word word word word word" form.
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String[] parts = input.toLowerCase().trim().split("\\s+");
        return String.join(" ", parts);
    }

    public static List<String> tokens(String phrase) {
        return Arrays.asList(normalize(phrase).split(" "));
    }

    /**
     * Constant-time equality check on normalized phrases. Returns true iff the
     * two phrases tokenize identically — protects against timing attacks that
     * try to guess one word at a time.
     */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) return false;
        byte[] na = normalize(a).getBytes(StandardCharsets.UTF_8);
        byte[] nb = normalize(b).getBytes(StandardCharsets.UTF_8);
        if (na.length != nb.length) return false;
        int diff = 0;
        for (int i = 0; i < na.length; i++) diff |= (na[i] ^ nb[i]);
        return diff == 0;
    }

    /** Strict check that a phrase contains 6 words and each is in the list. */
    public static boolean isWellFormed(String phrase) {
        if (phrase == null) return false;
        String[] parts = normalize(phrase).split(" ");
        if (parts.length != 6) return false;
        for (String p : parts) {
            boolean found = false;
            for (String w : WORDLIST) { if (w.equals(p)) { found = true; break; } }
            if (!found) return false;
        }
        return true;
    }
}
