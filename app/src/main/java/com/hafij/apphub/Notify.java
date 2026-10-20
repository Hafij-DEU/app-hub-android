package com.hafij.apphub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

final class Notify {
    private Notify() {}

    private static final String CHANNEL = "updates";
    private static final int ID_UPDATES = 1;

    private static NotificationManager nm(Context c) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        return m;
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void updates(Context c, List<AppEntry> pending) {
        if (pending.isEmpty()) {
            nm(c).cancel(ID_UPDATES);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (AppEntry e : pending) {
            if (names.length() > 0) names.append(", ");
            names.append(e.name);
        }
        String title = pending.size() == 1 ? "1 update available" : pending.size() + " updates available";
        post(c, ID_UPDATES, title, names.toString(), openApp(c));
    }

    static void result(Context c, String title, String text) {
        post(c, ID_UPDATES, title, text, openApp(c));
    }

    static void confirm(Context c, int session, String name, PendingIntent pi) {
        post(c, 1000 + session, "Tap to update " + name, "Android needs your confirmation once", pi);
    }

    static void cancel(Context c, int session) {
        nm(c).cancel(1000 + session);
    }

    private static void post(Context c, int id, String title, String text, PendingIntent pi) {
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm(c).notify(id, n);
        } catch (SecurityException ignored) {
            // Notifications not allowed.
        }
    }
}
