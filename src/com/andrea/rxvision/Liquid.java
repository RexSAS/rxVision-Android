package com.andrea.rxvision;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The home: one ink drop holding the time. Hold anywhere and it splits into the favourite apps around the
 * finger; slide onto one and let go to open it. Tap the drop for the calendar. Flick up anywhere for all apps,
 * down for the notifications.
 *
 * Two layers: {@link #ink} draws only the drops, blurred and alpha-thresholded so nearby drops melt
 * together (the "goo" effect); this view sits on top and draws the glyphs, clock and date crisp.
 */
final class Liquid extends View {

    interface Host {
        void open(Apps.App app, Rect from);
        void showAll();
        void openCalendar(Rect from);
        void showNotifications();
        void showWidgets();
    }

    private final int fg, glyph; // the theme's text colour, and the colour drawn on top of the drops
    private final int[] inks;
    private final Paint strandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float STEP_MS = 1000f / 60; // physics runs at a fixed 60 Hz whatever the display rate

    private static final class Drop {
        float x, y, vx, vy, tx, ty, r, tr;
        float rx, ry; // its place in the ring; the finger pulls it away from there
        int color;
        long at; // doesn't move before this uptime: staggers the drops
        Apps.App app;

        void step() { step(0.075f, 0.8f); }

        void step(float k, float damp) {
            vx = (vx + (tx - x) * k) * damp;
            vy = (vy + (ty - y) * k) * damp;
            x += vx;
            y += vy;
        }

        boolean settled() {
            return Math.abs(vx) + Math.abs(vy) < 0.05f && Math.abs(tx - x) + Math.abs(ty - y) < 0.5f
                && Math.abs(tr - r) < 0.3f;
        }
    }

    final View ink;
    /** Under the ink: the background deepening to pure black the further it is from the drop (dark themes). */
    final View shade;
    private final Host host;
    private final float dp;
    private final Drop core = new Drop(), finger = new Drop();

    /** Charging: a drop riding the stream from the port into the core; s is how far along (0 port, 1 core). */
    private static final class Rider { float s, r; int color; }
    private final List<Rider> riders = new ArrayList<>();
    /** The stream's bend: a lagging control point, so the stream curves after the core when it moves. */
    private final Drop bend = new Drop();
    private boolean plugged;
    private long nextDrip;
    /** How far the charging stream has risen from the port toward the core: 0 = none, 1 = joined. */
    private float stream;
    /** Unplugged: how far up the stream's bottom end has let go of the port (it falls up into the core). */
    private float tail;
    private final Path streamPath = new Path();
    private final Paint streamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Drop> drops = new ArrayList<>();
    private final Path strand = new Path();
    private static final float STRAND_MAX = 400; // dp: pulled further than this (toward a screen edge), the strand snaps
    private static final float LINK = 70;        // dp: a drop this close to the strand reaches out and joins it
    private static final long SNAP_MS = 480;     // the two halves of a snapped strand spring back this long
    private boolean broken; // snapped; joins again once the finger comes back well within STRAND_MAX
    private long snapAt = -SNAP_MS;
    private static final long JOIN_MS = 380;     // rejoining: two tails reach out from the core and the finger and meet
    private long joinAt = -JOIN_MS;
    private float snapLen;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG), clockPaint, datePaint, glyphPaint;
    private int top, bottom; // system bar insets
    private boolean open, swiping;
    private Drop hover;
    private float downX, downY, clockAlpha = 1;
    private long introAt = -INTRO_MS;
    private boolean introPending;
    private boolean waiting; // empty until intro(): a fresh start or a screen that went off
    private static final long INTRO_MS = 1500, ARRIVE_MS = 420;
    /** Where the in-display fingerprint scanner sits, as a fraction of the screen height (Nothing Phone (3a)). */
    private static final float SCANNER_Y = 0.911f;
    private long downAt;
    private boolean onCore; // the touch began on the resting drop: a quick tap there opens the calendar
    private long lastFrame;
    private boolean alive; // Home is on screen: the core keeps flowing
    private float acc;

    Liquid(Context ctx, Host host, Theme theme, Typeface serif, Typeface italic) {
        super(ctx);
        fg = theme.fg;
        glyph = theme.glyph;
        inks = theme.inks;
        core.color = finger.color = inks[0];
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        clockPaint = text(serif, glyph, 46);
        datePaint = text(italic, fg, 24);
        glyphPaint = text(serif, glyph, 30);
        core.r = core.tr = 82 * dp;

        ink = new View(ctx) {
            @Override protected void onDraw(Canvas c) {
                drawStrand(c, true);
                for (Drop d : drops) { circle(c, d.x, d.y, d.r, d.color); tail(c, d, d.r); }
                tail(c, core, core.r * 0.8f);
                drawStream(c, true);
                for (Rider r : riders) { float[] at = streamAt(r.s); circle(c, at[0], at[1], r.r, r.color); }
                circle(c, finger.x, finger.y, finger.r, finger.color);
                // The core is a few lobes drifting around its centre; the goo melts them into one wobbling blob.
                float t = SystemClock.uptimeMillis() / 1000f;
                // In Colour each lobe is another ink, and the blur blends them where they meet.
                circle(c, core.x, core.y, core.r * 0.72f, core.color);
                for (int k = 0; k < 5; k++) { // each lobe swings in and out, and swells and shrinks, at its own pace
                    double a = t * (0.7 + k * 0.23) + k * 1.3;
                    float off = core.r * (0.36f + 0.16f * (float) Math.sin(t * 0.8 + k));
                    float r = core.r * (0.52f + 0.1f * (float) Math.sin(t * 1.1 + k * 2));
                    circle(c, core.x + (float) Math.cos(a) * off, core.y + (float) Math.sin(a * 1.3) * off, r, inks[(k + 1) % inks.length]);
                }
                // Droplets circling the rim: melted into the edge they're moving bumps; drifting a little further
                // out, they hang on by a thin neck before sliding back in.
                float s = core.r / (82 * dp);
                for (int k = 0; k < 8; k++) {
                    double a = t * (0.22 + k * 0.05) * (k % 2 == 0 ? 1 : -1) + k * 0.785;
                    float rim = core.r * (0.86f + 0.2f * (float) Math.sin(t * 0.6 + k * 1.7));
                    float r = (8 + 4 * (float) Math.sin(t * 0.8 + k * 2.1)) * dp * s;
                    circle(c, core.x + (float) Math.cos(a) * rim, core.y + (float) Math.sin(a) * rim, r, inks[(k + 2) % inks.length]);
                }
            }
        };
        shade = new View(ctx) {
            private final Paint p = new Paint();
            private final android.graphics.Matrix at = new android.graphics.Matrix();
            @Override protected void onDraw(Canvas c) {
                if (p.getShader() == null) {
                    float reach = (float) Math.hypot(getWidth(), getHeight()) * 0.6f;
                    p.setShader(new android.graphics.RadialGradient(0, 0, reach,
                        new int[] {0x00000000, 0x00000000, 0x8C000000, 0xFF000000}, new float[] {0, 0.16f, 0.6f, 1},
                        Shader.TileMode.CLAMP));
                }
                at.setTranslate(shadeX, shadeY); // follows the drop (the page's centre once it has fallen away)
                p.getShader().setLocalMatrix(at);
                c.drawPaint(p);
            }
        };
        shade.setVisibility(theme.lightBg ? GONE : VISIBLE);

        float blur = 16 * dp;
        ColorMatrix threshold = new ColorMatrix(new float[] {
            1, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 24, -10 * 255}); // alpha ×24 −10: the blur's soft edge becomes a sharp liquid one
        ink.setLayerType(LAYER_TYPE_HARDWARE, null); // the blurred ink is cached: when it's still, showing it is free
        ink.setRenderEffect(RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(threshold),
            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)));
    }

    /** A moving blob trails smaller drops behind it, so in the goo it stretches into a teardrop as it travels. */
    private void tail(Canvas c, Drop d, float r) {
        float speed = (float) Math.hypot(d.vx, d.vy);
        if (speed < 0.6f * dp || r < 4 * dp) return;
        float ux = -d.vx / speed, uy = -d.vy / speed, pull = Math.min(1, speed / (9 * dp));
        for (int j = 1; j <= 3; j++)
            circle(c, d.x + ux * r * 0.45f * j * pull, d.y + uy * r * 0.45f * j * pull, r * (0.82f - 0.17f * j), d.color);
    }

    private void circle(Canvas c, float x, float y, float r, int color) {
        inkPaint.setColor(color);
        c.drawCircle(x, y, r, inkPaint);
    }

    private Paint text(Typeface tf, int color, float sp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        return p;
    }

    void setInsets(int top, int bottom) {
        this.top = top;
        this.bottom = bottom;
        invalidate();
    }

    void setFavourites(List<Apps.App> apps) {
        drops.clear();
        for (Apps.App a : apps) {
            Drop d = new Drop();
            d.app = a;
            d.color = inks[(drops.size() + 1) % inks.length];
            d.x = d.tx = core.tx;
            d.y = d.ty = core.ty;
            d.r = d.tr = waiting || widgetMode ? 0 : DROP * dp;
            drops.add(d);
        }
        frame();
    }

    private float cx() { return getWidth() / 2f; }
    private float cy() { return getHeight() * 0.54f; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (Drop d : all()) { d.x = d.tx = cx(); d.y = d.ty = cy(); }
        if (introPending) { introPending = false; post(this::intro); }
        // Never left empty: if nothing has started the intro shortly after Home is laid out, it plays anyway.
        else if (waiting) postDelayed(this::intro, 450);
    }

    private List<Drop> all() {
        List<Drop> l = new ArrayList<>(drops);
        l.add(core);
        l.add(finger);
        return l;
    }

    // ── gestures ──

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (flood != null) return true; // an app is opening
                downX = x;
                downY = y;
                downAt = e.getEventTime();
                onCore = !open && Math.hypot(x - core.x, y - core.y) < core.r;
                swiping = y > getHeight() - bottom - 110 * dp;
                if (!swiping) split(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (swiping) {
                    if (downY - y > 60 * dp) { swiping = false; host.showAll(); }
                } else if (open) {
                    follow(x, y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                if (open) {
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (up && flick(x, y, e.getEventTime(), FLICK_MS, 60)) return true;
                    if (up && onCore && hover == null && e.getEventTime() - downAt < 300
                            && Math.hypot(x - downX, y - downY) < 12 * dp) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        merge();
                        flood(core, () -> host.openCalendar(rect(core)));
                    } else if (hover != null && up) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        Drop d = hover;
                        flood(d, () -> host.open(d.app, rect(d)));
                    } else {
                        merge();
                        // a double tap on empty space opens the widgets page
                        if (up && !onCore && e.getEventTime() - downAt < 250 && Math.hypot(x - downX, y - downY) < 12 * dp) {
                            if (e.getEventTime() - lastTap < 320 && Math.hypot(x - tapX, y - tapY) < 60 * dp) {
                                lastTap = 0;
                                host.showWidgets();
                            } else {
                                lastTap = e.getEventTime();
                                tapX = x;
                                tapY = y;
                            }
                        }
                    }
                }
                return true;
        }
        return false;
    }

    /**
     * A flick is decided on release: up or down at least dist dp and let go within ms opens all apps (up) or the
     * notifications (down), wherever it ends. Held any longer, it's the ring: letting go on a drop opens that app.
     */
    private boolean flick(float x, float y, long t, long ms, float dist) {
        float dy = y - downY;
        if (t - downAt > ms || Math.abs(dy) < dist * dp || Math.abs(dy) < 2 * Math.abs(x - downX)) return false;
        merge();
        if (dy < 0) host.showAll(); else host.showNotifications();
        return true;
    }

    private static final long FLICK_MS = 250;
    private long lastTap;
    private float tapX, tapY;

    /** The drop flooding the screen with its ink while its app opens; null otherwise. */
    private Drop flood;

    /**
     * Opening: the drop swells until its ink floods the whole screen, then go() starts the app over it (it fades
     * and zooms in, see Apps.launch). Back Home, reset() lets the ink drain back into the drop.
     */
    private void flood(Drop d, Runnable go) {
        open = false;
        hover = null;
        finger.tr = 0;
        flood = d;
        d.tr = (float) Math.hypot(getWidth(), getHeight());
        frame();
        postDelayed(go, 240);
    }

    private static Rect rect(Drop d) {
        int r = (int) Math.min(d.r, 120 * Resources.getSystem().getDisplayMetrics().density);
        return new Rect((int) d.x - r, (int) d.y - r, (int) d.x + r, (int) d.y + r);
    }
    private static final float RING = 142, DROP = 36, HOVER = 52;

    /** Splits the core into a ring of drops around the finger, kept fully on screen. */
    private void split(float x, float y) {
        if (waiting) { waiting = false; for (Drop d : drops) d.tr = DROP * dp; } // touched before the intro
        open = true;
        hover = null;
        float m = (RING + HOVER + 6) * dp;
        float ax = Math.max(m, Math.min(getWidth() - m, x));
        float ay = Math.max(top + m + 70 * dp, Math.min(getHeight() - bottom - m - 40 * dp, y));
        core.tx = ax;
        core.ty = ay;
        core.tr = 44 * dp;
        finger.x = finger.tx = x;
        finger.y = finger.ty = y;
        finger.tr = 24 * dp;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < drops.size(); i++) {
            double g = -Math.PI / 2 + i * 2 * Math.PI / drops.size();
            Drop d = drops.get(i);
            d.tx = ax + (float) Math.cos(g) * RING * dp;
            d.ty = ay + (float) Math.sin(g) * RING * dp;
            d.rx = d.tx;
            d.ry = d.ty;
            d.tr = DROP * dp;
            d.at = now + i * 30L;
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        frame();
    }

    private void follow(float x, float y) {
        finger.tx = x;
        finger.ty = y;
        Drop best = null;
        float bestD = 84 * dp;
        for (Drop d : drops) {
            float dd = (float) Math.hypot(x - d.tx, y - d.ty);
            if (dd < bestD) { bestD = dd; best = d; }
        }
        if (best != hover) {
            hover = best;
            finger.color = hover != null ? hover.color : core.color;
            if (hover != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            for (Drop d : drops) d.tr = (d == hover ? HOVER : DROP) * dp;
        }
        frame();
    }

    /** Pulls every drop back into the core, the last one out first. */
    private void merge() {
        open = false;
        flood = null;
        broken = false;
        hover = null;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < drops.size(); i++) {
            Drop d = drops.get(i);
            d.tx = cx();
            d.ty = cy();
            d.tr = waiting || widgetMode ? 0 : DROP * dp; // hidden while Home is empty (intro to come, or widgets)
            d.at = now + (drops.size() - i) * 22L;
        }
        core.tx = finger.tx = cx();
        core.ty = finger.ty = cy();
        finger.tr = 0;
        frame();
    }

    /** Plugged in (or out): the stream rises from the port (or drains back), and drops start riding it up. */
    void setPlugged(boolean plugged, boolean justNow) {
        this.plugged = plugged;
        if (plugged) nextDrip = SystemClock.uptimeMillis() + (justNow ? 3200 : 600);
        frame();
    }

    private float portX() { int[] l = new int[2]; getLocationOnScreen(l); return getResources().getDisplayMetrics().widthPixels / 2f - l[0]; }
    private float portY() { int[] l = new int[2]; getLocationOnScreen(l); return getResources().getDisplayMetrics().heightPixels + 6 * dp - l[1]; }

    /**
     * The stream's centre at s (0 port, 1 core): a curve leaving the port straight up and bending toward the core
     * through the lagging bend point, swaying gently from side to side.
     */
    private float[] streamAt(float s) {
        float px = portX(), py = portY(), t = SystemClock.uptimeMillis() / 1000f, q = 1 - s;
        float x = q * q * px + 2 * q * s * bend.x + s * s * core.x;
        float y = q * q * py + 2 * q * s * bend.y + s * s * core.y;
        x += (float) Math.sin(Math.PI * s) * (float) Math.sin(t * 0.8 + s * 3) * 14 * dp;
        return new float[] {x, y};
    }

    /**
     * The charging stream: a slow liquid thread from the port up into the core, pooled wide at the port, thin in
     * the middle, widening where it joins; it swells around each drop riding it, and sways. Like the strand, it's
     * drawn in the goo (where its wide ends melt into the pool and the core) and crisp on top (its thin middle).
     */
    private void drawStream(Canvas c, boolean goo) {
        if (stream <= 0.001f || core.r < 8 * dp) return;
        int n = 32;
        float[] lx = new float[n], ly = new float[n], rx = new float[n], ry = new float[n];
        for (int i = 0; i < n; i++) {
            float s = tail + i / (n - 1f) * (stream - tail); // from the port (or where it let go) up to the tip
            float[] p = streamAt(s), p2 = streamAt(Math.min(1, s + 0.015f));
            float w = 20 * dp * (float) Math.pow(1 - s, 3) + 4 * dp + 9 * dp * (float) Math.pow(s, 6); // pool, thread, joint
            for (Rider r : riders) { // the stream swells around each drop riding it
                float g = (s - r.s) / 0.045f;
                w += r.r * 0.8f * (float) Math.exp(-g * g);
            }
            if (i == n - 1) w *= 1 - Math.min(1, (1 - stream) * 6); // the rising tip tapers
            if (tail > 0) w *= (float) Math.sqrt(Math.min(1, (s - tail) / 0.07f)); // so does the end that let go
            float dx = p2[0] - p[0], dy = p2[1] - p[1], l = (float) Math.hypot(dx, dy);
            float nx = l == 0 ? 1 : -dy / l, ny = l == 0 ? 0 : dx / l;
            lx[i] = p[0] + nx * w; ly[i] = p[1] + ny * w; rx[i] = p[0] - nx * w; ry[i] = p[1] - ny * w;
        }
        streamPath.rewind();
        streamPath.moveTo(lx[0], ly[0]);
        for (int i = 1; i < n - 1; i++) streamPath.quadTo(lx[i], ly[i], (lx[i] + lx[i + 1]) / 2, (ly[i] + ly[i + 1]) / 2);
        streamPath.lineTo(lx[n - 1], ly[n - 1]);
        streamPath.lineTo(rx[n - 1], ry[n - 1]);
        for (int i = n - 2; i > 0; i--) streamPath.quadTo(rx[i], ry[i], (rx[i] + rx[i - 1]) / 2, (ry[i] + ry[i - 1]) / 2);
        streamPath.lineTo(rx[0], ry[0]);
        streamPath.close();
        // the core's ink at the top, another ink at the port (the same one, in the one-ink themes)
        streamPaint.setShader(new LinearGradient(portX(), portY(), core.x, core.y, inks[2 % inks.length], core.color, Shader.TileMode.CLAMP));
        c.drawPath(streamPath, streamPaint);
    }

    /** Starts or stops the core's idle flow, so a Home that isn't visible costs no frames. */
    void setAlive(boolean alive) {
        this.alive = alive;
        if (alive) frame();
    }

    /**
     * Unlock: an ink drop landing. The core grows from a point, throwing a small splash of droplets that fall
     * back into it, then the date and the clock ink in.
     */
    // ── widgets: double-tap empty space and the drop falls away, leaving the date and an empty page ──

    private boolean widgetMode;
    private float shadeX, shadeY;

    /** The drop falls off the bottom of the screen (stretching as it speeds up) and is gone; the date stays. */
    void enterWidgets() {
        if (widgetMode) return;
        if (open) merge();
        widgetMode = true;
        core.vx = 0;
        core.vy = -2 * dp; // a tiny lift before it drops
        for (Drop d : drops) { d.r = d.tr = 0; }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        frame();
    }

    /** Back from the widgets: the drop is born again from the scanner (the intro). */
    void exitWidgets() {
        if (!widgetMode) return;
        widgetMode = false;
        waiting = true;
        core.r = 0;
        intro();
    }

    boolean inWidgets() { return widgetMode; }

    void intro() {
        if (!waiting) return;
        if (widgetMode) { waiting = false; return; } // stays an empty page
        if (getWidth() == 0) { introPending = true; return; } // not laid out yet: play once it is
        waiting = false;
        long now = SystemClock.uptimeMillis();
        introAt = now;
        // Wells up out of the fingerprint scanner, rises to the centre trailing a strand back to the scanner (the
        // finger drop, left behind for a moment), and splashes as it arrives.
        int[] at = new int[2];
        getLocationOnScreen(at);
        float sx = getResources().getDisplayMetrics().widthPixels / 2f - at[0];
        float sy = getResources().getDisplayMetrics().heightPixels * SCANNER_Y - at[1];
        core.x = sx;
        core.y = sy;
        core.vx = core.vy = 0;
        core.tx = cx();
        core.ty = cy();
        core.r = 14 * dp;
        core.vx = (Math.random() < 0.5 ? -1 : 1) * 5 * dp; // a little sideways push: it rises on a soft arc
        finger.x = finger.tx = sx;
        finger.y = finger.ty = sy;
        finger.vx = finger.vy = 0;
        finger.r = 8 * dp;
        finger.tr = 24 * dp; // a pool on the scanner that the drop pulls itself out of
        finger.at = now + 260; // stays at the scanner a while, so the strand stretches
        postDelayed(() -> { finger.tx = cx(); finger.ty = cy(); finger.tr = 0; }, 260);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < drops.size(); i++) {
            Drop d = drops.get(i);
            double g = i * 2 * Math.PI / drops.size() + rnd.nextFloat() * 0.6;
            float far = (34 + rnd.nextInt(24)) * dp; // inside the glyphs' 70dp: the splash is plain ink
            d.x = d.tx = cx();
            d.y = d.ty = cy();
            d.vx = d.vy = 0;
            d.tx = cx() + (float) Math.cos(g) * far;
            d.ty = cy() + (float) Math.sin(g) * far;
            d.r = 0;
            d.tr = (9 + rnd.nextInt(9)) * dp;
            d.at = now + ARRIVE_MS; // the splash, as the drop lands
        }
        postDelayed(() -> { if (!open) merge(); }, ARRIVE_MS + 300); // the splash falls back in
        frame();
    }

    /** Empties Home (no drop, no date, no clock) until the next intro(), so the drop lands on a clean page. */
    void await() {
        waiting = true;
        if (open) merge();
        core.r = 0;
        for (Drop d : drops) { d.r = d.tr = 0; }
        invalidate();
        ink.invalidate();
    }

    boolean waiting() { return waiting; }

    /** 0→1 over the intro for something that inks in after delay ms (1 when no intro is playing). */
    private float inked(long delay) {
        if (waiting) return 0;
        return Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - introAt - delay) / 450f));
    }

    /** Back to the closed drop at once, e.g. when Home is shown again. */
    void reset() {
        merge();
        for (Drop d : all()) { d.x = d.tx; d.y = d.ty; d.vx = d.vy = 0; }
    }

    // ── animation ──

    /** Runs the physics once per display frame until everything rests, redrawing both layers together. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            acc += Math.min(now - lastFrame, 100);
            lastFrame = now;
            boolean settled = false;
            while (acc >= STEP_MS) { settled = step(now); acc -= STEP_MS; }
            ink.invalidate();
            shade.invalidate();
            invalidate();
            if (!settled) postOnAnimation(this);
            else if (alive) postOnAnimationDelayed(this, 33); // only the slow wobble left: 30 fps is plenty
            else running = false;
        }
    };
    private boolean running;

    private void frame() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        acc = STEP_MS; // step right away so the first touch moves something this frame
        postOnAnimation(tick);
    }

    private boolean step(long now) {
        core.tr = waiting ? 0 : flood == core ? (float) Math.hypot(getWidth(), getHeight())
            : widgetMode ? (core.y > getHeight() + core.r ? 0 : core.r) : (open ? 44 : 82) * dp;
        shadeX += ((widgetMode ? cx() : core.x) - shadeX) * 0.06f;
        shadeY += ((widgetMode ? getHeight() * 0.45f : core.y) - shadeY) * 0.06f;
        if (open) {
            // Sticky: drops near the finger lean toward it, the hovered one stretches most.
            for (Drop d : drops) {
                float dist = (float) Math.hypot(finger.x - d.rx, finger.y - d.ry);
                float pull = d == hover ? 0.38f : Math.max(0, 0.2f * (1 - dist / (240 * dp)));
                d.tx = d.rx + (finger.x - d.rx) * pull;
                d.ty = d.ry + (finger.y - d.ry) * pull;
            }
            float span = (float) Math.hypot(finger.x - core.x, finger.y - core.y);
            if (!broken && span > STRAND_MAX * dp) {
                broken = true;
                snapAt = now;
                snapLen = span;
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } else if (broken && span < STRAND_MAX * dp * 0.7f) {
                broken = false;
                joinAt = now;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if (!broken && span > core.r) {
                // The strand is sticky too: drops near it lean in (and drawStrand gives them a thread to it).
                for (Drop d : drops) {
                    if (d == hover) continue;
                    float[] q = closest(d.rx, d.ry);
                    float gap = (float) Math.hypot(q[0] - d.rx, q[1] - d.ry);
                    float pull = 0.3f * Math.max(0, 1 - gap / (120 * dp));
                    d.tx += (q[0] - d.rx) * pull;
                    d.ty += (q[1] - d.ry) * pull;
                }
            }
        }
        if (plugged && !waiting && flood == null && !widgetMode) {
            if (tail > 0) { stream = 0; tail = 0; } // plugged back in while it was letting go: a fresh one rises
            stream += (1 - stream) * 0.012f; // rises slowly from the port
            if (1 - stream < 0.002f) stream = 1;
        } else if (stream > 0) {
            // Unplugged: the stream lets go of the port and what's left falls up into the core, slow then faster.
            tail += 0.0025f + 0.035f * tail * tail;
            if (tail >= stream - 0.01f) { stream = 0; tail = 0; core.r += 4 * dp; } // swallowed whole
        }
        if (stream > 0) {
            // the bend lags after the core: the stream leaves the port straight up and curves to wherever it is
            bend.tx = portX();
            bend.ty = portY() + (core.y - portY()) * 0.55f;
            if (bend.x == 0 && bend.y == 0) { bend.x = bend.tx; bend.y = bend.ty; }
            bend.step(0.03f, 0.86f);
        }
        if (stream > 0.97f && tail == 0 && plugged && alive && now >= nextDrip) { // a drop sets off from the port
            Rider r = new Rider();
            r.r = (11 + (float) Math.random() * 5) * dp;
            r.color = inks[(int) (Math.random() * inks.length)];
            riders.add(r);
            nextDrip = now + 1800 + (long) (Math.random() * 1600);
        }
        for (int i = riders.size() - 1; i >= 0; i--) {
            Rider r = riders.get(i);
            r.s = Math.min(stream, r.s + 0.0016f + 0.009f * r.s * r.s); // slow off the port, sliding faster into the core
            r.s = Math.max(r.s, tail); // a letting-go stream carries its drops up with it
            if (r.s >= 0.985f || stream == 0) { riders.remove(i); core.r += 3 * dp; } // swallowed: the core swells a touch
        }
        boolean settled = riders.isEmpty() && stream == 0;
        for (Drop d : all()) {
            if (now < d.at) { /* waits its turn */ }
            else if (d == core && widgetMode) { // falling: gravity, so it stretches into a teardrop as it speeds up
                if (core.y < getHeight() + core.r * 2) { d.vy += 0.9f * dp; d.x += d.vx; d.y += d.vy; }
                else { d.vx = d.vy = 0; d.r = 0; }
            }
            else if (d == core && now - introAt < 1000) d.step(0.035f, 0.87f); // the intro's rise: slow and soft
            else d.step();
            if (now >= d.at) // waiting drops neither move nor grow
                d.r += (d.tr - d.r) * (now - introAt < INTRO_MS ? 0.07f : d == flood ? 0.2f : 0.16f); // the intro's drop swells slowly
            settled &= d == core && widgetMode ? d.r == 0 : d.settled() && now >= d.at; // fallen away = at rest
        }
        float clockTo = open || flood != null || widgetMode ? 0 : 1;
        clockAlpha += (clockTo - clockAlpha) * 0.25f;
        return settled && Math.abs(clockAlpha - clockTo) < 0.01f && now - snapAt > SNAP_MS && now - joinAt > JOIN_MS && now - introAt > INTRO_MS;
    }

    /** The point on the core-to-finger line nearest (x, y). */
    private float[] closest(float x, float y) {
        float dx = finger.x - core.x, dy = finger.y - core.y, l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - core.x) * dx + (y - core.y) * dy) / l2));
        return new float[] {core.x + dx * t, core.y + dy * t, t};
    }

    /**
     * The web: a strand from the core to the finger, wide where it joins each blob, pinched in the middle, thinner
     * the further it's pulled; drops near it reach out with threads of their own. Past STRAND_MAX it snaps and
     * each half springs back into its blob.
     *
     * Everything is drawn twice: in the goo (goo = true), where wide bases melt into the blobs and thin parts blur
     * away, and crisp on top, fading in at the base so it takes over where the goo copy thins out.
     */
    private void drawStrand(Canvas c, boolean goo) {
        if (finger.r < 2 * dp) return;
        float cdx = finger.x - core.x, cdy = finger.y - core.y, clen = (float) Math.hypot(cdx, cdy);
        if (clen < core.r) return;
        float ux = cdx / clen, uy = cdy / clen;
        float sx = core.x + ux * core.r * 0.55f, sy = core.y + uy * core.r * 0.55f; // the core's edge, not its centre
        float ea = core.r * 0.6f, eb = finger.r * 0.85f;
        long since = SystemClock.uptimeMillis() - snapAt;

        if (broken) {
            // Snapped: two tails springing back, fast at first then settling, into the core and into the finger.
            if (since <= SNAP_MS) tails(c, goo, sx, sy, ux, uy, ea, eb, snapLen / 2 * (float) Math.pow(1 - since / (float) SNAP_MS, 3));
            return;
        }
        long joining = SystemClock.uptimeMillis() - joinAt;
        if (joining < JOIN_MS) {
            // Rejoining: the tails shoot out toward each other, slowing as their beads meet in the middle.
            float half = (float) Math.hypot(finger.x - sx, finger.y - sy) / 2;
            tails(c, goo, sx, sy, ux, uy, ea, eb, half * (1 - (float) Math.pow(1 - joining / (float) JOIN_MS, 3)));
            return;
        }

        float stretch = Math.min(0.999f, clen / (STRAND_MAX * dp));
        float waist = Math.max(0.8f * dp, Math.min(ea, eb) * 0.9f * (float) Math.pow(1 - stretch, 1.6));
        neck(c, sx, sy, finger.x, finger.y, ea, eb, waist, core.color, finger.color, goo);

        for (Drop d : drops) {
            if (d == hover) continue;
            float[] q = closest(d.x, d.y);
            float gx = q[0] - d.x, gy = q[1] - d.y, dist = (float) Math.hypot(gx, gy), gap = dist - d.r;
            if (gap <= 0 || gap > LINK * dp) continue;
            float near = 1 - gap / (LINK * dp);
            int at = mix(core.color, finger.color, q[2]);
            neck(c, d.x + gx / dist * d.r * 0.5f, d.y + gy / dist * d.r * 0.5f, q[0], q[1],
                d.r * 0.5f * near, waist + dp, Math.max(0.7f * dp, d.r * 0.22f * near * near), d.color, at, goo);
        }
    }

    /** A tail from the core's edge and one from the finger, each reach long, with a bead on its tip. */
    private void tails(Canvas c, boolean goo, float sx, float sy, float ux, float uy, float ea, float eb, float reach) {
        float tip = 2 * dp + 4 * dp * Math.min(1, reach / (120 * dp)); // beads shrink as the tails get short
        float ax = sx + ux * reach, ay = sy + uy * reach, bx = finger.x - ux * reach, by = finger.y - uy * reach;
        neck(c, sx, sy, ax, ay, ea, tip, 1.2f * dp, core.color, core.color, goo);
        neck(c, finger.x, finger.y, bx, by, eb, tip, 1.2f * dp, finger.color, finger.color, goo);
        if (!goo) { circle(c, ax, ay, tip * 1.3f, core.color); circle(c, bx, by, tip * 1.3f, finger.color); }
    }

    /** A liquid neck from a to b: half-width wa at a, wb at b, pinched to waist between; ink ca fading to cb. */
    private void neck(Canvas c, float ax, float ay, float bx, float by, float wa, float wb, float waist,
                      int ca, int cb, boolean goo) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.hypot(dx, dy);
        if (len < 1) return;
        float nx = -dy / len, ny = dx / len, mx = ax + dx / 2, my = ay + dy / 2;
        // It sways like a liquid thread: the middle bends sideways and back, more on a long strand.
        float sway = (float) Math.sin(SystemClock.uptimeMillis() / 380.0 + len / (90 * dp)) * Math.min(len * 0.07f, 18 * dp);
        float s1 = sway * 0.7f, s2 = sway, s3 = sway * 0.7f;
        strand.rewind();
        strand.moveTo(ax + nx * wa, ay + ny * wa);
        strand.quadTo(ax + dx * 0.25f + nx * (waist + s1), ay + dy * 0.25f + ny * (waist + s1), mx + nx * (waist + s2), my + ny * (waist + s2));
        strand.quadTo(ax + dx * 0.75f + nx * (waist + s3), ay + dy * 0.75f + ny * (waist + s3), bx + nx * wb, by + ny * wb);
        strand.lineTo(bx - nx * wb, by - ny * wb);
        strand.quadTo(ax + dx * 0.75f - nx * (waist - s3), ay + dy * 0.75f - ny * (waist - s3), mx - nx * (waist - s2), my - ny * (waist - s2));
        strand.quadTo(ax + dx * 0.25f - nx * (waist - s1), ay + dy * 0.25f - ny * (waist - s1), ax - nx * wa, ay - ny * wa);
        strand.close();
        strandPaint.setShader(new LinearGradient(ax, ay, bx, by,
            new int[] {goo ? ca : ca & 0x00FFFFFF, ca, cb}, new float[] {0, 0.22f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(strand, strandPaint);
    }

    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8)
            r |= Math.round(((a >>> sh) & 0xFF) * (1 - t) + ((b >>> sh) & 0xFF) * t) << sh;
        return r;
    }

    @Override protected void onDraw(Canvas c) {
        drawStream(c, false);
        drawStrand(c, false);
        datePaint.setAlpha((int) (255 * inked(450)));
        c.drawText(DateFormat.format("EEEE d MMMM", System.currentTimeMillis()).toString(), cx(), top + 72 * dp, datePaint);

        for (Drop d : drops) {
            if (d.r < 10 * dp) continue; // too small to carry a glyph (hidden in the core, or a splash droplet)
            float sep = (float) Math.hypot(d.x - core.x, d.y - core.y);
            int alpha = (int) (255 * Math.max(0, Math.min(1, (sep - 70 * dp) / (45 * dp)))
                * Math.max(0, Math.min(1, 1 - (d.r - HOVER * dp) / (70 * dp))) // a flooding drop's glyph melts away
                * (flood == null || flood == d ? 1 : 0)); // and the flood swallows the others
            if (alpha == 0) continue;
            float gr = Math.min(d.r, HOVER * dp);
            if (d.app.glyph != null) {
                int s = (int) (gr * 1.05f); // a monochrome layer is a 108-unit canvas with the glyph in its middle third
                d.app.glyph.setTint(glyph);
                d.app.glyph.setAlpha(alpha);
                d.app.glyph.setBounds((int) d.x - s, (int) d.y - s, (int) d.x + s, (int) d.y + s);
                d.app.glyph.draw(c);
            } else {
                glyphPaint.setAlpha(alpha);
                glyphPaint.setTextSize(gr * 0.9f);
                c.drawText(d.app.label.substring(0, 1), d.x, d.y + gr * 0.3f, glyphPaint);
            }
        }

        if (clockAlpha > 0.01f) {
            String time = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", System.currentTimeMillis()).toString();
            clockPaint.setAlpha((int) (255 * clockAlpha * inked(700)));
            c.drawText(time, core.x, core.y - (clockPaint.ascent() + clockPaint.descent()) / 2, clockPaint);
        }

    }
}
