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

/**
 * Small GitHub REST client. Every answer is cached with its ETag: a 304 does not count
 * against the rate limit, and a cached answer is reused (marked stale) when offline or limited.
 */
final class GitHub {
    private GitHub() {}

    static final class Response {
        final String body;
        final boolean stale;

        Response(String body, boolean stale) {
            this.body = body;
            this.stale = stale;
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static Response releases(Context c, String repo) throws IOException {
        return get(c, "https://api.github.com/repos/" + repo + "/releases?per_page=20", true);
    }

    static Response get(Context c, String url, boolean api) throws IOException {
        File dir = new File(c.getCacheDir(), "http");
        dir.mkdirs();
        String key = Integer.toHexString(url.hashCode());
        File body = new File(dir, key + ".body"), etag = new File(dir, key + ".etag");

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
            if (code == 304) return new Response(read(body), false);
            if (code == 200) {
                String text = readAll(con.getInputStream());
                write(body, text);
                String tag = con.getHeaderField("ETag");
                if (tag != null) write(etag, tag);
                else etag.delete();
                return new Response(text, false);
            }
            String msg;
            if ((code == 403 || code == 429) && "0".equals(con.getHeaderField("X-RateLimit-Remaining"))) {
                msg = "GitHub rate limit reached (add a token in Settings)";
            } else if (code == 404) {
                msg = "Repository or releases not found";
            } else {
                msg = "HTTP " + code;
            }
            if (body.exists() && code != 404) return new Response(read(body), true);
            throw new HttpError(code, msg);
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            if (body.exists()) return new Response(read(body), true);
            throw new IOException("Offline: " + e.getMessage(), e);
        } finally {
            if (con != null) con.disconnect();
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
