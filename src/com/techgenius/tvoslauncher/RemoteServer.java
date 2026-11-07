package com.techgenius.tvoslauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone remote: a tiny HTTP server on the local network. The TV shows a QR code carrying a random
 * token; the phone opens the page in its browser (nothing to install) and sends keys, volume,
 * media keys and app launches. LAN only; every API call must carry the token.
 */
final class RemoteServer {
    static final int PORT = 8765;
    private static RemoteServer instance;

    private final Context app;
    private volatile String token;
    private final ThreadPoolExecutor pool = idlePool(4);
    private volatile ServerSocket socket;
    private volatile boolean adbWorks = true;

    /** Threads exist only while there is work: a remote nobody is using costs no memory for them. */
    private static ThreadPoolExecutor idlePool(int size) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(size, size, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64));
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** Called when memory is needed elsewhere. */
    static void trim() {
        RemoteServer s = instance;
        if (s != null) s.iconCache.evictAll();
    }

    private RemoteServer(Context c) {
        this.app = c.getApplicationContext();
        // Kept across restarts and updates so a QR the phone already scanned stays valid.
        android.content.SharedPreferences sp = app.getSharedPreferences("remote_server", Context.MODE_PRIVATE);
        String t = sp.getString("token", null);
        this.token = t == null || t.length() != 12 ? newToken() : t;
    }

    private String newToken() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        String t = sb.toString();
        app.getSharedPreferences("remote_server", Context.MODE_PRIVATE).edit().putString("token", t).apply();
        return t;
    }

    private boolean tokenOk(String given) {
        return given != null && java.security.MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    static synchronized RemoteServer get(Context c) {
        if (instance == null) instance = new RemoteServer(c);
        instance.start();
        return instance;
    }

    /** URLs for the QR code, with the token. One per local IPv4 address. */
    List<String> urls() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add("http://" + a.getHostAddress() + ":" + PORT + "/?k=" + token);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    private synchronized void start() {
        if (socket != null && !socket.isClosed()) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(PORT));
            socket = s;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        Socket c = s.accept();
                        try {
                            pool.execute(() -> handle(c));
                        } catch (RejectedExecutionException busy) {
                            c.close();
                        }
                    } catch (Exception e) {
                        if (s.isClosed()) return;
                    }
                }
            }, "remote-server");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            socket = null;
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(4000);
            InputStream in = new java.io.BufferedInputStream(sock.getInputStream(), 8192);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int last4 = 0;
            while (head.size() < 8192) {
                int b = in.read();
                if (b < 0) break;
                head.write(b);
                last4 = (last4 << 8) | b;
                if (last4 == 0x0D0A0D0A) break;
            }
            String req = head.toString("UTF-8");
            String line = req.split("\r\n", 2)[0];
            String[] p = line.split(" ");
            OutputStream out = sock.getOutputStream();
            boolean post = p.length >= 2 && "POST".equals(p[0]);
            if (p.length < 2 || !(post || "GET".equals(p[0]))) {
                write(out, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = p[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            Map<String, String> qs = parse(query);
            boolean authed = tokenOk(qs.get("k"));
            if (post) {
                if (!authed) {
                    write(out, 403, "application/json", "{\"ok\":false,\"err\":\"token\"}".getBytes(StandardCharsets.UTF_8));
                } else if (path.equals("/api/upload") && "wall".equals(qs.get("dest"))) {
                    json(out, uploadWallpaper(in, req, qs.get("name"), sock));
                } else if (path.equals("/api/upload")) {
                    json(out, upload(sock, in, req, qs.get("name")));
                } else {
                    write(out, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                // The page itself is harmless; the API below needs the token.
                write(out, 200, "text/html; charset=utf-8", asset("remote.html"));
            } else if (path.equals("/manifest.webmanifest")) {
                // Lets the phone add the remote to its home screen with its own name and icon. No token in it:
                // the page keeps the token in the phone's browser storage.
                write(out, 200, "application/manifest+json", WEB_MANIFEST.getBytes(StandardCharsets.UTF_8), "max-age=86400");
            } else if (path.equals("/icon-192.png") || path.equals("/icon-512.png")) {
                write(out, 200, "image/png", remoteIcon(path.contains("512") ? 512 : 192), "max-age=86400");
            } else if (!authed) {
                write(out, 403, "application/json", "{\"ok\":false,\"err\":\"token\"}".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/ping")) {
                json(out, "{\"ok\":true,\"adb\":" + adbWorks + ",\"ver\":" + appVersion() + ",\"name\":\"" + esc(deviceName()) + "\"}");
            } else if (path.equals("/api/key")) {
                json(out, key(qs.get("c")));
            } else if (path.equals("/api/apps")) {
                json(out, apps());
            } else if (path.equals("/api/icon")) {
                byte[] png = icon(qs.get("p"));
                if (png == null) write(out, 404, "text/plain", "no icon".getBytes(StandardCharsets.UTF_8));
                else write(out, 200, "image/png", png, "public, max-age=86400");
            } else if (path.equals("/api/widgets")) {
                json(out, widgets());
            } else if (path.equals("/api/widget")) {
                json(out, setWidget(qs));
            } else if (path.equals("/api/browser")) {
                json(out, browserState());
            } else if (path.equals("/api/browse")) {
                json(out, browse(qs.get("u")));
            } else if (path.equals("/api/type")) {
                json(out, typeInto(qs));
            } else if (path.equals("/api/bmadd")) {
                json(out, bmAdd(qs.get("u"), qs.get("n")));
            } else if (path.equals("/api/bmdel")) {
                json(out, listDel("bm", qs.get("u")));
            } else if (path.equals("/api/histdel")) {
                json(out, listDel("hist", qs.get("u")));
            } else if (path.equals("/api/histclear")) {
                prefsB().edit().putString("hist", "[]").apply();
                json(out, browserState());
            } else if (path.equals("/api/mouse")) {
                json(out, mouse(qs));
            } else if (path.equals("/api/clock")) {
                json(out, clockState());
            } else if (path.equals("/api/clockset")) {
                json(out, setClock(qs));
            } else if (path.equals("/api/widgetreset")) {
                Widgets.reset(app);
                applyWidgets();
                json(out, widgets());
            } else if (path.equals("/api/sys")) {
                json(out, system());
            } else if (path.equals("/api/clean")) {
                json(out, cleanMemory());
            } else if (path.equals("/api/sleep")) {
                json(out, sleep(qs));
            } else if (path.equals("/api/lowmem")) {
                json(out, setLowMemory("1".equals(qs.get("on"))));
            } else if (path.equals("/api/rotate")) {
                token = newToken();
                json(out, "{\"ok\":true}");
            } else if (path.equals("/api/saver")) {
                json(out, saverState());
            } else if (path.equals("/api/saverset")) {
                json(out, setSaver(qs));
            } else if (path.equals("/api/videodel")) {
                json(out, deleteVideo(qs.get("name")));
            } else if (path.equals("/api/launch")) {
                json(out, launch(qs.get("p")));
            } else {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ system panel
    private String system() {
        android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        final long mb = 1024 * 1024;
        return "{\"ok\":true,\"ramTotal\":" + mi.totalMem / mb + ",\"ramFree\":" + mi.availMem / mb
                + ",\"self\":" + android.os.Debug.getPss() / 1024
                + ",\"diskTotal\":" + fs.getTotalBytes() / mb + ",\"diskFree\":" + fs.getAvailableBytes() / mb
                + ",\"upMin\":" + android.os.SystemClock.elapsedRealtime() / 60000
                + ",\"sleep\":" + SleepReceiver.timerLeft(app) + ",\"low\":" + Scenes.isLowMemoryMode(app) + "}";
    }

    /** Asks Android to end background processes; whatever is on screen is left alone. */
    private String cleanMemory() {
        android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long before = mi.availMem;
        PackageManager pm = app.getPackageManager();
        Set<String> pkgs = new LinkedHashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                pkgs.add(ri.activityInfo.packageName);
            }
        }
        pkgs.remove(app.getPackageName());
        for (String p : pkgs) {
            try {
                am.killBackgroundProcesses(p);
            } catch (Exception ignored) {
            }
        }
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
        }
        am.getMemoryInfo(mi);
        return "{\"ok\":true,\"freed\":" + Math.max(0, mi.availMem - before) / (1024 * 1024) + "}";
    }

    private String sleep(Map<String, String> qs) {
        if ("1".equals(qs.get("now"))) {
            uiH.post(() -> SleepReceiver.sleepNow(app));
            return "{\"ok\":true}";
        }
        try {
            int m = Integer.parseInt(qs.get("m"));
            if (m < 0 || m > 720) return "{\"ok\":false,\"err\":\"geçersiz süre\"}";
            SleepReceiver.setTimer(app, m);
            return system();
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"err\":\"geçersiz süre\"}";
        }
    }

    private String setLowMemory(boolean on) {
        Scenes.setLowMemoryMode(app, on);
        final MainActivity a = MainActivity.activeInstance;
        if (a != null) a.runOnUiThread(a::recreate);
        return system();
    }

    // ------------------------------------------------------------------ screensaver panel and video upload
    private String saverState() {
        android.content.SharedPreferences p = ScreensaverService.prefs(app);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"style\":\"")
                .append(p.getString(ScreensaverService.KEY_STYLE, ScreensaverService.STYLE_AUTO))
                .append("\",\"clock\":").append(p.getBoolean(ScreensaverService.KEY_CLOCK, true))
                .append(",\"date\":").append(p.getBoolean(ScreensaverService.KEY_DATE, true))
                .append(",\"night\":").append(p.getBoolean(ScreensaverService.KEY_NIGHT, true))
                .append(",\"videos\":[");
        List<java.io.File> videos = VideoFireView.videoFiles(app);
        for (int i = 0; i < videos.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"n\":\"").append(esc(videos.get(i).getName())).append("\",\"mb\":")
                    .append(videos.get(i).length() / (1024 * 1024)).append('}');
        }
        return sb.append("]}").toString();
    }

    private String setSaver(Map<String, String> qs) {
        android.content.SharedPreferences.Editor e = ScreensaverService.prefs(app).edit();
        String st = qs.get("style");
        if (ScreensaverService.STYLE_AUTO.equals(st) || ScreensaverService.STYLE_FIRE.equals(st)
                || ScreensaverService.STYLE_SCENES.equals(st)) {
            e.putString(ScreensaverService.KEY_STYLE, st);
        }
        for (String k : new String[]{ScreensaverService.KEY_CLOCK, ScreensaverService.KEY_DATE, ScreensaverService.KEY_NIGHT}) {
            if (qs.get(k) != null) e.putBoolean(k, "1".equals(qs.get(k)));
        }
        e.apply();
        return saverState();
    }

    /** A plain file name with a video extension, or null. Nothing that could leave the videos folder. */
    private static String safeVideoName(String raw) {
        if (raw == null) return null;
        String n = new java.io.File(raw).getName().replaceAll("[^\\p{L}\\p{N}._ -]", "_").replaceFirst("^[. ]+", "");
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return VideoFireView.isVideoName(n) && n.indexOf('.') > 0 ? n : null;
    }

    private String deleteVideo(String raw) {
        String n = safeVideoName(raw);
        java.io.File dir = app.getExternalFilesDir(null);
        if (n == null || dir == null || !new java.io.File(dir, n).delete()) {
            return "{\"ok\":false,\"err\":\"silinemedi\"}";
        }
        return saverState();
    }

    private final AtomicBoolean uploading = new AtomicBoolean();

    /** Saves a video sent from the phone or a computer browser, straight to disk (never held in memory). */
    private String upload(Socket sock, InputStream in, String head, String rawName) {
        java.io.File dir = app.getExternalFilesDir(null);
        String name = safeVideoName(rawName);
        if (dir == null) return "{\"ok\":false,\"err\":\"depolama yok\"}";
        if (name == null) return "{\"ok\":false,\"err\":\"mp4, mkv, webm, mov veya m4v olmalı\"}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\r\\ncontent-length:\\s*(\\d+)").matcher(head);
        long len = m.find() ? Long.parseLong(m.group(1)) : -1;
        if (len <= 0) return "{\"ok\":false,\"err\":\"boyut bilinmiyor\"}";
        if (dir.getUsableSpace() < len + 200L * 1024 * 1024) return "{\"ok\":false,\"err\":\"TV'de yeterli yer yok\"}";
        if (!uploading.compareAndSet(false, true)) return "{\"ok\":false,\"err\":\"başka bir yükleme sürüyor\"}";
        java.io.File part = new java.io.File(dir, name + ".part");
        try {
            sock.setSoTimeout(30000);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new java.io.EOFException();
                    os.write(buf, 0, r);
                    left -= r;
                }
            }
            java.io.File dst = new java.io.File(dir, name);
            if (dst.exists() && !dst.delete()) throw new java.io.IOException("in use");
            if (!part.renameTo(dst)) throw new java.io.IOException("rename");
            return saverState();
        } catch (Exception e) {
            part.delete();
            return "{\"ok\":false,\"err\":\"yükleme yarıda kaldı\"}";
        } finally {
            uploading.set(false);
        }
    }

    /** Folder under Pictures where wallpapers sent from the phone land. */
    static final String WALL_DIR = "Kumandadan";

    /** A plain picture file name (jpg, png, webp), or null. */
    private static String safeImageName(String raw) {
        if (raw == null) return null;
        String n = new java.io.File(raw).getName().replaceAll("[^\\p{L}\\p{N}._ -]", "_").replaceFirst("^[. ]+", "");
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return n.matches("(?i).+\\.(jpe?g|png|webp)") ? n : null;
    }

    /**
     * Saves a picture sent from the phone into Pictures/Kumandadan through MediaStore (no storage permission
     * needed on Android 10+), streaming it to disk.
     */
    private String uploadWallpaper(InputStream in, String head, String rawName, Socket sock) {
        String name = safeImageName(rawName);
        if (name == null) return "{\"ok\":false,\"err\":\"jpg, png veya webp olmalı\"}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\r\\ncontent-length:\\s*(\\d+)").matcher(head);
        long len = m.find() ? Long.parseLong(m.group(1)) : -1;
        if (len <= 0) return "{\"ok\":false,\"err\":\"boyut bilinmiyor\"}";
        if (len > 80L * 1024 * 1024) return "{\"ok\":false,\"err\":\"resim 80 MB'tan büyük\"}";
        if (android.os.Build.VERSION.SDK_INT < 29) return "{\"ok\":false,\"err\":\"bu TV'de desteklenmiyor (Android 10+)\"}";
        if (!uploading.compareAndSet(false, true)) return "{\"ok\":false,\"err\":\"başka bir yükleme sürüyor\"}";
        android.content.ContentResolver cr = app.getContentResolver();
        android.net.Uri uri = null;
        try {
            sock.setSoTimeout(30000);
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/" + WALL_DIR);
            v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("insert");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new java.io.IOException("open");
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new java.io.EOFException();
                    os.write(buf, 0, r);
                    left -= r;
                }
            }
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return "{\"ok\":true,\"name\":\"" + esc(name) + "\",\"dir\":\"Pictures/" + WALL_DIR + "\"}";
        } catch (Exception e) {
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return "{\"ok\":false,\"err\":\"yükleme yarıda kaldı\"}";
        } finally {
            uploading.set(false);
        }
    }

    private static float finite(String s) {
        float v = Float.parseFloat(s);
        if (Float.isNaN(v) || Float.isInfinite(v)) throw new NumberFormatException(s);
        return v;
    }

    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("menu", KeyEvent.KEYCODE_MENU);
        KEYS.put("tvinput", KeyEvent.KEYCODE_TV_INPUT);
        KEYS.put("tv", KeyEvent.KEYCODE_TV);
        KEYS.put("pgup", KeyEvent.KEYCODE_PAGE_UP);
        KEYS.put("pgdn", KeyEvent.KEYCODE_PAGE_DOWN);
        KEYS.put("chup", KeyEvent.KEYCODE_CHANNEL_UP);
        KEYS.put("chdown", KeyEvent.KEYCODE_CHANNEL_DOWN);
        KEYS.put("subt", KeyEvent.KEYCODE_CAPTIONS);
        KEYS.put("audio", KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK);
        KEYS.put("info", KeyEvent.KEYCODE_INFO);
        KEYS.put("playpause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("ff", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        KEYS.put("rew", KeyEvent.KEYCODE_MEDIA_REWIND);
        KEYS.put("next", KeyEvent.KEYCODE_MEDIA_NEXT);
        KEYS.put("prev", KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private String key(String name) {
        if (name == null) return "{\"ok\":false,\"err\":\"komut yok\"}";
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        switch (name) {
            case "volup":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "{\"ok\":true}";
            case "voldown":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "{\"ok\":true}";
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return "{\"ok\":true}";
            case "back":
                return PowerService.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        ? "{\"ok\":true}" : adbKey(KeyEvent.KEYCODE_BACK);
            case "recents":
                return PowerService.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        ? "{\"ok\":true}" : "{\"ok\":false,\"err\":\"erişilebilirlik servisi kapalı\"}";
            case "home": {
                // This launcher, not whichever home app the firmware resolves HOME to.
                Intent h = MainActivity.homeIntent(app)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                app.startActivity(h);
                return "{\"ok\":true}";
            }
            default:
                break;
        }
        Integer code = KEYS.get(name);
        if (code == null) return "{\"ok\":false,\"err\":\"bilinmeyen komut\"}";
        if (name.equals("playpause") || name.equals("ff") || name.equals("rew")
                || name.equals("next") || name.equals("prev")) {
            boolean seek = name.equals("ff") || name.equals("rew");
            if (seek && !seekResumePending) seekWasPlaying = am.isMusicActive();
            long t = System.currentTimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            if (seek) {
                // YouTube pauses after a seek key; if it was playing, resume once the seeking is over.
                seekResumePending = true;
                uiH.removeCallbacks(seekResume);
                seekChecks = 0;
                uiH.postDelayed(seekResume, 700);
            }
            return "{\"ok\":true}";
        }
        return adbKey(code);
    }

    private volatile boolean seekWasPlaying, seekResumePending;
    private int seekChecks;
    private final Runnable seekResume = new Runnable() {
        @Override
        public void run() {
            // YouTube pauses a moment after the seek key, so look a few times instead of once.
            if (++seekChecks < 4) uiH.postDelayed(this, 800);
            else seekResumePending = false;
            try {
                android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (seekWasPlaying && !am.isMusicActive()) {
                    long t = System.currentTimeMillis();
                    am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                    am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                }
            } catch (Exception ignored) {
            }
        }
    };

    /** D-pad/OK/Menu/Input need the shell: an app cannot inject them itself. Uses the launcher's local ADB. */
    private String adbKey(int code) {
        try {
            LocalAdb.execShellOutput(app, "input keyevent " + code);
            adbWorks = true;
            return "{\"ok\":true}";
        } catch (Exception e) {
            adbWorks = false;
            return "{\"ok\":false,\"err\":\"Yön tuşları için TV'de ADB açık olmalı\"}";
        }
    }

    /** Current widget layout as JSON; bw/bh = a 1.0-scale widget as a fraction of screen width/height. */
    private String widgets() {
        android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
        float px = Widgets.BASE_DP * dm.density;
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"all\":").append(Widgets.enabled(app))
                .append(",\"bw\":").append(px / Math.max(1, dm.widthPixels))
                .append(",\"bh\":").append(px / Math.max(1, dm.heightPixels)).append(",\"list\":[");
        for (int i = 0; i < Widgets.IDS.length; i++) {
            String id = Widgets.IDS[i];
            Widgets.Cfg c = Widgets.get(app, id);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(Widgets.nameOf(id))
                    .append("\",\"v\":").append(c.visible).append(",\"x\":").append(c.x)
                    .append(",\"y\":").append(c.y).append(",\"s\":").append(c.scale).append('}');
        }
        return sb.append("]}").toString();
    }

    private String setWidget(Map<String, String> qs) {
        String id = qs.get("id");
        boolean known = false;
        for (String k : Widgets.IDS) known |= k.equals(id);
        if (!known) return "{\"ok\":false,\"err\":\"bilinmeyen widget\"}";
        Widgets.Cfg c = Widgets.get(app, id);
        try {
            if (qs.get("x") != null) c.x = finite(qs.get("x"));
            if (qs.get("y") != null) c.y = finite(qs.get("y"));
            if (qs.get("s") != null) c.scale = finite(qs.get("s"));
            if (qs.get("v") != null) c.visible = "1".equals(qs.get("v"));
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"err\":\"geçersiz değer\"}";
        }
        Widgets.set(app, id, c);
        applyWidgets();
        return widgets();
    }

    private void applyWidgets() {
        final MainActivity a = MainActivity.activeInstance;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.sceneHome != null) a.sceneHome.refreshWidgets();
        });
    }

    private String clockState() {
        Context c = app;
        return "{\"ok\":true,\"style\":\"" + Scenes.getClockStyle(c) + "\",\"color\":" + Scenes.getClockColor(c)
                + ",\"op\":" + Scenes.getClockOpacity(c) + ",\"solid\":" + Scenes.isClockSolid(c)
                + ",\"size\":" + Scenes.getClockSize(c)
                + ",\"weight\":" + Scenes.getClockWeight(c) + ",\"wdef\":" + Scenes.CLOCK_WEIGHT_DEFAULT + ",\"pos\":\"" + Scenes.getCustomClockPos(c)
                + "\",\"h24\":" + Scenes.isClock24h(c) + "}";
    }

    private String setClock(Map<String, String> qs) {
        try {
            String st = qs.get("style");
            if (st != null) {
                boolean ok = false;
                for (String k : Scenes.CLOCK_STYLES) ok |= k.equals(st);
                if (ok) Scenes.setClockStyle(app, st);
            }
            String pos = qs.get("pos");
            if (pos != null) {
                for (String k : Scenes.CLOCK_POSITIONS) if (k.equals(pos)) Scenes.setCustomClockPos(app, pos);
            }
            if (qs.get("h24") != null) Scenes.setClock24h(app, "1".equals(qs.get("h24")));
            if ("1".equals(qs.get("reset"))) Scenes.resetClockLook(app);
            Integer color = qs.get("color") == null ? null : Integer.valueOf((int) Long.parseLong(qs.get("color")));
            Float op = qs.get("op") == null ? null : Float.valueOf(finite(qs.get("op")));
            Boolean solid = qs.get("solid") == null ? null : Boolean.valueOf("1".equals(qs.get("solid")));
            Float size = qs.get("size") == null ? null : Float.valueOf(finite(qs.get("size")));
            Scenes.setClockLook(app, color, op, solid, size);
            if (qs.get("weight") != null) Scenes.setClockWeight(app, finite(qs.get("weight")));
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"err\":\"geçersiz değer\"}";
        }
        final MainActivity a = MainActivity.activeInstance;
        if (a != null) {
            a.runOnUiThread(() -> {
                if (a.sceneHome != null) a.sceneHome.sceneView.invalidate();
            });
        }
        return clockState();
    }

    // ------------------------------------------------------------------ BrowseHere helper
    private static final String BROWSER = "com.tcl.browser";

    private android.content.SharedPreferences prefsB() {
        return app.getSharedPreferences("remote_browser", Context.MODE_PRIVATE);
    }

    private org.json.JSONArray arr(String key) {
        try {
            return new org.json.JSONArray(prefsB().getString(key, "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private String browserState() {
        return "{\"ok\":true,\"hist\":" + arr("hist") + ",\"bm\":" + arr("bm")
                + ",\"installed\":" + isInstalled(BROWSER) + "}";
    }

    private boolean isInstalled(String pkg) {
        try {
            app.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Turns what was typed or pasted into a URL: keeps full links, adds https:// to bare domains, otherwise searches. */
    static String normalizeUrl(String in) {
        String u = in == null ? "" : in.trim();
        if (u.isEmpty()) return "";
        if (u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return u;
        if (!u.contains(" ") && (u.matches("(?i)^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$") || u.startsWith("localhost"))) {
            return "https://" + u;
        }
        try {
            return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(u, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com";
        }
    }

    private String browse(String raw) {
        String url = normalizeUrl(raw);
        if (url.isEmpty()) return "{\"ok\":false,\"err\":\"bağlantı boş\"}";
        // Web pages only: no file:, content: or intent: links from the phone page.
        if (!url.matches("(?i)^https?://.*")) return "{\"ok\":false,\"err\":\"yalnızca web bağlantısı açılır\"}";
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (isInstalled(BROWSER)) i.setPackage(BROWSER);
        try {
            app.startActivity(i);
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"tarayıcı açılamadı\"}";
        }
        remember("hist", url, null, 60);
        return "{\"ok\":true,\"url\":\"" + esc(url) + "\"}";
    }

    /** Adds (or moves to the top) an entry {u, n, t}; keeps at most max entries. */
    private void remember(String key, String url, String name, int max) {
        try {
            org.json.JSONArray old = arr(key), out = new org.json.JSONArray();
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("u", url);
            e.put("n", name != null && !name.isEmpty() ? name : hostOf(url));
            e.put("t", System.currentTimeMillis());
            out.put(e);
            for (int k = 0; k < old.length() && out.length() < max; k++) {
                org.json.JSONObject o = old.getJSONObject(k);
                if (!url.equals(o.optString("u"))) out.put(o);
            }
            prefsB().edit().putString(key, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h != null) return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception ignored) {
        }
        return url;
    }

    private String bmAdd(String raw, String name) {
        String url = normalizeUrl(raw);
        if (url.isEmpty()) return "{\"ok\":false,\"err\":\"bağlantı boş\"}";
        remember("bm", url, name, 200);
        return browserState();
    }

    private String listDel(String key, String url) {
        try {
            org.json.JSONArray old = arr(key), out = new org.json.JSONArray();
            for (int k = 0; k < old.length(); k++) {
                org.json.JSONObject o = old.getJSONObject(k);
                if (!o.optString("u").equals(url)) out.put(o);
            }
            prefsB().edit().putString(key, out.toString()).apply();
        } catch (Exception ignored) {
        }
        return browserState();
    }

    /**
     * Writes into the text field that is open on the TV. The phone sends the whole field on every change.
     * ACTION_SET_TEXT (accessibility) is tried first. Fields that refuse it (web pages in TV browsers) fall back to
     * shell "input", which is slow and only appends; so the fallback runs on one worker that always jumps to the
     * newest text and sends only the difference. Old states are skipped instead of queueing up and being typed
     * later into whatever field has focus by then.
     */
    private String typeInto(Map<String, String> qs) {
        String t = qs.get("t");
        String mode = qs.get("mode");
        if ("enter".equals(mode)) return adbKey(KeyEvent.KEYCODE_ENTER);
        if ("bksp".equals(mode)) return adbKey(KeyEvent.KEYCODE_DEL);
        boolean append = "add".equals(mode);
        boolean go = "1".equals(qs.get("go"));
        if (t == null) t = "";
        String target;
        synchronized (typeLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (typeWanted == null && now - fallbackTypedAt > FALLBACK_TYPED_TTL_MS) fallbackTyped = "";
            String base = typeWanted != null ? typeWanted : fallbackTyped;
            target = append ? base + t : t;
            if (typeWanted == null && !typeBusy) {
                String err = PowerService.typeText(t, append);
                if (err == null) {
                    fallbackTyped = target;
                    fallbackTypedAt = now;
                    if (go) adbKey(KeyEvent.KEYCODE_ENTER);
                    return "{\"ok\":true}";
                }
                if (!isAscii(target)) return "{\"ok\":false,\"err\":\"" + esc(err) + "\"}";
            } else if (!isAscii(target)) {
                return "{\"ok\":false,\"err\":\"Türkçe harfler bu kutuya yazılamıyor\"}";
            }
            typeWanted = target;
            typeGo |= go;
            if (!typeBusy) {
                typeBusy = true;
                Thread w = new Thread(this::typeWorker, "remote-type");
                w.setDaemon(true);
                w.start();
            }
        }
        return "{\"ok\":true}";
    }

    private void typeWorker() {
        while (true) {
            String target;
            boolean go;
            String from;
            synchronized (typeLock) {
                if (typeWanted == null) {
                    typeBusy = false;
                    return;
                }
                target = typeWanted;
                go = typeGo;
                typeWanted = null;
                typeGo = false;
                from = fallbackTyped;
            }
            int p = 0, max = Math.min(from.length(), target.length());
            while (p < max && from.charAt(p) == target.charAt(p)) p++;
            int del = from.length() - p;
            String add = target.substring(p);
            StringBuilder cmd = new StringBuilder();
            if (del > 0) {
                cmd.append("input keyevent");
                for (int i = 0; i < del; i++) cmd.append(' ').append(KeyEvent.KEYCODE_DEL);
            }
            if (!add.isEmpty()) {
                if (cmd.length() > 0) cmd.append("; ");
                cmd.append("input text '").append(add.replace("'", "'\\''").replace(" ", "%s")).append('\'');
            }
            if (go) {
                if (cmd.length() > 0) cmd.append("; ");
                cmd.append("input keyevent ").append(KeyEvent.KEYCODE_ENTER);
            }
            boolean ok = cmd.length() == 0;
            if (!ok) {
                try {
                    LocalAdb.execShellOutput(app, cmd.toString());
                    ok = true;
                } catch (Exception ignored) {
                }
            }
            synchronized (typeLock) {
                if (ok) fallbackTyped = target;
                fallbackTypedAt = android.os.SystemClock.elapsedRealtime();
                if (!ok) {
                    // Could not reach the shell: drop what is waiting rather than typing it later somewhere else.
                    typeWanted = null;
                    typeGo = false;
                }
            }
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    private final Object typeLock = new Object();
    /** Newest text the phone wants that the fallback worker has not written yet (null = nothing waiting). */
    private String typeWanted;
    private boolean typeGo, typeBusy;
    /** What the TV's field holds as far as this server knows; forgotten after a pause (likely another field). */
    private String fallbackTyped = "";
    private long fallbackTypedAt = 0L;
    private static final long FALLBACK_TYPED_TTL_MS = 30_000L;

    // ------------------------------------------------------------------ phone trackpad -> on-screen mouse
    private final android.os.Handler uiH = new android.os.Handler(android.os.Looper.getMainLooper());
    private CursorView cursorView;
    private android.view.WindowManager.LayoutParams cursorLp;
    private float curX = -1, curY = -1;
    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            dropCursor();
        }
    };

    /**
     * Removes the overlay window, not just the arrow: a full-screen window left in place keeps its own
     * graphics buffers and is composited over every app, video included, for as long as the launcher lives.
     */
    private void dropCursor() {
        if (cursorLayer == null) return;
        try {
            cursorView.animate().cancel();
            ((android.view.WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(cursorLayer);
        } catch (Exception ignored) {
        }
        cursorLayer = null;
        cursorView = null;
    }

    /** The pointer drawn over every app. White arrow with a dark outline; hot-spot is its tip. */
    private static final class CursorView extends android.view.View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        CursorView(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF111114);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(3f);
            edge.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float u = getWidth() / 24f;
            path.reset();
            path.moveTo(2 * u, 1.5f * u);
            path.lineTo(2 * u, 19 * u);
            path.lineTo(7 * u, 14.6f * u);
            path.lineTo(10.4f * u, 22 * u);
            path.lineTo(13.4f * u, 20.6f * u);
            path.lineTo(10 * u, 13.4f * u);
            path.lineTo(16.5f * u, 13.4f * u);
            path.close();
            cv.drawPath(path, fill);
            cv.drawPath(path, edge);
        }
    }

    private int screenW() {
        return app.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return app.getResources().getDisplayMetrics().heightPixels;
    }

    /** Overlay windows need the "display over other apps" permission; grant it through the launcher's own ADB if missing. */
    private boolean overlayAllowed() {
        if (android.provider.Settings.canDrawOverlays(app)) return true;
        try {
            LocalAdb.execShellOutput(app, "appops set " + app.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        } catch (Exception ignored) {
        }
        return android.provider.Settings.canDrawOverlays(app);
    }

    private android.widget.FrameLayout cursorLayer;

    private void ensureCursor() {
        if (cursorView != null) return;
        cursorView = new CursorView(app);
        cursorView.setVisibility(android.view.View.INVISIBLE);   // shown at its position, not sliding in from a corner
        int size = Math.round(36 * app.getResources().getDisplayMetrics().density / 2f);
        // One full-screen, untouchable layer that never moves; only the arrow inside it is translated,
        // which is a cheap render-property change instead of a window relayout per move.
        cursorLayer = new android.widget.FrameLayout(app);
        cursorLayer.addView(cursorView, new android.widget.FrameLayout.LayoutParams(size, size));
        cursorLp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        cursorLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        cursorLp.setTitle("tvOSRemoteCursor");
        ((android.view.WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(cursorLayer, cursorLp);
    }

    // ------------------------------------------------------------------ TV browser (own D-pad driven cursor)
    // com.tcl.browser ignores touch and mouse taps: it draws its own cursor that only moves while a D-pad key is
    // held (distance grows with hold time, roughly 0.0015 * ms^2 px) and clicks on OK. So while it is on top, the
    // trackpad drives that cursor with timed key holds instead of the overlay arrow.
    private final ThreadPoolExecutor tvbWorker = idlePool(1);
    private float tvbX, tvbY;
    private boolean tvbBusy;
    private long tvbCheckedAt;
    private boolean tvbOn;

    private boolean tvBrowserOnTop() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - tvbCheckedAt < 1500) return tvbOn;
        tvbCheckedAt = now;
        try {
            String o = LocalAdb.execShellOutput(app, "dumpsys activity activities | grep -m1 topResumedActivity");
            tvbOn = o != null && o.contains("com.tcl.browser");
        } catch (Exception ignored) {
        }
        return tvbOn;
    }

    private static int tvbMs(float px) {
        int t = Math.round((float) Math.sqrt(Math.abs(px) / 0.0015f));
        return Math.max(50, Math.min(280, t));
    }

    private void tvbDrain() {
        for (int guard = 0; guard < 60; guard++) {
            float ax, ay;
            synchronized (this) {
                ax = tvbX;
                ay = tvbY;
            }
            boolean hx = Math.abs(ax) >= 6f, hy = Math.abs(ay) >= 6f;
            if (!hx && !hy) break;
            // Both axes pending: hold two keys at once (diagonal, equal distance on each axis).
            float d = hx && hy ? Math.min(Math.abs(ax), Math.abs(ay)) : (hx ? Math.abs(ax) : Math.abs(ay));
            int t = tvbMs(d);
            float done = Math.min(d, 0.0015f * t * t);
            StringBuilder keys = new StringBuilder();
            if (hx) keys.append(ax > 0 ? " KEYCODE_DPAD_RIGHT" : " KEYCODE_DPAD_LEFT");
            if (hy) keys.append(ay > 0 ? " KEYCODE_DPAD_DOWN" : " KEYCODE_DPAD_UP");
            if (keys.indexOf(" ", 1) < 0) keys.append(keys);   // keycombination needs two codes
            try {
                LocalAdb.execShellOutput(app, "input keycombination -t " + t + keys);
            } catch (Exception ignored) {
            }
            synchronized (this) {
                if (hx) tvbX -= Math.signum(ax) * done;
                if (hy) tvbY -= Math.signum(ay) * done;
            }
        }
        synchronized (this) {
            tvbBusy = false;
            if (Math.abs(tvbX) >= 6f || Math.abs(tvbY) >= 6f) {
                tvbBusy = true;
                tvbWorker.execute(this::tvbDrain);
            }
        }
    }

    private String tvbMouse(float dx, float dy, String click) {
        synchronized (this) {
            tvbX = Math.max(-140f, Math.min(140f, tvbX + dx * 0.55f));
            tvbY = Math.max(-140f, Math.min(140f, tvbY + dy * 0.55f));
            if (!tvbBusy && (Math.abs(tvbX) >= 6f || Math.abs(tvbY) >= 6f)) {
                tvbBusy = true;
                tvbWorker.execute(this::tvbDrain);
            }
        }
        if (click != null) {
            final boolean lng = "2".equals(click);
            tvbWorker.execute(() -> {
                try {
                    tvbDrain();
                    LocalAdb.execShellOutput(app, "input keyevent " + (lng ? "--longpress " : "") + "KEYCODE_DPAD_CENTER");
                } catch (Exception ignored) {
                }
            });
        }
        return "{\"ok\":true,\"tvb\":true}";
    }

    private String mouse(Map<String, String> qs) {
        if (!overlayAllowed()) {
            return "{\"ok\":false,\"err\":\"TV'de 'diğer uygulamaların üzerinde göster' izni gerekli\"}";
        }
        final float W = screenW(), H = screenH();
        if (curX < 0) {
            curX = W / 2f;
            curY = H / 2f;
        }
        float dx = 0, dy = 0;
        try {
            if (qs.get("dx") != null) dx = finite(qs.get("dx"));
            if (qs.get("dy") != null) dy = finite(qs.get("dy"));
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"err\":\"geçersiz değer\"}";
        }
        if (!"toggle".equals(qs.get("show")) && !"1".equals(qs.get("center")) && tvBrowserOnTop()) {
            uiH.post(hideCursor);
            return tvbMouse(dx, dy, qs.get("click"));
        }
        String show = qs.get("show");
        final boolean hide = "0".equals(show);
        final boolean center = "1".equals(qs.get("center"));
        curX = Math.max(0, Math.min(W - 1, center ? W / 2f : curX + dx));
        curY = Math.max(0, Math.min(H - 1, center ? H / 2f : curY + dy));
        final int x = Math.round(curX), y = Math.round(curY);
        final boolean toggle = "toggle".equals(show);
        uiH.post(() -> {
            try {
                boolean visible = cursorView != null && cursorView.getVisibility() == android.view.View.VISIBLE;
                if (hide || (toggle && visible)) {
                    uiH.removeCallbacks(hideCursor);
                    dropCursor();
                    return;
                }
                ensureCursor();
                if (cursorView.getVisibility() != android.view.View.VISIBLE) {
                    cursorView.animate().cancel();
                    cursorView.setTranslationX(x);
                    cursorView.setTranslationY(y);
                    cursorView.setVisibility(android.view.View.VISIBLE);
                } else {
                    cursorView.animate().translationX(x).translationY(y).setDuration(45)
                            .setInterpolator(new android.view.animation.LinearInterpolator()).start();
                }
                uiH.removeCallbacks(hideCursor);
                uiH.postDelayed(hideCursor, 7000);
            } catch (Exception ignored) {
            }
        });
        String click = qs.get("click");
        if (click != null) {
            try {
                String cmd = "2".equals(click)
                        ? "input swipe " + x + " " + y + " " + x + " " + y + " 800"
                        : "input tap " + x + " " + y;
                LocalAdb.execShellOutput(app, cmd);
            } catch (Exception e) {
                adbWorks = false;
                return "{\"ok\":false,\"err\":\"Tıklama için TV'de ADB açık olmalı\"}";
            }
        }
        return "{\"ok\":true,\"x\":" + x + ",\"y\":" + y + "}";
    }

    private int appVersion() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String deviceName() {
        String n = null;
        try {
            n = android.provider.Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        return n == null || n.isEmpty() ? android.os.Build.MODEL : n;
    }

    private String apps() {
        PackageManager pm = app.getPackageManager();
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> list = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(app.getPackageName()) || !seen.add(pkg)) continue;
                list.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
            }
        }
        Collections.sort(list, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"apps\":[");
        for (int k = 0; k < list.size(); k++) {
            if (k > 0) sb.append(',');
            sb.append("{\"l\":\"").append(esc(list.get(k)[0])).append("\",\"p\":\"").append(esc(list.get(k)[1])).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private final android.util.LruCache<String, byte[]> iconCache = new android.util.LruCache<String, byte[]>(512 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    /** App icon as a small PNG (the phone page shows it next to the app name). */
    private byte[] icon(String pkg) {
        if (pkg == null) return null;
        byte[] c = iconCache.get(pkg);
        if (c != null) return c;
        try {
            android.graphics.drawable.Drawable d = app.getPackageManager().getApplicationIcon(pkg);
            int px = 96;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, px, px);
            d.draw(cv);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
            bmp.recycle();
            byte[] out = bo.toByteArray();
            iconCache.put(pkg, out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private String launch(String pkg) {
        if (pkg == null) return "{\"ok\":false}";
        PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return "{\"ok\":false}";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(i);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = app.getAssets().open(name); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' || ch == '"') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(' ');   // an app name with a tab or newline must not break the JSON
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static void json(OutputStream out, String body) throws Exception {
        write(out, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static final String WEB_MANIFEST = "{\"name\":\"TV Kumanda\",\"short_name\":\"Kumanda\","
            + "\"start_url\":\"/\",\"scope\":\"/\",\"display\":\"standalone\",\"orientation\":\"portrait\","
            + "\"background_color\":\"#05070c\",\"theme_color\":\"#05070c\",\"icons\":["
            + "{\"src\":\"/icon-192.png\",\"sizes\":\"192x192\",\"type\":\"image/png\",\"purpose\":\"any maskable\"},"
            + "{\"src\":\"/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\",\"purpose\":\"any maskable\"}]}";

    private final java.util.Map<Integer, byte[]> homeIconPng = new java.util.concurrent.ConcurrentHashMap<>();

    /** Home-screen icon for the phone: the launcher's remote glyph on a dark rounded square. */
    private byte[] remoteIcon(int size) {
        byte[] cached = homeIconPng.get(size);
        if (cached != null) return cached;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, size, 0xFF1C2233, 0xFF05070C,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);   // full bleed: "maskable" icons are cut to the phone's own shape
        int g = Math.round(size * 0.5f);
        android.graphics.Bitmap glyph = Glyphs.get("remote", g, 0xFFF4F5F8);
        if (glyph != null) c.drawBitmap(glyph, (size - glyph.getWidth()) / 2f, (size - glyph.getHeight()) / 2f, null);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
        b.recycle();
        byte[] png = bo.toByteArray();
        homeIconPng.put(size, png);
        return png;
    }

    private static void write(OutputStream out, int code, String type, byte[] body, String cache) throws Exception {
        String h = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: " + cache + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void write(OutputStream out, int code, String type, byte[] body) throws Exception {
        String h = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
