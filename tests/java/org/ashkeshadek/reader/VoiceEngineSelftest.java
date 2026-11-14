package org.ashkeshadek.reader;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM self-test for the shipped voice-match engine (runs in CI without an
 * Android SDK). The songs below are real catalogue entries extracted when the
 * test was generated; they are embedded as backslash-uXXXX escaped literals
 * so the source file carries no encoding risk.
 *
 * Gates:
 *   1. Every old-spelling alias fires after the NFC fix (the 14 previously
 *      dead patterns can never regress silently).
 *   2. normalize() canonicalises both Bengali encodings of the nukta letters.
 *   3. An exact sung fragment aligns at 1.0 and ranks its song first.
 *   4. A garbled sung fragment still ranks its song first.
 *   5. Ordinary speech produces no candidates at all.
 */
public class VoiceEngineSelftest {
    static final String SONG_A_ID = "1-1";
    static final int SONG_A_VOLUME = 1;
    static final int SONG_A_NUMBER = 1;
    static final String SONG_A_TITLE = "\u0986\u09b2\u09b9\u09be\u09ae\u09a6\u09c1\u09b2\u09bf\u09b2\u09cd\u09b2\u09be\u09b9\u09bf \u09ae\u09a4\u09be \u09b0\u09ac\u09cd\u09ac\u09bf\u09b2 \u0986\u09b2\u09be\u09ae\u09bf\u09a8";
    static final String SONG_A_LYRICS = "\u0986\u09b2\u09b9\u09be\u09ae\u09a6\u09c1\u09b2\u09bf\u09b2\u09cd\u09b2\u09be\u09b9\u09bf \u09ae\u09a4\u09be \u09b0\u09ac\u09cd\u09ac\u09bf\u09b2 \u0986\u09b2\u09be\u09ae\u09bf\u09a8\n\u09ac\u09bf\u099b\u09ae\u09bf\u09b2\u09cd\u09b2\u09be\u09b9\u09bf\u09b0 \u09b0\u09b9\u09ae\u09be\u09a8\u09bf\u09b0 \u09b0\u09b9\u09bf\u09ae \u0986\u09ae\u09bf\u09a8\u0964 \u0990\n\u09b9\u09be\u09ae\u09a6\u09c7\u09b0 \u09aa\u09b0\u09c7 \u099b\u09be\u09a8\u09be \u09aa\u09dc\u09bf \u09ae\u09be\u09b2\u09bf\u0995\u09bf \u098f\u09df\u09be\u0993\u09ae\u09bf\u09a6\u09cd\u09a6\u09c0\u09a8\n\u0987\u09df\u09be\u0995\u09be\u09a8\u09be\u2019\u09ac\u09c1\u09a6\u09c1 \u098f\u09ac\u09be\u09a6\u09a4\u09c7\u09b0 \u099a\u09bf\u09a8,\n\u09ae\u09a6\u09a6 \u0993-\u0987\u09df\u09be\u0995\u09be \u09a8\u09be\u09b8\u09cd\u09a4\u09be\u09df\u09c0\u09a8 \u09b9\u09c7\u09a6\u09be\u09df\u09a4\u09c7\u09b0 \u099a\u09bf\u09a8,\n\u0987\u09b9\u09a6\u09bf\u09a8\u09be\u099a-\u099b\u09bf\u09b0\u09be\u09a4\u09be\u09b2 \u09ae\u09c1\u09b8\u09cd\u09a4\u09be\u0995\u09c0\u09ae \u0986\u09ae\u09bf\u09a8\u0964 \u0990\n\u09a6\u09b0\u09a6 \u09a6\u09c1\u0983\u0996 \u09ae\u09be\u09a8 \u09ae\u09b0\u09cd\u09af\u09be\u09a6\u09be \u09b8\u0995\u09b2 \u0996\u09cb\u09a6\u09be\u09b0 \u099f\u09be\u0987,\n\u0986\u09ae\u09bf \u09a6\u09be\u09b8\u09c7 \u09a4\u09cb\u09ae\u09be\u09b0 \u0995\u09be\u099b\u09c7 \u09b8\u09cb\u099c\u09be \u09b0\u09be\u09b8\u09cd\u09a4\u09be \u099a\u09be\u0987,\n\u0989\u09aa\u09b0 \u099b\u09bf\u09b0\u09be\u09a4\u09be\u09b2\u09cd\u09b2\u09be\u099c\u09bf\u09a8\u09be \u0986\u09a8-\u0986\u09ae\u09a4\u09be \u0986\u09b2\u09be\u0987\u09b9\u09bf\u09ae,\n\u0997\u09be\u0987\u09b0\u09bf\u09b2 \u09ae\u09be\u0997\u09a6\u09c1\u09ac\u09bf \u0986\u09b2\u09be\u0987\u09b9\u09bf\u09ae \u0993\u09df\u09be\u09b2\u09be\u09a6\u09cd\u09a6\u09cb\u09df\u09be\u09b2\u09cd\u09b2\u09bf\u09a8\u0964 \u0990\n\u09b2\u0995\u09cd\u09ac\u09a6 \u0995\u09b0\u09b0\u09ae\u09a8\u09be \u09ac\u09a8\u09c0 \u0986\u09a6\u09ae\u09be \u09a8\u09ac\u09c0,\n\u09ae\u09cb\u09b9\u09be\u09ae\u09cd\u09ae\u09a6\u09c7 \u09a7\u09b0\u09bf \u09ac\u09b8\u09c7 \u0986\u09b9\u09be\u09a6\u09c0 \u099b\u09ac\u09bf,\n\u09b2\u09be\u09a4\u0995\u09a8\u09be\u09a4\u09c1 \u0998\u09cb\u09b7\u09a3\u09be \u0995\u09b0\u09c7 \u09b0\u09b9\u09ae\u09a4 \u0986\u09b2\u09be\u09ae\u09bf\u09a8,\n\u09a8\u09c1\u09b0\u09c7 \u0986\u09b2\u09cd\u09b2\u09be\u09b9 \u09a8\u09c1\u09b0 \u09ae\u09cb\u09b9\u09be\u09ae\u09cd\u09ae\u09a6 \u09a8\u09c1\u09b0\u09c7 \u0986\u09b2\u09be\u09ae\u09bf\u09a8\u0964 \u0990\n\u09b9\u09be\u099c\u09be\u09b0 \u09b2\u0995\u09cd\u09b7 \u0995\u09cb\u099f\u09bf \u09a6\u09b0\u09c1\u09a6 \u09a8\u09ac\u09c0\u09b0 \u0989\u09aa\u09b0,\n\u09aa\u09be\u09a0\u09be\u0993 \u09b9\u09c7, \u0996\u09cb\u09a6\u09be \u09a4\u09c1\u09ae\u09bf \u09ae\u09cb\u09b8\u09cd\u09a4\u09ab\u09be\u09b0 \u0989\u09aa\u09b0,\n\u09ae\u09be\u09a8\u09ac \u09aa\u09a5 \u09ad\u09cd\u09b0\u09b7\u09cd\u099f \u09b9\u0987\u09a4\u09c7 \u09b8\u09c1\u09aa\u09a5\u09c7\u09b0 \u099c\u09be\u09ae\u09bf\u09a8,\n\u09b2\u09be \u0987\u09b2\u09be\u09b9\u09be \u0995\u09b2\u09ae\u09be \u09a6\u09bf\u09df\u09c7 \u09a4\u09cd\u09ac\u09b0\u09be\u0993 \u09b0\u09c7 \u0986\u09ae\u09bf\u09a8\u0964 \u0990\n\u0988\u09ae\u09be\u09a8 \u09b9\u09be\u09b0\u09be \u099c\u09be\u09b9\u09c7\u09b2\u0997\u09a3\u0995\u09c7 \u0995\u09b0\u09bf \u0988\u09ae\u09be\u09a8 \u09a6\u09be\u09a8,\n\u09b6\u09cd\u09b0\u09c7\u09b7\u09cd\u099f \u099c\u09c0\u09ac \u09ac\u09be\u09a8\u09be\u0987\u09b2 \u0986\u09b2\u09cd\u09b2\u09be\u09b9 \u09aa\u09be\u0995 \u099b\u09cb\u09ac\u09be\u09b9\u09be\u09a8,\n\u099c\u0997\u09ce\u09ac\u09be\u09b8\u09bf \u0989\u09a6\u09cd\u09a7\u09be\u09b0\u09bf\u09b2 \u09a6\u09bf\u09df\u09be \u0996\u09cb\u09a6\u09be\u09b0 \u099a\u09bf\u09a8,\n\u09b0\u09ac\u09cd\u09ac\u09c1\u09b2 \u0986\u09b2\u09be\u09ae\u09bf\u09a8 \u09b9\u09df\u09c7 \u09b0\u09b9\u09ae\u09a4 \u0986\u09b2\u09be\u09ae\u09bf\u09a8\u0964 \u0990\n\u09ad\u09be\u09a3\u09cd\u09a1\u09be\u09b0 \u09a6\u09df\u09be\u09b0 \u09a8\u09c2\u09b0\u09c7 \u0986\u09b2\u09cd\u09b2\u09be\u09b9 \u09ae\u09cb\u09b8\u09cd\u09a4\u09ab\u09be \u09b0\u099b\u09c1\u09b2,\n\u09a8\u09c1\u09b0\u09c7 \u09a8\u09c1\u09b0\u09c7 \u09a8\u09c1\u09b0 \u09ae\u09bf\u09b6\u09be\u0987\u09df\u09be \u0995\u09b0\u09bf\u09b2 \u09ae\u0995\u09ac\u09c1\u09b2,\n\u09a8\u09b0\u0995 \u0985\u09a8\u09b2 \u09b9\u0987\u09a4\u09c7 \u09ac\u09be\u0981\u099a\u09be\u09df \u09b6\u09be\u09a8\u09cd\u09a4\u09bf\u09b0 \u099c\u09be\u09ae\u09bf\u09a8,\n\u09a6\u09bf\u09a4\u09c7 \u099a\u09c7\u09b7\u09cd\u099f\u09be \u0995\u09b0\u09c7 \u09b0\u09b9\u09ae \u09b0\u09ac\u09cd\u09ac\u09c1\u09b2 \u0986\u09b2\u09be\u09ae\u09bf\u09a8\u0964 \u0990\n\u09a1\u09be\u09b2\u09c7 \u09a8\u09bf\u099c \u09b0\u09c1\u09b9 \u0986\u09a6\u09ae\u09c7\u09a4\u09c7 \u09aa\u09cd\u09b0\u09c7\u09ae\u09c7 \u09ae\u09a4\u09cd\u09a4 \u09b9\u0987,\n\u09b8\u09c7\u09b0\u09c2\u09aa\u09c7 \u0997\u09cc\u09b0\u09ac\u09c7\u09a4\u09c7 \u09a8\u09bf\u099c\u09c7 \u09ae\u09c1\u0997\u09cd\u09a7 \u09b9\u0987,\n\u09b9\u09c3\u09a6\u09df \u0986\u09a6\u09ae \u09af\u09be\u09b9\u09be\u09b0 \u099b\u09bf\u09ab\u09a4 \u0995\u09b0\u09bf\u09df\u09be \u09a6\u09b0\u09cd\u09aa\u09a3,\n\u09ae\u09cb\u09ae\u09c7\u09a8 \u09b9\u09c3\u09a6\u09df \u09af\u09bf\u09a8\u09bf \u09a6\u09bf\u09b2 \u09a6\u09b0\u09b6\u09a8 \u0986\u09ae\u09bf\u09a8\u0964 \u0990";
    static final String SONG_A_FIRST_LINE = "\u0986\u09b2\u09b9\u09be\u09ae\u09a6\u09c1\u09b2\u09bf\u09b2\u09cd\u09b2\u09be\u09b9\u09bf \u09ae\u09a4\u09be \u09b0\u09ac\u09cd\u09ac\u09bf\u09b2 \u0986\u09b2\u09be\u09ae\u09bf\u09a8";
    static final String SONG_B_ID = "2-1";
    static final int SONG_B_VOLUME = 2;
    static final int SONG_B_NUMBER = 1;
    static final String SONG_B_TITLE = "\u0986\u0981\u09a7\u09be\u09b0 \u0998\u09b0\u099f\u09bf \u09ac\u09bf\u09b6\u09cd\u09ac\u09be\u09b8\u09c7\u09b0\u09bf";
    static final String SONG_B_LYRICS = "\u0986\u0981\u09a7\u09be\u09b0 \u0998\u09b0\u099f\u09bf \u09ac\u09bf\u09b6\u09cd\u09ac\u09be\u09b8\u09c7\u09b0\u09bf \u0986\u09b2\u09cb \u099c\u09cd\u09ac\u09be\u09b2\u09be\u09df \u09a4\u09be\u09b2\u09be\u09b6 \u0995\u09b0 \u09a6\u09c7\u0996\u09ac\u09bf\u09b0\u09c7 \u09a4\u09c1\u0987 \u09ae\u09cc\u09b2\u09be \u0995\u09bf \u09b8\u09c1\u09a8\u09cd\u09a6\u09b0\u0964\n\u09b9\u09b2 \u0995\u09bf \u09ae\u09a8 \u0995\u09aa\u09be\u09b2 \u09aa\u09cb\u09dc\u09be \u09ad\u09be\u0999\u09cd\u0997\u09be \u0985\u09a8\u09cd\u09a4\u09b0 \u09b2\u09df\u09a8\u09be \u099c\u09cb\u09dc\u09be\n\u09ae\u09a8 \u09a4\u09c1\u0987 \u09b9\u09b2\u09bf \u09ae\u09c1\u09a8\u09bf\u09ac \u099a\u09cb\u09b0\u09be \u0995\u09b0\u09b2\u09bf\u09a8\u09be \u09ae\u09c1\u09a8\u09bf\u09ac \u09ad\u099c\u09a8\n\u09a6\u09ae\u09a8\u09bf \u09b6\u09bf\u09b0\u09be\u09df \u09b6\u09bf\u09b0\u09be\u09df \u09a5\u09be\u0995\u09bf \u09a4\u09cb\u09b0\u09c7 \u09a8\u09be\u09ae\u09b2\u0987 \u0985\u09a8\u09c7\u0995 \u09a1\u09be\u0995\u09bf\n\u0989\u09b9\u09c1\u09a4\u09c7 \u09af\u09be\u0987 \u09a6\u09c7\u0996\u09be \u0995\u09b0\u09bf \u09a8\u09c1\u09b0\u09c0 \u099a\u09b0\u09a3 \u099c\u09dc\u09bf\u09df\u09c7 \u09a7\u09b0\u0964\u0964\n\u09b2\u0987 \u09ae\u09c1\u09a8\u09bf\u09ac \u09a8\u09be\u09ae \u09ae\u09c1\u0996\u09c7 \u099c\u09be\u09b0\u09bf \u099c\u09bf\u0995\u09bf\u09b0 \u09b9\u09be\u09a4\u09c1\u09dc \u0995\u09b2\u09ac\u09c7 \u09ae\u09be\u09b0\u09bf\n\u09b2\u09be\u0997\u09be\u0987 \u0985\u099c\u09c1\u09a6 \u09ac\u09b0\u099c\u0996\u09c0 \u09a1\u09be\u0995 \u0986\u0981\u09a7\u09be\u09b0 \u0995\u09b2\u09ac \u0986\u09b2\u09cb \u0995\u09b0\n\u09b9\u0987\u09b2\u09c7 \u09ae\u09a8 \u09a4\u09c1\u0987 \u09ae\u09c1\u09a8\u09bf\u09ac \u099a\u09be\u0995\u09b0 \u0986\u09b2\u09cb\u0995\u09bf\u09a4 \u0995\u09b2\u09ac \u0985\u09a8\u09cd\u09a4\u09b0\n\u09ae\u09be\u09df\u09be-\u09a6\u09df\u09be\u09df \u0995\u09a4 \u0986\u09a6\u09b0 \u09a4\u09c1\u0987 \u09b9\u0987 \u09af\u09be\u09ac\u09bf \u098f\u0995\u09be\u09b6\u09cd\u09ac\u09b0\u0964\u0964";
    static final String SONG_B_FIRST_LINE = "\u0986\u0981\u09a7\u09be\u09b0 \u0998\u09b0\u099f\u09bf \u09ac\u09bf\u09b6\u09cd\u09ac\u09be\u09b8\u09c7\u09b0\u09bf \u0986\u09b2\u09cb \u099c\u09cd\u09ac\u09be\u09b2\u09be\u09df \u09a4\u09be\u09b2\u09be\u09b6 \u0995\u09b0 \u09a6\u09c7\u0996\u09ac\u09bf\u09b0\u09c7 \u09a4\u09c1\u0987 \u09ae\u09cc\u09b2\u09be \u0995\u09bf \u09b8\u09c1\u09a8\u09cd\u09a6\u09b0\u0964";
    private static final String ORDINARY_SPEECH = "\u0986\u099c \u09ac\u09bf\u0995\u09c7\u09b2 \u09a6\u09cb\u0995\u09be\u09a8 \u09ac\u09be\u099c\u09be\u09b0";

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        testAliasesAllFire();
        testNormalizeCanonicalForm();
        testFoldClasses();
        testExactFragmentRanksFirst();
        testGarbledFragmentRanksFirst();
        testOrdinarySpeechIsRejected();
        report();
    }

    private static void testAliasesAllFire() {
        for (String[] alias : VoiceMatchEngine.OLD_BENGALI_ALIASES) {
            String modernized = VoiceMatchEngine.normalize(
                    VoiceMatchEngine.modernizeOldBengali(alias[0]));
            check(modernized.equals(VoiceMatchEngine.normalize(alias[1])),
                    "alias fires: " + alias[0] + " -> " + alias[1]
                            + " (got " + modernized + ")");
        }
    }

    private static void testNormalizeCanonicalForm() {
        // Precomposed and decomposed য় must fold to one canonical string.
        String precomposed = VoiceMatchEngine.normalize(
                "\u09b9\u0987\u09df\u09be\u099b\u09c7");
        String decomposed = VoiceMatchEngine.normalize(
                "\u09b9\u0987\u09af\u09bc\u09be\u099b\u09c7");
        check(precomposed.equals(decomposed),
                "nukta encodings canonicalise identically");
        check(VoiceMatchEngine.normalize("\u09a2\u09bc").equals(
                        VoiceMatchEngine.normalize("\u09a1\u09bc")),
                "ঢ় folds to ড়");
    }

    private static void testFoldClasses() {
        char[] fold = VoiceMatchEngine.VOICE_FOLD;
        check(fold['\u09b6'] == fold['\u09b8'], "শ folds to স");
        check(fold['\u09a3'] == fold['\u09a8'], "ণ folds to ন");
        check(fold['\u09c0'] == fold['\u09bf'], "ী folds to ি");
        check(fold['\u0995'] == fold['\u0996'],
                "shipped fold depth merges the aspiration pair ক/খ");
    }

    private static void testExactFragmentRanksFirst() {
        List<Song> songs = catalogue();
        Map<String, SearchDocument> index = buildIndex(songs);
        String fragment = VoiceMatchEngine.normalize(SONG_A_FIRST_LINE);
        double similarity = VoiceMatchEngine.similarity(fragment,
                VoiceMatchEngine.normalize(SONG_A_TITLE + " " + SONG_A_LYRICS),
                VoiceMatchEngine.VOICE_FOLD);
        check(similarity == 1.0, "exact fragment aligns at 1.0 (got " + similarity + ")");
        VoiceMatchEngine.MatchResult result = VoiceMatchEngine.compute(
                songs, index, SONG_A_FIRST_LINE);
        check(!result.candidates.isEmpty(), "exact fragment admits candidates");
        check(result.candidates.get(0).song.id.equals(SONG_A_ID),
                "exact fragment ranks song A first");
    }

    private static void testGarbledFragmentRanksFirst() {
        List<Song> songs = catalogue();
        Map<String, SearchDocument> index = buildIndex(songs);
        String garbled = garble(SONG_A_FIRST_LINE);
        VoiceMatchEngine.MatchResult result = VoiceMatchEngine.compute(
                songs, index, garbled);
        check(!result.candidates.isEmpty(),
                "garbled fragment admits candidates");
        if (!result.candidates.isEmpty()) {
            check(result.candidates.get(0).song.id.equals(SONG_A_ID),
                    "garbled fragment ranks song A first (got "
                            + result.candidates.get(0).song.id
                            + ", sim=" + result.candidates.get(0).similarity + ")");
            check(result.candidates.get(0).similarity
                            >= VoiceMatchEngine.VOICE_ALIGN_THRESHOLD,
                    "garbled fragment clears the alignment threshold");
        }
    }

    private static void testOrdinarySpeechIsRejected() {
        List<Song> songs = catalogue();
        Map<String, SearchDocument> index = buildIndex(songs);
        VoiceMatchEngine.MatchResult result = VoiceMatchEngine.compute(
                songs, index, ORDINARY_SPEECH);
        check(result.candidates.isEmpty(),
                "ordinary speech produces no candidates (got "
                        + result.candidates.size() + ")");
    }

    /** Deterministic mixed garbling: substitutions, deletions, insertions. */
    private static String garble(String text) {
        StringBuilder out = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (i % 13 == 4) ch = text.charAt(n - 1 - i);
            if (i % 17 == 9) continue;
            out.append(ch);
            if (i % 11 == 6 && out.length() > 0) {
                out.append(out.charAt(out.length() - 1));
            }
        }
        return out.toString();
    }

    private static List<Song> catalogue() {
        List<Song> songs = new ArrayList<>();
        songs.add(new Song(SONG_A_ID, SONG_A_VOLUME, SONG_A_NUMBER,
                SONG_A_TITLE, SONG_A_LYRICS, "", new ArrayList<>()));
        songs.add(new Song(SONG_B_ID, SONG_B_VOLUME, SONG_B_NUMBER,
                SONG_B_TITLE, SONG_B_LYRICS, "", new ArrayList<>()));
        return songs;
    }

    private static Map<String, SearchDocument> buildIndex(List<Song> songs) {
        Map<String, SearchDocument> index = new HashMap<>();
        for (Song song : songs) {
            String corpus = song.title + " " + song.lyrics;
            String modern = VoiceMatchEngine.modernizeOldBengali(corpus);
            index.put(song.id, new SearchDocument(
                    VoiceMatchEngine.normalize(corpus),
                    VoiceMatchEngine.normalize(modern),
                    VoiceMatchEngine.phoneticNormalize(corpus),
                    VoiceMatchEngine.normalize(song.title),
                    VoiceMatchEngine.searchWords(corpus),
                    VoiceMatchEngine.searchWords(modern)));
        }
        return index;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void report() {
        System.out.println("voice engine self-test: " + checks
                + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
