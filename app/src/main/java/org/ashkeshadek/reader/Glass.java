package org.ashkeshadek.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;

/**
 * Phase 2 glass design system. The app runs on Android 5 phones with tight
 * RAM and no runtime blur, so every glass surface is built from layered
 * primitives that read as freezer-cold frosted glass on every device: a
 * vertically lit translucent fill (the frost), a crystalline grain tile, a
 * diagonal gloss streak, a bright top rim, and a one-pixel hairline edge.
 * Content behind still ghosts through at the bar's density, while dialogs
 * keep their dense quiet stage. Every layer is a filled or stroked Path
 * with a shader paint - no clipPath, so hardware acceleration renders it
 * identically back to Android 5. The RenderEffect blur tier for Android
 * 12+ (real backdrop blur under pinned bars, RAM-gated) joins with the
 * backdrop-capture pipeline; it must never change what pre-API 31 devices
 * render.
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

    /** Frosted panel: lit ice fill + grain + gloss + rim + hairline. */
    static Drawable panel(float radiusPx) {
        return panel(radiusPx, FILL);
    }

    /** Frosted panel with an explicit frost density - dialogs use the dense PANEL. */
    static Drawable panel(float radiusPx, int fill) {
        return new Frost(radiusPx, fill);
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

    /**
     * The frosty water bottle: five coats on one rounded path. The fill is a
     * vertical light band around the requested frost density, the grain is a
     * single deterministic 128px speck tile repeated across the panel, the
     * gloss is a diagonal streak like freezer light raking across wet ice,
     * and the rim is the top edge catching that light before it fades down
     * the sides.
     */
    private static final class Frost extends Drawable {
        /** Speck paint alpha over the tile - the grain must carry the frost's tooth. */
        private static final int GRAIN_ALPHA = 110;
        /** Ice reads cool against the cream page - a real cast, not a whisper. */
        private static final int ICE_RGB = 0xF5FBFF;
        /** Frost crystals shade slightly blue-grey before they sparkle white. */
        private static final int SHADE_RGB = 0xA9BFD0;
        private static Bitmap tile;

        private final float radius;
        private final int meanAlpha;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final Rect built = new Rect();
        private int alpha = 255;
        private boolean ready;

        Frost(float radiusPx, int fill) {
            radius = radiusPx;
            meanAlpha = Color.alpha(fill);
            fillPaint.setStyle(Paint.Style.FILL);
            grainPaint.setStyle(Paint.Style.FILL);
            glossPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1f);
            hairPaint.setStyle(Paint.Style.STROKE);
            hairPaint.setStrokeWidth(1f);
            hairPaint.setColor(HAIRLINE);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            if (!ready || !built.equals(bounds)) build(bounds);
            canvas.drawPath(shape, fillPaint);
            canvas.drawPath(shape, grainPaint);
            canvas.drawPath(shape, glossPaint);
            canvas.drawPath(shape, rimPaint);
            canvas.drawPath(shape, hairPaint);
        }

        /** Rebuilds every layer against the current bounds and alpha. */
        private void build(Rect bounds) {
            ready = true;
            built.set(bounds);
            // Strokes are centred on the path, so sit it half a pixel inside
            // the bounds and the hairline stays wholly on the panel.
            float inset = 0.5f;
            float l = bounds.left + inset, t = bounds.top + inset;
            float r = bounds.right - inset, b = bounds.bottom - inset;
            float rounded = Math.max(0f, radius - inset);
            shape.reset();
            shape.addRoundRect(new RectF(l, t, r, b), rounded, rounded, Path.Direction.CW);

            // Fill: a lit vertical band around the requested frost density -
            // bright crown, quiet waist, and a faint lift at the bottom edge.
            int crown = Math.min(255, meanAlpha + Math.max(6, (255 - meanAlpha) / 3));
            int waist = Math.max(0, meanAlpha - Math.max(6, (255 - meanAlpha) / 4));
            int lift = Math.min(255, meanAlpha + Math.max(3, (255 - meanAlpha) / 6));
            fillPaint.setShader(new LinearGradient(l, t, l, b, new int[]{
                            ice(crown), ice((crown + waist) / 2), ice(waist), ice(lift)},
                    new float[]{0f, 0.42f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            fillPaint.setAlpha(alpha);

            // Grain: one deterministic speck tile, repeated across the panel.
            grainPaint.setShader(new BitmapShader(tile(), Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT));
            grainPaint.setAlpha(GRAIN_ALPHA * alpha / 255);

            // Gloss: freezer light raking across wet ice - one hot specular
            // line near the crown, a soft wide band through the middle, and
            // a faint reflection lower down.
            glossPaint.setShader(new LinearGradient(l, t, r, b, new int[]{
                            0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
                            0x2EFFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[]{0.03f, 0.075f, 0.115f, 0.26f, 0.40f, 0.56f, 0.70f, 0.80f},
                    Shader.TileMode.CLAMP));
            glossPaint.setAlpha(alpha);

            // Rim: the top edge catches the light, fading down the sides.
            rimPaint.setShader(new LinearGradient(l, t, l, t + Math.max(10f, radius),
                    new int[]{0x82FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP));
            rimPaint.setAlpha(alpha);

            hairPaint.setAlpha(alpha);
        }

        private static int ice(int alphaChannel) {
            return (alphaChannel << 24) | ICE_RGB;
        }

        /** Crystalline speck tile: frost-shade dust plus white glints, deterministic, seam-free. */
        private static Bitmap tile() {
            if (tile == null) {
                final int size = 128;
                int[] pixels = new int[size * size];
                long state = 0x9E3779B97F4A7C15L;
                for (int i = 0; i < pixels.length; i++) {
                    state ^= state << 13;
                    state ^= state >>> 7;
                    state ^= state << 17;
                    if ((i % size) >= size - 2) continue;
                    int chip = (int) ((state >>> 44) & 3);
                    if (((state >>> 20) & 0xFF) < 6) {
                        // white glint - frozen light catching the eye
                        int glint = 150 + (int) ((state >>> 48) & 0x69);
                        pixels[i] = (glint << 24) | 0xFFFFFF;
                        if (chip == 0) pixels[i + 1] = ((glint / 2) << 24) | 0xFFFFFF;
                    } else if (((state >>> 24) & 0xFF) < 11) {
                        // frost shade - the crystalline crust itself
                        int shade = 40 + (int) ((state >>> 52) & 0x2F);
                        pixels[i] = (shade << 24) | SHADE_RGB;
                        if (chip == 1) pixels[i + 1] = ((shade / 2) << 24) | SHADE_RGB;
                        if (chip == 2) pixels[i + 2] = ((shade / 3) << 24) | SHADE_RGB;
                    }
                }
                for (int y = 0; y < size; y++) pixels[y * size + size - 1] = 0;
                Bitmap crystal = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                crystal.setPixels(pixels, 0, size, 0, 0, size, size);
                tile = crystal;
            }
            return tile;
        }

        @Override public void setAlpha(int value) {
            if (alpha != value) {
                alpha = value;
                if (ready) build(built);
            }
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override public void setColorFilter(ColorFilter filter) {
            // The frost is pre-tinted; a filter would break the ice.
        }
    }
}
