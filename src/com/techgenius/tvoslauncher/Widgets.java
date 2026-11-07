package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** iOS-style home widgets drawn with Canvas: a month calendar and an analog clock. */
final class Widgets {
    private Widgets() { }

    private static final String KEY = "home_widgets";

    static boolean enabled(Context c) {
        return c == null || Profiles.homePrefs(c).getBoolean(KEY, true);
    }

    static void setEnabled(Context c, boolean on) {
        if (c != null) Profiles.homePrefs(c).edit().putBoolean(KEY, on).apply();
    }

    /** Where a widget sits and how big it is: top-left as a fraction of the screen, size as a multiple of 124 dp. */
    static final class Cfg {
        boolean visible = true;
        float x, y, scale = 1f;
    }

    static final String[] IDS = {"cal", "clock"};

    /** Base edge in dp and the 960x540 dp screen the defaults were laid out on. */
    static final float BASE_DP = 124f, SCREEN_W_DP = 960f, SCREEN_H_DP = 540f;

    static String nameOf(String id) {
        return "cal".equals(id) ? "Takvim" : "Saat";
    }

    static Cfg defaults(String id) {
        Cfg c = new Cfg();
        c.x = ("cal".equals(id) ? 40f : 40f + BASE_DP + 14f) / SCREEN_W_DP;
        c.y = 112f / SCREEN_H_DP;
        return c;
    }

    static Cfg get(Context c, String id) {
        Cfg d = defaults(id);
        android.content.SharedPreferences p = Profiles.homePrefs(c);
        Cfg r = new Cfg();
        r.visible = p.getBoolean("widget_" + id + "_v", true);
        r.x = p.getFloat("widget_" + id + "_x", d.x);
        r.y = p.getFloat("widget_" + id + "_y", d.y);
        r.scale = p.getFloat("widget_" + id + "_s", 1f);
        return clamp(r);
    }

    static void set(Context c, String id, Cfg v) {
        Cfg r = clamp(v);
        Profiles.homePrefs(c).edit()
                .putBoolean("widget_" + id + "_v", r.visible)
                .putFloat("widget_" + id + "_x", r.x)
                .putFloat("widget_" + id + "_y", r.y)
                .putFloat("widget_" + id + "_s", r.scale)
                .apply();
    }

    static void reset(Context c) {
        android.content.SharedPreferences.Editor e = Profiles.homePrefs(c).edit();
        for (String id : IDS) {
            e.remove("widget_" + id + "_v").remove("widget_" + id + "_x")
                    .remove("widget_" + id + "_y").remove("widget_" + id + "_s");
        }
        e.apply();
    }

    /** Keeps a widget fully on screen and within a sensible size range. */
    static Cfg clamp(Cfg v) {
        Cfg r = new Cfg();
        r.visible = v.visible;
        r.scale = Math.max(0.5f, Math.min(2.4f, Float.isNaN(v.scale) ? 1f : v.scale));
        float fw = BASE_DP * r.scale / SCREEN_W_DP, fh = BASE_DP * r.scale / SCREEN_H_DP;
        r.x = Math.max(0f, Math.min(1f - fw, Float.isNaN(v.x) ? 0f : v.x));
        r.y = Math.max(0f, Math.min(1f - fh, Float.isNaN(v.y) ? 0f : v.y));
        return r;
    }

    private static Locale locale(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0);
    }

    /** A view that wakes up on a timer only while it can be seen, so nothing ticks behind YouTube. */
    private abstract static class TickingView extends View {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                onTick();
                handler.postDelayed(this, nextDelayMs());
            }
        };

        TickingView(Context c) {
            super(c);
            setFocusable(false);
        }

        abstract void onTick();

        abstract long nextDelayMs();

        private void update() {
            handler.removeCallbacks(tick);
            if (isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) handler.post(tick);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            update();
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            update();
        }

        @Override
        protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility);
            if (changed == this) update();
        }
    }

    /** Month grid, Monday first: month name in red, today in a red circle, weekend days grey. */
    static final class CalendarView extends TickingView {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Typeface semi = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        private int drawnDay = -1;

        CalendarView(Context c) {
            super(c);
            bg.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            dot.setColor(0xFFEF4F4F);
        }

        @Override
        void onTick() {
            // Only the date matters here: redraw when it changes, not every minute.
            if (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) != drawnDay) invalidate();
        }

        @Override
        long nextDelayMs() {
            return 60_000L;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            box.set(0, 0, w, h);
            cv.drawRoundRect(box, w * 0.24f, w * 0.24f, bg);

            Locale loc = locale(getContext());
            Calendar now = Calendar.getInstance();
            drawnDay = now.get(Calendar.DAY_OF_YEAR);
            int today = now.get(Calendar.DAY_OF_MONTH);
            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;  // Monday = 0
            int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);

            float padX = w * 0.073f;
            float cell = (w - 2 * padX) / 7f;

            // Month name
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(semi);
            text.setColor(0xFFE8504F);
            text.setTextSize(w * 0.07f);
            String month = new SimpleDateFormat("LLLL", loc).format(now.getTime()).toUpperCase(loc);
            cv.drawText(month, w * 0.105f, w * 0.188f, text);

            // Weekday letters (Monday first)
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(w * 0.062f);
            text.setTypeface(bold);
            Calendar d = Calendar.getInstance();
            d.set(2024, Calendar.JANUARY, 1);  // a Monday
            SimpleDateFormat wd = new SimpleDateFormat("EEE", loc);
            float top = w * 0.288f;
            for (int i = 0; i < 7; i++) {
                String s = wd.format(d.getTime());
                text.setColor(i >= 5 ? 0xFF9B9BA1 : 0xFF111114);
                cv.drawText(s.substring(0, 1).toUpperCase(loc), padX + cell * (i + 0.5f), top, text);
                d.add(Calendar.DAY_OF_MONTH, 1);
            }

            // Day numbers: up to six rows
            float rowH = w * 0.097f;
            float firstBase = w * 0.396f;
            text.setTextSize(w * 0.068f);
            for (int day = 1; day <= days; day++) {
                int idx = offset + day - 1;
                int col = idx % 7, row = idx / 7;
                float cx = padX + cell * (col + 0.5f);
                float cy = firstBase + rowH * row;
                if (day == today) {
                    cv.drawCircle(cx, cy - w * 0.024f, w * 0.052f, dot);
                    text.setColor(0xFFFFFFFF);
                    text.setTypeface(bold);
                } else {
                    text.setColor(col >= 5 ? 0xFF9B9BA1 : 0xFF111114);
                    text.setTypeface(semi);
                }
                cv.drawText(String.valueOf(day), cx, cy, text);
            }
        }
    }

    /** White square analog clock: ticks run to the edge, 12/3/6/9, orange second hand. */
    static final class ClockView extends TickingView {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float[] hourTicks = new float[12 * 4], minuteTicks = new float[48 * 4];

        ClockView(Context c) {
            super(c);
            bg.setColor(0xFFFFFFFF);
            line.setStrokeCap(Paint.Cap.ROUND);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            num.setColor(0xFF1C1C1E);
        }

        @Override
        void onTick() {
            invalidate();
        }

        @Override
        long nextDelayMs() {
            return 1000L - (System.currentTimeMillis() % 1000L);
        }

        /** Ticks follow the widget's rounded-square outline (superellipse); computed once per size. */
        @Override
        protected void onSizeChanged(int nw, int nh, int ow, int oh) {
            super.onSizeChanged(nw, nh, ow, oh);
            float w = nw, cx = nw / 2f, cy = nh / 2f, half = nw / 2f;
            final double n = 5.0;
            int hi = 0, mi = 0;
            for (int m = 0; m < 60; m++) {
                boolean hour = m % 5 == 0;
                double a = Math.toRadians(m * 6);
                float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
                double k = Math.pow(Math.pow(Math.abs(dx), n) + Math.pow(Math.abs(dy), n), 1.0 / n);
                float edge = (float) (half / k);
                float r1 = edge - w * 0.052f;
                float grow = Math.max(0f, edge - half);  // longer toward the corners, like the real widget
                float r0 = r1 - (hour ? w * 0.085f + grow * 0.55f : w * 0.044f + grow * 0.06f);
                float[] out = hour ? hourTicks : minuteTicks;
                int i = hour ? hi : mi;
                out[i] = cx + dx * r0;
                out[i + 1] = cy + dy * r0;
                out[i + 2] = cx + dx * r1;
                out[i + 3] = cy + dy * r1;
                if (hour) hi += 4; else mi += 4;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            box.set(0, 0, w, h);
            cv.drawRoundRect(box, w * 0.24f, w * 0.24f, bg);
            float cx = w / 2f, cy = h / 2f;

            line.setStrokeWidth(w * 0.0095f);
            line.setColor(0xFFB5B5BA);
            cv.drawLines(minuteTicks, line);
            line.setStrokeWidth(w * 0.0075f);
            line.setColor(0xFF1C1C1E);
            cv.drawLines(hourTicks, line);

            num.setTextSize(w * 0.148f);
            float off = num.descent() - (num.descent() - num.ascent()) / 2f;
            cv.drawText("12", cx, w * 0.24f - off, num);
            cv.drawText("6", cx, h - w * 0.22f - off, num);
            cv.drawText("9", w * 0.222f, cy - off, num);
            cv.drawText("3", w - w * 0.222f, cy - off, num);

            Calendar c = Calendar.getInstance();
            float sec = c.get(Calendar.SECOND);
            float min = c.get(Calendar.MINUTE) + sec / 60f;
            float hr = (c.get(Calendar.HOUR) % 12) + min / 60f;

            dualHand(cv, cx, cy, hr * 30f, w * 0.062f, w * 0.268f, w * 0.034f, w * 0.010f);
            dualHand(cv, cx, cy, min * 6f, w * 0.062f, w * 0.452f, w * 0.034f, w * 0.010f);
            hand(cv, cx, cy, sec * 6f, w * 0.44f, w * 0.006f, 0xFFF2A33A);

            hub.setColor(0xFFF2A33A);
            cv.drawCircle(cx, cy, w * 0.026f, hub);
            hub.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, cy, w * 0.0125f, hub);
        }

        /** Black hand as on the real widget: a thin stem from the hub, then a thick rounded blade. */
        private void dualHand(Canvas cv, float cx, float cy, float deg, float from, float to, float thick, float thin) {
            double a = Math.toRadians(deg);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            line.setColor(0xFF1C1C1E);
            line.setStrokeWidth(thin);
            cv.drawLine(cx, cy, cx + dx * from, cy + dy * from, line);
            line.setStrokeWidth(thick);
            cv.drawLine(cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to, line);
        }

        private void hand(Canvas cv, float cx, float cy, float deg, float len, float width, int color) {
            double a = Math.toRadians(deg);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            line.setColor(color);
            line.setStrokeWidth(width);
            cv.drawLine(cx - dx * len * 0.17f, cy - dy * len * 0.17f, cx + dx * len, cy + dy * len, line);
        }
    }
}
