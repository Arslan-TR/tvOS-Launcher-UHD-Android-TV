package com.techgenius.tvoslauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Remote app buttons (YouTube, Netflix, Prime Video...) on Google TV. The system sends
 * com.google.global_button.ACTION_LAUNCH_APP with the package to open to every manifest receiver of that action,
 * normally only Google TV's own launcher. With that launcher disabled (so Home never flashes through it) the buttons
 * did nothing; this opens the app instead. Does nothing while Google TV's launcher is enabled (no double launch).
 */
public class AppButtonReceiver extends BroadcastReceiver {
    private static final String STOCK_TV_LAUNCHER = "com.google.android.apps.tv.launcherx";

    @Override
    public void onReceive(Context c, Intent i) {
        String pkg = i == null ? null : i.getStringExtra("launchPackageName");
        if (pkg == null || pkg.isEmpty() || stockLauncherEnabled(c)) return;
        PackageManager pm = c.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    private static boolean stockLauncherEnabled(Context c) {
        try {
            int s = c.getPackageManager().getApplicationEnabledSetting(STOCK_TV_LAUNCHER);
            if (s != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && s != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return false;
            }
            return c.getPackageManager().getApplicationInfo(STOCK_TV_LAUNCHER, 0).enabled;
        } catch (Throwable notInstalled) {
            return false;
        }
    }
}
