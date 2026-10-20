package com.hafij.apphub;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Finds the newest release with an installable APK for each app. */
final class Updates {
    private Updates() {}

    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    static void refreshInstalled(Context c, AppEntry e) {
        e.installedVersion = null;
        if (e.pkg == null) return;
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(e.pkg, 0);
            e.installedVersion = info.versionName == null ? "?" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    static void check(Context c, AppEntry e) {
        refreshInstalled(c, e);
        e.error = null;
        try {
            GitHub.Response r = GitHub.releases(c, e.repo);
            e.stale = r.stale;
            pick(c, e, new JSONArray(r.body));
            if (e.release == null) e.error = "No release with an APK yet";
        } catch (IOException ex) {
            e.error = ex.getMessage();
        } catch (JSONException ex) {
            e.error = "Bad answer from GitHub";
        }
    }

    static void checkAll(Context c, List<AppEntry> apps) {
        Catalog.refreshRemote(c);
        for (AppEntry e : apps) check(c, e);
        Settings.prefs(c).edit().putLong("last_check", System.currentTimeMillis()).apply();
    }

    private static void pick(Context c, AppEntry e, JSONArray releases) throws JSONException {
        e.release = null;
        e.asset = null;
        e.sums = null;
        boolean pre = e.prerelease || Settings.includePrerelease(c);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optBoolean("draft")) continue;
            if (r.optBoolean("prerelease") && !pre) continue;
            AppEntry.Release rel = new AppEntry.Release();
            rel.tag = r.optString("tag_name");
            rel.name = r.optString("name", rel.tag);
            rel.body = r.optString("body", "");
            rel.htmlUrl = r.optString("html_url");
            rel.published = r.optString("published_at", "");
            rel.version = Versions.fromTag(rel.tag);
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                AppEntry.Asset as = new AppEntry.Asset();
                as.name = a.optString("name");
                as.url = a.optString("browser_download_url");
                as.size = a.optLong("size");
                rel.assets.add(as);
            }
            AppEntry.Asset best = bestApk(rel);
            if (best == null) continue;
            e.release = rel;
            e.asset = best;
            for (AppEntry.Asset a : rel.assets) {
                if (a.name.toUpperCase(Locale.ROOT).startsWith("SHA256SUMS")) e.sums = a;
            }
            return;
        }
    }

    /** Device ABI build first, then a universal/plain build. Builds for other ABIs never match. */
    static AppEntry.Asset bestApk(AppEntry.Release rel) {
        String[] supported = Build.SUPPORTED_ABIS;
        AppEntry.Asset best = null;
        int bestScore = -1;
        for (AppEntry.Asset a : rel.assets) {
            String n = a.name.toLowerCase(Locale.ROOT);
            if (!n.endsWith(".apk")) continue;
            String abi = abiOf(n);
            int score;
            if (abi == null) {
                score = 10;
            } else {
                int idx = indexOf(supported, abi);
                if (idx < 0) continue;
                score = 100 - idx;
            }
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    private static String abiOf(String name) {
        if (name.contains("x86_64")) return "x86_64";
        for (String abi : ABIS) {
            if (name.contains(abi)) return abi;
        }
        if (name.contains("arm64")) return "arm64-v8a";
        return null;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }
}
