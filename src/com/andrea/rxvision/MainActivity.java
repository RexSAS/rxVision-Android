package com.andrea.rxvision;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.List;
import java.util.Random;

/** The launcher: the liquid home with the all-apps search on top of it. */
public class MainActivity extends Activity implements Liquid.Host, AllApps.Host, WidgetsPage.Host {

    private Liquid liquid;
    private AllApps allApps;
    private List<Apps.App> apps, favourites;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Back hides the keyboard first, then closes the settings, then the app list. */
    private WidgetsPage widgets;
    /** Back on the widget page: closes the picker or the pill first, then brings the drop back. */
    private final OnBackInvokedCallback closeWidgets = () -> {
        WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
        if (in != null && in.isVisible(WindowInsets.Type.ime())) widgets.getWindowInsetsController().hide(WindowInsets.Type.ime());
        else if (!widgets.back()) hideWidgets(true);
    };

    /** Double-tap on empty space: the drop falls away and the widget page is all that's left (with the date). */
    @Override public void showWidgets() {
        if (widgets.isOpen()) return;
        liquid.enterWidgets();
        widgets.enter();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, closeWidgets);
    }

    @Override public void leaveWidgets() { hideWidgets(true); }

    private void hideWidgets(boolean animated) {
        if (!widgets.isOpen()) return;
        widgets.leave(animated);
        liquid.exitWidgets();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeWidgets);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == WidgetsPage.BIND || request == WidgetsPage.CONFIGURE) widgets.onResult(request, result, data);
    }

    private final OnBackInvokedCallback closeAll = () -> {
        WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
        if (in != null && in.isVisible(WindowInsets.Type.ime())) allApps.hideKeyboard();
        else if (allApps.settingsOpen()) allApps.showSettings(false, false);
        else hideAll(true);
    };
    /** Set across the recreate() a theme change does, so the settings come back open where they were. */
    private static boolean reopenSettings;

    private boolean resumed, fresh = true;

    /** Screen off: empty Home, so whenever it's next seen (after unlock, usually) the ink drop lands again. */
    /** Plugged in or out: drops rise from the charging port while plugged (see Liquid.setPlugged). */
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            liquid.setPlugged(Intent.ACTION_POWER_CONNECTED.equals(i.getAction()), true);
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { liquid.await(); }
    };

    /** Unlocked straight onto Home. */
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (resumed) liquid.intro(); }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { liquid.invalidate(); }
    };

    private final LauncherApps.Callback changed = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reload(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reload(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reload(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reload(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reload(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Typeface serif = Typeface.createFromAsset(getAssets(), "InstrumentSerif-Regular.ttf");
        Typeface italic = Typeface.createFromAsset(getAssets(), "InstrumentSerif-Italic.ttf");

        FrameLayout root = new FrameLayout(this);
        Theme theme = Theme.load(this);
        theme.applyWallpaper(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bg));
        liquid = new Liquid(this, this, theme, serif, italic);
        allApps = new AllApps(this, this, theme, serif, italic);
        allApps.setBackground(paper(theme));
        allApps.setVisibility(View.GONE);
        root.setBackground(paper(theme));
        root.addView(liquid.shade);
        root.addView(liquid.ink);
        root.addView(liquid);
        root.addView(allApps);
        widgets = new WidgetsPage(this, this, theme, serif); // see-through: Home's background and date stay
        widgets.setVisibility(View.GONE);
        root.addView(widgets);
        liquid.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            liquid.setInsets(bars.top, bars.bottom);
            return in;
        });
        setContentView(root);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(theme.lightBg ? light : 0, light);

        reload();
        getSystemService(LauncherApps.class).registerCallback(changed, main);
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        IntentFilter plug = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        plug.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, plug);
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); // the sticky state now
        liquid.setPlugged(battery != null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0, false);
        liquid.await(); // the first intro plays once the window has appeared
        if (reopenSettings) {
            reopenSettings = false;
            showAll();
            allApps.showSettings(true, true);
        }
        // Back on Home does nothing (by default it would finish us and show the previous launcher).
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {});
    }

    @Override protected void onDestroy() {
        getSystemService(LauncherApps.class).unregisterCallback(changed);
        unregisterReceiver(tick);
        unregisterReceiver(unlocked);
        unregisterReceiver(screenOff);
        unregisterReceiver(power);
        super.onDestroy();
    }

    private void reload() {
        apps = Apps.all(this);
        favourites = Apps.favourites(this, apps);
        liquid.setFavourites(favourites);
        allApps.setApps(apps, favourites);
    }

    /** Home pressed while already home: close the search, or settle the drops. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (allApps.isOpen()) hideAll(true);
        else if (widgets.isOpen()) hideWidgets(true);
        else liquid.reset();
    }

    /** A fresh start lands like an unlock, once the window has finished appearing (else it plays unseen). */
    @Override public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        if (fresh) { fresh = false; liquid.intro(); }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!fresh) liquid.intro(); // only plays if Home was emptied (screen off); a fresh start waits for its window
        // ...unless no enter animation comes (e.g. started by the Home key): then play it shortly anyway
        else liquid.postDelayed(() -> { fresh = false; liquid.intro(); }, 450);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override protected void onStart() {
        super.onStart();
        widgets.startListening();
        liquid.setAlive(true);
    }

    @Override protected void onStop() {
        super.onStop();
        liquid.setAlive(false);
        widgets.stopListening();
        hideAll(false); // come back to the drop, not the search
        liquid.reset();
    }

    // ── hosts ──

    @Override public void open(Apps.App app, Rect from) {
        Apps.launch(this, app, liquid, from);
    }

    @Override public void openCalendar(Rect from) {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Apps.opening(this));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No calendar app", Toast.LENGTH_SHORT).show();
        }
    }

    /** StatusBarManager.expandNotificationsPanel isn't public API, but it's what launchers use (EXPAND_STATUS_BAR). */
    @Override public void showNotifications() {
        try {
            Object bar = getSystemService("statusbar");
            bar.getClass().getMethod("expandNotificationsPanel").invoke(bar);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Toast.makeText(this, "Can't open the notifications", Toast.LENGTH_SHORT).show();
        }
    }

    /** Hidden under the app list, the liquid stops: its full-screen blur would otherwise cost every frame. */
    @Override public void covered() {
        liquid.setAlive(false);
        liquid.setVisibility(View.INVISIBLE);
        liquid.ink.setVisibility(View.INVISIBLE);
    }

    @Override public void uncovered() {
        liquid.setVisibility(View.VISIBLE);
        liquid.ink.setVisibility(View.VISIBLE);
        lift(false);
        // the wobble resumes once the list has drawn back in, so the two don't compete for frames
        liquid.postDelayed(() -> { if (resumed && !allApps.isOpen()) liquid.setAlive(true); }, 360);
    }

    /** Home lifting away as the app list comes in (or settling back as it goes): up a little, smaller, faded. */
    private void lift(boolean away) {
        for (View v : new View[] {liquid, liquid.ink}) {
            v.animate().translationY(away ? -48 * getResources().getDisplayMetrics().density : 0)
                .scaleX(away ? 0.9f : 1).scaleY(away ? 0.9f : 1).alpha(away ? 0 : 1)
                .setDuration(away ? 420 : 380).setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1)).start();
        }
    }

    @Override public void showAll() {
        lift(true);
        liquid.setAlive(false); // a still drop under the opening list: its cached blur costs nothing to show
        allApps.show();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, closeAll);
    }

    private void hideAll(boolean animated) {
        if (!allApps.isOpen()) return;
        allApps.hide(animated);
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeAll);
    }

    @Override public void themeChanged() {
        reopenSettings = true;
        recreate();
    }

    @Override public void favouritesChanged() {
        liquid.setFavourites(favourites);
    }

    /** The theme's background with a fine grain, tiled. */
    private Drawable paper(Theme theme) {
        int n = 160;
        int[] px = new int[n * n];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(256);
            px[i] = (r.nextInt(26) << 24) | (v << 16) | (v << 8) | v;
        }
        BitmapDrawable grain = new BitmapDrawable(getResources(), Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888));
        grain.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        if (theme.inks.length == 1) return new LayerDrawable(new Drawable[] {new ColorDrawable(theme.bg), grain});
        // Colour: a calm dusk, a few soft glows of the inks bleeding into it
        GradientDrawable dusk = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF15172A, 0xFF1E1B2E, 0xFF2A2033});
        float w = getResources().getDisplayMetrics().widthPixels;
        return new LayerDrawable(new Drawable[] {dusk,
            glow(0x2E5FE0C1, 0.1f, 0.18f, w), glow(0x26A68BFF, 0.95f, 0.12f, w), glow(0x2BFF7A6B, 0.9f, 0.88f, w), grain});
    }

    private static Drawable glow(int color, float cx, float cy, float w) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(w);
        return g;
    }
}
