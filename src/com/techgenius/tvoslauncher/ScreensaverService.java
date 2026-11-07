package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Screen saver: looping video(s) if any are on the TV, otherwise the drawn fireplace, with a clock on top.
 * Settings live in the "screensaver" preferences and are changed from the phone remote.
 * Style "scenes" is the old behaviour: the home wallpaper shuffle with the big clock.
 */
public class ScreensaverService extends DreamService {
    static final String PREFS = "screensaver";
    static final String KEY_STYLE = "screensaver_style";
    static final String STYLE_AUTO = "fireplace";   // stored name kept: video if there is one, else fireplace
    static final String STYLE_FIRE = "fire";
    static final String STYLE_SCENES = "scenes";
    static final String KEY_CLOCK = "show_clock";
    static final String KEY_DATE = "show_date";
    static final String KEY_NIGHT = "night_dim";

    private static final long DRIFT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private Scenes.SceneView scene;
    private FireplaceView fire;
    private VideoFireView video;
    private FrameLayout root;
    private View clockBox, nightVeil;
    private boolean dreaming;

    /** Moves the clock a little every minute so no pixels stay lit in one place, and follows the night dim. */
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            float r = 14 * getResources().getDisplayMetrics().density;
            if (clockBox != null) {
                clockBox.animate().translationX((rnd.nextFloat() * 2 - 1) * r).translationY((rnd.nextFloat() * 2 - 1) * r)
                        .setDuration(4000L).start();
            }
            applyNightDim();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        SharedPreferences p = prefs(this);
        String style = p.getString(KEY_STYLE, STYLE_AUTO);
        if (STYLE_SCENES.equals(style)) {
            scene = new Scenes.SceneView(this);
            scene.setLockScreenMode(false);
            scene.syncSettings();
            scene.setZOrderOnTop(true);   // the dream window is opaque black, so the surface must sit above it
            setContentView(scene, lp);
            return;
        }
        root = new FrameLayout(this);
        List<File> videos = STYLE_FIRE.equals(style) ? java.util.Collections.<File>emptyList() : VideoFireView.videoFiles(this);
        if (videos.isEmpty()) {
            fire = new FireplaceView(this);
            root.addView(fire, new FrameLayout.LayoutParams(-1, -1));
        } else {
            video = new VideoFireView(this, videos, this::fallBackToFire);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        }
        nightVeil = new View(this);
        nightVeil.setBackgroundColor(0xFF000000);
        nightVeil.setAlpha(0f);
        root.addView(nightVeil, new FrameLayout.LayoutParams(-1, -1));
        addClock(p);
        setContentView(root, lp);
    }

    /** No video could be played (for example a 4K file this TV cannot decode). */
    private void fallBackToFire() {
        if (root == null || video == null) return;
        video.release();
        root.removeView(video);
        video = null;
        fire = new FireplaceView(this);
        root.addView(fire, 0, new FrameLayout.LayoutParams(-1, -1));
        if (dreaming) fire.start();
    }

    /** The clock is the home screen's own (same font, colour, size and position), including the phone remote's choices. */
    private void addClock(SharedPreferences p) {
        boolean showTime = p.getBoolean(KEY_CLOCK, true), showDate = p.getBoolean(KEY_DATE, true);
        if (!showTime && !showDate) return;
        HomeClockView v = new HomeClockView(this, showTime, showDate);
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        clockBox = v;
    }

    /** Full-screen transparent view that redraws only when the shown text changes. */
    private static final class HomeClockView extends View {
        private final Scenes.SceneView painter;
        private final boolean showTime, showDate;
        private String drawn;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!painter.clockSignature().equals(drawn)) invalidate();
                postDelayed(this, 5000L);
            }
        };

        HomeClockView(Context c, boolean showTime, boolean showDate) {
            super(c);
            this.showTime = showTime;
            this.showDate = showDate;
            painter = Scenes.SceneView.clockPainter(c);
            Weather.Data d = Weather.enabled(c) ? Weather.cached(c) : null;
            if (d != null) painter.setWeather(d.temp + "\u00b0", Weather.describe(c, d.code, d.day), d.code);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            painter.destroy();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            drawn = painter.clockSignature();
            painter.drawClockOverlay(c, getWidth(), getHeight(), showTime, showDate);
        }
    }

    /** Between midnight and 07:00 the picture is darkened, so the room stays dark and the panel is spared. */
    private void applyNightDim() {
        if (nightVeil == null) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean night = h < 7 && prefs(this).getBoolean(KEY_NIGHT, true);
        nightVeil.setAlpha(night ? 0.55f : 0f);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (scene != null) scene.start();
        if (fire != null) fire.start();
        if (video != null) video.start();
        handler.removeCallbacks(drift);
        if (root != null) handler.post(drift);
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        handler.removeCallbacks(drift);
        if (scene != null) scene.stop();
        if (fire != null) fire.stop();
        if (video != null) video.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(drift);
        if (clockBox != null) clockBox.animate().cancel();
        if (scene != null) {
            scene.destroy();
            scene = null;
        }
        if (fire != null) {
            fire.release();
            fire = null;
        }
        if (video != null) {
            video.release();
            video = null;
        }
        root = null;
        clockBox = null;
        nightVeil = null;
        super.onDetachedFromWindow();
    }
}
