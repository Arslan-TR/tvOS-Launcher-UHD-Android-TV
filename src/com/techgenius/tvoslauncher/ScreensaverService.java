package com.techgenius.tvoslauncher;

import android.content.Context;
import android.service.dreams.DreamService;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;

/**
 * Screen saver. Default: animated fireplace (FireplaceView).
 * Set the "screensaver_style" preference (file "screensaver") to "scenes" for the old behaviour:
 * the home wallpaper shuffle with the big clock, without dock and widgets.
 */
public class ScreensaverService extends DreamService {
    private Scenes.SceneView scene;
    private FireplaceView fire;
    private VideoFireView video;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        String style = getSharedPreferences("screensaver", Context.MODE_PRIVATE)
                .getString("screensaver_style", "fireplace");
        if ("scenes".equals(style)) {
            scene = new Scenes.SceneView(this);
            scene.setLockScreenMode(false);
            scene.syncSettings();
            scene.setZOrderOnTop(true);   // the dream window is opaque black, so the surface must sit above it
            setContentView(scene, lp);
        } else if (VideoFireView.videoFile(this) != null && VideoFireView.videoFile(this).exists()) {
            video = new VideoFireView(this, VideoFireView.videoFile(this));
            setContentView(withClock(video), lp);
        } else {
            fire = new FireplaceView(this);
            setContentView(withClock(fire), lp);
        }
    }

    /** Puts a large clock + date over the given background view. */
    private View withClock(View bg) {
        FrameLayout root = new FrameLayout(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(0xF2FFFFFF);
        time.setTextSize(88);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setShadowLayer(14 * d, 0, 2 * d, 0xCC000000);
        time.setGravity(Gravity.CENTER);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("d MMMM EEEE");
        date.setFormat12Hour("d MMMM EEEE");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(22);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setShadowLayer(10 * d, 0, 1 * d, 0xCC000000);
        date.setGravity(Gravity.CENTER);
        box.addView(time);
        box.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (48 * d);
        root.addView(box, lp);
        return root;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
        if (fire != null) fire.start();
        if (video != null) video.start();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        if (fire != null) fire.stop();
        if (video != null) video.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) {
            scene.releaseCache();
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
        super.onDetachedFromWindow();
    }
}
