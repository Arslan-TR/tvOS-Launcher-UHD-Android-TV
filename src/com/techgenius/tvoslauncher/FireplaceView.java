package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/**
 * Animated fireplace for the screensaver. Everything is drawn in code (no video, no download):
 * a heat-diffusion fire on a small grid scaled up smoothly, a brick wall, mantel, hearth, logs,
 * rising embers and a flickering orange glow that lights up the room.
 */
public class FireplaceView extends View {
    private static final int GW = 176, GH = 112;   // fire simulation grid
    private static final int LEVELS = 48;
    private static final int NE = 40;               // max embers
    private static final long STEP_MS = 33;         // about 30 fps

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final int[] palette = new int[LEVELS];
    private final byte[] heat = new byte[GW * GH];
    private final int[] px = new int[GW * GH];
    private final float[] edge = new float[GW];
    private final Bitmap fireBmp = Bitmap.createBitmap(GW, GH, Bitmap.Config.ARGB_8888);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint();
    private final Paint glow2Paint = new Paint();
    private final Paint emberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF open = new RectF();
    private final RectF lg = new RectF();   // log area (centered, smaller than the full-screen fire)
    private final Rect full = new Rect();
    private final Path clip = new Path();

    private Bitmap bg, logs;
    private boolean running;
    private float flick = 1f, flickTarget = 1f;
    private long lastTarget, lastStep;

    private final float[] ex = new float[NE], ey = new float[NE], evx = new float[NE],
            evy = new float[NE], elife = new float[NE], emax = new float[NE], ephase = new float[NE];

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            float dt = Math.min(0.1f, (now - lastStep) / 1000f);
            lastStep = now;
            step(now, dt);
            invalidate();
            h.postDelayed(this, STEP_MS);
        }
    };

    public FireplaceView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        buildPalette();
        float cx = GW / 2f;
        for (int x = 0; x < GW; x++) {
            float d = (x - cx) / cx;
            edge[x] = d * d * 0.55f;
        }
    }

    public void start() {
        if (running) return;
        running = true;
        lastStep = System.currentTimeMillis();
        h.post(tick);
    }

    public void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    public void release() {
        stop();
        if (bg != null) { bg.recycle(); bg = null; }
        if (logs != null) { logs.recycle(); logs = null; }
    }

    // ------------------------------------------------------------------ simulation

    private void buildPalette() {
        float[][] s = {
                {0f, 0, 0, 0}, {0.10f, 60, 6, 0}, {0.32f, 185, 38, 0}, {0.55f, 255, 105, 8},
                {0.78f, 255, 180, 40}, {1f, 255, 240, 180}};
        for (int i = 0; i < LEVELS; i++) {
            float t = i / (float) (LEVELS - 1);
            int k = 0;
            while (k < s.length - 2 && t > s[k + 1][0]) k++;
            float u = (t - s[k][0]) / (s[k + 1][0] - s[k][0]);
            u = Math.max(0, Math.min(1, u));
            int r = (int) (s[k][1] + (s[k + 1][1] - s[k][1]) * u);
            int g = (int) (s[k][2] + (s[k + 1][2] - s[k][2]) * u);
            int b = (int) (s[k][3] + (s[k + 1][3] - s[k][3]) * u);
            int a = Math.min(255, i * 17);
            palette[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private void step(long now, float dt) {
        // slow, organic flicker of the whole fire and the room light
        if (now - lastTarget > 90 + rnd.nextInt(160)) {
            lastTarget = now;
            flickTarget = 0.82f + rnd.nextFloat() * 0.3f;
            if (rnd.nextInt(14) == 0) flickTarget = 1.22f;   // an occasional flare
        }
        flick += (flickTarget - flick) * 0.22f;

        // fuel: two bottom rows, concentrated in the middle
        float cx = GW / 2f, sig = GW * 0.36f;
        for (int row = GH - 2; row < GH; row++) {
            for (int x = 0; x < GW; x++) {
                float d = (x - cx) / sig;
                float p = (float) Math.exp(-d * d);
                int v = (int) ((LEVELS - 1) * p * (0.62f + 0.38f * rnd.nextFloat()) * flick);
                heat[row * GW + x] = (byte) Math.max(0, Math.min(LEVELS - 1, v));
            }
        }
        // spread upwards with jitter, cooling faster toward the sides
        for (int y = 1; y < GH; y++) {
            int rowOff = y * GW;
            int dstOff = (y - 1) * GW;
            for (int x = 0; x < GW; x++) {
                int v = heat[rowOff + x];
                if (v == 0) { heat[dstOff + x] = 0; continue; }
                int r = rnd.nextInt(4);
                int dx = x - r + 1;
                if (dx < 0) dx = 0; else if (dx >= GW) dx = GW - 1;
                int decay = (r & 1) + ((rnd.nextFloat() < 0.05f + edge[x] * 0.55f) ? 1 : 0);
                int nv = v - decay;
                heat[dstOff + dx] = (byte) (nv < 0 ? 0 : nv);
            }
        }
        for (int y = 0; y < GH; y++) {
            for (int x = 0; x < GW; x++) {
                int i = y * GW + x;
                int l = x > 0 ? heat[i - 1] : heat[i], r2 = x < GW - 1 ? heat[i + 1] : heat[i];
                int u2 = y > 0 ? heat[i - GW] : heat[i], d = y < GH - 1 ? heat[i + GW] : heat[i];
                px[i] = palette[(4 * heat[i] + l + r2 + u2 + d) >> 3];
            }
        }
        fireBmp.setPixels(px, 0, GW, 0, 0, GW, GH);

        stepEmbers(dt);
    }

    private void spawnEmber(int i) {
        float ow = open.width(), oh = open.height();
        ex[i] = open.left + ow * (0.15f + rnd.nextFloat() * 0.70f);
        ey[i] = open.bottom - oh * (0.18f + rnd.nextFloat() * 0.2f);
        float u = getHeight() / 1080f;
        evx[i] = (rnd.nextFloat() - 0.5f) * 50f * u;
        evy[i] = -(70f + rnd.nextFloat() * 150f) * u;
        emax[i] = elife[i] = 1.2f + rnd.nextFloat() * 2.2f;
        ephase[i] = rnd.nextFloat() * 6.28f;
    }

    private void stepEmbers(float dt) {
        if (open.isEmpty()) return;
        float u = getHeight() / 1080f;
        for (int i = 0; i < NE; i++) {
            if (elife[i] <= 0) {
                if (rnd.nextInt(26) == 0) spawnEmber(i);
                continue;
            }
            elife[i] -= dt;
            ephase[i] += dt * 5f;
            ex[i] += (evx[i] + (float) Math.sin(ephase[i]) * 26f * u) * dt;
            ey[i] += evy[i] * dt;
            if (ey[i] < open.top + open.height() * 0.08f) elife[i] = 0;
        }
    }

    // ------------------------------------------------------------------ static layers

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        if (w <= 0 || hh <= 0) return;
        full.set(0, 0, w, hh);
        // the fire fills the whole screen (edge to edge); only the hearth strip at the bottom is room
        open.set(0, 0, w, hh * 0.90f);
        clip.reset();
        clip.addRect(open, Path.Direction.CW);
        float lw = w * 0.46f, lh = hh * 0.36f;
        lg.set((w - lw) / 2f, open.bottom - lh, (w + lw) / 2f, open.bottom);

        float cx = w / 2f, cy = open.bottom - open.height() * 0.15f;
        glowPaint.setShader(new RadialGradient(cx, cy, w * 0.80f,
                new int[]{0x46FF7A1E, 0x1CD04A0A, 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        glow2Paint.setShader(new RadialGradient(cx, cy, w * 0.30f,
                new int[]{0x30FFB040, 0x00FF6010}, null, Shader.TileMode.CLAMP));

        if (bg != null) bg.recycle();
        if (logs != null) logs.recycle();
        bg = Bitmap.createBitmap(w / 2, hh / 2, Bitmap.Config.ARGB_8888);
        logs = Bitmap.createBitmap(w / 2, hh / 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bg);
        c.scale(0.5f, 0.5f);
        paintBackground(c, w, hh);
        Canvas l = new Canvas(logs);
        l.scale(0.5f, 0.5f);
        paintFrontLogs(l);
    }

    private void paintBackground(Canvas c, int w, int h) {
        Random r = new Random(7);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF1B1210);

        // brick wall
        float bw = w / 15f, bh = h / 24f, mort = Math.max(2f, h / 360f);
        p.setStyle(Paint.Style.FILL);
        int row = 0;
        for (float y = 0; y < h; y += bh, row++) {
            float off = (row % 2 == 0) ? 0 : -bw / 2f;
            for (float x = off; x < w; x += bw) {
                int base = 44 + r.nextInt(22);
                int rr = base, gg = (int) (base * 0.52f), bb = (int) (base * 0.44f);
                p.setColor(0xFF000000 | (rr << 16) | (gg << 8) | bb);
                c.drawRect(x + mort, y + mort, x + bw - mort, y + bh - mort, p);
            }
        }
        // soft vignette over the wall
        p.setShader(new RadialGradient(w / 2f, h * 0.45f, w * 0.75f,
                new int[]{0x00000000, 0xD8000000}, new float[]{0.25f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // floor
        float floorY = open.bottom + h * 0.035f;
        p.setShader(new LinearGradient(0, floorY, 0, h, 0xFF2A1A10, 0xFF080504, Shader.TileMode.CLAMP));
        c.drawRect(0, floorY, w, h, p);
        p.setShader(null);

        // stone surround (never inside the opening)
        float m = h * 0.045f;
        c.save();
        c.clipPath(clip, android.graphics.Region.Op.DIFFERENCE);
        RectF fr = new RectF(open.left - m, open.top - m, open.right + m, open.bottom + h * 0.02f);
        p.setColor(0xFF3E3A36);
        c.drawRoundRect(fr, h * 0.05f, h * 0.05f, p);
        float sbh = h / 22f;
        for (float y = fr.top + 4; y < fr.bottom; y += sbh) {
            for (float x = fr.left; x < fr.right; x += sbh * 2.2f) {
                int g = 62 + r.nextInt(34);
                p.setColor(0xFF000000 | (g << 16) | ((g - 4) << 8) | (g - 10));
                c.drawRect(x + 3, y + 2, Math.min(x + sbh * 2.2f - 3, fr.right), y + sbh - 2, p);
            }
        }
        c.restore();
        // darken stone toward the opening (ambient occlusion)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(m * 0.6f);
        p.setColor(0x66000000);
        c.drawRoundRect(open.left - m * 0.3f, open.top - m * 0.3f, open.right + m * 0.3f,
                open.bottom + 2, h * 0.07f, h * 0.07f, p);
        p.setStyle(Paint.Style.FILL);

        // firebox interior
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setStyle(Paint.Style.FILL);
        ip.setShader(new LinearGradient(0, open.top, 0, open.bottom, 0xFF020101, 0xFF160B07,
                Shader.TileMode.CLAMP));
        c.drawPath(clip, ip);
        // faint brick back wall inside
        c.save();
        c.clipPath(clip);
        for (float y = open.top; y < open.bottom; y += bh * 0.8f) {
            p.setColor(0x14FFFFFF);
            c.drawRect(open.left, y, open.right, y + 1.5f, p);
        }
        c.restore();

        // mantel
        float mantelH = h * 0.05f;
        RectF mt = new RectF(fr.left - w * 0.07f, fr.top - mantelH - h * 0.02f,
                fr.right + w * 0.07f, fr.top - h * 0.02f);
        p.setShader(new LinearGradient(0, mt.top, 0, mt.bottom, 0xFFA06A3C, 0xFF50301A,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(mt, 8, 8, p);
        p.setShader(null);
        p.setColor(0x55FFC890);
        c.drawRect(mt.left + 6, mt.top + 2, mt.right - 6, mt.top + 5, p);
        p.setColor(0x80000000);
        c.drawRect(mt.left, mt.bottom, mt.right, mt.bottom + h * 0.025f, p);

        // hearth slab
        RectF hs = new RectF(fr.left - w * 0.03f, open.bottom, fr.right + w * 0.03f,
                open.bottom + h * 0.035f);
        p.setShader(new LinearGradient(0, hs.top, 0, hs.bottom, 0xFF8A837B, 0xFF4C4640,
                Shader.TileMode.CLAMP));
        c.drawRect(hs, p);
        p.setShader(null);

        // two logs at the back (the fire covers most of them)
        float lx = lg.centerX(), ly = lg.bottom - lg.height() * 0.10f;
        drawLog(c, lx - lg.width() * 0.10f, ly - lg.height() * 0.07f, lg.width() * 0.56f,
                lg.height() * 0.11f, -14, r);
        drawLog(c, lx + lg.width() * 0.12f, ly - lg.height() * 0.05f, lg.width() * 0.50f,
                lg.height() * 0.10f, 11, r);
    }

    private void paintFrontLogs(Canvas c) {
        Random r = new Random(21);
        float lx = lg.centerX(), by = lg.bottom;
        drawLog(c, lx - lg.width() * 0.04f, by - lg.height() * 0.045f, lg.width() * 0.74f,
                lg.height() * 0.12f, 2, r);
        drawLog(c, lx + lg.width() * 0.20f, by - lg.height() * 0.025f, lg.width() * 0.40f,
                lg.height() * 0.095f, -7, r);
        // glowing coals between the logs
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(lx, by - lg.height() * 0.03f, lg.width() * 0.32f,
                new int[]{0x99FF6A10, 0x00FF3A00}, null, Shader.TileMode.CLAMP));
        c.drawRect(lg.left, by - lg.height() * 0.2f, lg.right, by, p);
    }

    private void drawLog(Canvas c, float cx, float cy, float len, float th, float deg, Random r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.translate(cx, cy);
        c.rotate(deg);
        RectF body = new RectF(-len / 2f, -th / 2f, len / 2f, th / 2f);
        p.setShader(new LinearGradient(0, -th / 2f, 0, th / 2f,
                new int[]{0xFF6A4226, 0xFF4A2C18, 0xFF1E1109}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRoundRect(body, th * 0.45f, th * 0.45f, p);
        p.setShader(null);
        // bark streaks
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, th * 0.03f));
        for (int i = 0; i < 14; i++) {
            float x0 = -len / 2f + th * 0.4f + r.nextFloat() * (len - th * 0.8f);
            float y0 = -th * 0.4f + r.nextFloat() * th * 0.8f;
            p.setColor(0x55000000);
            c.drawLine(x0, y0, x0 + th * (0.5f + r.nextFloat()), y0 + (r.nextFloat() - 0.5f) * th * 0.12f, p);
        }
        // cut end with growth rings
        p.setStyle(Paint.Style.FILL);
        RectF end = new RectF(len / 2f - th * 0.7f, -th / 2f, len / 2f, th / 2f);
        p.setColor(0xFFB98A55);
        c.drawOval(end, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, th * 0.025f));
        p.setColor(0x669A6A38);
        for (int i = 1; i <= 3; i++) {
            float f = i * 0.22f;
            c.drawOval(new RectF(end.centerX() - end.width() * (0.5f - f * 0.5f),
                    -th * (0.5f - f * 0.5f), end.centerX() + end.width() * (0.5f - f * 0.5f),
                    th * (0.5f - f * 0.5f)), p);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (bg == null) return;
        c.drawBitmap(bg, null, full, bmpPaint);

        c.save();
        c.clipPath(clip);
        c.drawBitmap(fireBmp, null, open, bmpPaint);
        c.restore();

        c.drawBitmap(logs, null, full, bmpPaint);

        // room light, pulsing with the flames
        glowPaint.setAlpha(Math.min(255, (int) (255 * (0.55f + 0.45f * flick))));
        c.drawRect(0, 0, getWidth(), getHeight(), glowPaint);
        glow2Paint.setAlpha(Math.min(255, (int) (255 * (0.5f + 0.5f * flick))));
        c.drawRect(0, 0, getWidth(), getHeight(), glow2Paint);

        // embers
        float u = getHeight() / 1080f;
        for (int i = 0; i < NE; i++) {
            if (elife[i] <= 0) continue;
            float f = Math.max(0f, elife[i] / emax[i]);
            int a = (int) (255 * Math.min(1f, f * 1.8f));
            emberPaint.setColor((a / 4 << 24) | 0xFF8010);
            c.drawCircle(ex[i], ey[i], 7f * u, emberPaint);
            emberPaint.setColor((a << 24) | 0xFFC050);
            c.drawCircle(ex[i], ey[i], 2.6f * u, emberPaint);
        }
    }
}
