package org.ashkeshadek.reader;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.TypefaceSpan;

/**
 * Word-source bold emphasis inside a lyric line. The printed book sets these
 * words in a heavier cut of the same serif; the old StyleSpan(BOLD) made the
 * system synthesize a faux bold over the Bengali fallback font, which smeared
 * emphasized words. This span applies the bundled serif's true semibold, and
 * only falls back to faux bold if the font bundle is unavailable.
 */
public class SerifEmphasisSpan extends TypefaceSpan {
    public SerifEmphasisSpan() {
        super("");
    }

    @Override public void updateDrawState(TextPaint paint) {
        apply(paint);
    }

    @Override public void updateMeasureState(TextPaint paint) {
        apply(paint);
    }

    private void apply(TextPaint paint) {
        Typeface semibold = Fonts.serif(true);
        if (semibold != null) {
            paint.setTypeface(semibold);
            paint.setFakeBoldText(false);
        } else {
            paint.setFakeBoldText(true);
        }
    }
}
