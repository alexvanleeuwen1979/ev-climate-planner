package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

final class WindowAlerts {
    static final String END = "nl.axlproductions.kiaklimaatplanner.WINDOW_END";
    private static final String CHANNEL = "climate_planner_alerts";

    static void schedule(Context context, long session, long end, boolean manual) {
        schedule(context, session, end, manual, false);
    }

    static void scheduleOnce(Context context, long session, long end) {
        schedule(context, session, end, false, true);
    }

    private static void schedule(Context context, long session, long end, boolean manual, boolean once) {
        scheduleAt(context, session, end, end, manual, once);
    }

    static void scheduleAt(Context context, long session, long end, long due, boolean manual, boolean once) {
        if (due <= System.currentTimeMillis() || !Planner.hasAlarmAccess(context)) return;
        var intent = new Intent(context, Planner.class).setAction(END)
            .setData(Uri.parse("kia-planner://window-end/" + (once ? "once" : Boolean.toString(manual)) + "/" + end + "/" + due))
            .putExtra("session", session).putExtra("end", end).putExtra("manual", manual).putExtra("once", once);
        var operation = PendingIntent.getBroadcast(context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            context.getSystemService(AlarmManager.class)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, operation);
        } catch (SecurityException ignored) {
            Planner.note(context, t(R.string.android_does_not_have_permission_for));
        }
    }

    static boolean permitted(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED)
            && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static void show(Context context, int id, String title, String message) {
        show(context, id, title, message, null);
    }

    static void failed(Context context, String message, StartLedger.Cause cause) {
        show(context, 24, t(R.string.climate_start_not_confirmed), message, cause);
    }

    static void failed(Context context, int id, String title, String message, StartLedger.Cause cause) {
        show(context, id, title, message, cause);
    }

    private static void show(Context context, int id, String title, String message, StartLedger.Cause cause) {
        if (!permitted(context)) return;
        var manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL, t(R.string.time_slots_and_starting_limit), NotificationManager.IMPORTANCE_HIGH));
        var open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        try {
            var notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true);
            if (cause != null) {
                notification.addAction(new Notification.Action.Builder(null, t(R.string.try_again),
                    openAction(context, MainActivity.OPEN_RETRY, 61)).build());
                String label = switch (cause) {
                    case AUTH -> t(R.string.reconnect_kia);
                    case ALARM -> t(R.string.allow_alarms);
                    default -> t(R.string.check_connection);
                };
                notification.addAction(new Notification.Action.Builder(null, label,
                    openAction(context, MainActivity.OPEN_RECOVERY, 62)).build());
            }
            manager.notify(id, notification.build());
        } catch (SecurityException ignored) {
            // Permission may have changed while preparing the notification.
        }
    }
    private static PendingIntent openAction(Context context, String action, int request) {
        return PendingIntent.getActivity(context, request, new Intent(context, MainActivity.class).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
