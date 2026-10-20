package com.hafij.apphub;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives PackageInstaller session results. */
public final class InstallReceiver extends BroadcastReceiver {
    static final String EXTRA_APP_ID = "com.hafij.apphub.APP_ID";
    static final String EXTRA_APP_NAME = "com.hafij.apphub.APP_NAME";

    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        String id = intent.getStringExtra(EXTRA_APP_ID);
        String name = intent.getStringExtra(EXTRA_APP_NAME);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                InstallQueue.finish(session, "Installer gave no confirmation screen");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            InstallQueue.waitingForUser(id);
            if (MainActivity.visible) {
                c.startActivity(confirm);
            } else {
                PendingIntent pi = PendingIntent.getActivity(c, session, confirm,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notify.confirm(c, session, name, pi);
                // Don't hold the queue: the session stays open until the notification is tapped.
                InstallQueue.finish(session, "Needs your OK: tap the notification");
            }
            return;
        }

        Notify.cancel(c, session);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            InstallQueue.finish(session, null);
        } else {
            InstallQueue.finish(session, describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }

    private static String describe(int status, String msg) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Install cancelled";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "Install blocked by the system";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "Signed with a different key than the installed app; uninstall it first";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "Not compatible with this device";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough storage";
            default:
                return msg != null ? msg : "Install failed";
        }
    }
}
