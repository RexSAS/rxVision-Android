package com.andrea.rxvision;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
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
        /** The icon's monochrome layer (Android 13 themed icons), or null: then the first letter is drawn. */
        final Drawable glyph;

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
            out.add(new App(info.getComponentName(), info.getLabel().toString(), glyph == null ? null : glyph.mutate()));
        }
        Collator c = Collator.getInstance();
        out.sort((a, b) -> c.compare(a.label, b.label));
        return out;
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
            return out;
        }
        List<String> pkgs = new ArrayList<>(DEFAULTS);
        ResolveInfo cam = ctx.getPackageManager().resolveActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0);
        if (cam != null) pkgs.add(cam.activityInfo.packageName);
        for (String p : pkgs)
            for (App a : all)
                if (a.component.getPackageName().equals(p) && out.size() < MAX_FAVOURITES) { out.add(a); break; }
        return out;
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
