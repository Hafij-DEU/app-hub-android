package com.hafij.apphub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Periodic background check; optionally installs the updates it finds. */
public final class UpdateJob extends JobService {
    private static final int JOB_ID = 42;
    private static final long PERIOD = 6 * 60 * 60 * 1000L;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (MainActivity.visible || InstallQueue.busy()) return false;
        new Thread(() -> {
            run(this);
            jobFinished(params, false);
        }, "update-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private static void run(Context c) {
        List<AppEntry> apps = Catalog.load(c);
        Updates.checkAll(c, apps);
        List<AppEntry> pending = new ArrayList<>();
        AppEntry self = null;
        for (AppEntry e : apps) {
            if (!e.hasUpdate()) continue;
            if (e.self) self = e;
            else pending.add(e);
        }
        if (self != null) pending.add(self); // last: updating App Hub ends this process

        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        boolean auto = Settings.autoInstall(c) && Build.VERSION.SDK_INT >= 31
                && cm != null && !cm.isActiveNetworkMetered();
        if (!auto || pending.isEmpty()) {
            Notify.updates(c, pending);
            return;
        }
        List<String> done = new ArrayList<>(), failed = new ArrayList<>();
        for (AppEntry e : pending) {
            if (e.self) {
                Notify.result(c, "Updated " + done.size() + " apps", "Now updating App Hub itself");
            }
            String err = InstallQueue.installBlocking(c, e);
            if (err == null) done.add(e.name + " " + e.release.version);
            else failed.add(e.name + ": " + err);
            InstallQueue.status.remove(e.id);
        }
        StringBuilder text = new StringBuilder(String.join(", ", done));
        if (!failed.isEmpty()) {
            if (text.length() > 0) text.append("\n");
            text.append(String.join("\n", failed));
        }
        Notify.result(c, failed.isEmpty() ? "Apps updated" : "Some updates need attention", text.toString());
    }
}
