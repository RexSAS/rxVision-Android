package com.andrea.rxvision;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings: the ring as it opens on Home, a small ink dot with the favourites around it. Drag a drop onto another
 * drop's place and they swap; the order is saved and Home's ring follows. Each drop's badge ("+", or how many
 * apps it holds) opens its folder: the apps in the little ring that pops out of it.
 */
final class RingEditor extends View {

    private final Theme theme;
    private final Runnable changed;
    private final java.util.function.Consumer<Apps.App> folder;
    private int badge = -1; // the badge pressed: tapping it opens that drop's folder
    private final float dp;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), letter = new Paint(Paint.ANTI_ALIAS_FLAG),
        ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Each app's glyph, copied: the drops on Home tint theirs and fade them. */
    private final Map<ComponentName, Drawable> glyphs = new HashMap<>();
    private List<Apps.App> favourites;
    /** Where each drop is drawn now, gliding toward its place: x, y per index. */
    private float[] at = new float[0];
    private int dragged = -1, target = -1;
    private float fx, fy;

    RingEditor(Context ctx, Theme theme, Typeface serif, Runnable changed, java.util.function.Consumer<Apps.App> folder) {
        super(ctx);
        this.theme = theme;
        this.changed = changed;
        this.folder = folder;
        dp = getResources().getDisplayMetrics().density;
        letter.setTypeface(serif);
        letter.setColor(theme.glyph);
        letter.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * dp);
        ring.setColor(theme.fg(0x66));
    }

    void setFavourites(List<Apps.App> favourites) {
        this.favourites = favourites;
        at = new float[favourites.size() * 2];
        for (int i = 0; i < favourites.size(); i++) { float[] p = slot(i); at[2 * i] = p[0]; at[2 * i + 1] = p[1]; }
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (330 * dp));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (favourites != null) setFavourites(favourites);
    }

    private static final float RADIUS = 112, DROP = 30, CORE = 40;

    private float[] slot(int i) {
        double g = -Math.PI / 2 + i * 2 * Math.PI / Math.max(1, favourites.size());
        return new float[] {getWidth() / 2f + (float) Math.cos(g) * RADIUS * dp, getHeight() / 2f + (float) Math.sin(g) * RADIUS * dp};
    }

    /** The place nearest (x, y), or -1 if none is close. */
    private int nearest(float x, float y) {
        int best = -1;
        float bestD = 46 * dp;
        for (int i = 0; i < favourites.size(); i++) {
            float[] p = slot(i);
            float d = (float) Math.hypot(x - p[0], y - p[1]);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (favourites == null) return false;
        fx = e.getX();
        fy = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                badge = -1;
                for (int i = 0; i < favourites.size(); i++) {
                    float[] b = badgeAt(i);
                    if (Math.hypot(fx - b[0], fy - b[1]) < 20 * dp) badge = i;
                }
                if (badge >= 0) { getParent().requestDisallowInterceptTouchEvent(true); return true; }
                dragged = nearest(fx, fy);
                if (dragged < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                target = dragged;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (badge >= 0) return true;
                int t = nearest(fx, fy);
                if (t != target && t >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                target = t;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (badge >= 0) {
                    float[] b = badgeAt(badge);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.hypot(fx - b[0], fy - b[1]) < 28 * dp) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        folder.accept(favourites.get(badge));
                    }
                    badge = -1;
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP && target >= 0 && target != dragged) {
                    Collections.swap(favourites, dragged, target);
                    // the drops keep their drawn places and glide to their new ones
                    float tx = at[2 * dragged], ty = at[2 * dragged + 1];
                    at[2 * dragged] = at[2 * target]; at[2 * dragged + 1] = at[2 * target + 1];
                    at[2 * target] = fx; at[2 * target + 1] = fy;
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    changed.run();
                } else if (dragged >= 0) {
                    at[2 * dragged] = fx; at[2 * dragged + 1] = fy; // let go elsewhere: it glides back
                }
                dragged = target = -1;
                break;
        }
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        if (favourites == null || favourites.isEmpty()) return;
        boolean moving = false;
        ink.setColor(theme.inks[0]);
        c.drawCircle(getWidth() / 2f, getHeight() / 2f, CORE * dp, ink);
        for (int i = 0; i < favourites.size(); i++) {
            float[] p = slot(i);
            if (i == target && target != dragged) c.drawCircle(p[0], p[1], (DROP + 7) * dp, ring); // where it will land
            if (i == dragged) continue;
            at[2 * i] += (p[0] - at[2 * i]) * 0.22f;
            at[2 * i + 1] += (p[1] - at[2 * i + 1]) * 0.22f;
            moving |= Math.abs(p[0] - at[2 * i]) + Math.abs(p[1] - at[2 * i + 1]) > 0.5f;
            drop(c, i, at[2 * i], at[2 * i + 1], DROP * dp);
        }
        if (dragged >= 0) drop(c, dragged, fx, fy, (DROP + 6) * dp); // the one in hand, on top and a little larger
        if (moving) postInvalidateOnAnimation();
    }

    /** Where drop i's folder badge sits: on its outer edge, away from the dot. */
    private float[] badgeAt(int i) {
        float[] p = slot(i);
        float ux = p[0] - getWidth() / 2f, uy = p[1] - getHeight() / 2f, l = (float) Math.hypot(ux, uy);
        float r = (DROP + 2) * dp;
        return new float[] {p[0] + ux / l * r * 0.72f + uy / l * r * 0.72f, p[1] + uy / l * r * 0.72f - ux / l * r * 0.72f};
    }

    private void drop(Canvas c, int i, float x, float y, float r) {
        Apps.App a = favourites.get(i);
        ink.setColor(theme.inks[(i + 1) % theme.inks.length]);
        c.drawCircle(x, y, r, ink);
        if (i != dragged) { // its folder badge: "+" when empty, else how many apps it holds
            float[] b = badgeAt(i), p = slot(i);
            float bx = x + b[0] - p[0], by = y + b[1] - p[1], br = 11 * dp; // rides the drop as it glides
            ink.setColor(theme.accent);
            c.drawCircle(bx, by, br, ink);
            letter.setColor(0xFF141414);
            letter.setTextSize(br * 1.5f);
            c.drawText(a.folder.isEmpty() ? "+" : String.valueOf(a.folder.size()), bx, by + br * 0.5f, letter);
            letter.setColor(theme.glyph);
        }
        Drawable g = glyphs.get(a.component);
        if (g == null) {
            g = a.glyph.getConstantState().newDrawable().mutate();
            g.setTint(theme.glyph);
            g.setAlpha(255);
            glyphs.put(a.component, g);
        }
        int s = (int) (r * 1.05f);
        g.setBounds((int) x - s, (int) y - s, (int) x + s, (int) y + s);
        g.draw(c);
    }
}
