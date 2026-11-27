package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class ExtensionReminder {
    private static final String REMIND = "nl.axlproductions.kiaklimaatplanner.EXTENSION_REMINDER";
    private static final String EXTEND = "nl.axlproductions.kiaklimaatplanner.EXTEND_WINDOW";
    private static final String CHANNEL = "climate_extension_reminders";
    private static final int ID = 73;

    static boolean eligible(long session, long end, long cycleEnd, long confirmed,
                            int confirmedCycles, boolean stopped, long now) {
        return session > 0 && end > session && confirmed > 0 && cycleEnd > now
            && confirmedCycles >= Schedule.cycleCount(session, end) && !stopped;
    }

    private static boolean active(Context context) {
        var p = Planner.prefs(context);
        return p.getBoolean("notifyExtension", true)
            && p.getLong("confirmedStart", 0) >= p.getLong("lastStart", 0)
            && eligible(p.getLong("activeSession", 0), p.getLong("activeEnd", 0),
                p.getLong("cycleEnd", 0), p.getLong("confirmedStart", 0),
                p.getInt("activeConfirmedCycles", 0), Planner.manuallyStopped(p),
                System.currentTimeMillis());
    }

    private static String token(Context context) {
        var p = Planner.prefs(context);
        return p.getLong("activeSession", 0) + "/" + p.getLong("activeEnd", 0)
            + "/" + p.getLong("cycleEnd", 0);
    }

    static void schedule(Context context) {
        var alarms = context.getSystemService(AlarmManager.class);
        var intent = new Intent(context, Planner.class).setAction(REMIND);
        var pending = PendingIntent.getBroadcast(context, ID, intent,
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) alarms.cancel(pending);
        if (!active(context)) {
            context.getSystemService(NotificationManager.class).cancel(ID);
            return;
        }
        var p = Planner.prefs(context);
        String token = token(context);
        if (token.equals(p.getString("extensionReminderShown", ""))) return;
        context.getSystemService(NotificationManager.class).cancel(ID);
        long due = p.getLong("cycleEnd", 0) - 5 * Schedule.MINUTE;
        if (due <= System.currentTimeMillis()) {
            show(context, token);
            return;
        }
        if (!Planner.hasAlarmAccess(context)) return;
        intent.putExtra("token", token);
        pending = PendingIntent.getBroadcast(context, ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending);
        } catch (SecurityException ignored) {
            // Android alarm access can be revoked after the check.
        }
    }

    static boolean receive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!REMIND.equals(action) && !EXTEND.equals(action)) return false;
        synchronized (Planner.class) {
            String expected = intent.getStringExtra("token");
            if (!active(context) || !token(context).equals(expected)) return true;
            if (EXTEND.equals(action)) {
                Planner.extend(context, intent.getIntExtra("minutes", 0));
            } else if (Planner.prefs(context).getLong("cycleEnd", 0) - System.currentTimeMillis()
                    <= 5 * Schedule.MINUTE) {
                show(context, expected);
            }
        }
        return true;
    }

    private static void show(Context context, String token) {
        if (!WindowAlerts.permitted(context)) return;
        var p = Planner.prefs(context);
        if (token.equals(p.getString("extensionReminderShown", ""))) return;
        var manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            t(R.string.extension_reminder_setting), NotificationManager.IMPORTANCE_HIGH));
        var open = PendingIntent.getActivity(context, ID, new Intent(context, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        var notification = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ui_bell)
            .setContentTitle(t(R.string.extension_reminder_title))
            .setContentText(t(R.string.extension_reminder_body))
            .setContentIntent(open)
            .setLocalOnly(false)
            .setAutoCancel(true)
            .setTimeoutAfter(Math.max(1, p.getLong("cycleEnd", 0) - System.currentTimeMillis()));
        var wearable = new Notification.WearableExtender();
        for (int minutes : new int[]{15, 30, 45}) {
            var intent = new Intent(context, Planner.class).setAction(EXTEND)
                .setData(Uri.parse("kia-planner://extend/" + token + "/" + minutes))
                .putExtra("token", token).putExtra("minutes", minutes);
            var operation = PendingIntent.getBroadcast(context, minutes, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            var action = new Notification.Action.Builder(null, "+" + minutes + " min", operation).build();
            notification.addAction(action);
            wearable.addAction(action);
        }
        notification.extend(wearable);
        try {
            manager.notify(ID, notification.build());
            p.edit().putString("extensionReminderShown", token).apply();
        } catch (SecurityException ignored) {
            // Notification permission can change while constructing the reminder.
        }
    }

    private ExtensionReminder() {}
}
