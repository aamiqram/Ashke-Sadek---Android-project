package org.ashkeshadek.reader;

/** Voice-session memory for one song: best-ever score and display excerpt. */
class VoiceCandidate {
    final Song song;
    int bestScore;
    int lastFinalScore;
    int observations;
    String lastObservedQuery = "";
    String excerpt;

    VoiceCandidate(Song song) {
        this.song = song;
        this.excerpt = song.firstLine();
    }
}
