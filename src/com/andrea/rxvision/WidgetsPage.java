package com.andrea.rxvision;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Home's widget page: what's left once the drop has fallen away (Liquid.enterWidgets), the date staying above.
 * Transparent: Home's background and date show through. Hold empty space for "add a widget" (the picker,
 * WidgetPicker); hold a widget to remove it; double-tap empty space to bring the drop back.
 */
final class WidgetsPage extends FrameLayout {

    interface Host { void leaveWidgets(); }

    static final int BIND = 43, CONFIGURE = 42;
    private static final int HOST_ID = 0x7276; // "rv"

    private final Activity activity;
    private final Host home;
    private final Theme theme;
    private final float dp;
    private final AppWidgetHost host;
    private final AppWidgetManager manager;
    private final Grid list;
    private final TextView pill;
    final WidgetPicker picker;
    private final Handler main = new Handler(Looper.getMainLooper());

    WidgetsPage(Activity activity, Host home, Theme theme, Typeface serif) {
        super(activity);
        this.activity = activity;
        this.home = home;
        this.theme = theme;
        dp = getResources().getDisplayMetrics().density;
        host = new AppWidgetHost(activity.getApplicationContext(), HOST_ID);
        manager = AppWidgetManager.getInstance(activity);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        list = new Grid(activity);
        int side = (int) (16 * dp);
        list.setPadding(side, 0, side, (int) (40 * dp));
        scroll.addView(list);
        addView(scroll);

        pill = new TextView(activity);
        pill.setTypeface(serif);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        pill.setTextColor(theme.lightBg ? 0xFFE8E3D9 : 0xFF141414);
        pill.setPadding((int) (22 * dp), (int) (10 * dp), (int) (22 * dp), (int) (12 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.fg);
        bg.setCornerRadius(999);
        pill.setBackground(bg);
        pill.setVisibility(GONE);
        addView(pill, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        picker = new WidgetPicker(activity, theme, serif, this::add);
        addView(picker);

        setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            scroll.setPadding(0, bars.top + (int) (110 * dp), 0, bars.bottom); // below the date, which stays
            picker.setInsets(bars.top, bars.bottom);
            return in;
        });
        for (int[] w : saved()) show(w[0], w[1], w[2]); // id, column, row (-1: not placed yet)
    }

    void startListening() { host.startListening(); }
    void stopListening() { host.stopListening(); }

    // ── empty space: hold for "add a widget", double-tap to bring the drop back ──

    private float downX, downY;
    private long lastTap;
    private final Runnable held = () -> {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showPill(downX, downY, "add a widget", this::openPicker);
    };

    private void openPicker() { picker.open(); }

    private boolean onEmpty; // this touch began on empty space (not a widget, the pill or the picker)

    /** Watches every touch (the scroll view underneath would swallow them) for holds and double taps on empty space. */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (!picker.isOpen()) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    boolean onPill = pill.getVisibility() == VISIBLE && hits(pill, e);
                    if (pill.getVisibility() == VISIBLE && !onPill) hidePill();
                    onEmpty = !onPill && !overWidget(e);
                    if (onEmpty) main.postDelayed(held, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * dp) main.removeCallbacks(held);
                    break;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(held);
                    if (onEmpty && e.getEventTime() - e.getDownTime() < 250 && Math.hypot(e.getX() - downX, e.getY() - downY) < 12 * dp) {
                        if (e.getEventTime() - lastTap < 320) { lastTap = 0; home.leaveWidgets(); }
                        else lastTap = e.getEventTime();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(held);
                    break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private boolean overWidget(MotionEvent e) {
        for (int i = 0; i < list.getChildCount(); i++) if (hits(list.getChildAt(i), e)) return true;
        return false;
    }

    private static boolean hits(View v, MotionEvent e) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return e.getRawX() >= at[0] && e.getRawX() < at[0] + v.getWidth() && e.getRawY() >= at[1] && e.getRawY() < at[1] + v.getHeight();
    }

    private void showPill(float x, float y, String text, Runnable action) {
        pill.setText(text);
        pill.setOnClickListener(v -> { hidePill(); action.run(); });
        pill.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        float px = Math.max(16 * dp, Math.min(getWidth() - pill.getMeasuredWidth() - 16 * dp, x - pill.getMeasuredWidth() / 2f));
        pill.setX(px);
        pill.setY(Math.max(0, y - pill.getMeasuredHeight() - 24 * dp)); // just above the finger
        pill.setVisibility(VISIBLE);
        pill.setAlpha(0);
        pill.setScaleX(0.6f);
        pill.setScaleY(0.6f);
        pill.animate().alpha(1).scaleX(1).scaleY(1).setDuration(260).setInterpolator(new PathInterpolator(0.3f, 1.4f, 0.5f, 1)).start();
    }

    private void hidePill() {
        pill.animate().alpha(0).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction(() -> pill.setVisibility(GONE)).start();
    }

    // ── adding: bind (Android asks the user once), then the widget's own setup if it has one ──

    private int pending = -1;

    private void add(AppWidgetProviderInfo info) {
        int id = host.allocateAppWidgetId();
        pending = id;
        if (manager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null)) { configure(id, info); return; }
        Intent ask = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        activity.startActivityForResult(ask, BIND);
    }

    private void configure(int id, AppWidgetProviderInfo info) {
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) { /* no setup after all: add it as it is */ }
        }
        placed(id);
    }

    /** Android's answer to the bind request, or the widget's setup screen's, from MainActivity.onActivityResult. */
    void onResult(int request, int result, Intent data) {
        int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending) : pending;
        if (id < 0) return;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (result != Activity.RESULT_OK || info == null) { host.deleteAppWidgetId(id); return; }
        if (request == BIND) configure(id, info); else placed(id);
    }

    private void placed(int id) {
        pending = -1;
        View v = show(id, -1, -1);
        saveAll();
        if (v != null) { v.setAlpha(0); v.setScaleX(0.9f); v.setScaleY(0.9f); v.animate().alpha(1).scaleX(1).scaleY(1).setDuration(380).start(); }
    }

    // ── showing and removing ──

    /** Shows widget id at column x, row y of the grid (x < 0: the first free place that fits it). */
    private View show(int id, int x, int y) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null) { host.deleteAppWidgetId(id); saveAll(); return null; } // its app was uninstalled
        int w = span(info.targetCellWidth, info.minWidth, Grid.COLS), h = span(info.targetCellHeight, info.minHeight, 6);
        if (x < 0 || !list.free(x, y, w, h, null)) { int[] at = list.find(w, h); x = at[0]; y = at[1]; }
        AppWidgetHostView view = host.createView(getContext().getApplicationContext(), id, info);
        float cellDp = list.cell() / dp;
        view.updateAppWidgetSize(new Bundle(), (int) (w * cellDp), (int) (h * cellDp), (int) (w * cellDp), (int) (h * cellDp));
        Holder[] self = new Holder[1];
        Holder box = new Holder(getContext(), new Holder.Drag() {
            boolean moved;
            int tx, ty;

            @Override public void start() { // lifted: the grid shows, the widget follows the finger
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                moved = false;
                Grid.Cell p = (Grid.Cell) self[0].getLayoutParams();
                tx = p.x; ty = p.y;
                self[0].setElevation(14 * dp);
                self[0].animate().scaleX(1.05f).scaleY(1.05f).setDuration(160).start();
                list.lines(true, p.x, p.y, p.w, p.h);
            }

            @Override public void move(float dx, float dy) {
                if (!moved && Math.hypot(dx, dy) < 10 * dp) return;
                moved = true;
                self[0].setTranslationX(dx);
                self[0].setTranslationY(dy);
                Grid.Cell p = (Grid.Cell) self[0].getLayoutParams();
                float c = list.cell();
                int nx = Math.max(0, Math.min(Grid.COLS - p.w, Math.round(p.x + dx / c)));
                int ny = Math.max(0, Math.round(p.y + dy / c));
                if ((nx != tx || ny != ty) && list.free(nx, ny, p.w, p.h, self[0])) {
                    tx = nx; ty = ny;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    list.lines(true, tx, ty, p.w, p.h);
                }
            }

            @Override public void end(boolean cancelled) {
                Holder b = self[0];
                list.lines(false, 0, 0, 0, 0);
                b.setElevation(0);
                b.animate().scaleX(1).scaleY(1).setDuration(160).start();
                if (!moved) {
                    if (!cancelled) offerRemove(id, b);
                    return;
                }
                // lands in its new cells: re-laid out there, then glides from where it was let go
                Grid.Cell p = (Grid.Cell) b.getLayoutParams();
                float fromX = b.getLeft() + b.getTranslationX(), fromY = b.getTop() + b.getTranslationY();
                b.setLayoutParams(new Grid.Cell(tx, ty, p.w, p.h));
                b.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                        v.removeOnLayoutChangeListener(this);
                        v.setTranslationX(fromX - l);
                        v.setTranslationY(fromY - t);
                        v.animate().translationX(0).translationY(0).setDuration(260)
                            .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.2f, 1)).start();
                    }
                });
                saveAll();
            }
        });
        self[0] = box;
        box.setTag(id);
        int pad = (int) (5 * dp);
        box.setPadding(pad, pad, pad, pad);
        box.addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        list.addView(box, new Grid.Cell(x, y, w, h));
        return box;
    }

    private void offerRemove(int id, View b) {
        int[] at = new int[2], me = new int[2];
        b.getLocationInWindow(at);
        getLocationInWindow(me);
        showPill(at[0] - me[0] + b.getWidth() / 2f, at[1] - me[1] + 20 * dp, "remove", () -> {
            host.deleteAppWidgetId(id);
            b.animate().alpha(0).scaleX(0.9f).scaleY(0.9f).setDuration(220)
                .withEndAction(() -> { list.removeView(b); saveAll(); }).start();
        });
    }

    /** A widget's span in grid cells: its declared target size, else estimated from its minimum size. */
    private int span(int target, int minPx, int max) {
        int n = target > 0 ? target : (int) Math.ceil((minPx + 16 * dp) / Math.max(1, list.cell()));
        return Math.max(1, Math.min(max, n));
    }

    /**
     * The page's grid: COLS columns of square cells across the width, as many rows as the widgets need. Each
     * widget sits at its cell (x, y) spanning w × h.
     */
    static final class Grid extends android.view.ViewGroup {
        static final int COLS = 4;

        static final class Cell extends android.view.ViewGroup.LayoutParams {
            final int x, y, w, h;
            Cell(int x, int y, int w, int h) { super(0, 0); this.x = x; this.y = y; this.w = w; this.h = h; }
        }

        private final android.graphics.Paint line = new android.graphics.Paint(), spot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float shown; // grid lines: 0 hidden, 1 shown
        private int sx, sy, sw, sh; // the cells the dragged widget would land on

        Grid(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            line.setStrokeWidth(Math.max(1, getResources().getDisplayMetrics().density * 0.7f));
            line.setColor(0x40888888); // thin grey
            spot.setColor(0x24888888);
        }

        /** Shows (or fades out) the grid's thin lines while a widget is moved, with the cells it would land on. */
        void lines(boolean on, int x, int y, int w, int h) {
            sx = x; sy = y; sw = on ? w : 0; sh = h;
            animate().cancel();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(shown, on ? 1 : 0);
            a.setDuration(on ? 200 : 260);
            a.addUpdateListener(v -> { shown = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
            requestLayout(); // a few spare rows below while moving, to drop it lower
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (shown <= 0) return;
            float cell = cell(), l = getPaddingLeft(), t = getPaddingTop();
            int rows = (int) ((getHeight() - t) / cell) + 1;
            line.setAlpha((int) (0x40 * shown));
            for (int i = 0; i <= COLS; i++) c.drawLine(l + i * cell, t, l + i * cell, t + rows * cell, line);
            for (int j = 0; j <= rows; j++) c.drawLine(l, t + j * cell, l + COLS * cell, t + j * cell, line);
            if (sw > 0) {
                spot.setAlpha((int) (0x24 * shown));
                float in = 5 * getResources().getDisplayMetrics().density;
                c.drawRoundRect(l + sx * cell + in, t + sy * cell + in, l + (sx + sw) * cell - in, t + (sy + sh) * cell - in, 22 * in / 5, 22 * in / 5, spot);
            }
        }

        /** One cell's side, in px: before the first layout, worked out from the screen's width. */
        float cell() {
            int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            return (w - getPaddingLeft() - getPaddingRight()) / (float) COLS;
        }

        boolean free(int x, int y, int w, int h, View except) {
            if (x < 0 || y < 0 || x + w > COLS) return false;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c == except) continue;
                Cell p = (Cell) c.getLayoutParams();
                if (x < p.x + p.w && p.x < x + w && y < p.y + p.h && p.y < y + h) return false;
            }
            return true;
        }

        /** The first place (reading order) where a w × h widget fits. */
        int[] find(int w, int h) {
            for (int y = 0; ; y++) for (int x = 0; x + w <= COLS; x++) if (free(x, y, w, h, null)) return new int[] {x, y};
        }

        @Override protected void onMeasure(int ws, int hs) {
            int width = MeasureSpec.getSize(ws);
            float c = (width - getPaddingLeft() - getPaddingRight()) / (float) COLS;
            int rows = 0;
            for (int i = 0; i < getChildCount(); i++) {
                Cell p = (Cell) getChildAt(i).getLayoutParams();
                rows = Math.max(rows, p.y + p.h);
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec((int) (p.w * c), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((int) (p.h * c), MeasureSpec.EXACTLY));
            }
            if (shown > 0 || sw > 0) rows = Math.max(rows + 3, (int) (getResources().getDisplayMetrics().heightPixels / c)); // room to move into
            setMeasuredDimension(width, (int) (rows * c) + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float c = cell();
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                Cell p = (Cell) v.getLayoutParams();
                int x = getPaddingLeft() + (int) (p.x * c), y = getPaddingTop() + (int) (p.y * c);
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            }
        }
    }

    /** Wraps a widget: holding it (which the widget itself would swallow) picks it up to move, or to remove. */
    private static final class Holder extends FrameLayout {
        interface Drag {
            void start();
            void move(float dx, float dy);
            void end(boolean cancelled);
        }

        private final Handler h = new Handler(Looper.getMainLooper());
        private final Runnable longPress;
        private float x, y;
        private boolean fired;

        Holder(Context ctx, Drag drag) {
            super(ctx);
            this.drag = drag;
            longPress = () -> { fired = true; getParent().requestDisallowInterceptTouchEvent(true); drag.start(); };
        }

        private final Drag drag;

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fired = false;
                    x = e.getRawX(); y = e.getRawY();
                    h.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!fired && Math.hypot(e.getRawX() - x, e.getRawY() - y) > ViewConfiguration.get(getContext()).getScaledTouchSlop())
                        h.removeCallbacks(longPress);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longPress);
                    if (fired) drag.end(e.getActionMasked() == MotionEvent.ACTION_CANCEL); // held and let go without moving
                    break;
            }
            return fired; // once held, the rest of the touch is ours, not the widget's
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!fired) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: drag.move(e.getRawX() - x, e.getRawY() - y); break;
                case MotionEvent.ACTION_UP: drag.end(false); fired = false; break;
                case MotionEvent.ACTION_CANCEL: drag.end(true); fired = false; break;
            }
            return true;
        }
    }

    // ── saved widget ids, in order ──

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("rxvision", Context.MODE_PRIVATE);
    }

    /** Saved widgets: id, column, row each (column -1 for ones saved before the grid: placed anew). */
    private int[][] saved() {
        String s = prefs().getString("widgets", "");
        if (s.isEmpty()) return new int[0][];
        String[] parts = s.split(",");
        int[][] out = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] f = parts[i].split(":");
            out[i] = new int[] {Integer.parseInt(f[0]), f.length > 2 ? Integer.parseInt(f[1]) : -1, f.length > 2 ? Integer.parseInt(f[2]) : -1};
        }
        return out;
    }

    private void saveAll() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            Grid.Cell p = (Grid.Cell) v.getLayoutParams();
            sb.append(sb.length() == 0 ? "" : ",").append(v.getTag()).append(':').append(p.x).append(':').append(p.y);
        }
        prefs().edit().putString("widgets", sb.toString()).apply();
    }

    // ── shown / gone ──

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void enter() {
        setVisibility(VISIBLE);
        setAlpha(0);
        animate().alpha(1).setStartDelay(260).setDuration(420).start(); // after the drop has fallen
    }

    void leave(boolean animated) {
        picker.close(false);
        if (pill.getVisibility() == VISIBLE) pill.setVisibility(GONE);
        if (!animated) { animate().cancel(); setVisibility(GONE); return; }
        animate().alpha(0).setStartDelay(0).setDuration(240).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Back: closes the pill or the picker first. Returns false when there was nothing to close. */
    boolean back() {
        if (picker.isOpen()) { picker.close(true); return true; }
        if (pill.getVisibility() == VISIBLE) { hidePill(); return true; }
        return false;
    }
}
