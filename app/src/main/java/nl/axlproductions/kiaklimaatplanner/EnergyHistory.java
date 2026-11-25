package nl.axlproductions.kiaklimaatplanner;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class EnergyHistory extends SQLiteOpenHelper {
    private static EnergyHistory instance;

    record Point(long time, double value) {}
    record Day(LocalDate date, double energy, long duration) {}

    private EnergyHistory(Context context) {
        super(context, "climate_history.db", null, 1);
    }

    static synchronized EnergyHistory open(Context context) {
        if (instance == null) instance = new EnergyHistory(context.getApplicationContext());
        return instance;
    }

    @Override public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE battery (time INTEGER PRIMARY KEY, percentage REAL NOT NULL)");
        database.execSQL("CREATE TABLE cycle_day (cycle INTEGER NOT NULL, day TEXT NOT NULL, begin INTEGER NOT NULL, "
            + "end INTEGER NOT NULL, energy REAL NOT NULL, duration INTEGER NOT NULL, PRIMARY KEY(cycle, day))");
        database.execSQL("CREATE INDEX cycle_day_date ON cycle_day(day, begin)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    static void captureBattery(Context context, JSONObject reading) {
        try {
            open(context).battery(reading.optLong("time"), reading.optDouble("percentage"), System.currentTimeMillis());
        } catch (android.database.sqlite.SQLiteException ignored) {
            // History is independent of reading the vehicle status.
        }
    }

    static void captureCycle(Context context, JSONObject entry) {
        try {
            open(context).cycle(entry, ZoneId.systemDefault());
        } catch (android.database.sqlite.SQLiteException ignored) {
            // The cycle stays available in the existing per-cycle log for a later import.
            Planner.prefs(context).edit().putBoolean("energyHistoryImported", false).apply();
        }
    }

    void battery(long time, double percentage, long now) {
        if (time <= 0 || time > now + 5_000 || !Double.isFinite(percentage) || percentage < 0 || percentage > 100) return;
        var values = new ContentValues();
        values.put("time", time);
        values.put("percentage", percentage);
        getWritableDatabase().insertWithOnConflict("battery", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void cycle(JSONObject data, ZoneId zone) {
        long start = data.optLong("start");
        long duration = data.optLong("elapsed");
        double kwh = data.optDouble("estimate");
        if (start <= 0 || duration <= 0 || duration > Schedule.INTERVAL + Schedule.MINUTE || !Double.isFinite(kwh) || kwh < 0) return;
        var database = getWritableDatabase();
        database.beginTransaction();
        try {
            try (var known = database.rawQuery("SELECT 1 FROM cycle_day WHERE cycle = ? LIMIT 1",
                    new String[]{Long.toString(start)})) {
                if (known.moveToFirst()) return;
            }
            long end = start + duration;
            for (long cursor = start; cursor < end;) {
                LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
                long afterMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                long cut = Math.min(end, afterMidnight);
                var values = new ContentValues();
                values.put("cycle", start);
                values.put("day", day.toString());
                values.put("begin", cursor);
                values.put("end", cut);
                values.put("energy", kwh * (cut - cursor) / duration);
                values.put("duration", cut - cursor);
                database.insertOrThrow("cycle_day", null, values);
                cursor = cut;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void inherit(Context context) {
        var prefs = Planner.prefs(context);
        battery(prefs.getLong("batteryTime", 0), prefs.getInt("battery", -1), System.currentTimeMillis());
        if (prefs.getBoolean("energyHistoryImported", false)) return;
        var records = ClimateEnergy.log(context);
        var zone = ZoneId.systemDefault();
        for (int index = 0; index < records.length(); index++) {
            var current = records.optJSONObject(index);
            if (current != null) cycle(current, zone);
        }
        prefs.edit().putBoolean("energyHistoryImported", true).apply();
    }

    List<Point> batteryChart(LocalDate day, ZoneId zone) {
        var points = new ArrayList<Point>();
        long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        try (var cursor = getReadableDatabase().rawQuery(
                "SELECT time, percentage FROM battery WHERE time >= ? AND time < ? ORDER BY time",
                new String[]{Long.toString(start), Long.toString(end)})) {
            while (cursor.moveToNext()) points.add(new Point(cursor.getLong(0), cursor.getDouble(1)));
        }
        return points;
    }

    List<Point> cumulativeChart(LocalDate day, ZoneId zone, long now) {
        var points = new ArrayList<Point>();
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        points.add(new Point(from, 0));
        double total = 0;
        try (var cursor = getReadableDatabase().rawQuery(
                "SELECT begin, end, energy FROM cycle_day WHERE day = ? ORDER BY begin",
                new String[]{day.toString()})) {
            while (cursor.moveToNext()) {
                points.add(new Point(cursor.getLong(0), total));
                total += cursor.getDouble(2);
                points.add(new Point(cursor.getLong(1), total));
            }
        }
        points.add(new Point(Math.max(points.get(points.size() - 1).time(), Math.min(now, until)), total));
        return points;
    }

    List<Day> days(LocalDate first, LocalDate end) {
        var days = new ArrayList<Day>();
        try (var cursor = getReadableDatabase().rawQuery(
                "SELECT day, SUM(energy), SUM(duration) FROM cycle_day WHERE day >= ? AND day < ? GROUP BY day ORDER BY day",
                new String[]{first.toString(), end.toString()})) {
            boolean hasRecord = cursor.moveToFirst();
            for (LocalDate date = first; date.isBefore(end); date = date.plusDays(1)) {
                if (hasRecord && date.toString().equals(cursor.getString(0))) {
                    days.add(new Day(date, cursor.getDouble(1), cursor.getLong(2)));
                    hasRecord = cursor.moveToNext();
                } else {
                    days.add(new Day(date, 0, 0));
                }
            }
        }
        return days;
    }
}
