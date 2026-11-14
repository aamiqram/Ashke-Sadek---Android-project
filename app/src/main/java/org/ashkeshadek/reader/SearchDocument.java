package org.ashkeshadek.reader;

import java.util.HashSet;
import java.util.Set;

/** Per-song search forms and the sampled gram sketch used to prefilter the aligner. */
class SearchDocument {
    final String normalized, modern, phonetic, title;
    final Set<String> words, modernWords, phoneticWords = new HashSet<>();
    /** Evenly sampled character n-gram hashes of the normalized lyrics. */
    final long[] gramSketch;
    /** Total character n-gram count the sketch was sampled from. */
    final int gramCount;
    SearchDocument(String normalized, String modern, String phonetic, String title,
                   Set<String> words, Set<String> modernWords) {
        this.normalized = normalized; this.modern = modern; this.phonetic = phonetic;
        this.title = title; this.words = words; this.modernWords = modernWords;
        for (String word : words) phoneticWords.add(phoneticizeWord(word));
        for (String word : modernWords) phoneticWords.add(phoneticizeWord(word));
        this.gramCount = Math.max(0, normalized.length() - VoiceMatchEngine.VOICE_GRAM_SIZE + 1);
        this.gramSketch = VoiceMatchEngine.buildGramSketch(normalized);
    }

    private static String phoneticizeWord(String value) {
        return value.replace("\u09b6", "\u09b8").replace("\u09b7", "\u09b8")
                .replace("\u09a3", "\u09a8").replace("\u09c0", "\u09bf")
                .replace("\u0988", "\u0987").replace("\u09c2", "\u09c1")
                .replace("\u098a", "\u0989").replace("\u09ce", "\u09a4");
    }
}
