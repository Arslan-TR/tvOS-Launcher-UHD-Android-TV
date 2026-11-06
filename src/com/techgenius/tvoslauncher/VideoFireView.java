package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;

/** Looping, muted full-screen video (center-crop so it always fills the screen). */
public class VideoFireView extends TextureView implements TextureView.SurfaceTextureListener {
    private final File file;
    private MediaPlayer mp;
    private boolean wantPlay;
    private int vw, vh;

    public static File videoFile(Context c) {
        File d = c.getExternalFilesDir(null);
        return d == null ? null : new File(d, "somine.mp4");
    }

    public VideoFireView(Context c, File f) {
        super(c);
        file = f;
        setSurfaceTextureListener(this);
    }

    public void start() { wantPlay = true; if (mp != null) mp.start(); }
    public void stop() { wantPlay = false; if (mp != null) try { mp.pause(); } catch (Exception ignored) {} }
    public void release() {
        wantPlay = false;
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        try {
            mp = new MediaPlayer();
            mp.setDataSource(file.getAbsolutePath());
            mp.setSurface(new Surface(st));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((m, x, y) -> { vw = x; vh = y; fit(); });
            mp.setOnPreparedListener(m -> { vw = m.getVideoWidth(); vh = m.getVideoHeight(); fit(); if (wantPlay) m.start(); });
            mp.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    /** Center-crop: scale the video so it covers the whole view. */
    private void fit() {
        int w = getWidth(), h = getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float s = Math.max(w / (float) vw, h / (float) vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f);
        setTransform(m);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
