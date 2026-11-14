package com.hafij.apphub;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Date;

/**
 * Small GitHub REST client. Every answer is cached with its ETag: a 304 does not count
 * against the rate limit, and a cached answer is reused (marked stale) when offline or limited.
 * Once the API's hourly limit is used up, releases are read from github.com's own pages
 * (WebReleases) until the limit resets.
 */
final class GitHub {
    private GitHub() {}

    private static final String LIMIT_MSG = "GitHub rate limit reached (add a token in Settings)";
    private static final String OFFLINE_NOTE = "Offline: showing last known release";

    /** When the API's rate limit resets (ms since epoch); 0 while not limited. */
    private static volatile long limitedUntil;

    static final class Response {
        final String body;
        /** Why a cached answer was used instead of a fresh one; null when the answer is fresh. */
        final String staleNote;

        Response(String body, String staleNote) {
            this.body = body;
            this.staleNote = staleNote;
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static Response releases(Context c, String repo, boolean includePre) throws IOException {
        String url = "https://api.github.com/repos/" + repo + "/releases?per_page=20";
        IOException apiError = null;
        if (!limited()) {
            try {
                Response r = get(c, url, true);
                if (r.staleNote == null || !limited()) return r;
            } catch (IOException e) {
                if (!limited()) throw e;
                apiError = e;
            }
        }
        // The API limit is used up: github.com's own release pages do not count against it.
        try {
            String json = WebReleases.fetch(repo, includePre);
            // Keep it as the last known answer. Without its ETag the next API call asks afresh.
            write(cacheFile(c, url, ".body"), json);
            cacheFile(c, url, ".etag").delete();
            return new Response(json, null);
        } catch (IOException e) {
            boolean answered = e instanceof HttpError || e instanceof WebReleases.Unusable;
            String cached = cachedBody(c, url);
            if (cached != null) return new Response(cached, answered ? limitNote() : OFFLINE_NOTE);
            if (!answered) throw new IOException("Offline: " + e.getMessage(), e);
            throw apiError != null ? apiError : new HttpError(403, LIMIT_MSG);
        }
    }

    static Response get(Context c, String url, boolean api) throws IOException {
        File body = cacheFile(c, url, ".body"), etag = cacheFile(c, url, ".etag");

        HttpURLConnection con = null;
        try {
            con = open(url);
            if (api) {
                con.setRequestProperty("Accept", "application/vnd.github+json");
                String token = Settings.token(c);
                if (!token.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body.exists() && etag.exists()) {
                con.setRequestProperty("If-None-Match", read(etag));
            }
            int code = con.getResponseCode();
            if (code == 304) return new Response(read(body), null);
            if (code == 200) {
                String text = readAll(con.getInputStream());
                write(body, text);
                String tag = con.getHeaderField("ETag");
                if (tag != null) write(etag, tag);
                else etag.delete();
                return new Response(text, null);
            }
            String msg, note;
            if (api && rateLimited(con, code)) {
                msg = LIMIT_MSG;
                note = limitNote();
            } else if (code == 404) {
                msg = "Repository or releases not found";
                note = null;
            } else {
                msg = "HTTP " + code;
                note = "GitHub answered HTTP " + code + ": showing last known release";
            }
            if (body.exists() && code != 404) return new Response(read(body), note);
            throw new HttpError(code, msg);
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            if (body.exists()) return new Response(read(body), OFFLINE_NOTE);
            throw new IOException("Offline: " + e.getMessage(), e);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Notes the reset time when GitHub refused the request for its rate limit. */
    private static boolean rateLimited(HttpURLConnection con, int code) {
        if (code != 403 && code != 429) return false;
        long now = System.currentTimeMillis();
        String retry = con.getHeaderField("Retry-After");
        if ("0".equals(con.getHeaderField("X-RateLimit-Remaining"))) {
            long reset = parseLong(con.getHeaderField("X-RateLimit-Reset"));
            limitedUntil = reset > 0 ? reset * 1000 : now + 60_000;
        } else if (retry != null) {
            limitedUntil = now + Math.max(1, parseLong(retry)) * 1000;
        } else {
            return false;
        }
        return true;
    }

    /** Forgets a remembered limit, e.g. after the token changed. */
    static void resetLimit() {
        limitedUntil = 0;
    }

    /** The hourly limit GitHub grants this token. Asking for it does not count against it. */
    static long tokenLimit(String token) throws IOException {
        HttpURLConnection con = open("https://api.github.com/rate_limit");
        try {
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("Authorization", "Bearer " + token);
            int code = con.getResponseCode();
            if (code != 200) throw new HttpError(code, "HTTP " + code);
            return parseLong(con.getHeaderField("X-RateLimit-Limit"));
        } finally {
            con.disconnect();
        }
    }

    private static boolean limited() {
        return System.currentTimeMillis() < limitedUntil;
    }

    private static String limitNote() {
        String until = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(limitedUntil));
        return "GitHub limit reached: showing last known release (resets " + until + ")";
    }

    private static String cachedBody(Context c, String url) {
        File body = cacheFile(c, url, ".body");
        try {
            return body.exists() ? read(body) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static File cacheFile(Context c, String url, String ext) {
        File dir = new File(c.getCacheDir(), "http");
        dir.mkdirs();
        return new File(dir, Integer.toHexString(url.hashCode()) + ext);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "AppHub-Android/" + BuildConfig.VERSION_NAME);
        return con;
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
