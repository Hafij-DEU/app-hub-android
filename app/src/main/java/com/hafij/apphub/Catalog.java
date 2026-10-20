package com.hafij.apphub;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app list: the bundled catalog, overridden by the catalog in the App Hub repo
 * (so new apps show up without an App Hub release), plus apps the user added.
 */
final class Catalog {
    private Catalog() {}

    static final String REMOTE =
            "https://raw.githubusercontent.com/Hafij-DEU/app-hub-android/main/catalog.json";

    /** Bundled and cached catalogs only; never touches the network. */
    static List<AppEntry> load(Context c) {
        Map<String, AppEntry> apps = new LinkedHashMap<>();
        try (InputStream in = c.getAssets().open("catalog.json")) {
            parse(GitHub.readAll(in), apps, false);
        } catch (IOException | JSONException ignored) {
        }
        File remote = remoteCache(c);
        if (remote.exists()) {
            try {
                parse(GitHub.read(remote), apps, false);
            } catch (IOException | JSONException ignored) {
            }
        }
        File custom = customFile(c);
        if (custom.exists()) {
            try {
                parse(GitHub.read(custom), apps, true);
            } catch (IOException | JSONException ignored) {
            }
        }
        List<AppEntry> list = new ArrayList<>(apps.values());
        for (AppEntry e : list) {
            if (e.self) e.pkg = c.getPackageName();
        }
        return list;
    }

    /** Fetches the repo catalog into the cache. Failures keep the previous copy. */
    static void refreshRemote(Context c) {
        try {
            GitHub.Response r = GitHub.get(c, REMOTE, false);
            JSONObject o = new JSONObject(r.body);
            if (o.has("apps")) GitHub.write(remoteCache(c), r.body);
        } catch (IOException | JSONException ignored) {
        }
    }

    private static void parse(String json, Map<String, AppEntry> into, boolean custom)
            throws JSONException {
        JSONArray arr = new JSONObject(json).getJSONArray("apps");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            AppEntry e = new AppEntry();
            e.id = o.getString("id");
            e.repo = o.getString("repo");
            e.name = o.optString("name", e.repo.substring(e.repo.indexOf('/') + 1));
            e.pkg = o.optString("package", null);
            if (e.pkg != null && e.pkg.isEmpty()) e.pkg = null;
            e.icon = o.optString("icon", null);
            e.prerelease = o.optBoolean("prerelease", false);
            e.self = o.optBoolean("self", false);
            e.custom = custom;
            if (custom && into.containsKey(e.id)) continue;
            into.put(e.id, e);
        }
    }

    /** The current list with {@code changed} swapped in, for saving one updated entry. */
    static List<AppEntry> loadWith(Context c, AppEntry changed) {
        List<AppEntry> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(changed.id)) list.set(i, changed);
        }
        return list;
    }

    static void saveCustom(Context c, List<AppEntry> all) throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (AppEntry e : all) {
                if (!e.custom) continue;
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("name", e.name);
                o.put("repo", e.repo);
                if (e.pkg != null) o.put("package", e.pkg);
                if (e.prerelease) o.put("prerelease", true);
                arr.put(o);
            }
            GitHub.write(customFile(c), new JSONObject().put("apps", arr).toString(2));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Accepts "owner/repo", "github.com/owner/repo" or a full URL. Returns null if invalid. */
    static String parseRepo(String text) {
        if (text == null) return null;
        String s = text.trim();
        s = s.replaceFirst("^(https?://)?(www\\.)?github\\.com/", "");
        s = s.replaceAll("\\.git$", "").replaceAll("/+$", "");
        String[] parts = s.split("/");
        if (parts.length < 2) return null;
        String owner = parts[0], name = parts[1];
        if (!owner.matches("[A-Za-z0-9-]+") || !name.matches("[A-Za-z0-9._-]+")) return null;
        return owner + "/" + name;
    }

    private static File remoteCache(Context c) {
        return new File(c.getFilesDir(), "catalog-remote.json");
    }

    private static File customFile(Context c) {
        return new File(c.getFilesDir(), "custom.json");
    }
}
