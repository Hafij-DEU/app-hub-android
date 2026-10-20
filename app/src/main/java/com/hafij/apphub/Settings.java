package com.hafij.apphub;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    private Settings() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Install updates from the background check (only on unmetered networks). */
    static boolean autoInstall(Context c) {
        return prefs(c).getBoolean("auto_install", false);
    }

    static boolean includePrerelease(Context c) {
        return prefs(c).getBoolean("prerelease", false);
    }

    /** Optional GitHub token; lifts the 60 requests/hour anonymous limit. */
    static String token(Context c) {
        String t = prefs(c).getString("token", "");
        return t == null ? "" : t.trim();
    }

    static long lastCheck(Context c) {
        return prefs(c).getLong("last_check", 0);
    }
}
