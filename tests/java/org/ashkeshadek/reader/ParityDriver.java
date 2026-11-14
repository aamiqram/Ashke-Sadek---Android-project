package org.ashkeshadek.reader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity driver for the Python calibration mirror. Reads parity_cases.txt
 * (UTF-8): "PAIR <n>" then n query/text line pairs, then "PIPE <n>" then n
 * transcript lines. Prints one similarity per pair (6 decimals) and one
 * candidate line per transcript. Python must print identical text.
 */
public class ParityDriver {
    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                ParityDriver.class.getResourceAsStream("/parity_cases.txt"),
                StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) lines.add(line);
        int index = 0;
        if (!lines.get(index).startsWith("PAIR ")) throw new IllegalStateException();
        int pairs = Integer.parseInt(lines.get(index).substring(5).trim());
        index++;
        for (int i = 0; i < pairs; i++) {
            String query = lines.get(index++);
            String text = lines.get(index++);
            double sim = VoiceMatchEngine.similarity(query, text,
                    VoiceMatchEngine.VOICE_FOLD);
            System.out.println(String.format(java.util.Locale.ROOT, "%.6f", sim));
        }
        if (!lines.get(index).startsWith("PIPE ")) throw new IllegalStateException();
        int transcripts = Integer.parseInt(lines.get(index).substring(5).trim());
        index++;
        List<Song> songs = new ArrayList<>();
        songs.add(new Song(VoiceEngineSelftest.SONG_A_ID,
                VoiceEngineSelftest.SONG_A_VOLUME,
                VoiceEngineSelftest.SONG_A_NUMBER,
                VoiceEngineSelftest.SONG_A_TITLE,
                VoiceEngineSelftest.SONG_A_LYRICS, "", new ArrayList<>()));
        songs.add(new Song(VoiceEngineSelftest.SONG_B_ID,
                VoiceEngineSelftest.SONG_B_VOLUME,
                VoiceEngineSelftest.SONG_B_NUMBER,
                VoiceEngineSelftest.SONG_B_TITLE,
                VoiceEngineSelftest.SONG_B_LYRICS, "", new ArrayList<>()));
        Map<String, SearchDocument> searchIndexMap = new HashMap<>();
        for (Song song : songs) {
            String corpus = song.title + " " + song.lyrics;
            String modern = VoiceMatchEngine.modernizeOldBengali(corpus);
            searchIndexMap.put(song.id, new SearchDocument(
                    VoiceMatchEngine.normalize(corpus),
                    VoiceMatchEngine.normalize(modern),
                    VoiceMatchEngine.phoneticNormalize(corpus),
                    VoiceMatchEngine.normalize(song.title),
                    VoiceMatchEngine.searchWords(corpus),
                    VoiceMatchEngine.searchWords(modern)));
        }
        // The driver needs the real threshold constants: read them from the
        // engine via reflection-free direct access (package-private).
        for (int i = 0; i < transcripts; i++) {
            String transcript = lines.get(index++);
            VoiceMatchEngine.MatchResult result = VoiceMatchEngine.compute(
                    songs, searchIndexMap, transcript);
            StringBuilder out = new StringBuilder();
            for (VoiceMatchEngine.Candidate candidate : result.candidates) {
                if (out.length() > 0) out.append(";");
                out.append(candidate.song.id).append(':')
                        .append(candidate.finalScore).append(':')
                        .append(String.format(java.util.Locale.ROOT, "%.6f",
                                candidate.similarity));
            }
            System.out.println(out.length() == 0 ? "-" : out.toString());
        }
        // Also emit the java constants for cross-checking.
        System.out.println("CONST " + VoiceMatchEngine.VOICE_ALIGN_THRESHOLD
                + " " + VoiceMatchEngine.VOICE_SKETCH_GATE + " "
                + VoiceMatchEngine.VOICE_QUERY_WINDOW + " "
                + VoiceMatchEngine.VOICE_ALIGN_SHORTLIST);
    }
}
