package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;

final class KiaBackoff {
    private static final String UNTIL = "kiaStartLimitedUntil";
    private static final String COUNT = "kiaRateLimitCount";
    private static final String LAST = "kiaRateLimitLast";
    private static final String RESTORED = "kiaRateLimitRestored";
    private static final String MANUAL_NEXT = "kiaPausedManualRefreshNext";
    static final long HOUR = 60 * Schedule.MINUTE;

    static long until(SharedPreferences prefs) {
        return prefs.getLong(UNTIL, 0);
    }

    static boolean paused(Context context) {
        return until(Planner.prefs(context)) > System.currentTimeMillis();
    }

    static synchronized long reserveManualRefresh(SharedPreferences prefs, long now) {
        long next = prefs.getLong(MANUAL_NEXT, 0);
        if (next > now) return next;
        prefs.edit().putLong(MANUAL_NEXT, now + 5 * Schedule.MINUTE).apply();
        return 0;
    }

    static synchronized boolean resumeAfterStatus(SharedPreferences prefs, long expectedPause, long now) {
        if (expectedPause <= now || until(prefs) != expectedPause) return false;
        prefs.edit().remove(UNTIL).remove(MANUAL_NEXT).putLong("lastDashboardKiaRequest", now).apply();
        return true;
    }

    static synchronized long manualRefreshLimited(SharedPreferences prefs, long now) {
        long end = Math.max(until(prefs), now + HOUR);
        prefs.edit().putLong(UNTIL, end).putLong(MANUAL_NEXT, end).apply();
        return end;
    }

    static synchronized long record(Context context, long now) {
        var prefs = Planner.prefs(context);
        long current = until(prefs);
        if (current > now) return current;
        long last = prefs.getLong(LAST, 0);
        int count = last > 0 && now - last < 24 * HOUR ? Math.min(4, prefs.getInt(COUNT, 0) + 1) : 1;
        long end = now + (1L << (count - 1)) * HOUR;
        prefs.edit().putLong(UNTIL, end).putInt(COUNT, count).putLong(LAST, now).apply();
        return end;
    }

    static synchronized void restoreFromHistory(Context context, long now) {
        var prefs = Planner.prefs(context);
        if (prefs.getBoolean(RESTORED, false)) return;
        var editor = prefs.edit().putBoolean(RESTORED, true);
        try {
            var history = new JSONArray(prefs.getString("history", "[]"));
            for (int index = 0; index < history.length(); index++) {
                var row = history.optJSONObject(index);
                if (row == null) continue;
                long time = row.optLong("time");
                if (time < now - HOUR || time > now) continue;
                String message = row.optString("message");
                if (message.contains("RateLimitingError") || "LIMIT".equals(row.optString("cause"))
                        || message.contains("Kia meldt dat de startlimiet is bereikt")) {
                    if (time + HOUR > until(prefs)) {
                        editor.putLong(UNTIL, time + HOUR).putLong(LAST, time).putInt(COUNT, 1);
                    }
                    break;
                }
            }
        } catch (JSONException ignored) {}
        editor.apply();
    }

    static String message(long end) {
        return t(R.string.kia_connect_paused_until) + resumeLabel(end);
    }

    static String resumeLabel(long end) {
        var format = DateTimeFormatter.ofPattern("EEE HH:mm", AppLanguage.locale()).withZone(ZoneId.systemDefault());
        return format.format(Instant.ofEpochMilli(end));
    }
}
