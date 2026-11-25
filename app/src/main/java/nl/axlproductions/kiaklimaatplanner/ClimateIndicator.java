package nl.axlproductions.kiaklimaatplanner;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.json.JSONObject;

final class ClimateIndicator {
    static final String WEATHER_ENABLED = "estimateClimateFromWeather";
    private static final long CAR_AGE = 20 * Schedule.MINUTE;
    private static final long WEATHER_AGE = 45 * Schedule.MINUTE;
    private static final ExecutorService WEATHER = Executors.newSingleThreadExecutor();
    private static String lastWeatherRequest = "";
    private static long lastRequestedAt;

    enum Mode { HEATING, COOLING, UNKNOWN }
    record Reading(double outdoor, double target, boolean weather, long measured) {
        Mode mode() {
            return estimate(outdoor, target);
        }
    }

    static Mode estimate(double outside, double target) {
        if (!Double.isFinite(outside) || !Double.isFinite(target)) return Mode.UNKNOWN;
        if (outside < target - 1) return Mode.HEATING;
        if (outside > target + 1) return Mode.COOLING;
        return Mode.UNKNOWN;
    }

    static Reading read(SharedPreferences prefs, long now, boolean ownCycle) {
        long vehicleTime = prefs.getLong("climateEnvironmentTime", 0);
        boolean freshVehicle = fresh(vehicleTime, now, CAR_AGE);
        boolean requestedTarget = ownCycle && prefs.getLong("climateTargetRequest", 0) > 0
            && prefs.getLong("climateTargetRequest", 0) == prefs.getLong("lastStart", 0);
        double target = requestedTarget ? prefs.getFloat("climateRequestedTarget", Float.NaN)
            : freshVehicle ? prefs.getFloat("climateVehicleTarget", Float.NaN) : Double.NaN;
        float outdoor = freshVehicle ? prefs.getFloat("climateOutside", Float.NaN) : Float.NaN;
        if (Float.isFinite(outdoor)) return new Reading(outdoor, target, false, vehicleTime);
        boolean weatherAvailable = prefs.getBoolean(WEATHER_ENABLED, true)
            && !prefs.getString("climateWeatherCell", "").isEmpty()
            && prefs.getString("climateWeatherCell", "").equals(prefs.getString("climateWeatherReadingCell", ""))
            && fresh(prefs.getLong("climateWeatherTime", 0), now, WEATHER_AGE);
        float weather = weatherAvailable ? prefs.getFloat("climateWeatherDegrees", Float.NaN) : Float.NaN;
        return new Reading(weather, target, Float.isFinite(weather),
            Float.isFinite(weather) ? prefs.getLong("climateWeatherTime", 0) : 0);
    }

    record Outdoor(double value, long measured, boolean weather) {}

    static Outdoor lastOutdoor(SharedPreferences prefs, long now) {
        long vehicleTime = prefs.getLong("climateLastOutdoorTime", prefs.getLong("climateEnvironmentTime", 0));
        float vehicle = prefs.getFloat("climateLastOutdoor", prefs.getFloat("climateOutside", Float.NaN));
        long weatherTime = prefs.getLong("climateWeatherTime", 0);
        float weather = prefs.getFloat("climateWeatherDegrees", Float.NaN);
        String cell = prefs.getString("climateWeatherCell", "");
        boolean validVehicle = Float.isFinite(vehicle) && vehicleTime > 0 && vehicleTime <= now;
        if (prefs.getBoolean(WEATHER_ENABLED, true) && Float.isFinite(weather)
                && weatherTime > 0 && weatherTime <= now && (!validVehicle || weatherTime > vehicleTime)
                && !cell.isEmpty() && cell.equals(prefs.getString("climateWeatherReadingCell", ""))) {
            return new Outdoor(weather, weatherTime, true);
        }
        return validVehicle ? new Outdoor(vehicle, vehicleTime, false) : null;
    }

    static void update(Context context, JSONObject environment) {
        update(context, environment, false);
    }

    static void update(Context context, JSONObject environment, boolean immediateWeather) {
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long measured = environment.optLong("time", 0);
        float outside = (float) environment.optDouble("outside");
        if (Float.isFinite(outside) && fresh(measured, now, 24 * 60 * Schedule.MINUTE)
                && measured >= prefs.getLong("climateLastOutdoorTime", prefs.getLong("climateEnvironmentTime", 0))) {
            prefs.edit().putLong("climateLastOutdoorTime", measured).putFloat("climateLastOutdoor", outside).apply();
        }
        if (fresh(measured, now, CAR_AGE) && measured >= prefs.getLong("climateEnvironmentTime", 0)) {
            prefs.edit().putLong("climateEnvironmentTime", measured).putFloat("climateOutside", outside)
                .putFloat("climateVehicleTarget", (float) environment.optDouble("target")).apply();
        }
        if (!prefs.getBoolean(WEATHER_ENABLED, true)) return;
        String cell = environment.optString("weatherCell", "");
        if (!cell.matches("-?\\d{1,3}\\.\\d,-?\\d{1,3}\\.\\d")) return;
        prefs.edit().putString("climateWeatherCell", cell).apply();
        if (Float.isFinite(outside) && fresh(measured, now, CAR_AGE)) return;
        String location = environment.optString("weatherLocation", "");
        if (location.matches("-?\\d{1,3}\\.\\d{6},-?\\d{1,3}\\.\\d{6}")) {
            if (immediateWeather && !Double.isFinite(read(prefs, now, false).outdoor())) {
                fetchWeather(context, cell, location);
            } else {
                requestWeather(context.getApplicationContext(), cell, location, now);
            }
        }
    }

    private static synchronized void requestWeather(Context context, String cell, String location, long now) {
        var prefs = Planner.prefs(context);
        if ((cell.equals(lastWeatherRequest) && now - lastRequestedAt < 10 * Schedule.MINUTE)
                || (cell.equals(prefs.getString("climateWeatherReadingCell", ""))
                    && fresh(prefs.getLong("climateWeatherTime", 0), now, 30 * Schedule.MINUTE))) return;
        lastWeatherRequest = cell;
        lastRequestedAt = now;
        WEATHER.execute(() -> fetchWeather(context, cell, location));
    }

    private static void fetchWeather(Context context, String cell, String location) {
        var prefs = Planner.prefs(context);
        if (!prefs.getBoolean(WEATHER_ENABLED, true) || !cell.equals(prefs.getString("climateWeatherCell", ""))) return;
        HttpURLConnection connection = null;
        try {
            String[] coordinates = location.split(",");
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + coordinates[0]
                + "&longitude=" + coordinates[1] + "&current=temperature_2m&timeformat=unixtime");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                var weather = new JSONObject(reader.lines().collect(Collectors.joining())).getJSONObject("current");
                long time = weather.getLong("time") * 1_000;
                float degrees = (float) weather.getDouble("temperature_2m");
                if (Float.isFinite(degrees) && fresh(time, System.currentTimeMillis(), WEATHER_AGE)
                        && prefs.getBoolean(WEATHER_ENABLED, true) && cell.equals(prefs.getString("climateWeatherCell", ""))) {
                    prefs.edit().putFloat("climateWeatherDegrees", degrees).putLong("climateWeatherTime", time)
                        .putString("climateWeatherReadingCell", cell).apply();
                }
            }
        } catch (Exception ignored) {
            // No climate command depends on a weather response.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean fresh(long time, long now, long age) {
        return time > 0 && time <= now + Schedule.MINUTE && now - time <= age;
    }
}
