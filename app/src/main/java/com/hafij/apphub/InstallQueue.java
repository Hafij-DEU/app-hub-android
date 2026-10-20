package com.hafij.apphub;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and installs one APK at a time through a PackageInstaller session.
 *
 * <p>On Android 12+ the session asks for USER_ACTION_NOT_REQUIRED. The system then skips the
 * confirmation dialog for updates of apps that App Hub installed itself (it is their installer
 * of record) as long as they target a recent SDK. The first install of an app, or an update of
 * one installed by another installer, still gets one system dialog.
 */
final class InstallQueue {
    private InstallQueue() {}

    interface Listener {
        void onQueueChanged();
    }

    /** What the UI shows for an app while it is in the queue. */
    static final class Status {
        String text;
        int percent = -1;
        boolean active = true;
        String error;
    }

    static final Map<String, Status> status = new ConcurrentHashMap<>();
    private static final Map<Integer, CompletableFuture<String>> sessions = new ConcurrentHashMap<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;

    static void setListener(Listener l) {
        listener = l;
    }

    static boolean busy() {
        for (Status s : status.values()) if (s.active) return true;
        return false;
    }

    static boolean queued(String id) {
        Status s = status.get(id);
        return s != null && s.active;
    }

    /** Queues an install; returns at once. */
    static void enqueue(Context ctx, AppEntry e) {
        if (queued(e.id)) return;
        Status s = new Status();
        s.text = "Waiting…";
        status.put(e.id, s);
        changed();
        Context app = ctx.getApplicationContext();
        worker.execute(() -> run(app, e, s));
    }

    /** Runs the install on the calling thread; used by the background job. Returns an error or null. */
    static String installBlocking(Context ctx, AppEntry e) {
        Status s = new Status();
        status.put(e.id, s);
        try {
            return worker.submit(() -> run(ctx.getApplicationContext(), e, s)).get();
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    /** Called by InstallReceiver with the final result of a session. */
    static void finish(int sessionId, String error) {
        CompletableFuture<String> f = sessions.remove(sessionId);
        if (f != null) f.complete(error == null ? "" : error);
    }

    static void waitingForUser(String id) {
        Status s = status.get(id);
        if (s != null) {
            s.text = "Waiting for your confirmation";
            s.percent = -1;
            changed();
        }
    }

    private static String run(Context c, AppEntry e, Status s) {
        File dir = new File(c.getCacheDir(), "apk");
        dir.mkdirs();
        File apk = new File(dir, e.id + ".apk");
        String err;
        try {
            download(e.asset.url, apk, e.asset.size, s);
            if (e.sums != null) verify(c, e, apk, s);
            s.text = "Installing…";
            s.percent = -1;
            changed();
            PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null) throw new IOException("Downloaded file is not a valid APK");
            if (e.pkg != null && !e.pkg.equals(info.packageName)) {
                throw new IOException("APK is " + info.packageName + ", expected " + e.pkg);
            }
            if (e.pkg == null) {
                e.pkg = info.packageName;
                if (e.custom) Catalog.saveCustom(c, Catalog.loadWith(c, e));
            }
            err = install(c, e, apk);
        } catch (IOException ex) {
            err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        } finally {
            apk.delete();
        }
        if (err == null || err.isEmpty()) {
            status.remove(e.id);
            err = null;
        } else {
            s.active = false;
            s.error = err;
            s.text = err;
        }
        changed();
        return err;
    }

    private static void download(String url, File out, long size, Status s) throws IOException {
        s.text = "Downloading…";
        s.percent = 0;
        changed();
        HttpURLConnection con = GitHub.open(url);
        con.setReadTimeout(60000);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("Download failed: HTTP " + code);
            long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : size;
            try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int p = (int) (done * 100 / total);
                        if (p != last) {
                            last = p;
                            s.percent = p;
                            s.text = "Downloading " + p + "%";
                            changed();
                        }
                    }
                }
            }
        } finally {
            con.disconnect();
        }
    }

    /** Checks the APK against the release's SHA256SUMS when one is published. */
    private static void verify(Context c, AppEntry e, File apk, Status s) throws IOException {
        s.text = "Verifying…";
        changed();
        String sums = GitHub.readAll(GitHub.open(e.sums.url).getInputStream());
        String want = null;
        for (String line : sums.split("\n")) {
            String[] p = line.trim().split("\\s+\\*?", 2);
            if (p.length == 2 && p[1].trim().replaceFirst("^\\*", "").equals(e.asset.name)) {
                want = p[0].toLowerCase(Locale.ROOT);
            }
        }
        if (want == null) return;
        String got = sha256(apk);
        if (!want.equals(got)) throw new IOException("Checksum mismatch; download is corrupt");
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }

    private static String install(Context c, AppEntry e, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(e.pkg);
        params.setSize(apk.length());
        params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
        }
        int id = pi.createSession(params);
        CompletableFuture<String> result = new CompletableFuture<>();
        sessions.put(id, result);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(c, InstallReceiver.class)
                    .putExtra(InstallReceiver.EXTRA_APP_ID, e.id)
                    .putExtra(InstallReceiver.EXTRA_APP_NAME, e.name);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(c, id, cb, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException ex) {
            sessions.remove(id);
            try {
                pi.abandonSession(id);
            } catch (RuntimeException ignored) {
            }
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage(), ex);
        }
        try {
            String r = result.get(30, TimeUnit.MINUTES);
            return r.isEmpty() ? null : r;
        } catch (Exception ex) {
            sessions.remove(id);
            return "Install timed out";
        }
    }

    static void changed() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onQueueChanged();
        });
    }
}
