package nl.axlproductions.kiaklimaatplanner;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;

record WeeklyAdjustment(long original, long start, long end) {
    static final String PAUSE = "pauseWeeklyUntil";
    static final String PAUSE_FROM = "pauseWeeklyFrom";
    static final String RESUMED = "lastWeeklyResume";

    static boolean excludes(SharedPreferences prefs, long start, long active) {
        if (start == active) return false;
        long until = prefs.getLong(PAUSE, 0);
        long from = prefs.getLong(PAUSE_FROM, 0);
        return start >= from && start < Math.max(until, prefs.getLong(RESUMED, 0));
    }
    static final String KEY = "weeklyAdjustments";

    static List<WeeklyAdjustment> read(SharedPreferences prefs) {
        var result = new ArrayList<WeeklyAdjustment>();
        for (String encoded : prefs.getStringSet(KEY, Collections.emptySet())) {
            String[] values = encoded.split(",");
            if (values.length == 3) result.add(new WeeklyAdjustment(Long.parseLong(values[0]),
                Long.parseLong(values[1]), Long.parseLong(values[2])));
        }
        return result;
    }

    static boolean replace(SharedPreferences prefs, long original, WeeklyAdjustment replacement, long now) {
        var edits = new HashSet<String>();
        for (var previous : read(prefs)) {
            if (previous.original != original && Math.max(previous.original, previous.end) >= now - Schedule.INTERVAL) {
                edits.add(previous.encode());
            }
        }
        if (replacement != null) edits.add(replacement.encode());
        return prefs.edit().putStringSet(KEY, edits).commit();
    }

    private String encode() {
        return original + "," + start + "," + end;
    }
}
