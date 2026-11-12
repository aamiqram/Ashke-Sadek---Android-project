package org.ashkeshadek.reader;

import android.graphics.drawable.GradientDrawable;

/**
 * Phase 2 glass design system. The app runs on Android 5 phones with tight
 * RAM and no runtime blur, so every glass surface is built from the same
 * three primitives that read as frosted glass on every device: a
 * translucent panel fill (content behind shows through faintly - the
 * frost), a one-pixel hairline edge, and a soft elevation shadow. The
 * RenderEffect blur tier for Android 12+ (real backdrop blur under pinned
 * bars, RAM-gated) joins with the backdrop-capture pipeline; it must never
 * change what pre-API 31 devices render.
 */
final class Glass {
    /** Translucent white - the frost over the cream page background. */
    static final int FILL = 0xD9FFFFFF;
    /**
     * Nearly-opaque frost for dialogs. Platform titles, buttons and form
     * fields need a quiet stage; at the bar's 85% the screen behind ghosts
     * through and reads as dirt, not glass. A whisper of the backdrop keeps
     * the frosted character without the noise.
     */
    static final int PANEL = 0xF2FFFFFF;
    /** Hairline panel edge: one quiet ink line, not a border. */
    static final int HAIRLINE = 0x1C1E2924;

    private Glass() { }

    /** Frosted panel: translucent fill + hairline, radius in px. */
    static GradientDrawable panel(float radiusPx) {
        return panel(radiusPx, FILL);
    }

    /** Frosted panel with an explicit fill - dialogs use the dense PANEL. */
    static GradientDrawable panel(float radiusPx, int fill) {
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(fill);
        panel.setCornerRadius(radiusPx);
        panel.setStroke(1, HAIRLINE);
        return panel;
    }

    /**
     * Printed paper: barely-there vertical shade, like a page sitting under
     * a reading lamp - flat white cards stop looking like UI tiles.
     */
    static GradientDrawable paper(float radiusPx) {
        GradientDrawable paper = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, 0xFFFBF8F0});
        paper.setCornerRadius(radiusPx);
        paper.setStroke(1, 0xFFE7E8E1);
        return paper;
    }
}
