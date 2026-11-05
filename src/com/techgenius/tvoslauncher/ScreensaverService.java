package com.techgenius.tvoslauncher;

import android.service.dreams.DreamService;
import android.view.View;

/** Screen saver: the home scene (wallpaper shuffle with the big clock), without the dock and widgets. */
public class ScreensaverService extends DreamService {
    private Scenes.SceneView scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        scene = new Scenes.SceneView(this);
        scene.setLockScreenMode(false);
        scene.syncSettings();
        scene.setZOrderOnTop(true);   // the dream window is opaque black, so the surface must sit above it
        setContentView(scene, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) {
            scene.releaseCache();
            scene = null;
        }
        super.onDetachedFromWindow();
    }
}
