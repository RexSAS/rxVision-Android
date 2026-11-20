package com.andrea.rxvision;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The widget picker, after Nothing OS's: a sheet sliding up with a search field and every app's widgets grouped
 * under it, each a card with its preview, name and size. Tapping a card picks it.
 */
final class WidgetPicker extends FrameLayout {

    interface Pick { void pick(AppWidgetProviderInfo info); }

    private final Theme theme;
    private final Typeface serif;
    private final Pick pick;
    private final float dp;
    private final View scrim;
    private final LinearLayout sheet, groups;
    private final EditText search;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Providers by app package, apps sorted by name; and their app names and icons. */
    private final Map<String, List<AppWidgetProviderInfo>> byApp = new LinkedHashMap<>();
    private final Map<String, String> appNames = new HashMap<>();
    /** Previews, loaded off the main thread as the cards appear. */
    private final Map<AppWidgetProviderInfo, Drawable> previews = new HashMap<>();
    private final int ink, faint;
    private static final java.util.concurrent.Executor LOADER = java.util.concurrent.Executors.newSingleThreadExecutor();

    WidgetPicker(Context ctx, Theme theme, Typeface serif, Pick pick) {
        super(ctx);
        this.theme = theme;
        this.serif = serif;
        this.pick = pick;
        dp = getResources().getDisplayMetrics().density;
        ink = theme.lightBg ? 0xFF141414 : 0xFFECE7DD;
        faint = (ink & 0xFFFFFF) | 0x14000000;
        setVisibility(GONE);

        scrim = new View(ctx);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close(true));
        addView(scrim);

        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.lightBg ? 0xFFF4F1EB : theme.inks.length > 1 ? 0xFF22233A : 0xFF1A1A1C);
        float r = 30 * dp;
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        int side = (int) (20 * dp);
        sheet.setPadding(side, (int) (10 * dp), side, 0);

        View handle = new View(ctx);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor((ink & 0xFFFFFF) | 0x40000000);
        hb.setCornerRadius(99);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = (int) (16 * dp);
        sheet.addView(handle, hl);

        TextView title = new TextView(ctx);
        title.setText("Widgets");
        title.setTypeface(serif);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTextColor(ink);
        sheet.addView(title);

        search = new EditText(ctx);
        search.setHint("Search widgets");
        search.setHintTextColor((ink & 0xFFFFFF) | 0x66000000);
        search.setTextColor(ink);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine();
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(faint);
        sb.setCornerRadius(99);
        search.setBackground(sb);
        search.setPadding((int) (18 * dp), (int) (11 * dp), (int) (18 * dp), (int) (11 * dp));
        GradientDrawable caret = new GradientDrawable();
        caret.setColor(theme.accent);
        caret.setSize((int) (2 * dp), 0);
        search.setTextCursorDrawable(caret);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (14 * dp);
        sl.bottomMargin = (int) (8 * dp);
        sheet.addView(search, sl);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        groups = new LinearLayout(ctx);
        groups.setOrientation(LinearLayout.VERTICAL);
        groups.setPadding(0, 0, 0, (int) (24 * dp));
        scroll.addView(groups);
        sheet.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        lp.gravity = Gravity.BOTTOM;
        addView(sheet, lp);
    }

    void setInsets(int top, int bottom) {
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(), sheet.getPaddingRight(), bottom);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        load();
        search.setText("");
        fill();
        setVisibility(VISIBLE);
        scrim.setAlpha(0);
        scrim.animate().alpha(1).setDuration(300).start();
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheet.animate().translationY(0).setDuration(420).setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.2f, 1)).start();
    }

    void close(boolean animated) {
        if (!isOpen()) return;
        search.getWindowInsetsController().hide(WindowInsets.Type.ime());
        if (!animated) { setVisibility(GONE); return; }
        scrim.animate().alpha(0).setDuration(260).start();
        sheet.animate().translationY(sheet.getHeight()).setDuration(300).setInterpolator(new PathInterpolator(0.4f, 0, 1, 1))
            .withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Every widget on the phone, grouped by app, apps by name. */
    private void load() {
        byApp.clear();
        PackageManager pm = getContext().getPackageManager();
        Map<String, List<AppWidgetProviderInfo>> found = new HashMap<>();
        for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(getContext()).getInstalledProvidersForProfile(Process.myUserHandle())) {
            String pkg = info.provider.getPackageName();
            found.computeIfAbsent(pkg, k -> new ArrayList<>()).add(info);
            if (!appNames.containsKey(pkg)) {
                try { appNames.put(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()); }
                catch (PackageManager.NameNotFoundException e) { appNames.put(pkg, pkg); }
            }
        }
        List<String> pkgs = new ArrayList<>(found.keySet());
        Collator c = Collator.getInstance();
        pkgs.sort((a, b) -> c.compare(appNames.get(a), appNames.get(b)));
        for (String p : pkgs) byApp.put(p, found.get(p));
    }

    /** The groups matching the search: an app's name matching shows all its widgets, else just those that match. */
    private void fill() {
        groups.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        PackageManager pm = getContext().getPackageManager();
        for (Map.Entry<String, List<AppWidgetProviderInfo>> e : byApp.entrySet()) {
            String app = appNames.get(e.getKey());
            boolean appMatches = q.isEmpty() || app.toLowerCase(Locale.ROOT).contains(q);
            List<AppWidgetProviderInfo> shown = new ArrayList<>();
            for (AppWidgetProviderInfo i : e.getValue())
                if (appMatches || i.loadLabel(pm).toLowerCase(Locale.ROOT).contains(q)) shown.add(i);
            if (shown.isEmpty()) continue;
            groups.addView(header(e.getKey(), app, shown.size()));
            HorizontalScrollView row = new HorizontalScrollView(getContext());
            row.setHorizontalScrollBarEnabled(false);
            row.setOverScrollMode(OVER_SCROLL_NEVER);
            LinearLayout cards = new LinearLayout(getContext());
            for (AppWidgetProviderInfo i : shown) cards.addView(card(i));
            row.addView(cards);
            groups.addView(row);
        }
    }

    private View header(String pkg, String app, int count) {
        LinearLayout h = new LinearLayout(getContext());
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, (int) (22 * dp), 0, (int) (10 * dp));
        ImageView icon = new ImageView(getContext());
        try { icon.setImageDrawable(getContext().getPackageManager().getApplicationIcon(pkg)); }
        catch (PackageManager.NameNotFoundException ignored) { }
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (26 * dp), (int) (26 * dp));
        il.rightMargin = (int) (12 * dp);
        h.addView(icon, il);
        TextView name = new TextView(getContext());
        name.setText(app);
        name.setTextColor(ink);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        h.addView(name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView n = new TextView(getContext());
        n.setText(String.valueOf(count));
        n.setTextColor((ink & 0xFFFFFF) | 0x80000000);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.addView(n);
        return h;
    }

    private View card(AppWidgetProviderInfo info) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        // Sized like the widget: about 72dp a cell, so a 4 × 2 calendar shows as a wide card and a 1 × 1 as a small one.
        int cw = cells(info.targetCellWidth, info.minWidth), ch = cells(info.targetCellHeight, info.minHeight);
        int w = (int) (Math.max(110, Math.min(4, cw) * 72) * dp), h = (int) (Math.max(96, Math.min(3, ch) * 72) * dp);
        // The whole card takes the tap: a live preview's own buttons (e.g. ChatGPT's) would otherwise swallow it.
        FrameLayout box = new FrameLayout(getContext()) {
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) { return true; }
        };
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(faint);
        bb.setCornerRadius(20 * dp);
        box.setBackground(bb);
        ImageView preview = new ImageView(getContext());
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = (int) (12 * dp);
        preview.setPadding(pad, pad, pad, pad);
        box.addView(preview);
        card.addView(box, new LinearLayout.LayoutParams(w, h));
        Drawable cached = previews.get(info);
        View live = info.previewLayout != 0 ? live(info, box) : null;
        if (live != null) { // its own preview layout (Android 12+): a real-looking widget, not a picture
            box.removeView(preview);
            box.addView(live, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else if (cached != null) preview.setImageDrawable(cached);
        else {
            Context ctx = getContext();
            LOADER.execute(() -> { // previews can be big images: decode them one by one, off the main thread
                Drawable d = info.loadPreviewImage(ctx, 0);
                if (d == null) d = info.loadIcon(ctx, 0);
                Drawable got = d;
                main.post(() -> { previews.put(info, got); preview.setImageDrawable(got); });
            });
        }

        TextView label = new TextView(getContext());
        label.setText(info.loadLabel(getContext().getPackageManager()));
        label.setMaxWidth(w);
        label.setTextColor(ink);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding((int) (4 * dp), (int) (8 * dp), (int) (4 * dp), 0);
        card.addView(label, new LinearLayout.LayoutParams(w, LayoutParams.WRAP_CONTENT));
        TextView size = new TextView(getContext());
        size.setText(cw + " × " + ch);
        size.setTextColor((ink & 0xFFFFFF) | 0x80000000);
        size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        size.setPadding((int) (4 * dp), (int) (2 * dp), 0, 0);
        card.addView(size);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cl.rightMargin = (int) (12 * dp);
        card.setLayoutParams(cl);
        card.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            close(true);
            pick.pick(info);
        });
        return card;
    }

    /** The widget's own preview layout, inflated from its app, or null if it can't be. */
    private View live(AppWidgetProviderInfo info, FrameLayout box) {
        try {
            View v = new android.widget.RemoteViews(info.provider.getPackageName(), info.previewLayout).apply(getContext(), box);
            int pad = (int) (8 * dp);
            v.setPadding(pad, pad, pad, pad);
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A widget's size in home-screen cells: its declared target, else estimated from its minimum size (70dp a cell). */
    private int cells(int target, int minPx) {
        return target > 0 ? target : Math.max(1, (int) Math.ceil((minPx / dp + 30) / 70));
    }
}
