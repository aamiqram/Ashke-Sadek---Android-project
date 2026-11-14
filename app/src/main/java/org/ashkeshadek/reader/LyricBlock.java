package org.ashkeshadek.reader;

import java.util.List;

/** One rendered paragraph of a song, addressed by offsets into Song.lyrics. */
class LyricBlock {
    final int startOffset; final int endOffset; final int alignment;
    final float leftIndentPt; final float firstLineIndentPt; final float rightIndentPt;
    final float spaceBeforePt; final float spaceAfterPt;
    List<int[]> boldSpans;
    LyricBlock(int startOffset,int endOffset,int alignment,float leftIndentPt,float firstLineIndentPt,
               float rightIndentPt,float spaceBeforePt,float spaceAfterPt){
        this.startOffset=startOffset; this.endOffset=endOffset; this.alignment=alignment;
        this.leftIndentPt=leftIndentPt; this.firstLineIndentPt=firstLineIndentPt;
        this.rightIndentPt=rightIndentPt; this.spaceBeforePt=spaceBeforePt; this.spaceAfterPt=spaceAfterPt;
    }
    int length() { return endOffset - startOffset; }
}
