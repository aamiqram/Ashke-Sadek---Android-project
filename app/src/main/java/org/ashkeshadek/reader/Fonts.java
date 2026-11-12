package org.ashkeshadek.reader;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Bundled Bengali typefaces (all SIL OFL, see assets/fonts/OFL.txt):
 *
 * - Noto Serif Bengali Regular/SemiBold for lyric text — the reading page
 *   should look like the printed book, which sets a serif at one size for
 *   every line of a song, and the Word-source bold emphasis maps to the
 *   serif's true semibold instead of synthetic faux bold (which the system
 *   sans fallback had to fake for Bengali, smearing emphasized words).
 * - Noto Sans Bengali Regular/Medium for interface labels.
 *
 * Static weights are bundled instead of the variable font on purpose:
 * variable-weight selection only works on Android 8+, and every supported
 * device back to Android 5 must render identically. Every load falls back
 * to the system typeface if an asset is missing, so a broken font bundle
 * can never make the app unreadable.
 */
public final class Fonts {
    private static Typeface serifRegular;
    private static Typeface serifSemiBold;
    private static Typeface sansRegular;
    private static Typeface sansMedium;

    private Fonts() {
    }

    public static void init(Context context) {
        if (serifRegular != null) return;
        serifRegular = load(context, "NotoSerifBengali-Regular.ttf");
        serifSemiBold = load(context, "NotoSerifBengali-SemiBold.ttf");
        sansRegular = load(context, "NotoSansBengali-Regular.ttf");
        sansMedium = load(context, "NotoSansBengali-Medium.ttf");
    }

    private static Typeface load(Context context, String name) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/" + name);
        } catch (Exception error) {
            return null;
        }
    }

    /** Lyric text; bold=true is the Word-source emphasis weight (true semibold). */
    public static Typeface serif(boolean bold) {
        if (bold) return serifSemiBold != null ? serifSemiBold : serifRegular;
        return serifRegular;
    }

    /** Interface labels; bold=true is the real medium weight, never faux bold. */
    public static Typeface sans(boolean bold) {
        if (bold) return sansMedium != null ? sansMedium : sansRegular;
        return sansRegular;
    }
}
