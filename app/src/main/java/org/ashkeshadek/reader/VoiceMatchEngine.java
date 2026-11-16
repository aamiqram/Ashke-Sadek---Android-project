package org.ashkeshadek.reader;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalogue-side text matching: normalisation, the old-spelling bridge,
 * the strict word scorer and the semi-global alignment matcher for voice.
 *
 * Pure JDK on purpose: the CI self-test
 * (tests/java/org/ashkeshadek/reader/VoiceEngineSelftest.java) compiles and
 * runs this file without an Android SDK, so the shipped matcher is exercised
 * directly on every build instead of only through a Python mirror.
 *
 * Bengali text enters here in two shapes: catalogue text (old Sadhu-Bhasha
 * spellings, precomposed য়/ড়/ঢ় from the Word master) and recognizer text
 * (modern spellings, either encoding). Both are folded into one canonical
 * form before any comparison; see normalize() for why the nukta letters
 * cannot simply be left to Unicode NFC.
 */
final class VoiceMatchEngine {
    private VoiceMatchEngine() { }

    private static final Pattern WORD_PATTERN =
            Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern NORMALIZE_SEPARATOR_PATTERN =
            Pattern.compile("[\\p{Punct}\\p{Z}\\s\\u200c\\u200d]+");

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    /**
     * Canonical search form. NFC first, then the folds the catalogue and the
     * recognizer actually need:
     *
     * - য়/ড়/ঢ় are Unicode composition exclusions: NFC decomposes the
     *   precomposed letters (U+09DF/U+09DC/U+09DD) into letter+nukta and
     *   never composes them back, whichever way they arrived. The canonical
     *   precomposed forms are rebuilt explicitly so that both sides of every
     *   comparison hold the same codepoints.
     * - Any nukta that did not attach to য/ড/ঢ is unpronounced; strip it.
     * - Assamese ৰ/ৱ fold to their Bengali neighbours, khanda-ta to ta.
     * - Separators (punctuation, spaces, ZWNJ/ZWJ) never carry meaning in a
     *   sung fragment; remove them so matching runs on a letter stream.
     */
    static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).trim();
        s = s.replace("\u09af\u09bc", "\u09df")   // য + nukta -> precomposed য়
                .replace("\u09a1\u09bc", "\u09dc") // ড + nukta -> ড়
                .replace("\u09a2\u09bc", "\u09dc") // ঢ + nukta -> ড়
                .replace("\u09dd", "\u09dc")       // leftover ঢ় -> ড়
                .replace("\u09f0", "\u09b0")       // ৰ -> র
                .replace("\u09f1", "\u0993")       // ৱ -> ও
                .replace("\u09bc", "")             // stray nukta
                .replace("\u09ce", "\u09a4");      // ৎ -> ত
        return NORMALIZE_SEPARATOR_PATTERN.matcher(s).replaceAll("");
    }

    // The table is written in the .java file in whatever encoding the editor
    // saved; 16 of these 30 patterns used to be dead because the precomposed
    // য় inside them cannot survive the NFC step above. Class init normalizes
    // both sides once, so pattern matching happens between two post-NFC forms.
    static final String[][] OLD_BENGALI_ALIASES = {
                    {"হইয়াছে", "হয়েছে"}, {"হইয়াছে", "হয়েছে"}, {"হইয়াছি", "হয়েছি"},
                    {"করিয়াছি", "করেছি"}, {"করিয়াছে", "করেছে"}, {"করিয়াছেন", "করেছেন"},
                    {"হইয়া", "হয়ে"}, {"হইয়ে", "হয়ে"}, {"হইলে", "হলে"},
                    {"হইতে", "হতে"}, {"হইবে", "হবে"}, {"হইল", "হল"},
                    {"করিয়া", "করে"}, {"করিতে", "করতে"}, {"করিতেছে", "করছে"},
                    {"করতেছে", "করছে"}, {"যাইতেছে", "যাচ্ছে"}, {"যাইয়া", "গিয়ে"},
                    {"দিয়া", "দিয়ে"}, {"লইয়া", "নিয়ে"}, {"পাইয়া", "পেয়ে"},
                    {"খাইয়া", "খেয়ে"}, {"বলিয়া", "বলে"}, {"দেখিয়া", "দেখে"},
                    {"তাহার", "তার"}, {"তাহাকে", "তাকে"}, {"যাহা", "যা"},
                    {"যাহার", "যার"}, {"যাহাতে", "যাতে"}, {"ইহা", "এটা"}
    };
    static final String[] ALIAS_FROM = new String[OLD_BENGALI_ALIASES.length];
    static final String[] ALIAS_TO = new String[OLD_BENGALI_ALIASES.length];
    private static final Pattern[] ALIAS_PATTERNS =
            new Pattern[OLD_BENGALI_ALIASES.length];
    static {
        for (int i = 0; i < OLD_BENGALI_ALIASES.length; i++) {
            ALIAS_FROM[i] = Normalizer.normalize(OLD_BENGALI_ALIASES[i][0],
                    Normalizer.Form.NFC);
            ALIAS_TO[i] = Normalizer.normalize(OLD_BENGALI_ALIASES[i][1],
                    Normalizer.Form.NFC);
            // Word-boundary guards: the replacement must not fire inside a
            // longer word that merely contains the pattern.
            ALIAS_PATTERNS[i] = Pattern.compile("(?<![\\p{L}\\p{M}])"
                    + Pattern.quote(ALIAS_FROM[i])
                    + "(?![\\p{L}\\p{M}])");
        }
    }

    /**
     * Search-only spelling bridge: Sadhu-Bhasha verb forms (হইয়াছে) become
     * their modern forms (হয়েছে). Displayed lyrics are never rewritten.
     */
    static String modernizeOldBengali(String value) {
        String result = Normalizer.normalize(value == null ? "" : value,
                Normalizer.Form.NFC);
        for (int i = 0; i < ALIAS_PATTERNS.length; i++) {
            result = ALIAS_PATTERNS[i].matcher(result)
                    .replaceAll(Matcher.quoteReplacement(ALIAS_TO[i]));
        }
        return result;
    }

    /** Third search form: consonant/vowel quality folded away. */
    static String phoneticNormalize(String value) {
        String result = normalize(value);
        return result.replace("\u09b6", "\u09b8")  // শ -> স
                .replace("\u09b7", "\u09b8")       // ষ -> স
                .replace("\u09a3", "\u09a8")       // ণ -> ন
                .replace("\u09c0", "\u09bf")       // ী -> ি
                .replace("\u0988", "\u0987")       // ঈ -> ই
                .replace("\u09c2", "\u09c1")       // ূ -> ু
                .replace("\u098a", "\u0989")       // ঊ -> উ
                .replace("\u09ce", "\u09a4");      // ৎ -> ত
    }

    static Set<String> searchWords(String value) {
        Set<String> words = new java.util.HashSet<>();
        if (value == null) return words;
        Matcher matcher = WORD_PATTERN.matcher(value);
        while (matcher.find()) {
            String word = normalize(matcher.group());
            if (word.length() >= 2) words.add(word);
        }
        return words;
    }

    static SearchQuery prepareSearchQuery(String value) {
        String original = value == null ? "" : value;
        LinkedHashSet<String> uniqueTerms = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(original);
        while (matcher.find()) {
            String token = normalize(matcher.group());
            if (token.length() >= 2) uniqueTerms.add(token);
        }
        List<SearchQuery.SearchTerm> terms =
                new ArrayList<>(uniqueTerms.size());
        for (String token : uniqueTerms) {
            String modern = normalize(modernizeOldBengali(token));
            terms.add(new SearchQuery.SearchTerm(token, modern,
                    phoneticNormalize(token)));
        }
        return new SearchQuery(normalize(original),
                normalize(modernizeOldBengali(original)),
                phoneticNormalize(original), terms);
    }

    // ------------------------------------------------------------------
    // Sketch prefilter (cheap first pass over all 583 documents)
    // ------------------------------------------------------------------

    /** Character n-gram length for the sketch prefilter. */
    static final int VOICE_GRAM_SIZE = 3;
    /** Evenly sampled gram hashes kept per document. */
    static final int VOICE_GRAM_SKETCH_LIMIT = 256;
    /**
     * Sketch recall gate as a fraction of the transcript's grams expected to
     * appear in the document sketch (scaled by the sketch's sample fraction).
     * Keeps alignment CPU off documents that share almost nothing. Measured
     * at 0.20: heavy sung garbling keeps the true song in the shortlist while
     * ordinary-speech blobs still produce zero candidates (see
     * tests/calibrate_voice_fuzzy.py); depths below 0.20 changed nothing.
     */
    static final double VOICE_SKETCH_GATE = 0.20;
    /** Transcripts keep growing; only the tail window drives voice scoring. */
    static final int VOICE_QUERY_WINDOW = 260;

    static long[] buildGramSketch(String normalized) {
        int count = normalized.length() - VOICE_GRAM_SIZE + 1;
        if (count <= 0) return new long[0];
        int keep = Math.min(count, VOICE_GRAM_SKETCH_LIMIT);
        long[] sketch = new long[keep];
        for (int i = 0; i < keep; i++) {
            int start = (int) ((long) i * count / keep);
            sketch[i] = gramHash(normalized, start, start + VOICE_GRAM_SIZE);
        }
        java.util.Arrays.sort(sketch);
        return sketch;
    }

    private static long gramHash(String text, int start, int end) {
        long hash = 0xcbf29ce484222325L;
        for (int i = start; i < end; i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean sketchContains(long[] sketch, long hash) {
        int low = 0, high = sketch.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long value = sketch[mid];
            if (value == hash) return true;
            if (value < hash) low = mid + 1; else high = mid - 1;
        }
        return false;
    }

    static int countGramsInSketch(String query, long[] sketch) {
        int count = query.length() - VOICE_GRAM_SIZE + 1;
        if (count <= 0) return 0;
        int hits = 0;
        for (int i = 0; i < count; i++) {
            if (sketchContains(sketch, gramHash(query, i, i + VOICE_GRAM_SIZE))) {
                hits++;
            }
        }
        return hits;
    }

    private static int sketchGate(SearchDocument doc, int grams) {
        double sampleFraction =
                doc.gramSketch.length / (double) Math.max(1, doc.gramCount);
        return Math.max(2, (int) Math.ceil(
                sampleFraction * grams * VOICE_SKETCH_GATE));
    }

    private static String tail(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(text.length() - limit);
    }

    // ------------------------------------------------------------------
    // Semi-global alignment matcher
    // ------------------------------------------------------------------

    /**
     * Sung audio garbles words: vowel signs drop, similar consonants swap,
     * the recognizer hears modern spellings for Sadhu-Bhasha lines. The
     * strict word scorer then finds nothing. The aligner instead scores the
     * whole transcript window against the lyric stream:
     *
     * - semi-global: the transcript must be aligned end to end (every word
     *   the reader sang has to land somewhere), while the song text may
     *   start and end anywhere — a sung line sits mid-song.
     * - fold classes let near-identical letters match before falling to a
     *   substitution penalty (see FOLD_*).
     *
     * similarity() maps the best alignment to 0..1, 1.0 being a verbatim
     * substring. Calibration (tests/calibrate_voice_fuzzy.py over the real
     * catalogue, seeded) picks the threshold, fold depth and score mapping;
     * keep both sides in sync when re-tuning.
     */
    private static final int ALIGN_MATCH = 2;
    private static final int ALIGN_MISMATCH = -1;
    private static final int ALIGN_GAP = -1;
    /** Fold depth for voice alignment; both built from buildFold(). */
    static final char[] VOICE_FOLD = buildFold(false);
    /**
     * Alignment admission threshold. Calibration on the real catalogue
     * (tests/calibrate_voice_fuzzy.py): ordinary-speech blobs produce zero
     * candidates from 0.43 upward, confusable twins never steal the lead,
     * and lowering from 0.52 to 0.45 lifts heavy-garble top-3 recall from
     * 72-81% to 86-95% plus two real-singing takes that sat just under the
     * old bar (sims 0.500 and 0.451).
     */
    static final double VOICE_ALIGN_THRESHOLD = 0.45;
    /** Align anything shorter than this only wastes CPU; ~3+ short words. */
    static final int VOICE_ALIGN_MIN_CHARS = 14;
    /** Shortlist cap for the alignment stage, ordered by sketch evidence. */
    static final int VOICE_ALIGN_SHORTLIST = 24;
    /** Strict phrase containment at this level already decides the match. */
    static final int VOICE_STRICT_DECISIVE = 1800;
    /** Aligner score mapping: threshold -> ALIGNER_BASE, sim 1.0 -> base+span. */
    static final int ALIGNER_BASE = 400;
    static final int ALIGNER_SPAN = 1000;

    /**
     * Fold tables: FOLD[ch] is the class representative every member of that
     * class folds to; identical classes may match for full credit. Chars
     * outside the table fold to themselves. Light keeps only the folds the
     * app's own normalisation already trusts; aggressive adds the
     * aspiration/nukta/ra-sound confusions that singing produces. Which depth
     * ships is measured by tests/calibrate_voice_fuzzy.py.
     */
    private static final int FOLD_TABLE_SIZE = 0x0a00;

    private static char[] newFoldTable() {
        char[] table = new char[FOLD_TABLE_SIZE];
        for (int i = 0; i < table.length; i++) table[i] = (char) i;
        return table;
    }

    /**
     * Unions every member into one class rooted at the first member. Classes
     * may chain (ঢ় already sits in ড়'s class; a later ড≡ঢ union merges the
     * whole family), so existing roots are remapped, not overwritten.
     */
    private static void foldClass(char[] table, String members) {
        char canonical = members.charAt(0);
        for (int i = 0; i < members.length(); i++) {
            char ch = members.charAt(i);
            if (ch >= FOLD_TABLE_SIZE) continue;
            char old = table[ch];
            if (old == canonical) continue;
            for (int c = 0; c < FOLD_TABLE_SIZE; c++) {
                if (table[c] == old) table[c] = canonical;
            }
        }
    }

    private static char[] buildFold(boolean light) {
        char[] table = newFoldTable();
        // Folds the normalisation itself trusts (harmless if already folded).
        foldClass(table, "\u09b6\u09b7\u09b8"); // শ ষ স
        foldClass(table, "\u09a3\u09a8");       // ণ ন
        foldClass(table, "\u09bf\u09c0");       // ি ী
        foldClass(table, "\u0987\u0988");       // ই ঈ
        foldClass(table, "\u09c1\u09c2");       // ু ূ
        foldClass(table, "\u0989\u098a");       // উ ঊ
        foldClass(table, "\u09ce\u09a4");       // ৎ ত
        foldClass(table, "\u09dc\u09dd");       // ড় ঢ়
        foldClass(table, "\u09f0\u09b0");       // ৰ র
        foldClass(table, "\u09f1\u0993");       // ৱ ও
        if (!light) {
            // Confusions singing actually produces; measured by calibration.
            foldClass(table, "\u09df\u09af");   // য় য
            foldClass(table, "\u09dc\u09a1\u09b0"); // ড় ড র
            foldClass(table, "\u09dd\u09a2");   // ঢ় ঢ
            foldClass(table, "\u0995\u0996");   // ক খ
            foldClass(table, "\u0997\u0998");   // গ ঘ
            foldClass(table, "\u099a\u099b");   // চ ছ
            foldClass(table, "\u099c\u099d");   // জ ঝ
            foldClass(table, "\u099f\u09a0");   // ট ঠ
            foldClass(table, "\u09a1\u09a2");   // ড ঢ
            foldClass(table, "\u09a4\u09a5");   // ত থ
            foldClass(table, "\u09a6\u09a7");   // দ ধ
            foldClass(table, "\u09aa\u09ab");   // প ফ
            foldClass(table, "\u09ac\u09ad");   // ব ভ
            foldClass(table, "\u0982\u0983\u0981"); // ং ঃ ঁ
            foldClass(table, "\u09c7\u09c8");   // ে ৈ
            foldClass(table, "\u09cb\u09cc");   // ো ৌ
        }
        return table;
    }

    private static int fold(char[] table, char ch) {
        return ch < FOLD_TABLE_SIZE ? table[ch] : ch;
    }

    /**
     * Best semi-global alignment score of query against text, normalised to
     * 0..1 (1.0 = every query character matched). Two-row DP, O(m*n), no
     * allocations inside the hot loop.
     */
    static double similarity(String query, String text, char[] fold) {
        int m = query.length(), n = text.length();
        if (m == 0 || n == 0) return 0.0;
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        // Row 0 stays 0: the song text may begin after any prefix.
        for (int i = 1; i <= m; i++) {
            int q = fold(fold, query.charAt(i - 1));
            cur[0] = prev[0] + ALIGN_GAP;
            for (int j = 1; j <= n; j++) {
                int diag = prev[j - 1]
                        + (q == fold(fold, text.charAt(j - 1))
                        ? ALIGN_MATCH : ALIGN_MISMATCH);
                int left = cur[j - 1] + ALIGN_GAP;
                int up = prev[j] + ALIGN_GAP;
                int best = diag >= left ? diag : left;
                cur[j] = best >= up ? best : up;
            }
            int[] swap = prev; prev = cur; cur = swap;
        }
        int best = prev[0];
        for (int j = 1; j <= n; j++) if (prev[j] > best) best = prev[j];
        if (best <= 0) return 0.0;
        double similarity = best / (double) (m * ALIGN_MATCH);
        return similarity > 1.0 ? 1.0 : similarity;
    }

    static int alignerScore(double similarity) {
        if (similarity < VOICE_ALIGN_THRESHOLD) return 0;
        return (int) (ALIGNER_BASE + (similarity - VOICE_ALIGN_THRESHOLD)
                * ALIGNER_SPAN / (1.0 - VOICE_ALIGN_THRESHOLD));
    }

    // ------------------------------------------------------------------
    // Strict word/phrase scorer (typed search and first-pass voice evidence)
    // ------------------------------------------------------------------

    /**
     * Shared catalogue matcher for typed and spoken input. Query variants are
     * prepared once; the voice path applies stricter evidence rules so normal
     * conversation and short ASR fragments do not produce confident song hits.
     */
    static int scoreSongMatch(Song song, SearchDocument doc, SearchQuery query,
            boolean voiceInput) {
        if (query.normalized.length() < (voiceInput ? 3 : 1)) return 0;

        // A short substring is not useful evidence: it creates many accidental
        // matches while someone is speaking. Keep typed autocomplete permissive.
        int phraseMinimum = voiceInput ? 5 : 3;
        // A long single spoken word can still occur incidentally in a lyric.
        // Require at least two distinct words before accepting phrase matches.
        if (query.normalized.length() >= phraseMinimum
                && (!voiceInput || query.terms.size() >= 2)) {
            if (doc.title.contains(query.normalized))
                return 2400 + query.normalized.length();
            if (doc.normalized.contains(query.normalized))
                return 1800 + query.normalized.length();
            if (!query.modern.equals(query.normalized)
                    && doc.modern.contains(query.modern))
                return 1550 + query.modern.length();
            if (query.phonetic.length() >= phraseMinimum
                    && doc.phonetic.contains(query.phonetic))
                return 1300 + query.phonetic.length();
        }
        if (query.terms.isEmpty()) return 0;
        // Two isolated common words are weak evidence. Short queries can
        // still qualify above when they form a contiguous lyric phrase.
        if (voiceInput && query.terms.size() < 3) return 0;

        int exact = 0, modern = 0, phonetic = 0, prefix = 0, fuzzy = 0;
        for (SearchQuery.SearchTerm term : query.terms) {
            if (doc.words.contains(term.original)) exact++;
            else if (doc.modernWords.contains(term.original)
                    || doc.words.contains(term.modern)) modern++;
            else if (doc.phoneticWords.contains(term.phonetic)) phonetic++;
            else if (!voiceInput && term.original.length() >= 2
                    && hasPrefixMatch(term.original, doc.words, doc.modernWords))
                prefix++;
            else if (!voiceInput && term.original.length() >= 4
                    && hasOneEditMatch(term.original, doc.words)) fuzzy++;
        }

        int matched = exact + modern + phonetic + prefix + fuzzy;
        if (matched == 0) return 0;
        int coverage = matched * 100 / query.terms.size();

        // Voice suggestions need corroboration from multiple recognized words.
        // A single long phrase can still qualify through the phrase checks.
        int orderedMatches = 0;
        if (voiceInput) {
            int required = query.terms.size() == 1 ? 2
                    : Math.max(2, (query.terms.size() * 60 + 99) / 100);
            if (matched < required) return 0;

            // Matching words anywhere in a long song is not enough. The words
            // heard by the microphone should also occur in lyric order nearby.
            orderedMatches = Math.max(
                    countOrderedMatches(doc.normalized, query.terms, 0),
                    Math.max(countOrderedMatches(doc.modern, query.terms, 1),
                            countOrderedMatches(doc.phonetic, query.terms, 2)));
            int requiredInOrder = Math.max(2, (query.terms.size() + 1) / 2);
            if (orderedMatches < requiredInOrder) return 0;
        } else if (query.terms.size() >= 3 && matched < 2) {
            return 0;
        }

        int score = exact * 125 + modern * 105 + phonetic * 82
                + prefix * 54 + fuzzy * 38 + coverage;
        if (query.terms.size() >= 2 && matched == query.terms.size()) score += 100;
        if (coverage == 100) score += 25;
        if (voiceInput) score += orderedMatches * 35;
        return score;
    }

    /**
     * Counts how many recognized words occur in lyric order within a short
     * character window. Uses existing normalized strings, avoiding a second
     * per-song token list and its memory cost on low-RAM Android devices.
     */
    private static int countOrderedMatches(String text,
            List<SearchQuery.SearchTerm> terms, int form) {
        int cursor = 0;
        int matched = 0;
        for (SearchQuery.SearchTerm term : terms) {
            String word = form == 1 ? term.modern
                    : form == 2 ? term.phonetic : term.original;
            int position = text.indexOf(word, cursor);
            if (position < 0 || position - cursor > 80) continue;
            matched++;
            cursor = position + word.length();
        }
        return matched;
    }

    private static boolean hasPrefixMatch(String query, Set<String> original,
            Set<String> modern) {
        for (String word : original) if (word.startsWith(query)) return true;
        for (String word : modern) if (word.startsWith(query)) return true;
        return false;
    }

    private static boolean hasOneEditMatch(String query, Set<String> candidates) {
        for (String candidate : candidates) {
            if (Math.abs(candidate.length() - query.length()) > 1
                    || candidate.length() < 3) continue;
            if (oneEditAway(query, candidate)) return true;
        }
        return false;
    }

    private static boolean oneEditAway(String first, String second) {
        int i = 0, j = 0, edits = 0;
        while (i < first.length() && j < second.length()) {
            if (first.charAt(i) == second.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (first.length() > second.length()) i++;
            else if (second.length() > first.length()) j++;
            else { i++; j++; }
        }
        if (i < first.length() || j < second.length()) edits++;
        return edits <= 1;
    }

    // ------------------------------------------------------------------
    // Voice pipeline: prefilter -> shortlist -> alignment
    // ------------------------------------------------------------------

    /** One ranked candidate after the full pipeline. */
    static final class Candidate {
        final Song song;
        final int strictScore;
        final double similarity;
        final int finalScore;

        Candidate(Song song, int strictScore, double similarity,
                int finalScore) {
            this.song = song;
            this.strictScore = strictScore;
            this.similarity = similarity;
            this.finalScore = finalScore;
        }
    }

    /** Result handed back to the UI thread for history/pin/display. */
    static final class MatchResult {
        final String transcript;
        final SearchQuery query;
        final List<Candidate> candidates;

        MatchResult(String transcript, SearchQuery query,
                List<Candidate> candidates) {
            this.transcript = transcript;
            this.query = query;
            this.candidates = candidates;
        }
    }

    private static final class PrefilterRow {
        final Song song;
        final int strictScore;
        final int sketchHits;

        PrefilterRow(Song song, int strictScore, int sketchHits) {
            this.song = song;
            this.strictScore = strictScore;
            this.sketchHits = sketchHits;
        }
    }

    /**
     * Full voice ranking for one transcript. Stage 1 scores every song with
     * the strict word rules; stage 2 prefilters by gram-sketch containment;
     * stage 3 aligns the shortlist. Runs on a worker thread; touches only
     * immutable catalogue data.
     */
    static MatchResult compute(List<Song> catalog,
            Map<String, SearchDocument> index, String transcript) {
        SearchQuery query = prepareSearchQuery(transcript);
        String window = tail(query.normalized, VOICE_QUERY_WINDOW);
        String windowModern = tail(query.modern, VOICE_QUERY_WINDOW);
        int grams = window.length() - VOICE_GRAM_SIZE + 1;
        if (grams < 10) {
            return new MatchResult(transcript, query,
                    Collections.<Candidate>emptyList());
        }

        // Stages 1+2 in one pass: strict evidence admits a song directly,
        // otherwise the sketch gate must pass.
        List<PrefilterRow> shortlist = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            Song song = catalog.get(i);
            SearchDocument doc = index.get(song.id);
            if (doc == null) continue;
            int strict = scoreSongMatch(song, doc, query, true);
            int hits;
            if (strict >= 300) {
                hits = 0;
            } else {
                if (doc.gramSketch.length == 0) continue;
                hits = countGramsInSketch(window, doc.gramSketch);
                if (hits < sketchGate(doc, grams)) continue;
            }
            shortlist.add(new PrefilterRow(song, strict, hits));
            if (shortlist.size() > 4 * VOICE_ALIGN_SHORTLIST) {
                // Extreme transcripts (word soup) keep the list bounded;
                // keep the strongest evidence before trimming.
                shortlist.sort((a, b) -> Integer.compare(
                        rankKey(b), rankKey(a)));
                shortlist.subList(VOICE_ALIGN_SHORTLIST * 2,
                        shortlist.size()).clear();
            }
        }
        if (shortlist.size() > VOICE_ALIGN_SHORTLIST) {
            shortlist.sort((a, b) -> Integer.compare(rankKey(b), rankKey(a)));
            shortlist.subList(VOICE_ALIGN_SHORTLIST, shortlist.size()).clear();
        }

        // Stage 3: alignment decides the fuzzy candidates.
        List<Candidate> candidates = new ArrayList<>();
        for (PrefilterRow row : shortlist) {
            SearchDocument doc = index.get(row.song.id);
            if (doc == null) continue;
            double similarity;
            if (row.strictScore >= VOICE_STRICT_DECISIVE) {
                // Phrase containment already placed the song; alignment
                // cannot improve the decision, skip its cost.
                similarity = 1.0;
            } else {
                similarity = similarity(window, doc.normalized, VOICE_FOLD);
                if (similarity < VOICE_ALIGN_THRESHOLD) {
                    double modernSimilarity =
                            similarity(windowModern, doc.modern, VOICE_FOLD);
                    if (modernSimilarity > similarity) {
                        similarity = modernSimilarity;
                    }
                }
            }
            int alignerScore = alignerScore(similarity);
            int finalScore = Math.max(row.strictScore, alignerScore);
            if (row.strictScore >= 300 || alignerScore > 0) {
                candidates.add(new Candidate(row.song, row.strictScore,
                        similarity, finalScore));
            }
        }
        candidates.sort((a, b) -> {
            if (a.finalScore != b.finalScore) {
                return Integer.compare(b.finalScore, a.finalScore);
            }
            if (a.song.volume != b.song.volume) {
                return Integer.compare(a.song.volume, b.song.volume);
            }
            return Integer.compare(a.song.number, b.song.number);
        });
        if (candidates.size() > 6) {
            candidates.subList(6, candidates.size()).clear();
        }
        return new MatchResult(transcript, query, candidates);
    }

    private static int rankKey(PrefilterRow row) {
        return row.strictScore + row.sketchHits * 20;
    }
}
