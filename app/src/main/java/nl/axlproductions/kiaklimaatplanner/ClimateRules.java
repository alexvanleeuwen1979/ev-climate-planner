package nl.axlproductions.kiaklimaatplanner;

import android.content.SharedPreferences;
import android.location.Location;
import java.time.MonthDay;
import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONObject;

final class ClimateRules {
    private static final long FRESH = 3 * Schedule.MINUTE;
    private static final long LOCATION_AGE = 15 * Schedule.MINUTE;
    private static final float HOME_RADIUS = 150;

    enum Reason {
        LOW_BATTERY("Accu onder de ingestelde grens"),
        MILD("Buitentemperatuur binnen het comfortbereik"),
        AWAY("Auto staat buiten de thuiszone"),
        LOCATION_UNKNOWN("Geen recente Kia-locatie voor de thuiszone");

        final String label;
        Reason(String label) { this.label = label; }
    }

    static int profile(SharedPreferences prefs, long time, ZoneId zone) {
        if (!prefs.getBoolean("seasonalProfile", false)) return ClimateProfiles.general(prefs);
        var date = MonthDay.from(Instant.ofEpochMilli(time).atZone(zone));
        int today = date.getMonthValue() * 100 + date.getDayOfMonth();
        int summer = prefs.getInt("summerDate", 401);
        int winter = prefs.getInt("winterDate", 1001);
        boolean isSummer = summer < winter ? today >= summer && today < winter : today >= summer || today < winter;
        return prefs.getInt(isSummer ? "summerProfile" : "winterProfile", isSummer ? 1 : 0);
    }

    static boolean conditional(SharedPreferences prefs) {
        return prefs.getInt("minimumUnpluggedBattery", 0) > 0 || prefs.getBoolean("skipMild", false);
    }

    static boolean refreshNeeded(SharedPreferences prefs, boolean home, JSONObject vehicle, JSONObject environment, long now) {
        if (home && position(environment, now) == null) return true;
        if (prefs.getInt("minimumUnpluggedBattery", 0) > 0
                && (!fresh(vehicle.optLong("time"), now, FRESH) || !(vehicle.opt("plugged") instanceof Boolean)
                    || vehicle.optDouble("percentage", -1) < 0)) return true;
        return prefs.getBoolean("skipMild", false)
            && (!fresh(environment.optLong("time"), now, FRESH) || !Double.isFinite(environment.optDouble("outside")));
    }

    static Reason skip(SharedPreferences prefs, boolean homeOnly, JSONObject vehicle, JSONObject environment, long now) {
        if (homeOnly) {
            var position = position(environment, now);
            if (position == null || !prefs.contains("homeLat")) return Reason.LOCATION_UNKNOWN;
            var distance = new float[1];
            Location.distanceBetween(position[0], position[1],
                Double.longBitsToDouble(prefs.getLong("homeLat", 0)),
                Double.longBitsToDouble(prefs.getLong("homeLon", 0)), distance);
            if (distance[0] > HOME_RADIUS) return Reason.AWAY;
        }
        int threshold = prefs.getInt("minimumUnpluggedBattery", 0);
        double battery = vehicle.optDouble("percentage", -1);
        if (threshold > 0 && fresh(vehicle.optLong("time"), now, FRESH) && Boolean.FALSE.equals(vehicle.opt("plugged"))
                && battery >= 0 && battery < threshold) return Reason.LOW_BATTERY;
        if (prefs.getBoolean("skipMild", false)
                && fresh(environment.optLong("time"), now, environment.optBoolean("fromWeather") ? LOCATION_AGE : FRESH)) {
            double outside = environment.optDouble("outside");
            if (outside >= prefs.getInt("mildMin", 18) && outside <= prefs.getInt("mildMax", 22)) return Reason.MILD;
        }
        return null;
    }

    static double[] position(JSONObject environment, long now) {
        if (!fresh(environment.optLong("locationTime"), now, LOCATION_AGE)) return null;
        String[] parts = environment.optString("weatherLocation").split(",");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0]), lon = Double.parseDouble(parts[1]);
            return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? new double[]{lat, lon} : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean fresh(long time, long now, long age) {
        return time > 0 && time <= now + 5_000 && now - time <= age;
    }
}
