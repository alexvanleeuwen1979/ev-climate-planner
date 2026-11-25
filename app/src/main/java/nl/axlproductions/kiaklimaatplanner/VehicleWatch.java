package nl.axlproductions.kiaklimaatplanner;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class VehicleWatch {
    static final String ACTION = "nl.axlproductions.kiaklimaatplanner.CHECK_VEHICLE";
    private static final int REQUEST_CODE = 5;
    private static final long AFTER_END = 15_000;
    private static final long RETRY = Schedule.MINUTE;
    private static final long MIN_DELAY = 10_000;

    private static long eligibleAt(long expected, int attempts, long last, long resume) {
        if (attempts >= 2 || expected <= 0) return 0;
        long target = attempts == 0 ? expected + AFTER_END : Math.max(expected + AFTER_END + RETRY, last + RETRY);
        return Math.max(target, resume);
    }

    static long due(long now, long expected, int attempts, long last, long resume) {
        long target = eligibleAt(expected, attempts, last, resume);
        return target == 0 ? 0 : Math.max(now + MIN_DELAY, target);
    }

    static void schedule(Context context) {
        var prefs = Planner.prefs(context);
        var slot = Planner.watchedSlot(context);
        var intent = new Intent(context, Planner.class).setAction(ACTION);
        var alarms = context.getSystemService(AlarmManager.class);
        long now = System.currentTimeMillis();
        long deadline = slot == null ? 0 : Planner.watchDeadline(prefs, slot.time(), slot.end());
        boolean sameCycle = slot != null && slot.time() == prefs.getLong("watchPollFor", 0);
        int attempts = sameCycle ? prefs.getInt("watchPollCount", 0) : 0;
        long last = sameCycle ? prefs.getLong("watchPollAt", 0) : 0;
        long next = due(now, Planner.cycleEnd(context), attempts, last, KiaBackoff.until(prefs));
        if (next == 0 || next >= deadline || !Planner.hasAlarmAccess(context) || !KiaCloud.linked(context)
                || KiaCloud.needsLink(context)) {
            var previous = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (previous != null) alarms.cancel(previous);
            return;
        }
        var pending = PendingIntent.getBroadcast(context, REQUEST_CODE, putSlot(intent, slot)
            .putExtra("watchDue", next).putExtra("watchAttempt", attempts),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            // The first check is at least 15 minutes after the preceding start.
            // Leave the shorter retry outside Android's allow-while-idle budget.
            if (attempts == 0) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
            else alarms.setExact(AlarmManager.RTC_WAKEUP, next, pending);
        } catch (SecurityException ignored) {
            // Access can change between checking it and scheduling.
        }
    }

    record Claim(boolean allowed, long nextTime) {}

    static synchronized Claim claim(Context context, Schedule.Slot slot, long now, int expectedAttempt) {
        if (!slot.equals(Planner.watchedSlot(context))) return new Claim(false, 0);
        var prefs = Planner.prefs(context);
        boolean sameCycle = prefs.getLong("watchPollFor", 0) == slot.time();
        int attempts = sameCycle ? prefs.getInt("watchPollCount", 0) : 0;
        long last = sameCycle ? prefs.getLong("watchPollAt", 0) : 0;
        long target = eligibleAt(Planner.cycleEnd(context), attempts, last, KiaBackoff.until(prefs));
        if (target <= 0 || target >= Planner.watchDeadline(prefs, slot.time(), slot.end())) return new Claim(false, 0);
        if (now < target || (expectedAttempt >= 0 && attempts != expectedAttempt)) return new Claim(false, target);
        prefs.edit().putLong("watchPollFor", slot.time()).putInt("watchPollCount", attempts + 1)
            .putLong("watchPollAt", now).apply();
        return new Claim(true, 0);
    }

    static void receive(Context context, Intent intent) {
        var slot = readSlot(intent);
        if (!slot.equals(Planner.watchedSlot(context))) return;
        long now = System.currentTimeMillis();
        int attempt = intent.getIntExtra("watchAttempt", -1);
        if (attempt < 0 || now < intent.getLongExtra("watchDue", Long.MAX_VALUE)) {
            schedule(context);
            return;
        }
        var claimed = claim(context, slot, now, attempt);
        schedule(context);
        if (claimed.allowed()) KiaStartService.enqueueWatch(context, slot);
    }

    static Intent putSlot(Intent intent, Schedule.Slot slot) {
        return intent.putExtra("time", slot.time()).putExtra("session", slot.session())
            .putExtra("end", slot.end());
    }

    static Schedule.Slot readSlot(Intent intent) {
        return new Schedule.Slot(intent.getLongExtra("time", 0),
            intent.getLongExtra("session", 0), intent.getLongExtra("end", 0));
    }
}
