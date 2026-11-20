package com.andrea.rxvision;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Every app behind a search line: type and the best match is first, ↵ opens it. Long-press pins it as a drop.
 * "settings" beside the search line swaps the list for the settings (the theme).
 */
final class AllApps extends LinearLayout {

    interface Host {
        void open(Apps.App app, Rect from);
        void favouritesChanged();
        void themeChanged();
        /** The list fully covers Home: it can stop drawing underneath. */
        void covered();
        /** About to uncover Home: it draws again. */
        void uncovered();
    }


    private final Host host;
    private final EditText query;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final View settings;
    private final TextView settingsLink;
    private final Theme theme;
    private final int INK; // the theme's ink
    private final Typeface serif;
    private final float dp;
    private List<Apps.App> apps = new ArrayList<>(), favourites = new ArrayList<>();
    private List<Integer> shown = new ArrayList<>();

    AllApps(Context ctx, Host host, Theme theme, Typeface serif, Typeface italic) {
        super(ctx);
        this.theme = theme;
        INK = theme.fg;
        this.host = host;
        this.serif = serif;
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setClickable(true); // touches stop here instead of reaching the drops underneath
        int side = (int) (28 * dp);

        query = new EditText(ctx);
        query.setTypeface(serif);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        query.setTextColor(INK);
        query.setHint("open…");
        query.setHintTextColor(theme.fg(0x47));
        query.setBackground(null);
        query.setSingleLine();
        query.setPadding(0, 0, 0, 0);
        query.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable caret = new GradientDrawable();
        caret.setColor(theme.accent);
        caret.setSize((int) (2 * dp), 0);
        query.setTextCursorDrawable(caret);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { render(); }
        });
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(query, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        settingsLink = new TextView(ctx);
        settingsLink.setText("settings");
        settingsLink.setTypeface(italic);
        settingsLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        settingsLink.setTextColor(theme.fg(0x99));
        settingsLink.setPadding((int) (12 * dp), (int) (8 * dp), 0, (int) (8 * dp));
        settingsLink.setOnClickListener(v -> { if (picking != null) donePicking(); else showSettings(!settingsOpen(), true); });
        head.addView(settingsLink);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        list.setPadding(0, (int) (16 * dp), 0, (int) (24 * dp));
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        settings = settingsView(serif);
        settings.setVisibility(GONE);
        addView(settings, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        query.setOnEditorActionListener((v, action, e) -> {
            if (!shown.isEmpty()) open(list.getChildAt(0), apps.get(shown.get(0)));
            return true;
        });
        setPadding(side, 0, side, 0);

        setOnApplyWindowInsetsListener((v, in) -> {
            int bars = WindowInsets.Type.systemBars();
            int bottom = Math.max(in.getInsets(bars).bottom, in.getInsets(WindowInsets.Type.ime()).bottom);
            setPadding(side, in.getInsets(bars).top + (int) (56 * dp), side, bottom);
            return in;
        });
    }

    /** Theme: Dark | Light | Ember, the chosen one full ink. Choosing redraws the whole launcher in it. */
    private View settingsView(Typeface serif) {
        LinearLayout v = new LinearLayout(getContext());
        v.setOrientation(VERTICAL);
        v.setPadding(0, (int) (28 * dp), 0, 0);
        TextView label = new TextView(getContext());
        label.setText("THEME");
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setLetterSpacing(0.2f);
        label.setTextColor(theme.fg(0x80));
        v.addView(label);
        LinearLayout options = new LinearLayout(getContext());
        for (String name : Theme.NAMES) {
            TextView o = new TextView(getContext());
            o.setText(name);
            o.setTypeface(serif);
            o.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
            o.setTextColor(name.equals(theme.name) ? INK : theme.fg(0x4D));
            o.setPadding(0, (int) (4 * dp), (int) (24 * dp), 0);
            o.setOnClickListener(x -> {
                if (name.equals(theme.name)) return;
                x.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                Theme.save(getContext(), name);
                host.themeChanged();
            });
            options.addView(o);
        }
        v.addView(options);

        TextView ringLabel = new TextView(getContext());
        ringLabel.setText("RING  ·  DRAG A DROP ONTO ANOTHER TO SWAP");
        ringLabel.setTypeface(Typeface.MONOSPACE);
        ringLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ringLabel.setLetterSpacing(0.2f);
        ringLabel.setTextColor(theme.fg(0x80));
        ringLabel.setPadding(0, (int) (36 * dp), 0, 0);
        v.addView(ringLabel);
        ringEditor = new RingEditor(getContext(), theme, serif, () -> {
            Apps.save(getContext(), favourites);
            host.favouritesChanged();
            render();
        }, this::pickFolder);
        v.addView(ringEditor);
        return v;
    }

    private RingEditor ringEditor;

    boolean settingsOpen() { return settings.getVisibility() == VISIBLE; }

    /** Swaps the app list for the settings; with keyboard, also hides or brings back the keyboard to match. */
    /** Picking a folder's apps: the list with "add to <favourite>…"; a tap puts an app in its folder or takes it out. */
    private Apps.App picking;

    boolean picking() { return picking != null; }

    private void pickFolder(Apps.App favourite) {
        picking = favourite;
        showSettings(false, true);
        query.setText("");
        query.setHint("add to " + favourite.label + "…");
        settingsLink.setText("done");
        rows.clear();
        render();
    }

    void donePicking() {
        picking = null;
        query.setText("");
        query.setHint("open…");
        rows.clear();
        render();
        ringEditor.setFavourites(favourites);
        showSettings(true, true);
    }

    void showSettings(boolean on, boolean keyboard) {
        settings.setVisibility(on ? VISIBLE : GONE);
        scroll.setVisibility(on ? GONE : VISIBLE);
        View shown = on ? settings : scroll; // a short crossfade, rising slightly, between list and settings
        shown.setAlpha(0);
        shown.setTranslationY(10 * dp);
        shown.animate().alpha(1).translationY(0).setStartDelay(0).setDuration(240).setInterpolator(EASE).start();
        query.setVisibility(on ? INVISIBLE : VISIBLE); // keeps the line's height so nothing jumps
        settingsLink.setText(on ? "apps" : "settings");
        if (!keyboard) return;
        if (on) query.getWindowInsetsController().hide(WindowInsets.Type.ime());
        else { query.requestFocus(); query.getWindowInsetsController().show(WindowInsets.Type.ime()); }
    }

    void setApps(List<Apps.App> apps, List<Apps.App> favourites) {
        this.apps = apps;
        this.favourites = favourites;
        if (ringEditor != null) ringEditor.setFavourites(favourites);
        rows.clear();
        render();
    }

    /** Each app's row is built once and reused: typing only reorders rows, it doesn't rebuild ~70 of them. */
    private final java.util.Map<android.content.ComponentName, View> rows = new java.util.HashMap<>();

    private void render() {
        List<String> labels = new ArrayList<>();
        for (Apps.App a : apps) labels.add(a.label);
        shown = Search.rank(labels, query.getText().toString());
        if (!isOpen() || !opened) { fill(); return; }
        // Searching: the current results drift up and out, then the new ones rise into place from below.
        list.animate().cancel();
        list.animate().translationY(-20 * dp).alpha(0).setStartDelay(0).setDuration(110)
            .setInterpolator(new PathInterpolator(0.4f, 0, 1, 1)).withLayer().withEndAction(() -> {
                fill();
                list.setTranslationY(20 * dp);
                list.animate().translationY(0).alpha(1).setDuration(220).setInterpolator(EASE).withLayer().start();
            }).start();
    }

    /** Puts the rows for the current results in the list, in order. */
    private void fill() {
        boolean typing = query.length() > 0;
        list.removeAllViews();
        for (int k = 0; k < shown.size(); k++) {
            Apps.App a = apps.get(shown.get(k));
            View r = rows.get(a.component);
            if (r == null) rows.put(a.component, r = row(a));
            ((View) r.getTag()).setVisibility(typing && k == 0 ? VISIBLE : GONE); // the "↵ opens this" mark
            list.addView(r);
        }
        scroll.scrollTo(0, 0);
    }

    private View row(Apps.App app) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (11 * dp);
        row.setPadding(0, pad, 0, pad);
        View bar = new View(getContext()); // what ↵ opens, marked like the caret: shown on the first row only
        bar.setBackgroundColor(theme.accent);
        bar.setVisibility(GONE);
        LayoutParams barLp = new LayoutParams((int) (2 * dp), (int) (22 * dp));
        barLp.rightMargin = (int) (12 * dp);
        row.addView(bar, barLp);
        row.setTag(bar);

        int size = (int) (22 * dp);
        if (app.glyph != null) {
            ImageView icon = new ImageView(getContext());
            Drawable g = app.glyph.getConstantState().newDrawable().mutate(); // the drops tint theirs the background
            g.setTint(INK);
            g.setAlpha(255); // the copy inherits whatever fade the drop was last drawn with
            icon.setImageDrawable(g);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // The glyph sits in the middle third of its canvas: draw the canvas larger so it reads at 22dp.
            int big = (int) (size * 2.2f);
            LayoutParams lp = new LayoutParams(big, big);
            lp.setMargins(-(big - size) / 2, -(big - size) / 2, -(big - size) / 2 + (int) (14 * dp), -(big - size) / 2);
            row.addView(icon, lp);
        } else {
            TextView letter = new TextView(getContext());
            letter.setText(app.label.substring(0, 1));
            letter.setTypeface(serif);
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            letter.setTextColor(INK);
            letter.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(size, size);
            lp.rightMargin = (int) (14 * dp);
            row.addView(letter, lp);
        }

        TextView name = new TextView(getContext());
        name.setText(app.label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTextColor(INK);
        name.setSingleLine();
        row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        if (picking != null ? Apps.isFavourite(picking.folder, app) : Apps.isFavourite(favourites, app)) {
            View dot = new View(getContext()); // a small ink drop: it's in the ring (or, picking, in the folder)
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(picking != null ? theme.accent : INK);
            dot.setBackground(d);
            row.addView(dot, new LayoutParams((int) (7 * dp), (int) (7 * dp)));
        }

        row.setOnClickListener(v -> {
            if (picking == null) { open(v, app); return; }
            if (app.component.equals(picking.component)) return;
            if (!Apps.toggleFolder(getContext(), picking, app)) {
                Toast.makeText(getContext(), "A folder holds " + Apps.MAX_FOLDER, Toast.LENGTH_SHORT).show();
                return;
            }
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            host.favouritesChanged();
            redraw(v, app);
        });
        row.setOnLongClickListener(v -> {
            if (picking != null) return false;
            if (!Apps.toggle(getContext(), favourites, app)) {
                Toast.makeText(getContext(), "The ring holds " + Apps.MAX_FAVOURITES + ": long-press one to remove it first", Toast.LENGTH_SHORT).show();
                return true;
            }
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            host.favouritesChanged();
            ringEditor.setFavourites(favourites);
            redraw(v, app);
            return true;
        });
        return row;
    }

    /** Rebuilds just this row (its dot changed), in place: the list doesn't reload or jump. */
    private void redraw(View old, Apps.App app) {
        int at = list.indexOfChild(old);
        View fresh = row(app);
        ((View) fresh.getTag()).setVisibility(((View) old.getTag()).getVisibility()); // keeps the "↵ opens this" mark
        rows.put(app.component, fresh);
        list.removeViewAt(at);
        list.addView(fresh, at);
    }

    /** The first rows rise and fade in one after another, from delay ms, each step ms after the one above. */
    private void float_in(long delay, long step, long duration) {
        for (int i = 0; i < Math.min(list.getChildCount(), 16); i++) {
            View r = list.getChildAt(i);
            r.setAlpha(0);
            r.setTranslationY(10 * dp);
            r.animate().alpha(1).translationY(0).setStartDelay(delay + i * step).setDuration(duration)
                .setInterpolator(EASE).withLayer().start();
        }
    }

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.2f, 1);
    private Animator reveal;
    /** The open fade has finished: from now on typing animates the results. */
    private boolean opened;

    /** How far the list is in: 0 = gone, 1 = fully shown. */
    private float focus = 1;

    /**
     * Focus pull: the list arrives slightly zoomed and blurred and sharpens as it fades in (or blurs away as it
     * fades out), while Home lifts away behind it (MainActivity).
     */
    private void reveal(boolean in, Runnable done) {
        if (reveal != null) reveal.cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(focus, in ? 1 : 0);
        a.setDuration(in ? 460 : 320);
        a.setInterpolator(EASE);
        a.addUpdateListener(v -> {
            focus = (float) v.getAnimatedValue();
            setAlpha(focus);
            setScaleX(1.06f - 0.06f * focus);
            setScaleY(1.06f - 0.06f * focus);
            float blur = (1 - focus) * 26 * dp;
            setRenderEffect(blur < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP));
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator x) { cancelled = true; }
            @Override public void onAnimationEnd(Animator x) { if (!cancelled && done != null) done.run(); }
        });
        reveal = a;
        a.start();
    }

    void hideKeyboard() {
        query.getWindowInsetsController().hide(WindowInsets.Type.ime());
    }

    private void open(View from, Apps.App app) {
        int[] at = new int[2];
        from.getLocationInWindow(at);
        host.open(app, new Rect(at[0], at[1], at[0] + from.getWidth(), at[1] + from.getHeight()));
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void show() {
        opened = false;
        focus = 0;
        setVisibility(VISIBLE);
        setAlpha(0);
        post(() -> { // once laid out, so the rows exist to fade in
            if (!isOpen()) return;
            reveal(true, () -> { opened = true; host.covered(); });
            float_in(120, 40, 520); // the apps fade in one after another as the list appears
        });
        query.requestFocus();
        // The keyboard comes up once the swipe that opened us is over (else the rest of the swipe lands on the
        // keyboard as gesture typing) and the list has faded in.
        postDelayed(() -> { if (isOpen() && !settingsOpen()) query.getWindowInsetsController().show(WindowInsets.Type.ime()); }, 450);
    }

    void hide(boolean animated) {
        query.getWindowInsetsController().hide(WindowInsets.Type.ime());
        opened = false;
        Runnable done = () -> {
            setVisibility(GONE);
            if (picking != null) { picking = null; query.setHint("open…"); rows.clear(); }
            query.setText("");
            showSettings(false, false);
        };
        host.uncovered();
        if (!animated || getWidth() == 0) { if (reveal != null) reveal.cancel(); done.run(); return; }
        reveal(false, done);
    }
}
