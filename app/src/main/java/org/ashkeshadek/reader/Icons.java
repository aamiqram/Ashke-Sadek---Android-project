package org.ashkeshadek.reader;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * Phase 2 icon set: Material-style vector glyphs loaded from res/drawable.
 * Text-glyph icons (⌂ ♡ ▣ 🎙 ⌕) render differently on every platform -
 * MIUI even forces the mic into a colour emoji - and no font agrees on
 * their weight. Vectors take our palette as a tint, keep one consistent
 * hairline weight on every Android version from 5 up, and scale crisply
 * at any size because they are pure geometry.
 */
final class Icons {
    private Icons() { }

    /** Tinted vector drawable; the single shared tint source for every screen. */
    static Drawable get(Context context, int resId, int tint) {
        Drawable icon = context.getDrawable(resId).mutate();
        icon.setTint(tint);
        return icon;
    }
}
