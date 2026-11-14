package com.hafij.apphub;

import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads releases from github.com's own pages, used when the REST API's hourly limit is used up
 * (those pages do not count against it). The Atom feed lists the newest releases, the
 * /releases/latest redirect names the newest stable one, and each release's asset list comes
 * from the expanded_assets fragment its page loads. The answer has the REST API's JSON shape,
 * so Updates reads it the same way.
 */
final class WebReleases {
    private WebReleases() {}

    private static final String WEB = "https://github.com/";
    private static final Pattern SIZE =
            Pattern.compile(">\\s*([0-9][0-9.,]*)\\s*(Bytes|B|KB|MB|GB)\\s*</span>");

    /** github.com answered, but with nothing App Hub can use (unreadable, or no APK). */
    static final class Unusable extends IOException {
        Unusable(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private static final class Entry {
        String tag;
        String tagPath;
        String title;
        String updated;
        String body = "";
        String htmlUrl;
    }

    /**
     * Releases in feed order; assets are fetched for each stable one whose version beats the
     * best APK release seen so far (the feed is not strictly by version). Releases
     * newer than /releases/latest are pre-releases; the feed does not mark older ones, so they
     * are taken as stable. Assets are only fetched for releases Updates would look at. Throws
     * when none of the feed's releases has a usable APK.
     */
    static String fetch(String repo, boolean includePre) throws IOException {
        List<Entry> entries = feed(repo);
        String latest = includePre ? null : latestTagPath(repo);
        boolean stable = false, found = false;
        String bestVersion = null;
        JSONArray out = new JSONArray();
        try {
            for (Entry en : entries) {
                if (latest != null && en.tagPath.equals(latest)) stable = true;
                boolean pre = !includePre && !stable;
                JSONObject r = new JSONObject();
                r.put("tag_name", en.tag);
                r.put("name", en.title.isEmpty() ? en.tag : en.title);
                r.put("body", en.body);
                r.put("html_url", en.htmlUrl);
                r.put("published_at", en.updated);
                r.put("prerelease", pre);
                out.put(r);
                if (pre) continue;
                // Only a higher version than the best APK so far can change Updates' pick.
                String v = Versions.fromTag(en.tag);
                if (bestVersion != null && !Versions.newer(v, bestVersion)) continue;
                AppEntry.Release rel = assets(repo, en);
                JSONArray arr = new JSONArray();
                for (AppEntry.Asset a : rel.assets) {
                    arr.put(new JSONObject()
                            .put("name", a.name)
                            .put("browser_download_url", a.url)
                            .put("size", a.size));
                }
                r.put("assets", arr);
                if (Updates.bestApk(rel) != null) {
                    found = true;
                    bestVersion = v;
                }
            }
        } catch (JSONException e) {
            throw new Unusable("Could not read github.com releases", e);
        }
        // The feed holds only the newest releases; an older APK is left to the cached API answer.
        if (!found) throw new Unusable("No APK in the newest github.com releases", null);
        return out.toString();
    }

    private static List<Entry> feed(String repo) throws IOException {
        String xml = get(WEB + repo + "/releases.atom");
        List<Entry> list = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            Entry cur = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("entry".equals(n)) {
                        cur = new Entry();
                    } else if (cur == null) {
                        continue;
                    } else if ("link".equals(n)) {
                        String href = p.getAttributeValue(null, "href");
                        int at = href == null ? -1 : href.indexOf("/releases/tag/");
                        if (at >= 0) {
                            cur.htmlUrl = href;
                            cur.tagPath = href.substring(at + "/releases/tag/".length());
                            cur.tag = URLDecoder.decode(cur.tagPath, "UTF-8");
                        }
                    } else if ("title".equals(n)) {
                        cur.title = p.nextText().trim();
                    } else if ("updated".equals(n)) {
                        cur.updated = p.nextText().trim();
                    } else if ("content".equals(n)) {
                        cur.body = Html.fromHtml(p.nextText(), Html.FROM_HTML_MODE_COMPACT)
                                .toString().trim();
                    }
                } else if (ev == XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                    if (cur != null && cur.tagPath != null) {
                        if (cur.title == null) cur.title = "";
                        if (cur.updated == null) cur.updated = "";
                        list.add(cur);
                    }
                    cur = null;
                }
            }
        } catch (XmlPullParserException e) {
            throw new Unusable("Could not read the github.com release feed", e);
        }
        return list;
    }

    /** Tag path of the newest stable release, or null when the repo has none. */
    private static String latestTagPath(String repo) throws IOException {
        HttpURLConnection con = GitHub.open(WEB + repo + "/releases/latest");
        try {
            con.setInstanceFollowRedirects(false);
            int code = con.getResponseCode();
            String loc = con.getHeaderField("Location");
            if (code / 100 != 3 || loc == null) return null;
            int at = loc.indexOf("/releases/tag/");
            return at < 0 ? null : loc.substring(at + "/releases/tag/".length());
        } finally {
            con.disconnect();
        }
    }

    private static AppEntry.Release assets(String repo, Entry en) throws IOException {
        String html = get(WEB + repo + "/releases/expanded_assets/" + en.tagPath);
        String prefix = "href=\"/" + repo + "/releases/download/" + en.tagPath + "/";
        AppEntry.Release rel = new AppEntry.Release();
        int from = 0;
        while (true) {
            int at = html.indexOf(prefix, from);
            if (at < 0) break;
            int start = at + prefix.length();
            int end = html.indexOf('"', start);
            if (end < 0) break;
            int next = html.indexOf(prefix, end);
            AppEntry.Asset a = new AppEntry.Asset();
            String path = html.substring(start, end).replace("&amp;", "&");
            a.name = URLDecoder.decode(path, "UTF-8");
            a.url = WEB + repo + "/releases/download/" + en.tagPath + "/" + path;
            Matcher m = SIZE.matcher(html);
            m.region(end, next < 0 ? html.length() : next);
            if (m.find()) a.size = bytes(m.group(1), m.group(2));
            rel.assets.add(a);
            from = end;
        }
        return rel;
    }

    /** Page sizes are rounded ("86.1 MB"); good enough for a progress bar. */
    private static long bytes(String num, String unit) {
        try {
            double v = Double.parseDouble(num.replace(",", ""));
            switch (unit.toUpperCase(Locale.ROOT)) {
                case "GB": return (long) (v * 1024 * 1024 * 1024);
                case "MB": return (long) (v * 1024 * 1024);
                case "KB": return (long) (v * 1024);
                default: return (long) v;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = GitHub.open(url);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new GitHub.HttpError(code, "github.com answered HTTP " + code);
            return GitHub.readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }
}
