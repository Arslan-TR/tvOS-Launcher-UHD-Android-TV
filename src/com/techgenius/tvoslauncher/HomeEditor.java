package com.techgenius.tvoslauncher;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Edit Home Screen" mode, opened from Control Center: a small panel over the home screen, driven only by the remote.
 * Up/Down picks a row, Left/Right changes it, OK toggles or starts moving a widget (arrows move it, OK or Back
 * ends), Back closes. Every change is applied and saved at once, so what you see is what stays.
 */
final class HomeEditor {
    private final MainActivity a;
    private final FrameLayout layer;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView hint;
    private final List<Row> rows = new ArrayList<>();
    private int sel;
    /** Widget id being moved with the arrows, or null. */
    private String moving;

    private interface Value {
        String get();
    }

    private interface Change {
        void by(int dir);
    }

    private static final class Row {
        final String label;
        final Value value;
        final Change change;
        final Runnable ok;
        TextView labelView, valueView;
        LinearLayout view;

        Row(String label, Value value, Change change, Runnable ok) {
            this.label = label;
            this.value = value;
            this.change = change;
            this.ok = ok;
        }
    }

    static boolean open(MainActivity a) {
        if (a.homeEditor != null || a.sceneHome == null || !Scenes.isScenesLook(a)) return false;
        a.homeEditor = new HomeEditor(a);
        return true;
    }

    private HomeEditor(MainActivity a) {
        this.a = a;
        buildRows();

        layer = new FrameLayout(a);
        layer.setClickable(true);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(22));
        bg.setColor(0xD9141418);
        bg.setStroke(Math.max(1, a.dp(1)), 0x33FFFFFF);
        card.setBackground(bg);

        TextView title = new TextView(a);
        title.setText(I18n.s(a, "Edit Home Screen", "Ana Ekranı Düzenle"));
        title.setTextColor(Color.WHITE);
        title.setTypeface(Ui.font(a, "semibold"));
        Ui.textSize(title, 15f);
        card.addView(title);

        hint = new TextView(a);
        hint.setTextColor(0xB3FFFFFF);
        Ui.textSize(hint, 10f);
        hint.setPadding(0, a.dp(2), 0, a.dp(8));
        card.addView(hint);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Row r : rows) list.addView(rowView(r));
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(300)));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(a.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        clp.rightMargin = a.dp(28);
        clp.bottomMargin = a.dp(96);
        layer.addView(card, clp);
        a.root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(180).start();
        refresh();
    }

    private View rowView(Row r) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        TextView l = new TextView(a);
        l.setText(r.label);
        l.setTypeface(Ui.font(a, "medium"));
        Ui.textSize(l, 11.5f);
        TextView val = new TextView(a);
        val.setTypeface(Ui.font(a, "semibold"));
        Ui.textSize(val, 11.5f);
        val.setGravity(Gravity.END);
        v.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        v.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = a.dp(3);
        v.setLayoutParams(lp);
        r.view = v;
        r.labelView = l;
        r.valueView = val;
        return v;
    }

    // ------------------------------------------------------------------ rows

    private void buildRows() {
        rows.add(new Row(t("Clock size", "Saat boyutu"),
                () -> pct(Scenes.getClockSize(a)),
                d -> {
                    Scenes.setClockLook(a, null, null, null, round2(Scenes.getClockSize(a) + d * 0.05f));
                    clockChanged();
                }, null));
        rows.add(new Row(t("Clock weight", "Saat kalınlığı"),
                this::weightText,
                d -> {
                    Scenes.setClockWeight(a, round2(Scenes.getClockWeight(a) + d * 0.04f));
                    clockChanged();
                }, null));
        rows.add(new Row(t("Clock style", "Saat stili"),
                () -> styleName(Scenes.getClockStyle(a)),
                d -> {
                    Scenes.setClockStyle(a, cycle(Scenes.CLOCK_STYLES, Scenes.getClockStyle(a), d));
                    clockChanged();
                }, null));
        rows.add(new Row(t("Clock position", "Saat konumu"),
                () -> posName(Scenes.getCustomClockPos(a)),
                d -> {
                    Scenes.setCustomClockPos(a, cycle(Scenes.CLOCK_POSITIONS, Scenes.getCustomClockPos(a), d));
                    clockChanged();
                }, null));
        rows.add(new Row(t("Clock look", "Saat görünümü"),
                () -> Scenes.isClockSolid(a) ? t("Solid", "Düz") : t("Glass", "Cam"),
                d -> toggleSolid(), this::toggleSolid));
        rows.add(new Row(t("Clock opacity", "Saat saydamlığı"),
                () -> pct(Scenes.getClockOpacity(a)),
                d -> {
                    Scenes.setClockLook(a, null, round2(Scenes.getClockOpacity(a) + d * 0.05f), null, null);
                    clockChanged();
                }, null));

        for (String id : Widgets.IDS) {
            String name = "cal".equals(id) ? t("Calendar", "Takvim") : t("Analog clock", "Analog saat");
            rows.add(new Row(name,
                    () -> Widgets.get(a, id).visible ? t("On", "Açık") : t("Off", "Kapalı"),
                    d -> toggleWidget(id), () -> toggleWidget(id)));
            rows.add(new Row(name + t(" size", " boyutu"),
                    () -> pct(Widgets.get(a, id).scale),
                    d -> {
                        Widgets.Cfg c = Widgets.get(a, id);
                        c.scale = round2(c.scale + d * 0.1f);
                        Widgets.set(a, id, c);
                        widgetsChanged();
                    }, null));
            rows.add(new Row(name + t(" position", " konumu"),
                    () -> id.equals(moving) ? t("Moving: arrows, OK", "Taşınıyor: oklar, OK")
                            : t("OK to move", "Taşımak için OK"),
                    null, () -> {
                        Widgets.Cfg c = Widgets.get(a, id);
                        if (!c.visible) {
                            c.visible = true;
                            Widgets.set(a, id, c);
                            widgetsChanged();
                        }
                        moving = id;
                    }));
        }

        rows.add(new Row(t("Continue Watching", "İzlemeye Devam Et"),
                () -> a.cwEnabled() ? t("On", "Açık") : t("Off", "Kapalı"),
                d -> toggleShelf(), this::toggleShelf));
        rows.add(new Row(t("Continue Watching content", "İzlemeye Devam Et içeriği"),
                () -> MediaWatchService.Store.onlyWatched(a) ? t("Only what I watched", "Sadece izlediklerim")
                        : t("Include suggestions", "Öneriler dahil"),
                d -> toggleOnlyWatched(), this::toggleOnlyWatched));
        rows.add(new Row(t("Continue Watching position", "İzlemeye Devam Et konumu"),
                () -> shelfPosText(),
                d -> {
                    // Right moves the row down, left moves it up.
                    SceneHome.setShelfTopDp(a, SceneHome.shelfTopDp(a) + d * 8f);
                    a.sceneHome.applyShelfPosition();
                }, null));
        rows.add(new Row(t("Clear Continue Watching", "İzlemeye Devam Et'i temizle"), () -> "OK", null, () -> {
            MediaWatchService.Store.clear(a);
            a.sceneHome.refreshKeepWatching();
        }));
        rows.add(new Row(t("Top-right buttons", "Sağ üst düğmeler"),
                () -> SceneHome.topButtonDp(a) + " dp",
                d -> {
                    SceneHome.setTopButtonDp(a, SceneHome.topButtonDp(a) + d * 2);
                    a.sceneHome.rebuildTopBar();
                }, null));
        rows.add(new Row(t("Reset all", "Hepsini sıfırla"), () -> "OK", null, this::resetAll));
        rows.add(new Row(t("Done", "Bitti"), () -> "OK", null, this::close));
    }

    private void toggleOnlyWatched() {
        MediaWatchService.Store.setOnlyWatched(a, !MediaWatchService.Store.onlyWatched(a));
        a.sceneHome.refreshKeepWatching();
    }

    private void toggleShelf() {
        Profiles.homePrefs(a).edit().putBoolean("cw_enabled", !a.cwEnabled()).apply();
        a.sceneHome.refreshKeepWatching();
    }

    private String shelfPosText() {
        float v = SceneHome.shelfTopDp(a);
        float pct = (v - SceneHome.SHELF_TOP_MIN_DP) / (SceneHome.SHELF_TOP_MAX_DP - SceneHome.SHELF_TOP_MIN_DP);
        String where = Math.abs(v - SceneHome.SHELF_TOP_DP) < 1f ? t("default", "varsayılan")
                : v < SceneHome.SHELF_TOP_DP ? t("higher", "yukarıda") : t("lower", "aşağıda");
        return Math.round(pct * 100f) + "% · " + where;
    }

    private void toggleSolid() {
        boolean solid = !Scenes.isClockSolid(a);
        // Solid without a colour would be invisible; use white. Glass goes back to the automatic tint.
        Scenes.setClockLook(a, solid ? 0xFFFFFFFF : 0, null, solid, null);
        clockChanged();
    }

    private void toggleWidget(String id) {
        Widgets.Cfg c = Widgets.get(a, id);
        c.visible = !c.visible;
        Widgets.set(a, id, c);
        widgetsChanged();
    }

    private void resetAll() {
        Scenes.resetClockLook(a);
        Scenes.setClockStyle(a, Scenes.CLOCK_STYLE_DUO);
        Scenes.setCustomClockPos(a, Scenes.CLOCK_POS_CENTER);
        Widgets.reset(a);
        SceneHome.setTopButtonDp(a, SceneHome.TOP_BTN_DEFAULT_DP);
        a.sceneHome.rebuildTopBar();
        SceneHome.setShelfTopDp(a, SceneHome.SHELF_TOP_DP);
        a.sceneHome.applyShelfPosition();
        clockChanged();
        widgetsChanged();
    }

    private void clockChanged() {
        if (a.sceneHome != null) a.sceneHome.sceneView.invalidate();
    }

    private void widgetsChanged() {
        if (a.sceneHome != null) a.sceneHome.refreshWidgets();
    }

    // ------------------------------------------------------------------ keys

    /** Takes every key while the editor is open. */
    boolean dispatch(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (k == KeyEvent.KEYCODE_HOME) return false;
        if (ev.getAction() != KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (moving != null) moving = null;
                else close();
                refresh();
            }
            return true;
        }
        if (moving != null) {
            Widgets.Cfg c = Widgets.get(a, moving);
            float step = ev.getRepeatCount() > 3 ? 0.02f : 0.008f;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: c.x -= step; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: c.x += step; break;
                case KeyEvent.KEYCODE_DPAD_UP: c.y -= step * 16f / 9f; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: c.y += step * 16f / 9f; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    moving = null;
                    refresh();
                    return true;
                default:
                    return true;
            }
            Widgets.set(a, moving, c);
            widgetsChanged();
            return true;
        }
        Row r = rows.get(sel);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                sel = Math.max(0, sel - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sel = Math.min(rows.size() - 1, sel + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (r.change != null) r.change.by(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (r.change != null) r.change.by(1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (ev.getRepeatCount() == 0) {
                    if (r.ok != null) r.ok.run();
                    else if (r.change != null) r.change.by(1);
                }
                break;
            default:
                return true;
        }
        if (a.homeEditor == this) refresh();
        return true;
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean on = i == sel;
            r.valueView.setText(r.change != null ? "‹  " + r.value.get() + "  ›" : r.value.get());
            r.labelView.setTextColor(on ? 0xFF111114 : 0xFFEDEDF2);
            r.valueView.setTextColor(on ? 0xFF111114 : 0xCCFFFFFF);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(a.dp(12));
            g.setColor(on ? 0xF2FFFFFF : 0x14FFFFFF);
            r.view.setBackground(g);
        }
        boolean onShelfPos = rows.get(sel).label.equals(t("Continue Watching position", "İzlemeye Devam Et konumu"));
        a.sceneHome.showShelfGhost(onShelfPos && a.homeEditor == this);
        hint.setText(moving != null
                ? t("Arrows move the widget, OK or Back to finish.", "Oklarla widget'ı taşıyın, bitirmek için OK veya Geri.")
                : t("Up/Down: choose  ·  Left/Right: change  ·  Back: close",
                    "Yukarı/Aşağı: seç  ·  Sol/Sağ: değiştir  ·  Geri: kapat"));
        View v = rows.get(sel).view;
        scroll.post(() -> {
            int top = v.getTop(), bottom = v.getBottom(), h = scroll.getHeight();
            if (top < scroll.getScrollY()) scroll.smoothScrollTo(0, top);
            else if (bottom > scroll.getScrollY() + h) scroll.smoothScrollTo(0, bottom - h);
        });
    }

    void close() {
        if (a.homeEditor != this) return;
        a.homeEditor = null;
        moving = null;
        if (a.sceneHome != null) a.sceneHome.showShelfGhost(false);
        layer.animate().alpha(0f).setDuration(150).withEndAction(() -> a.root.removeView(layer)).start();
    }

    // ------------------------------------------------------------------ text helpers

    private String t(String en, String tr) {
        return I18n.s(a, en, tr);
    }

    private String weightText() {
        float w = Scenes.getClockWeight(a);
        if (Math.abs(w - Scenes.CLOCK_WEIGHT_DEFAULT) < 0.02f) return t("Normal", "Normal");
        return Math.round(w * 100f) + (w < Scenes.CLOCK_WEIGHT_DEFAULT ? t(" (thin)", " (ince)") : t(" (bold)", " (kalın)"));
    }

    private String styleName(String s) {
        if (Scenes.CLOCK_STYLE_ROUNDED.equals(s)) return t("Rounded", "Yuvarlak");
        if (Scenes.CLOCK_STYLE_TALL.equals(s)) return t("Tall", "Uzun");
        return "Duo";
    }

    private String posName(String s) {
        if (Scenes.CLOCK_POS_LEFT.equals(s)) return t("Left", "Sol");
        if (Scenes.CLOCK_POS_RIGHT.equals(s)) return t("Right", "Sağ");
        return t("Center", "Orta");
    }

    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private static String cycle(String[] all, String cur, int dir) {
        int i = 0;
        for (int j = 0; j < all.length; j++) if (all[j].equals(cur)) i = j;
        return all[((i + dir) % all.length + all.length) % all.length];
    }
}
