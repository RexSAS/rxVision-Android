package com.andrea.rxvision;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Process;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launchable apps, the favourites shown as drops, and starting an app. */
final class Apps {
    static final int MAX_FAVOURITES = 8;

    static final class App {
        final ComponentName component;
        final String label;
        /** The icon's monochrome layer (Android 13 themed icons), else its foreground layer or the whole legacy icon, tinted alike;
         *  or, when those would tint into a solid block or nothing, the original icon with its brightness as the glyph. */
        final Drawable glyph;
        /** A favourite's folder: the apps in the little ring that pops out of its drop. */
        final List<App> folder = new ArrayList<>();

        App(ComponentName component, String label, Drawable glyph) {
            this.component = component;
            this.label = label;
            this.glyph = glyph;
        }
    }

    private Apps() {}

    /** Every launchable app, by name. */
    static List<App> all(Context ctx) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        List<App> out = new ArrayList<>();
        for (LauncherActivityInfo info : la.getActivityList(null, Process.myUserHandle())) {
            if (info.getComponentName().getPackageName().equals(ctx.getPackageName())) continue;
            Drawable icon = info.getIcon(0);
            Drawable glyph = icon instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) icon).getMonochrome() : null;
            if (glyph == null) glyph = icon instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) icon).getForeground()
                : new InsetDrawable(icon, 1 / 3f); // a legacy icon goes in the middle third, where a monochrome layer keeps its glyph
            out.add(new App(info.getComponentName(), info.getLabel().toString(), untintable(glyph) ? luminance(ctx, icon) : fitted(ctx, glyph.mutate())));
        }
        Collator c = Collator.getInstance();
        out.sort((a, b) -> c.compare(a.label, b.label));
        return out;
    }

    /** Whether the glyph tints into nothing useful: more than 93% of its middle third (the part a drop shows) opaque, a solid
     *  block; or under 2%, an empty layer whose art is all in the background. */
    static boolean untintable(Drawable glyph) {
        int n = 108;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Drawable d = glyph.getConstantState() == null ? glyph : glyph.getConstantState().newDrawable();
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(b));
        int[] px = new int[36 * 36];
        b.getPixels(px, 0, 36, 36, 36, 36, 36);
        b.recycle();
        int opaque = 0;
        for (int p : px) if (p >>> 24 > 127) opaque++;
        // YouTube's themed glyph fills 91%, the real blocks 96% and up; Brother Mobile Connect's foreground 0%
        return opaque > px.length * 93 / 100 || opaque < px.length * 2 / 100;
    }

    /** The original icon as a glyph, so the theme's tint shades it like the others: either its dark parts (a dark logo on a
     *  light icon) or its light parts (a light logo on a dark icon, like rxhome's), whichever covers less, since that's the logo.
     *  Cropped to the logo and scaled to fill what a drop or list row shows uncut. */
    static Drawable luminance(Context ctx, Drawable icon) {
        int n = 192;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, n, n);
        icon.draw(new Canvas(b));
        int[] px = new int[n * n], dark = new int[px.length], light = new int[px.length];
        b.getPixels(px, 0, n, 0, 0, n, n);
        long darkSum = 0, lightSum = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24;
            int lum = lum(p);
            // getPixels gives unpremultiplied colour, so weight by the pixel's own alpha; a short ramp keeps the edges smooth
            darkSum += dark[i] = keep(255 - lum) * a / 255;
            lightSum += light[i] = keep(lum) * a / 255;
        }
        int[] glyph = darkSum <= lightSum && darkSum > 0 || lightSum == 0 ? dark : light;
        if (glyph == light) { // a light logo: only what is near its brightest colour, so greys around it go too
            int top = 1;
            for (int p : px) if (p >>> 24 > 127) top = Math.max(top, lum(p));
            for (int i = 0; i < px.length; i++)
                light[i] = Math.max(0, Math.min(255, (lum(px[i]) - top * 70 / 100) * 255 / Math.max(1, top * 15 / 100)))
                    * (px[i] >>> 24) / 255;
        }
        int max = 1;
        for (int k : glyph) max = Math.max(max, k);
        for (int i = 0; i < px.length; i++) px[i] = glyph[i] * 255 / max << 24 | 0xFFFFFF; // its strongest part fully opaque
        b.setPixels(px, 0, n, 0, 0, n, n);
        Rect r = ink(b);
        return new BitmapDrawable(ctx.getResources(), r == null ? b : fit(b, r));
    }

    /** The share of the glyph canvas a drop or list row shows uncut, around its centre. */
    private static final float SHOWN = 0.44f;

    /** The glyph as it is, or, when its art reaches past what's shown, shrunk to fit inside it. */
    static Drawable fitted(Context ctx, Drawable glyph) {
        int n = 192;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Drawable d = glyph.getConstantState() == null ? glyph : glyph.getConstantState().newDrawable();
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(b));
        Rect r = ink(b);
        float lo = n * (1 - SHOWN) / 2, hi = n - lo;
        if (r == null || r.left >= lo && r.top >= lo && r.right <= hi && r.bottom <= hi) { b.recycle(); return glyph; }
        return new BitmapDrawable(ctx.getResources(), fit(b, r));
    }

    /** The bounds of a bitmap's visible pixels, or null when it has none. */
    private static Rect ink(Bitmap b) {
        int n = b.getWidth(), left = n, top = n, right = -1, bottom = -1;
        int[] px = new int[n * n];
        b.getPixels(px, 0, n, 0, 0, n, n);
        for (int i = 0; i < px.length; i++)
            if (px[i] >>> 24 > 20) { left = Math.min(left, i % n); right = Math.max(right, i % n); top = Math.min(top, i / n); bottom = Math.max(bottom, i / n); }
        return right < 0 ? null : new Rect(left, top, right + 1, bottom + 1);
    }

    /** The bitmap's art, cropped to its bounds and scaled to fill what's shown, centred. */
    private static Bitmap fit(Bitmap b, Rect r) {
        int n = b.getWidth();
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        float scale = n * SHOWN / Math.max(r.width(), r.height()), w = r.width() * scale / 2, h = r.height() * scale / 2;
        new Canvas(out).drawBitmap(b, r, new android.graphics.RectF(n / 2f - w, n / 2f - h, n / 2f + w, n / 2f + h),
            new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        b.recycle();
        return out;
    }

    private static int lum(int p) {
        return (2126 * ((p >> 16) & 0xFF) + 7152 * ((p >> 8) & 0xFF) + 722 * (p & 0xFF)) / 10000;
    }

    /** How much of a pixel to keep, by how close to white it is: nothing up to 35, everything from 115. */
    private static int keep(int v) {
        return Math.max(0, Math.min(255, (v - 35) * 255 / 80));
    }

    static void launch(Context ctx, App app, View from, Rect bounds) {
        try {
            ctx.getSystemService(LauncherApps.class).startMainActivity(app.component, Process.myUserHandle(), bounds,
                opening(ctx));
        } catch (RuntimeException e) { // uninstalled or disabled since the list was loaded
            Toast.makeText(ctx, app.label + " can't be opened", Toast.LENGTH_SHORT).show();
        }
    }

    /** How an app opens: fading and zooming in over the launcher (flooded with the drop's ink, from Home). */
    static android.os.Bundle opening(Context ctx) {
        return ActivityOptions.makeCustomAnimation(ctx, R.anim.app_enter, R.anim.home_hold).toBundle();
    }

    // ── favourites: the apps in the drops, kept as flattened component names ──

    private static final List<String> DEFAULTS = Arrays.asList(
        "com.anthropic.claude", "com.whatsapp", "com.spotify.music", "com.andrea.rxhome",
        "com.google.android.youtube", "org.telegram.messenger", "com.android.chrome");

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("rxvision", Context.MODE_PRIVATE);
    }

    static List<App> favourites(Context ctx, List<App> all) {
        String saved = prefs(ctx).getString("favourites", null);
        List<App> out = new ArrayList<>();
        if (saved != null) {
            for (String s : saved.split("\n"))
                for (App a : all) if (a.component.flattenToString().equals(s)) out.add(a);
            return withFolders(ctx, all, out);
        }
        List<String> pkgs = new ArrayList<>(DEFAULTS);
        ResolveInfo cam = ctx.getPackageManager().resolveActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0);
        if (cam != null) pkgs.add(cam.activityInfo.packageName);
        for (String p : pkgs)
            for (App a : all)
                if (a.component.getPackageName().equals(p) && out.size() < MAX_FAVOURITES) { out.add(a); break; }
        return withFolders(ctx, all, out);
    }

    // ── folders: apps tucked into a favourite's drop ──

    static final int MAX_FOLDER = 6;

    private static List<App> withFolders(Context ctx, List<App> all, List<App> favourites) {
        for (App f : favourites) {
            f.folder.clear();
            String saved = prefs(ctx).getString("folder:" + f.component.flattenToString(), "");
            for (String s : saved.split("\n"))
                for (App a : all) if (a.component.flattenToString().equals(s)) f.folder.add(a);
        }
        return favourites;
    }

    /** Adds the app to the favourite's folder, or takes it out; false when the folder is full. */
    static boolean toggleFolder(Context ctx, App favourite, App app) {
        if (isFavourite(favourite.folder, app)) favourite.folder.removeIf(f -> f.component.equals(app.component));
        else if (favourite.folder.size() >= MAX_FOLDER) return false;
        else favourite.folder.add(app);
        StringBuilder sb = new StringBuilder();
        for (App f : favourite.folder) sb.append(f.component.flattenToString()).append('\n');
        prefs(ctx).edit().putString("folder:" + favourite.component.flattenToString(), sb.toString()).apply();
        return true;
    }

    static boolean isFavourite(List<App> favourites, App app) {
        for (App f : favourites) if (f.component.equals(app.component)) return true;
        return false;
    }

    /** Adds or removes the app; returns false when adding would go past MAX_FAVOURITES. */
    static boolean toggle(Context ctx, List<App> favourites, App app) {
        if (isFavourite(favourites, app)) favourites.removeIf(f -> f.component.equals(app.component));
        else if (favourites.size() >= MAX_FAVOURITES) return false;
        else favourites.add(app);
        save(ctx, favourites);
        return true;
    }

    /** Saves the favourites, in their ring order. */
    static void save(Context ctx, List<App> favourites) {
        StringBuilder sb = new StringBuilder();
        for (App f : favourites) sb.append(f.component.flattenToString()).append('\n');
        prefs(ctx).edit().putString("favourites", sb.toString()).apply();
    }
}
