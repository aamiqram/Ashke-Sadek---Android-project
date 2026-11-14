package org.ashkeshadek.reader;

import java.util.List;

/** One catalogue song: identity, printed position, text and paragraph offsets. */
class Song {
    final String id; final int volume; final int number; final String title; final String lyrics;
    final String category; final List<LyricBlock> lyricBlocks;
    Song(String id,int volume,int number,String title,String lyrics,String category,List<LyricBlock> lyricBlocks){
        this.id=id; this.volume=volume; this.number=number; this.title=title; this.lyrics=lyrics;
        this.category=category; this.lyricBlocks=lyricBlocks;
    }
    String firstLine() {
        int lineEnd = lyrics.indexOf('\n');
        return lineEnd < 0 ? lyrics : lyrics.substring(0, lineEnd);
    }
}
