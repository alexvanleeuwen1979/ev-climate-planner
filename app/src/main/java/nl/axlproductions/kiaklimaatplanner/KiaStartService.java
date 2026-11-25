package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public final class KiaStartService extends Service {
    private static final String CHANNEL = "kia_start";
    private static final String MANUAL_START = "MANUAL_START";
    private static volatile boolean stopping;
    private final Handler handler = new Handler(Looper.getMainLooper());

    static void enqueue(Context context, Schedule.Slot slot, long wait) {
        enqueue(context, slot, wait, null);
    }

    static void enqueueManual(Context context, Schedule.Slot slot) {
        enqueue(context, slot, 0, MANUAL_START);
    }

    private static void enqueue(Context context, Schedule.Slot slot, long wait, String action) {
        Intent intent = new Intent(context, KiaStartService.class).setAction(action);
        if (slot != null) {
            VehicleWatch.putSlot(intent, slot).putExtra("wait", wait);
        }
        try {
            context.startForegroundService(intent);
        } catch (IllegalStateException | SecurityException error) {
            KiaCloud.startProblem(context, slot, 0, t(R.string.android_has_blocked_the_scheduled_background),
                StartLedger.Cause.ALARM);
        }
    }

    static void enqueueWatch(Context context, Schedule.Slot slot) {
        Intent intent = new Intent(context, KiaStartService.class).setAction(VehicleWatch.ACTION);
        try {
            context.startForegroundService(VehicleWatch.putSlot(intent, slot));
        } catch (IllegalStateException | SecurityException ignored) {
            // The next scheduled check can retry if Android denies this one.
        }
    }

    static boolean stopInProgress() { return stopping; }
    static void enqueueStop(Context context) {
        if (stopping) return;
        stopping = true;
        try {
            context.startForegroundService(new Intent(context, KiaStartService.class).setAction("STOP"));
        } catch (IllegalStateException | SecurityException error) {
            stopping = false;
            Planner.note(context, t(R.string.android_has_blocked_the_climate_command));
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, t(R.string.climate_start_2), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(t(R.string.connection_to_kia_connect))
            .setOngoing(true)
            .build());
        if (intent == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        boolean watching = VehicleWatch.ACTION.equals(intent.getAction());
        Schedule.Slot requested = VehicleWatch.readSlot(intent);
        Schedule.Slot slot = requested.time() == 0 ? null : requested;
        long wait = Math.max(0, intent.getLongExtra("wait", 0));
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KiaKlimaatplanner:klimaatstart");
        wake.acquire(Math.min(wait, 120_000) + 150_000);
        handler.postDelayed(() -> KiaCloud.WORKER.execute(() -> {
            try {
                if (watching && slot != null) KiaCloud.checkVehicleUse(this, slot);
                else if ("STOP".equals(intent.getAction())) KiaCloud.stop(this);
                else if (!watching && KiaCloud.linked(this)) KiaCloud.start(this, slot, MANUAL_START.equals(intent.getAction()));
            } finally {
                if ("STOP".equals(intent.getAction())) stopping = false;
                if (wake.isHeld()) wake.release();
                stopSelfResult(startId);
            }
        }), Math.min(wait, 120_000));
        return START_NOT_STICKY;
    }
}

