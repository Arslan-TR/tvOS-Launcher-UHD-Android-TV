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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone remote: a tiny HTTP server on the local network. The TV shows a QR code carrying a random
 * token; the phone opens the page in its browser (nothing to install) and sends keys, volume,
 * media keys and app launches. LAN only; every API call must carry the token.
 */
final class RemoteServer {
    static final int PORT = 8765;
    private static RemoteServer instance;

    private final Context app;
    private final String token;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile ServerSocket socket;
    private volatile boolean adbWorks = true;

    private RemoteServer(Context c) {
        this.app = c.getApplicationContext();
        // Kept across restarts and updates so a QR the phone already scanned stays valid.
        android.content.SharedPreferences sp = app.getSharedPreferences("remote_server", Context.MODE_PRIVATE);
        String t = sp.getString("token", null);
        if (t == null || t.length() != 12) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            sp.edit().putString("token", t).apply();
        }
        this.token = t;
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
                        pool.execute(() -> handle(c));
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
            InputStream in = sock.getInputStream();
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
            if (p.length < 2 || !"GET".equals(p[0])) {
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
            boolean authed = token.equals(qs.get("k"));
            if (path.equals("/") || path.equals("/index.html")) {
                // The page itself is harmless; the API below needs the token.
                write(out, 200, "text/html; charset=utf-8", asset("remote.html"));
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
            } else if (path.equals("/api/launch")) {
                json(out, launch(qs.get("p")));
            } else {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
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
                Intent h = new Intent(app, MainActivity.class)
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
            if (qs.get("x") != null) c.x = Float.parseFloat(qs.get("x"));
            if (qs.get("y") != null) c.y = Float.parseFloat(qs.get("y"));
            if (qs.get("s") != null) c.scale = Float.parseFloat(qs.get("s"));
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
            Float op = qs.get("op") == null ? null : Float.valueOf(qs.get("op"));
            Boolean solid = qs.get("solid") == null ? null : Boolean.valueOf("1".equals(qs.get("solid")));
            Float size = qs.get("size") == null ? null : Float.valueOf(qs.get("size"));
            Scenes.setClockLook(app, color, op, solid, size);
            if (qs.get("weight") != null) Scenes.setClockWeight(app, Float.parseFloat(qs.get("weight")));
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

    /** Writes into the text field that is open on the TV. ASCII falls back to adb "input text" if needed. */
    private String typeInto(Map<String, String> qs) {
        String t = qs.get("t");
        String mode = qs.get("mode");
        if ("enter".equals(mode)) return adbKey(KeyEvent.KEYCODE_ENTER);
        if ("bksp".equals(mode)) return adbKey(KeyEvent.KEYCODE_DEL);
        boolean append = "add".equals(mode);
        if (t == null) t = "";
        String err = PowerService.typeText(t, append);
        if (err == null) {
            if ("1".equals(qs.get("go"))) adbKey(KeyEvent.KEYCODE_ENTER);
            return "{\"ok\":true}";
        }
        if (t.matches("\\A\\p{ASCII}*\\z") && !t.isEmpty()) {
            try {
                LocalAdb.execShellOutput(app, "input text '" + t.replace("'", "'\\''").replace(" ", "%s") + "'");
                if ("1".equals(qs.get("go"))) adbKey(KeyEvent.KEYCODE_ENTER);
                return "{\"ok\":true}";
            } catch (Exception ignored) {
            }
        }
        return "{\"ok\":false,\"err\":\"" + esc(err) + "\"}";
    }

    // ------------------------------------------------------------------ phone trackpad -> on-screen mouse
    private final android.os.Handler uiH = new android.os.Handler(android.os.Looper.getMainLooper());
    private CursorView cursorView;
    private android.view.WindowManager.LayoutParams cursorLp;
    private float curX = -1, curY = -1;
    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            if (cursorView != null) cursorView.setVisibility(android.view.View.INVISIBLE);
        }
    };

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
    private final java.util.concurrent.ExecutorService tvbWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
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
            if (qs.get("dx") != null) dx = Float.parseFloat(qs.get("dx"));
            if (qs.get("dy") != null) dy = Float.parseFloat(qs.get("dy"));
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
                ensureCursor();
                boolean visible = cursorView.getVisibility() == android.view.View.VISIBLE;
                if (hide || (toggle && visible)) {
                    uiH.removeCallbacks(hideCursor);
                    cursorView.setVisibility(android.view.View.INVISIBLE);
                    return;
                }
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

    private final Map<String, byte[]> iconCache = new HashMap<>();

    /** App icon as a small PNG (the phone page shows it next to the app name). */
    private byte[] icon(String pkg) {
        if (pkg == null) return null;
        synchronized (iconCache) {
            byte[] c = iconCache.get(pkg);
            if (c != null) return c;
        }
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
            synchronized (iconCache) {
                iconCache.put(pkg, out);
            }
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static void json(OutputStream out, String body) throws Exception {
        write(out, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
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
