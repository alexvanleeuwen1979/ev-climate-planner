package nl.axlproductions.kiaklimaatplanner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ClimateEnergy {
    static final double BATTERY_KWH = 64;
    static final double INITIAL_KW = 2;
    static final long HOUR = 60 * Schedule.MINUTE;
    private static final long FRESH = 2 * Schedule.MINUTE;
    private static final long MAX_GAP = 5 * Schedule.MINUTE;
    private static final long LEARNING_AGE = 90L * 24 * HOUR;
    private static final int MAX_READINGS = 1000;
    private static final int LEARNING_CYCLES = 60;

    enum End { STATUS_OFF, MANUAL, UNLOCKED, UNKNOWN }

    record Average(double kilowatts, int samples, long duration, boolean learned) {}

    private record Sample(long time, double percentage, double odometer, boolean parked) {
        static Sample read(JSONObject source) {
            double percentage = source.optDouble("percentage", -1);
            double odometer = source.optDouble("odometer", -1);
            boolean parked = Boolean.FALSE.equals(source.opt("plugged"))
                && Boolean.FALSE.equals(source.opt("charging"))
                && Boolean.FALSE.equals(source.opt("ignition")) && !source.optBoolean("inUse");
            return new Sample(source.optLong("time"), percentage, odometer, parked);
        }

        boolean usable() {
            return parked && time > 0 && percentage >= 0 && percentage <= 100 && odometer > 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("climate_energy", Context.MODE_PRIVATE);
    }

    static synchronized void begin(Context context, long request, long session, long start, long duration,
                                   JSONObject before) {
        var prefs = prefs(context);
        try {
            var pending = pending(prefs);
            if (pending.optLong("request") == request) return;
            if (pending.optLong("start") > 0) finish(context, pending.optLong("request"), start, End.UNKNOWN, null);
            var sample = Sample.read(before);
            boolean eligible = sample.usable() && Boolean.FALSE.equals(before.opt("climate"))
                && sample.time() <= request && request - sample.time() <= FRESH;
            boolean recentBefore = sample.time() > 0 && sample.time() <= request && request - sample.time() <= FRESH;
            boolean canAnchor = !eligible && (!recentBefore || sample.parked());
            var cycle = new JSONObject().put("request", request).put("session", session).put("start", start)
                .put("duration", duration).put("before", before).put("eligible", eligible).put("last", start)
                .put("canAnchor", canAnchor);
            prefs.edit().putString("active", cycle.toString()).apply();
        } catch (JSONException ignored) {
            // Meter data is optional and independent of the climate commands.
        }
    }

    static synchronized void observe(Context context, long request, JSONObject reading) {
        var prefs = prefs(context);
        var pending = pending(prefs);
        var sample = Sample.read(reading);
        long last = pending.optLong("last");
        if (pending.optLong("request", -1) != request || sample.time() <= last) return;
        try {
            var before = Sample.read(pending.getJSONObject("before"));
            boolean eligible = pending.optBoolean("eligible") && sample.usable()
                && sample.time() - last <= MAX_GAP && sample.odometer() == before.odometer()
                && sample.percentage() <= before.percentage();
            boolean active = Boolean.TRUE.equals(reading.opt("climate"));
            if (pending.optBoolean("canAnchor") && active && sample.usable()
                    && sample.time() - pending.optLong("start") <= 4 * Schedule.MINUTE) {
                pending.put("before", reading);
                eligible = true;
            }
            pending.put("last", sample.time()).put("eligible", eligible).put("canAnchor", false);
            if (active) pending.put("seenActive", true);
            prefs.edit().putString("active", pending.toString()).apply();
        } catch (JSONException ignored) {
            // Ignore incomplete telemetry.
        }
    }

    static synchronized void finish(Context context, long request, long end, End reason, JSONObject reading) {
        var prefs = prefs(context);
        var pending = pending(prefs);
        long start = pending.optLong("start");
        if (start == 0 || (request != 0 && pending.optLong("request") != request)) return;
        try {
            long elapsed = Math.max(0, Math.min(end - start, pending.optLong("duration")));
            if (elapsed == 0) {
                prefs.edit().remove("active").apply();
                return;
            }
            var before = Sample.read(pending.getJSONObject("before"));
            var after = reading == null ? null : Sample.read(reading);
            long calibrationDuration = Math.min(elapsed, end - Math.max(before.time(), start));
            boolean seenActive = pending.optBoolean("seenActive");
            boolean timingReliable = after != null && (seenActive
                ? after.time() > pending.optLong("last") && after.time() - pending.optLong("last") <= MAX_GAP
                : after.time() >= start + pending.optLong("duration")
                    && after.time() <= start + pending.optLong("duration") + 2 * Schedule.MINUTE);
            boolean eligible = reason == End.STATUS_OFF && after != null && pending.optBoolean("eligible")
                && timingReliable && after.usable() && after.odometer() == before.odometer()
                && end <= start + pending.optLong("duration") + MAX_GAP && calibrationDuration >= 10 * Schedule.MINUTE;
            double delta = after == null ? -1 : before.percentage() - after.percentage();
            double measuredEnergy = delta * BATTERY_KWH / 100;
            eligible &= delta >= 0 && measuredEnergy <= 10.0 * calibrationDuration / HOUR + BATTERY_KWH / 100;

            var history = records(prefs);
            var average = average(history, prefs.getLong("learnFrom", 0), end);
            double estimate = average.kilowatts() * elapsed / HOUR;
            var entry = new JSONObject().put("start", start).put("session", pending.optLong("session"))
                .put("end", end).put("elapsed", elapsed).put("estimate", estimate)
                .put("power", average.kilowatts()).put("learned", average.learned()).put("reason", reason.name())
                .put("eligible", eligible);
            if (before.percentage() >= 0 && before.percentage() <= 100) entry.put("before", before.percentage());
            if (after != null && after.percentage() >= 0 && after.percentage() <= 100) entry.put("after", after.percentage());
            if (eligible) entry.put("batteryChangeKwh", measuredEnergy).put("calibrationDuration", calibrationDuration);
            var saved = new JSONArray().put(entry);
            for (int index = 0; index < Math.min(history.length(), MAX_READINGS - 1); index++) {
                saved.put(history.get(index));
            }
            prefs.edit().remove("active").putString("history", saved.toString()).apply();
            EnergyHistory.captureCycle(context, entry);
        } catch (JSONException ignored) {
            prefs.edit().remove("active").apply();
        }
    }

    static synchronized void expire(Context context, long now) {
        var pending = pending(prefs(context));
        long start = pending.optLong("start");
        if (start > 0 && now - start > pending.optLong("duration") + 20 * Schedule.MINUTE) {
            finish(context, pending.optLong("request"), start + pending.optLong("duration"), End.UNKNOWN, null);
        }
    }

    static synchronized void resetLearning(Context context) {
        prefs(context).edit().remove("active").putLong("learnFrom", System.currentTimeMillis()).apply();
    }

    static synchronized JSONArray log(Context context) {
        return records(prefs(context));
    }

    static synchronized Average average(Context context) {
        var prefs = prefs(context);
        return average(records(prefs), prefs.getLong("learnFrom", 0), System.currentTimeMillis());
    }

    private static Average average(JSONArray history, long learnFrom, long now) {
        long duration = 0;
        double total = 0;
        int samples = 0;
        long oldest = Math.max(learnFrom, now - LEARNING_AGE);
        for (int index = 0; index < history.length() && samples < LEARNING_CYCLES; index++) {
            var record = history.optJSONObject(index);
            if (record == null || !record.optBoolean("eligible") || record.optLong("start") < oldest) continue;
            duration += record.optLong("calibrationDuration");
            total += record.optDouble("batteryChangeKwh", 0);
            samples++;
        }
        boolean learned = samples >= 5 && duration >= HOUR && total > 0;
        return new Average(learned ? total * HOUR / duration : INITIAL_KW, samples, duration, learned);
    }

    private static JSONObject pending(SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString("active", "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static JSONArray records(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("history", "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }
}
