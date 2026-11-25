package nl.axlproductions.kiaklimaatplanner;

import android.content.SharedPreferences;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PlanningBackup {
    static final int MAX_BYTES = 32768;

    static JSONObject export(SharedPreferences prefs) throws JSONException {
        var days = new JSONArray();
        for (int day = 0; day < 7; day++) {
            var slots = new JSONArray();
            for (int index = 0; index < 2; index++) {
                slots.put(new JSONObject().put("clock", Planner.startMinute(prefs, day, index))
                    .put("duration", Planner.duration(prefs, day, index))
                    .put("enabled", Planner.windowEnabled(prefs, day, index))
                    .put("heat", Planner.windowHeat(prefs, day, index))
                    .put("profile", prefs.getInt(ClimateProfiles.windowKey(day, index), -1))
                    .put("ready", prefs.getBoolean(ClimateProfiles.readyKey(day, index), false))
                    .put("homeOnly", prefs.getBoolean("homeOnly" + day + "_" + index, false)));
            }
            days.put(new JSONObject().put("enabled", Planner.dayEnabled(prefs, day)).put("slots", slots));
        }
        var profiles = new JSONArray();
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            var profile = ClimateProfiles.get(prefs, index);
            profiles.put(new JSONObject().put("name", ClimateProfiles.backupName(index, profile)).put("temperature", profile.temperature())
                .put("heat", profile.heat()).put("threshold", profile.threshold()));
        }
        return new JSONObject().put("format", "Kia Climate Control").put("version", 1)
            .put("enabled", prefs.getBoolean("enabled", false)).put("days", days).put("profiles", profiles)
            .put("defaults", new JSONObject().put("profile", ClimateProfiles.general(prefs))
                .put("temperature", Planner.temperatureHalves(prefs))
                .put("heat", Planner.defaultHeat(prefs)).put("threshold",
                    prefs.getInt("windshieldThreshold", ClimateProfiles.DEFAULT_THRESHOLD)));
    }

    static SharedPreferences.Editor restore(SharedPreferences prefs, JSONObject backup) throws JSONException {
        if (!"Kia Climate Control".equals(backup.optString("format")) || backup.optInt("version") != 1)
            throw new JSONException("Unsupported backup");
        var days = backup.getJSONArray("days");
        var profiles = backup.getJSONArray("profiles");
        if (days.length() != 7 || profiles.length() != ClimateProfiles.COUNT) throw new JSONException("Invalid counts");
        var editor = prefs.edit().putBoolean("enabled", backup.getBoolean("enabled"))
            .remove(WeeklyAdjustment.KEY).remove(WeeklyAdjustment.PAUSE)
            .remove(WeeklyAdjustment.PAUSE_FROM).remove(WeeklyAdjustment.RESUMED);
        var windows = new ArrayList<Schedule.Window>();
        for (int day = 0; day < 7; day++) {
            var data = days.getJSONObject(day);
            boolean enabled = data.getBoolean("enabled");
            editor.putBoolean("dayEnabled" + day, enabled);
            var slots = data.getJSONArray("slots");
            if (slots.length() != 2) throw new JSONException("Invalid slots");
            for (int index = 0; index < 2; index++) {
                var slot = slots.getJSONObject(index);
                int clock = range(slot, "clock", 0, 1439);
                int duration = range(slot, "duration", 0, 1440);
                boolean active = slot.getBoolean("enabled");
                boolean ready = slot.getBoolean("ready");
                if (enabled && active && duration > 0) windows.add(Planner.rule(day, clock, duration, ready));
                String suffix = day + "_" + index;
                editor.putInt("start" + suffix, clock).putInt("duration" + suffix, duration)
                    .putBoolean("slotEnabled" + suffix, active).putBoolean(ClimateProfiles.readyKey(day, index), ready)
                    .putBoolean("homeOnly" + suffix, slot.optBoolean("homeOnly", false))
                    .putInt("slotHeat" + suffix, range(slot, "heat", -1, 15))
                    .putInt(ClimateProfiles.windowKey(day, index), range(slot, "profile", -1, ClimateProfiles.COUNT - 1));
            }
        }
        if (Schedule.overlaps(windows)) throw new JSONException("Overlapping windows");
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            var source = profiles.getJSONObject(index);
            String name = source.getString("name").trim();
            if (name.isEmpty() || name.length() > 30) throw new JSONException("Invalid name");
            ClimateProfiles.save(editor, index, new ClimateProfiles.Profile(name, temperature(source),
                range(source, "heat", 0, 15), range(source, "threshold", -15, 15)));
        }
        var defaults = backup.getJSONObject("defaults");
        int heat = range(defaults, "heat", 0, 15);
        int general = defaults.has("profile") ? range(defaults, "profile", -1, ClimateProfiles.COUNT - 1) : -1;
        return editor.putInt(ClimateProfiles.GENERAL, general).putInt("temperatureHalves", temperature(defaults))
            .putBoolean("heatWheel", (heat & Planner.HEAT_WHEEL) != 0)
            .putBoolean("heatMirrors", (heat & Planner.HEAT_MIRRORS) != 0)
            .putInt("windshieldMode", ClimateProfiles.windshieldMode(heat))
            .putInt("windshieldThreshold", range(defaults, "threshold", -15, 15));
    }

    private static int temperature(JSONObject source) throws JSONException {
        return range(source, "temperature", Planner.MIN_TEMP_HALVES, Planner.MAX_TEMP_HALVES);
    }

    private static int range(JSONObject source, String key, int min, int max) throws JSONException {
        Object value = source.get(key);
        if (!(value instanceof Integer number) || number < min || number > max) throw new JSONException(key);
        return number;
    }
}
