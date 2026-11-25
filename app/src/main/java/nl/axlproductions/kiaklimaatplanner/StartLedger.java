package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.content.Context;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class StartLedger {
    enum Kind { CONFIRMED, SKIPPED, FAILED }
    enum Cause {
        USER(R.string.deliberately_skipped), BATTERY(R.string.battery_below_limit), MILD(R.string.mild_weather),
        AWAY(R.string.outside_the_home_zone), LOCATION(R.string.no_recent_location), IN_USE(R.string.car_unlocked_or_in_use),
        REFUSED(R.string.kia_refused_the_start), LIMIT(R.string.starting_limit_at_kia), AUTH(R.string.reconnect_kia),
        ALARM(R.string.exact_alarms_needed), NETWORK(R.string.connection_not_confirmed), NO_REPLY(R.string.no_confirmation_from_kia);

        private final int label;
        Cause(int label) { this.label = label; }
        String label() { return t(label); }
    }

    record Summary(int confirmed, int skipped, int failed, String common, int commonCount) {}

    static synchronized void record(Context context, long key, Kind kind, Cause cause) {
        var prefs = context.getSharedPreferences("start_ledger", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String month = month(cause == Cause.USER && key > 0 ? key : now).toString();
        try {
            var entries = new JSONObject(prefs.getString(month, "{}"));
            entries.put(kind.name() + ":" + (key > 0 ? key : now), cause == null ? "" : cause.name());
            var edit = prefs.edit().putString(month, entries.toString());
            String oldest = month(now).minusMonths(12).toString();
            for (String previous : prefs.getAll().keySet()) {
                if (previous.compareTo(oldest) < 0) edit.remove(previous);
            }
            edit.apply();
        } catch (JSONException ignored) {
            // Statistics never affect a climate start.
        }
    }

    static synchronized void removeUserSkip(Context context, long time) {
        var prefs = context.getSharedPreferences("start_ledger", Context.MODE_PRIVATE);
        String month = month(time).toString();
        try {
            var entries = new JSONObject(prefs.getString(month, "{}"));
            entries.remove(Kind.SKIPPED.name() + ":" + time);
            prefs.edit().putString(month, entries.toString()).apply();
        } catch (JSONException ignored) {}
    }

    static Summary summary(Context context, YearMonth month) {
        var prefs = context.getSharedPreferences("start_ledger", Context.MODE_PRIVATE);
        int confirmed = 0, skipped = 0, failed = 0;
        Map<String, Integer> causes = new HashMap<>();
        try {
            var entries = new JSONObject(prefs.getString(month.toString(), "{}"));
            var keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("CONFIRMED:")) confirmed++;
                if (key.startsWith("SKIPPED:")) skipped++;
                if (key.startsWith("FAILED:")) failed++;
                String cause = entries.optString(key);
                if (!cause.isEmpty() && !cause.equals(Cause.USER.name())) causes.merge(cause, 1, Integer::sum);
            }
        } catch (JSONException ignored) {}
        var most = causes.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
            .thenComparing(Map.Entry.comparingByKey())).orElse(null);
        return new Summary(confirmed, skipped, failed, most == null ? "" : Cause.valueOf(most.getKey()).label(),
            most == null ? 0 : most.getValue());
    }

    static YearMonth month(long epoch) {
        return YearMonth.from(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()));
    }

    static String safeExport(Context context, JSONArray history, long session) throws JSONException {
        var rows = new JSONArray();
        for (int i = history.length() - 1; i >= 0; i--) {
            var item = history.optJSONObject(i);
            if (item == null || item.optLong("session") != session) continue;
            String code = knownCause(item.optString("cause"));
            if (code.isEmpty()) {
                if (item.optBoolean("confirmed")) code = "CONFIRMED_START";
                else if (!knownOutcome(item.optString("outcome")).isEmpty()) code = knownOutcome(item.optString("outcome"));
                else if (AppLanguage.messageStartsWith(context, item.optString("message"), R.string.climate_start_sent)) code = "START_SENT";
                else if (AppLanguage.messageStartsWith(context, item.optString("message"), R.string.climate_control_2)) code = "STATUS_CHECK";
                else code = "WINDOW_EVENT";
            }
            rows.put(new JSONObject().put("time", item.optLong("time")).put("statusCode", code));
        }
        return new JSONObject().put("windowStart", session).put("events", rows).toString(2);
    }
    private static String knownCause(String value) {
        for (var cause : Cause.values()) if (cause.name().equals(value)) return value;
        return "";
    }

    private static String knownOutcome(String value) {
        for (var outcome : Planner.Outcome.values()) if (outcome.name().equals(value)) return value;
        return "";
    }
}
