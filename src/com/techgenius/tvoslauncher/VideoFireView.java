package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Muted full-screen video for the screensaver (center-crop, so it always fills the screen). One file
 * loops; several play one after another in random order. A file the TV cannot decode is skipped, and
 * when none plays the owner is told so it can fall back to the drawn fireplace.
 */
public class VideoFireView extends TextureView implements TextureView.SurfaceTextureListener {
    interface OnFailed {
        void onAllVideosFailed();
    }

    private final List<File> files;
    private final OnFailed onFailed;
    private Surface surface;
    private MediaPlayer mp;
    private boolean prepared, wantPlay;
    private int vw, vh, index, failures;

    /** Every video in the app's external files folder, where the phone upload and adb push put them. */
    public static List<File> videoFiles(Context c) {
        List<File> out = new ArrayList<>();
        File dir = c.getExternalFilesDir(null);
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.length() > 0 && isVideoName(f.getName())) out.add(f);
            }
        }
        return out;
    }

    static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov");
    }

    public VideoFireView(Context c, List<File> videos, OnFailed failed) {
        super(c);
        files = new ArrayList<>(videos);
        Collections.shuffle(files);
        onFailed = failed;
        setSurfaceTextureListener(this);
    }

    public void start() {
        wantPlay = true;
        if (mp != null && prepared) mp.start();
    }

    public void stop() {
        wantPlay = false;
        if (mp == null || !prepared) return;
        try {
            mp.pause();
        } catch (Exception ignored) {
        }
    }

    public void release() {
        wantPlay = false;
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (mp == null) return;
        try {
            mp.release();
        } catch (Exception ignored) {
        }
        mp = null;
    }

    private void playCurrent() {
        releasePlayer();
        if (surface == null || files.isEmpty()) return;
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setDataSource(files.get(index).getAbsolutePath());
            p.setSurface(surface);
            p.setLooping(files.size() == 1);
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener((m, x, y) -> {
                vw = x;
                vh = y;
                fit();
            });
            p.setOnPreparedListener(m -> {
                prepared = true;
                failures = 0;
                vw = m.getVideoWidth();
                vh = m.getVideoHeight();
                fit();
                if (wantPlay) m.start();
            });
            p.setOnCompletionListener(m -> next());
            p.setOnErrorListener((m, what, extra) -> {
                failed();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            failed();
        }
    }

    private void next() {
        index = (index + 1) % files.size();
        playCurrent();
    }

    private void failed() {
        if (++failures >= files.size()) {
            releasePlayer();
            if (onFailed != null) post(onFailed::onAllVideosFailed);
        } else {
            post(this::next);
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

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        playCurrent();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        fit();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
