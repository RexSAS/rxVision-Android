package com.andrea.rxvision;

import android.content.Context;
import java.util.Arrays;

/** The launcher's colours: Dark (the default), Light, or Colour (multicolour ink on a calm dusk gradient). */
final class Theme {
    static final String[] NAMES = {"Dark", "Light", "Colour"};

    final String name;
    /** Background, the ink the drops and text are drawn with, and the caret / "↵ opens this" marker. */
    final int bg, fg, accent;
    /** Colour drawn on top of the drops (glyphs, clock). */
    final int glyph;
    /** The inks the drops are made of: one for the mono themes, several for Colour. */
    final int[] inks;
    /** Light background: the status and navigation bar icons turn dark. */
    final boolean lightBg;

    private Theme(String name) {
        this.name = name;
        switch (name) {
            case "Light": bg = 0xFFE8E3D9; fg = 0xFF141414; accent = 0xFFFF4D00; lightBg = true; break;
            case "Colour": bg = 0xFF1A1B29; fg = 0xFFECE7DD; accent = 0xFFFF7A6B; lightBg = false; break;
            default:      bg = 0xFF0F0F10; fg = 0xFFECE7DD; accent = 0xFFFF4D00; lightBg = false;
        }
        boolean colour = name.equals("Colour");
        glyph = colour ? 0xFF15161C : bg;
        // coral, saffron, mint, sky, lilac, pink: soft enough to sit together, bright enough to read as ink
        inks = colour ? new int[] {0xFFFF7A6B, 0xFFFFC46B, 0xFF5FE0C1, 0xFF6FB8FF, 0xFFA68BFF, 0xFFFF8FC8} : new int[] {fg};
    }

    /** fg at the given alpha (0–255). */
    int fg(int alpha) { return (fg & 0xFFFFFF) | (alpha << 24); }

    static Theme load(Context ctx) {
        String n = ctx.getSharedPreferences("rxvision", Context.MODE_PRIVATE).getString("theme", "Dark");
        return new Theme(Arrays.asList(NAMES).contains(n) ? n : "Dark");
    }

    /**
     * Makes the phone's wallpaper (home and lock screen) a plain fill of this theme's background, once per theme
     * (it's slow, and replaces whatever wallpaper was there). Runs off the main thread.
     */
    void applyWallpaper(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("rxvision", Context.MODE_PRIVATE);
        if (name.equals(p.getString("wallpaper", null))) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                android.util.DisplayMetrics m = app.getResources().getDisplayMetrics();
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    Math.max(m.widthPixels, m.heightPixels), Math.max(m.widthPixels, m.heightPixels), android.graphics.Bitmap.Config.ARGB_8888);
                b.eraseColor(bg);
                android.app.WallpaperManager.getInstance(app).setBitmap(b, null, true,
                    android.app.WallpaperManager.FLAG_SYSTEM | android.app.WallpaperManager.FLAG_LOCK);
                p.edit().putString("wallpaper", name).apply();
            } catch (java.io.IOException | RuntimeException e) {
                android.util.Log.w("rxVision", "wallpaper not set", e);
            }
        }, "wallpaper").start();
    }

    static void save(Context ctx, String name) {
        ctx.getSharedPreferences("rxvision", Context.MODE_PRIVATE).edit().putString("theme", name).apply();
    }
}
