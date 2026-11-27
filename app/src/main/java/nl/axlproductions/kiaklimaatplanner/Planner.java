package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Planner extends BroadcastReceiver {
    private static final String RUN = "nl.axlproductions.kiaklimaatplanner.RUN";
    private static final String TEST = "nl.axlproductions.kiaklimaatplanner.TEST";
    private static final long MAX_DELAY = 90_000;
    private static final long CONFIRMATION_GRACE = 120_000;
    private static final long STATUS_OVERRUN = 20 * Schedule.MINUTE;
    static final long HISTORY_AGE = 7L * 24 * 60 * Schedule.MINUTE;
    static final int DEFAULT_HEAT = -1;
    static final int HEAT_WHEEL = 1;
    static final int HEAT_MIRRORS = 2;
    static final int HEAT_WINDSHIELD = 4;
    static final int MIN_TEMP_HALVES = 32;
    static final int MAX_TEMP_HALVES = 60;
    private static final String CONFIRMED_WINDOWS = "confirmedClimateWindows";
    private static final String MANUAL_PARTS = "manualPlannedParts";
    private static final String MERGED_WEEKLY = "mergedWeeklySessions";
    private static final long DAY = 24 * 60 * Schedule.MINUTE;

    private record PlannedPart(long start, long end, boolean once) {}
    private record HeatPart(long start, long end, long plannedStart, long plannedEnd) {
        String encode() {
            return start + "," + end + "," + plannedStart + "," + plannedEnd;
        }
    }

    static int windowHeat(SharedPreferences prefs, int day, int index) {
        return prefs.getInt("slotHeat" + day + "_" + index, DEFAULT_HEAT);
    }

    static int defaultHeat(SharedPreferences prefs) {
        return (prefs.getBoolean("heatWheel", false) ? HEAT_WHEEL : 0)
            | (prefs.getBoolean("heatMirrors", false) ? HEAT_MIRRORS : 0)
            | ClimateProfiles.windshieldFlag(prefs.getInt("windshieldMode",
                prefs.getBoolean("heatWindshield", false) ? 1 : 0));
    }

    static int temperatureHalves(SharedPreferences prefs) {
        return Math.max(MIN_TEMP_HALVES, Math.min(MAX_TEMP_HALVES, prefs.getInt("temperatureHalves", 42)));
    }

    static int heatForCycle(SharedPreferences prefs, Schedule.Slot slot, ZoneId zone) {
        return heatForCycle(prefs, slot, climateOptions(prefs, slot, zone).heat());
    }

    static int heatForCycle(SharedPreferences prefs, Schedule.Slot slot, int heating) {
        if (slot == null || (heating & (HEAT_WINDSHIELD | ClimateProfiles.SMART)) == 0) return heating;
        Set<String> confirmed = prefs.getStringSet(CONFIRMED_WINDOWS, Collections.emptySet());
        long lastStart = prefs.getLong("lastStart", 0);
        boolean previouslyConfirmed = confirmed.contains(Long.toString(slot.session()))
            || (!prefs.contains(CONFIRMED_WINDOWS) && lastStart > 0
                && prefs.getLong("activeSession", 0) == slot.session()
                && prefs.getLong("confirmedStart", 0) >= lastStart);
        return previouslyConfirmed ? heating & ~(HEAT_WINDSHIELD | ClimateProfiles.SMART) : heating;
    }

    record ClimateOptions(int profile, int temperature, int heat, int threshold, boolean customHeat) {}

    static int heatFor(SharedPreferences prefs, Schedule.Slot slot, ZoneId zone) {
        return climateOptions(prefs, slot, zone).heat();
    }

    static ClimateOptions climateOptions(SharedPreferences prefs, Schedule.Slot slot, ZoneId zone) {
        int[] window = matchingWindow(prefs, originalWeekly(prefs, slot), zone);
        int assigned = assignedProfile(prefs, slot, window);
        long time = slot == null ? System.currentTimeMillis() : slot.time();
        int selected = assigned >= 0 ? assigned : ClimateRules.profile(prefs, time, zone);
        var profile = selected >= 0 ? ClimateProfiles.get(prefs, selected) : null;
        int localHeat = assigned < 0 && window != null ? windowHeat(prefs, window[0], window[1]) : DEFAULT_HEAT;
        boolean customHeat = localHeat != DEFAULT_HEAT;
        int heating = customHeat ? localHeat : profile == null ? defaultHeat(prefs) : profile.heat();
        int threshold = customHeat || profile == null
            ? prefs.getInt("windshieldThreshold", ClimateProfiles.DEFAULT_THRESHOLD) : profile.threshold();
        int temperature = profile == null ? temperatureHalves(prefs) : profile.temperature();
        if (slot != null && isManual(prefs, slot) && manualPart(prefs, slot.time()) == null) {
            temperature = prefs.getInt("manualTemperature", temperature);
        }
        return new ClimateOptions(selected, temperature, heating, threshold, customHeat);
    }

    private static int assignedProfile(SharedPreferences prefs, Schedule.Slot slot, int[] window) {
        if (slot == null) return -1;
        if (isOnce(prefs, slot)) return prefs.getInt("onceProfile", -1);
        if (isManual(prefs, slot)) {
            String[] fields = manualPart(prefs, slot.time());
            if (fields == null) return prefs.getInt("manualProfile", -1);
            if (fields.length == 5) return Integer.parseInt(fields[4]);
        }
        return window == null ? -1 : prefs.getInt(ClimateProfiles.windowKey(window[0], window[1]), -1);
    }

    static boolean isManual(SharedPreferences prefs, Schedule.Slot slot) {
        return slot.session() == prefs.getLong("manualFirst", 0) && slot.end() == prefs.getLong("manualEnd", 0);
    }

    private static String[] manualPart(SharedPreferences prefs, long time) {
        for (String entry : prefs.getStringSet(MANUAL_PARTS, Collections.emptySet())) {
            String[] fields = entry.split(",");
            if (fields.length >= 4 && time >= Long.parseLong(fields[0]) && time < Long.parseLong(fields[1])) {
                return fields;
            }
        }
        return null;
    }

    private static Schedule.Slot originalWeekly(SharedPreferences prefs, Schedule.Slot slot) {
        if (slot == null || isOnce(prefs, slot)) return null;
        if (isManual(prefs, slot)) {
            String[] fields = manualPart(prefs, slot.time());
            if (fields == null || "0".equals(fields[2])) return null;
            slot = new Schedule.Slot(slot.time(), Long.parseLong(fields[2]), Long.parseLong(fields[3]));
        }
        for (var adjustment : WeeklyAdjustment.read(prefs)) {
            if (slot.session() == adjustment.start() && slot.end() == adjustment.end()) {
                return new Schedule.Slot(slot.time(), adjustment.original(),
                    adjustment.original() + adjustment.end() - adjustment.start());
            }
        }
        return slot;
    }

    private static int[] matchingWindow(SharedPreferences prefs, Schedule.Slot slot, ZoneId zone) {
        if (slot == null) return null;
        var date = java.time.Instant.ofEpochMilli(slot.session()).atZone(zone).toLocalDate();
        for (int day = 0; day < 7; day++) {
            for (int index = 0; index < 2; index++) {
                int duration = duration(prefs, day, index);
                if (duration <= 0 || !windowEnabled(prefs, day, index)) continue;
                var window = rule(day, startMinute(prefs, day, index), duration,
                    prefs.getBoolean(ClimateProfiles.readyKey(day, index), false));
                if (slot.end() - slot.session() != duration * Schedule.MINUTE) continue;
                int daysToChoice = Math.floorMod(day - (date.getDayOfWeek().getValue() - 1), 7);
                if (daysToChoice <= (window.readyBy() ? 2 : 0)
                        && Schedule.startOn(date.plusDays(daysToChoice), window, zone) == slot.session()) {
                    return new int[]{day, index};
                }
            }
        }
        return null;
    }

    static Schedule.Window rule(int day, int clock, int duration, boolean ready) {
        return new Schedule.Window(day, clock, duration, ready);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("klimaat", Context.MODE_PRIVATE);
    }

    static synchronized void migrateDurations(Context context) {
        var prefs = prefs(context);
        if (prefs.getBoolean("durationsInQuarterHours", false)) return;
        var update = prefs.edit().putBoolean("durationsInQuarterHours", true);
        if (prefs.contains("manualMinutes")) {
            update.putInt("manualMinutes", Schedule.nearestDuration(prefs.getInt("manualMinutes", 20)));
        }
        long onceStart = prefs.getLong("onceStart", 0);
        long onceEnd = prefs.getLong("onceEnd", 0);
        if (onceStart > System.currentTimeMillis() && onceEnd > onceStart) {
            int saved = (int) ((onceEnd - onceStart) / Schedule.MINUTE);
            update.putLong("onceEnd", onceStart + Schedule.nearestDuration(saved) * Schedule.MINUTE);
        }
        for (int day = 0; day < 7; day++) {
            for (int index = 0; index < 2; index++) {
                int saved = duration(prefs, day, index);
                if (saved <= 0) continue;
                int nearest = Schedule.nearestDuration(saved);
                if (nearest > saved && dayEnabled(prefs, day) && windowEnabled(prefs, day, index)) {
                    int gap = minutesUntilNextWindow(prefs, day, index);
                    if (nearest > gap) {
                        int available = gap / Schedule.CYCLE_MINUTES * Schedule.CYCLE_MINUTES;
                        nearest = available > 0 ? available : saved;
                    }
                }
                if (nearest != saved) update.putInt("duration" + day + "_" + index, nearest);
            }
        }
        update.commit();
    }

    private static int minutesUntilNextWindow(SharedPreferences prefs, int day, int index) {
        int week = 7 * 1440;
        int start = day * 1440 + startMinute(prefs, day, index);
        int gap = week;
        for (int nextDay = 0; nextDay < 7; nextDay++) {
            if (!dayEnabled(prefs, nextDay)) continue;
            for (int nextIndex = 0; nextIndex < 2; nextIndex++) {
                if ((nextDay == day && nextIndex == index) || !windowEnabled(prefs, nextDay, nextIndex)
                        || duration(prefs, nextDay, nextIndex) <= 0) continue;
                int nextStart = nextDay * 1440 + startMinute(prefs, nextDay, nextIndex);
                gap = Math.min(gap, Math.floorMod(nextStart - start, week));
            }
        }
        return gap;
    }

    static int startMinute(SharedPreferences prefs, int day, int index) {
        int old = prefs.getInt("hour", 17) * 60 + prefs.getInt("minute", 30);
        return prefs.getInt("start" + day + "_" + index, index == 0 ? old : 19 * 60);
    }

    static int duration(SharedPreferences prefs, int day, int index) {
        int old = index == 0 && (prefs.getInt("days", 1) & (1 << day)) != 0
            ? prefs.getInt("duration", 60) : 0;
        return prefs.getInt("duration" + day + "_" + index, old);
    }

    static boolean windowEnabled(SharedPreferences prefs, int day, int index) {
        return prefs.getBoolean("slotEnabled" + day + "_" + index, duration(prefs, day, index) > 0);
    }

    static boolean dayEnabled(SharedPreferences prefs, int day) {
        return prefs.getBoolean("dayEnabled" + day, windowEnabled(prefs, day, 0)
            || windowEnabled(prefs, day, 1));
    }

    private static List<Schedule.Window> windows(SharedPreferences prefs) {
        var windows = new ArrayList<Schedule.Window>();
        if (!prefs.getBoolean("enabled", false)) return windows;
        for (int day = 0; day < 7; day++) {
            if (!dayEnabled(prefs, day)) continue;
            for (int index = 0; index < 2; index++) {
                int minutes = duration(prefs, day, index);
                if (minutes > 0 && windowEnabled(prefs, day, index)) {
                    windows.add(rule(day, startMinute(prefs, day, index), minutes,
                        prefs.getBoolean(ClimateProfiles.readyKey(day, index), false)));
                }
            }
        }
        return windows;
    }

    static boolean hasAlarmAccess(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static Schedule.Slot next(Context context) {
        var prefs = prefs(context);
        long allowed = nextAllowedStart(context);
        long now = System.currentTimeMillis();
        Schedule.Slot continuation = pendingContinuation(prefs, now);
        if (continuation != null && allowed <= now && continuation.time() > prefs.getLong("handled", 0)) {
            return continuation;
        }
        Schedule.Slot slot = next(prefs, now);
        while (slot != null && allowed >= slot.end()) {
            slot = next(prefs, slot.end() - 1);
        }
        return slot;
    }

    private static Schedule.Slot next(SharedPreferences prefs, long after) {
        long onceStart = prefs.getLong("onceStart", 0);
        long onceEnd = prefs.getLong("onceEnd", 0);
        var regular = nextRegular(prefs, after);
        if (onceStart == 0 || onceEnd <= after || (regular != null && regular.time() < onceStart)) return regular;
        var once = Schedule.nextCycle(after, onceStart, onceEnd);
        if (once != null) return once;
        return regular != null && regular.time() < onceEnd ? nextRegular(prefs, onceEnd - 1) : regular;
    }

    private static boolean isOnce(SharedPreferences prefs, Schedule.Slot slot) {
        return slot.session() > 0 && slot.session() == prefs.getLong("onceStart", 0)
            && slot.end() == prefs.getLong("onceEnd", 0);
    }

    private static Schedule.Slot nextRegular(SharedPreferences prefs, long after) {
        long manualEnd = prefs.getLong("manualEnd", 0);
        long manualFirst = prefs.getLong("manualFirst", 0);
        long manualStart = prefs.getLong("manualStart", 0);
        var weekly = nextWeekly(prefs, after);
        if (manualEnd <= after || manualFirst == 0) return weekly;
        if (weekly != null && weekly.time() < manualStart) return weekly;
        var manual = Schedule.nextCycle(after, manualFirst, manualEnd);
        return manual != null ? manual : nextWeekly(prefs, manualEnd - 1);
    }

    private static Schedule.Slot nextWeekly(SharedPreferences prefs, long after) {
        return nextWeekly(prefs, after, prefs.getStringSet(MERGED_WEEKLY, Collections.emptySet()));
    }

    private static Schedule.Slot nextWeekly(SharedPreferences prefs, long after, Set<String> merged) {
        if (!prefs.getBoolean("enabled", false)) return null;
        var adjustments = WeeklyAdjustment.read(prefs);
        var zone = ZoneId.systemDefault();
        long pausedUntil = prefs.getLong(WeeklyAdjustment.PAUSE, 0);
        long cancelled = prefs.getLong("cancelledWeeklySession", 0);
        long active = prefs.getLong("activeSession", 0);
        long probe = active == 0 || prefs.getLong("activeEnd", 0) <= after
            ? Math.max(after, pausedUntil - 1) : after;
        var regular = Schedule.next(probe, zone, windows(prefs),
            time -> WeeklyAdjustment.excludes(prefs, time, active) || time == cancelled || merged.contains(Long.toString(time))
                || adjustments.stream().anyMatch(change -> change.original() == time));
        for (var change : adjustments) {
            long start = change.start();
            if (start <= 0 || WeeklyAdjustment.excludes(prefs, start, active) || start == cancelled
                    || merged.contains(Long.toString(start))) continue;
            var moved = Schedule.nextCycle(after, start, change.end());
            if (moved == null || (regular != null && moved.time() >= regular.time())) continue;
            var original = new Schedule.Slot(change.original(), change.original(),
                change.original() + change.end() - start);
            int[] window = matchingWindow(prefs, original, zone);
            if (window != null && dayEnabled(prefs, window[0])) regular = moved;
        }
        return regular;
    }

    static boolean upcomingWeekly(SharedPreferences prefs, Schedule.Slot slot, long now) {
        return slot != null && slot.session() > now && slot.time() == slot.session()
            && !isManual(prefs, slot) && !isOnce(prefs, slot);
    }

    static String startType(SharedPreferences prefs, Schedule.Slot slot) {
        if (slot == null) return "";
        if (slot.time() > slot.session()) return t(R.string.next_cycle);
        if (isOnce(prefs, slot)) return t(R.string.one_time_start);
        return isManual(prefs, slot) ? t(R.string.manual_start) : t(R.string.weekly_schedule);
    }

    static synchronized WeeklyChange adjustWeekly(Context context, Schedule.Slot selected, long start) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (!upcomingWeekly(prefs, selected, now) || !selected.equals(next(context))) return null;
        long original = selected.session();
        WeeklyAdjustment previous = null;
        for (var change : WeeklyAdjustment.read(prefs)) {
            if (change.start() == original) {
                previous = change;
                original = change.original();
                break;
            }
        }
        long end = start == 0 ? 0 : start + selected.end() - selected.session();
        if (start != 0) {
            if (start <= now || start < prefs.getLong(WeeklyAdjustment.PAUSE, 0)) return null;
            long once = prefs.getLong("onceStart", 0);
            if (once < end && prefs.getLong("onceEnd", 0) > start) return null;
            var rules = windows(prefs);
            var changes = WeeklyAdjustment.read(prefs);
            long source = original;
            var zone = ZoneId.systemDefault();
            var exclusion = (java.util.function.LongPredicate) time -> time == source
                || changes.stream().anyMatch(change -> change.original() == time);
            var other = Schedule.next(start - DAY - 1, zone, rules, exclusion);
            while (other != null && other.end() <= start) {
                other = Schedule.next(other.end() - 1, zone, rules, exclusion);
            }
            if (other != null && other.session() < end) return null;
            for (var change : changes) {
                if (change.original() != original && change.start() > 0
                        && change.start() < end && change.end() > start) return null;
            }
        }
        var applied = new WeeklyAdjustment(original, start, end);
        if (!WeeklyAdjustment.replace(prefs, original, applied, now)) return null;
        if (start == 0) StartLedger.record(context, original, StartLedger.Kind.SKIPPED, StartLedger.Cause.USER);
        schedule(context);
        note(context, start == 0 ? t(R.string.next_start_skipped_from_the_weekly)
            : t(R.string.start_moved_once_from_the_weekly));
        return new WeeklyChange(previous, applied);
    }

    record WeeklyChange(WeeklyAdjustment previous, WeeklyAdjustment applied) {}

    static synchronized boolean undoWeekly(Context context, WeeklyChange change) {
        var prefs = prefs(context);
        var applied = change.applied();
        var previous = change.previous();
        long now = System.currentTimeMillis();
        long restoreAt = previous == null ? applied.original() : previous.start();
        if (restoreAt <= now || (applied.start() > 0 && applied.start() <= now)
                || !WeeklyAdjustment.read(prefs).contains(applied)
                || !WeeklyAdjustment.replace(prefs, applied.original(), previous, now)) return false;
        schedule(context);
        if (applied.start() == 0) StartLedger.removeUserSkip(context, applied.original());
        note(context, t(R.string.changes_to_the_next_week_start));
        return true;
    }

    static synchronized void pauseWeekly(Context context, long until) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        long previous = prefs.getLong(WeeklyAdjustment.PAUSE, 0);
        var edit = prefs.edit().putLong(WeeklyAdjustment.PAUSE, until);
        if (until > now && previous <= now) {
            edit.putLong(WeeklyAdjustment.PAUSE_FROM, now).remove(WeeklyAdjustment.RESUMED);
        } else if (until == 0 && previous > now) {
            edit.putLong(WeeklyAdjustment.RESUMED, now);
        }
        edit.commit();
        schedule(context);
        note(context, until == 0 ? t(R.string.weekly_planning_resumed) : t(R.string.weekly_planning_temporarily_paused));
    }

    static boolean manual(Context context, int minutes) {
        return manual(context, minutes, -1);
    }

    static boolean manual(Context context, int minutes, int profile) {
        return manual(context, minutes, profile, -1);
    }

    static synchronized boolean manual(Context context, int minutes, int profile, int temperature) {
        if (!hasAlarmAccess(context) || !KiaCloud.linked(context) || KiaCloud.needsLink(context)) return false;
        long now = System.currentTimeMillis();
        var prefs = prefs(context);
        long session = prefs.getLong("activeSession", 0);
        long nominalEnd = prefs.getLong("activeEnd", 0);
        if (countedRun(prefs, session, nominalEnd) && now >= nominalEnd && now < runDeadline(prefs)) {
            prefs.edit().putInt("previousManualMinutes", minutes).putInt("previousManualProfile", profile)
                .putInt("previousManualTemperature", temperature).apply();
            long extended = nominalEnd + minutes * Schedule.MINUTE;
            var merged = new HashSet<>(prefs.getStringSet(MERGED_WEEKLY, Collections.emptySet()));
            merged.add(Long.toString(session));
            var update = prefs.edit().putInt("manualMinutes", minutes).putLong("manualStart", now)
                .putLong("manualFirst", session).putLong("manualEnd", extended).putLong("activeEnd", extended)
                .putLong("activeRunEnd", Math.max(now, forecastEnd(prefs)) + minutes * Schedule.MINUTE)
                .putStringSet(MERGED_WEEKLY, merged);
            if (isOnce(prefs, new Schedule.Slot(session, session, nominalEnd))) update.remove("onceStart").remove("onceEnd");
            update.commit();
            note(context, t(R.string.manual_time_slot_added_to_the));
            scheduleMonitoredEnd(context, session, extended, runDeadline(prefs));
            schedule(context);
            VehicleWatch.schedule(context);
            return true;
        }
        long first = manualRestartAvailable(prefs, now) ? now : Math.max(now, nextAllowedStart(context, true));
        long end = (cycleEnd(context) > now ? now : first) + minutes * Schedule.MINUTE;
        var edit = prefs.edit().putInt("manualMinutes", minutes).putInt("manualProfile", profile)
            .putInt("previousManualMinutes", minutes).putInt("previousManualProfile", profile)
            .putInt("previousManualTemperature", temperature);
        if (temperature >= MIN_TEMP_HALVES && temperature <= MAX_TEMP_HALVES) edit.putInt("manualTemperature", temperature);
        else edit.remove("manualTemperature");
        edit.putLong("lastManualRequest", first).putLong("manualEnd", end)
            .putLong("manualStart", now).putLong("manualFirst", first)
            .remove(MANUAL_PARTS).commit();
        note(context, first >= end ? t(R.string.the_current_cycle_covers_the_selected)
            : first > now ? t(R.string.manual_start_scheduled_after_the_waiting) : t(R.string.manual_time_slot_started));
        schedule(context);
        long combinedEnd = prefs.getLong("manualEnd", end);
        long combinedFirst = prefs.getLong("manualFirst", first);
        WindowAlerts.schedule(context, prefs.getLong("manualStart", now), combinedEnd, true);
        if (combinedFirst == now) KiaStartService.enqueueManual(context, new Schedule.Slot(now, now, combinedEnd));
        return true;
    }

    static synchronized boolean extend(Context context) {
        return extend(context, 15);
    }

    static synchronized boolean extend(Context context, int minutes) {
        if (minutes != 15 && minutes != 30 && minutes != 45) return false;
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        if (!hasAlarmAccess(context) || !countedRun(prefs, session, end) || runDeadline(prefs) <= now
                || manuallyStopped(prefs)) return false;
        long newEnd = end + minutes * Schedule.MINUTE;
        var slot = new Schedule.Slot(now, session, end);
        var update = prefs.edit().putLong("activeEnd", newEnd)
            .putLong("activeRunEnd", Math.max(now, forecastEnd(prefs)) + minutes * Schedule.MINUTE);
        if (isOnce(prefs, slot)) {
            update.putLong("onceEnd", newEnd);
        } else if (isManual(prefs, slot)) {
            update.putLong("manualEnd", newEnd);
        } else {
            var merged = new HashSet<>(prefs.getStringSet(MERGED_WEEKLY, Collections.emptySet()));
            merged.add(Long.toString(session));
            var parts = new HashSet<String>();
            parts.add(new HeatPart(session, newEnd, session, end).encode());
            update.putLong("manualFirst", session).putLong("manualStart", now).putLong("manualEnd", newEnd)
                .putStringSet(MANUAL_PARTS, parts).putStringSet(MERGED_WEEKLY, merged)
                .remove("manualTemperature").remove("manualProfile");
        }
        if (!update.commit()) return false;
        note(context, String.format(t(R.string.extended_minutes), minutes), session, newEnd, false);
        scheduleMonitoredEnd(context, session, newEnd, runDeadline(prefs));
        schedule(context);
        VehicleWatch.schedule(context);
        return true;
    }

    record ManualPreview(long firstConflict, long end) {}

    static ManualPreview previewManual(Context context, int minutes, long now) {
        var prefs = prefs(context);
        long first = manualRestartAvailable(prefs, now) ? now : Math.max(now, nextAllowedStart(context));
        long end = (cycleEnd(context) > now ? now : first) + minutes * Schedule.MINUTE;
        var merged = new HashSet<>(prefs.getStringSet(MERGED_WEEKLY, Collections.emptySet()));
        long onceStart = prefs.getLong("onceStart", 0), onceEnd = prefs.getLong("onceEnd", 0);
        long firstConflict = 0;
        for (PlannedPart part; (part = overlappingPlan(prefs, now, Math.min(end, now + DAY), merged,
                onceStart, onceEnd)) != null;) {
            if (firstConflict == 0) firstConflict = part.start();
            end += part.end() - Math.max(now, part.start());
            if (part.once()) onceStart = 0;
            else merged.add(Long.toString(part.start()));
        }
        return new ManualPreview(firstConflict, end);
    }

    record AgendaRow(long start, long end, String type, long original) {}

    static java.util.List<AgendaRow> agenda(SharedPreferences prefs, long from, long until, ZoneId zone) {
        var result = new ArrayList<AgendaRow>();
        var rules = windows(prefs);
        var changes = WeeklyAdjustment.read(prefs);
        for (var slot = Schedule.next(from - 1, zone, rules); slot != null && slot.time() < until;
                slot = Schedule.next(slot.end() - 1, zone, rules)) {
            if (slot.session() < from) continue;
            long session = slot.session();
            var change = changes.stream().filter(item -> item.original() == session).findFirst().orElse(null);
            if (change != null) {
                if (change.start() == 0) result.add(new AgendaRow(slot.session(), slot.end(), t(R.string.skipped_2), 0));
            } else {
                boolean paused = WeeklyAdjustment.excludes(prefs, slot.session(), 0);
                result.add(new AgendaRow(slot.session(), slot.end(), paused ? t(R.string.paused) : t(R.string.weekly_schedule), 0));
            }
        }
        for (var change : changes) {
            if (change.start() < from || change.start() >= until) continue;
            var original = new Schedule.Slot(change.original(), change.original(), change.original() + change.end() - change.start());
            var window = matchingWindow(prefs, original, zone);
            if (!prefs.getBoolean("enabled", false) || window == null || !dayEnabled(prefs, window[0])) continue;
            boolean paused = WeeklyAdjustment.excludes(prefs, change.start(), 0);
            result.add(new AgendaRow(change.start(), change.end(), paused ? t(R.string.moved_paused) : t(R.string.moved),
                change.original()));
        }
        long once = prefs.getLong("onceStart", 0);
        if (once >= from && once < until) result.add(new AgendaRow(once, prefs.getLong("onceEnd", 0), t(R.string.one_off_2), 0));
        result.sort(java.util.Comparator.comparingLong(AgendaRow::start));
        return result;
    }

    static boolean firstPlannedAttempt(SharedPreferences prefs, Schedule.Slot slot) {
        return !isManual(prefs, slot) && !prefs.getStringSet(CONFIRMED_WINDOWS, Collections.emptySet())
            .contains(Long.toString(slot.session()));
    }

    static boolean homeOnly(SharedPreferences prefs, Schedule.Slot slot, ZoneId zone) {
        if (slot == null || isManual(prefs, slot)) return false;
        if (isOnce(prefs, slot)) return prefs.getBoolean("onceHomeOnly", false);
        var window = matchingWindow(prefs, originalWeekly(prefs, slot), zone);
        return window != null && prefs.getBoolean("homeOnly" + window[0] + "_" + window[1], false);
    }

    static synchronized void skipByRule(Context context, Schedule.Slot slot, ClimateRules.Reason reason) {
        if (!stillCurrent(context, slot)) return;
        var cause = switch (reason) {
            case LOW_BATTERY -> StartLedger.Cause.BATTERY;
            case MILD -> StartLedger.Cause.MILD;
            case AWAY -> StartLedger.Cause.AWAY;
            case LOCATION_UNKNOWN -> StartLedger.Cause.LOCATION;
        };
        completeWindow(context, slot.session(), slot.end());
        StartLedger.record(context, slot.time(), StartLedger.Kind.SKIPPED, cause);
        note(context, t(R.string.time_slot_skipped) + reason.label() + ".", slot.session(), slot.end(), false, Outcome.RULE, cause);
    }

    private static void warnDepartureDelay(Context context, long forecast) {
        var prefs = prefs(context);
        long session = prefs.getLong("activeSession", 0), end = prefs.getLong("activeEnd", 0);
        if (!prefs.getBoolean("notifyDepartureDelay", false) || session <= 0
                || prefs.getLong("reportedDepartureDelay", 0) == session || forecast <= end + 5 * Schedule.MINUTE) return;
        var slot = new Schedule.Slot(session, session, end);
        if (isOnce(prefs, slot)) {
            if (!prefs.getBoolean("onceReady", false)) return;
        } else {
            var weekly = originalWeekly(prefs, slot);
            var window = matchingWindow(prefs, weekly, ZoneId.systemDefault());
            if (window == null || !prefs.getBoolean(ClimateProfiles.readyKey(window[0], window[1]), false)) return;
        }
        prefs.edit().putLong("reportedDepartureDelay", session).apply();
        var clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
        String message = t(R.string.ready_by) + clock.format(java.time.Instant.ofEpochMilli(end)) + t(R.string.now_expected_to)
            + clock.format(java.time.Instant.ofEpochMilli(forecast));
        note(context, message, session, end, false);
        WindowAlerts.show(context, 25, t(R.string.departure_time_moves_up), message);
    }

    static boolean isCombined(SharedPreferences prefs, long now) {
        return prefs.getLong("manualEnd", 0) > now && prefs.contains(MANUAL_PARTS);
    }

    private static PlannedPart overlappingPlan(SharedPreferences prefs, long from, long until, Set<String> merged,
            long onceStart, long onceEnd) {
        if (ClimateRules.conditional(prefs)) return null;
        var zone = ZoneId.systemDefault();
        var weekly = nextWeekly(prefs, from - DAY - 1, merged);
        while (weekly != null && (weekly.end() <= from || homeOnly(prefs, weekly, zone))) {
            if (weekly.session() >= until) {
                weekly = null;
                break;
            }
            weekly = nextWeekly(prefs, weekly.end() - 1, merged);
        }
        if (!prefs.getBoolean("onceHomeOnly", false) && onceStart > 0 && onceStart < until && onceEnd > from
                && (weekly == null || onceStart <= weekly.session())) {
            return new PlannedPart(onceStart, onceEnd, true);
        }
        return weekly != null && weekly.session() < until
            ? new PlannedPart(weekly.session(), weekly.end(), false) : null;
    }

    private static synchronized void mergeManualPlan(Context context) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        long manualStart = prefs.getLong("manualStart", 0);
        long end = prefs.getLong("manualEnd", 0);
        if (manualStart == 0 || end <= now) return;
        long originalEnd = end;
        long first = prefs.getLong("manualFirst", manualStart);
        long originalFirst = first;
        long from = Math.max(now, manualStart);
        long limit = manualStart + DAY;
        var parts = new HashSet<>(prefs.getStringSet(MANUAL_PARTS, Collections.emptySet()));
        var merged = new HashSet<>(prefs.getStringSet(MERGED_WEEKLY, Collections.emptySet()));
        long onceStart = prefs.getLong("onceStart", 0);
        long onceEnd = prefs.getLong("onceEnd", 0);
        boolean consumedOnce = false;
        for (PlannedPart plan; (plan = overlappingPlan(prefs, from, Math.min(end, limit), merged,
                consumedOnce ? 0 : onceStart, onceEnd)) != null;) {
            boolean runningFirst = parts.isEmpty() && plan.start() <= from
                && prefs.getLong("activeSession", 0) == plan.start()
                && prefs.getLong("activeEnd", 0) == plan.end();
            long segmentStart = runningFirst ? from : end;
            long included = plan.end() - Math.max(manualStart, plan.start());
            end += included;
            long segmentEnd = runningFirst ? plan.end() : end;
            var part = new HeatPart(segmentStart, segmentEnd, plan.once() ? 0 : plan.start(),
                plan.once() ? 0 : plan.end());
            parts.add(plan.once() ? part.encode() + "," + prefs.getInt("onceProfile", -1) : part.encode());
            if (runningFirst) first = plan.start();
            if (plan.once()) consumedOnce = true;
            else merged.add(Long.toString(plan.start()));
        }
        if (end == originalEnd) return;
        merged.removeIf(value -> Long.parseLong(value) < now - 2 * DAY);
        var update = prefs.edit().putLong("manualFirst", first).putLong("manualEnd", end)
            .putLong("lastManualRequest", first).putStringSet(MANUAL_PARTS, parts).putStringSet(MERGED_WEEKLY, merged);
        if (consumedOnce) update.remove("onceStart").remove("onceEnd");
        long activeSession = prefs.getLong("activeSession", 0);
        if (activeSession != 0 && (activeSession == first || activeSession == originalFirst)) {
            update.putLong("activeEnd", end);
        }
        update.commit();
        WindowAlerts.schedule(context, manualStart, end, true);
        VehicleWatch.schedule(context);
        String endTime = java.time.Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        note(context, t(R.string.manual_start_overlaps_with_the_schedule) + endTime + ".");
    }

    static boolean planOnce(Context context, long start, int minutes) {
        return planOnce(context, start, minutes, -1, false);
    }

    static boolean planOnce(Context context, long start, int minutes, int profile, boolean ready) {
        return planOnce(context, start, minutes, profile, ready, false);
    }

    static synchronized boolean planOnce(Context context, long start, int minutes, int profile, boolean ready, boolean homeOnly) {
        if (!hasAlarmAccess(context) || !KiaCloud.linked(context) || KiaCloud.needsLink(context)) return false;
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (start <= now || minutes <= 0 || onceActive(prefs, now)) return false;
        long end = start + minutes * Schedule.MINUTE;
        if (!prefs.edit().putLong("onceStart", start).putLong("onceEnd", end)
                .putInt("onceProfile", profile).putBoolean("onceReady", ready).putBoolean("onceHomeOnly", homeOnly).commit()) return false;
        note(context, t(R.string.one_off_climate_start_planned));
        WindowAlerts.scheduleOnce(context, start, end);
        schedule(context);
        return true;
    }

    static boolean onceActive(SharedPreferences prefs, long now) {
        return prefs.getLong("onceStart", Long.MAX_VALUE) <= now && prefs.getLong("onceEnd", 0) > now;
    }

    static synchronized void cancelOnce(Context context) {
        var prefs = prefs(context);
        if (prefs.getLong("onceStart", 0) <= System.currentTimeMillis()) return;
        prefs.edit().remove("onceStart").remove("onceEnd").commit();
        schedule(context);
        note(context, t(R.string.one_off_climate_start_cancelled));
    }

    private static PendingIntent alarm(Context context, Schedule.Slot slot, long due, int flags) {
        Intent intent = new Intent(context, Planner.class).setAction(RUN);
        if (slot != null) {
            intent.putExtra("time", slot.time()).putExtra("session", slot.session()).putExtra("end", slot.end()).putExtra("due", due);
        }
        return PendingIntent.getBroadcast(context, 1, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context) {
        ExtensionReminder.schedule(context);
        mergeManualPlan(context);
        ClimateWidget.refresh(context);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        long allowed = nextAllowedStart(context);
        Schedule.Slot slot = next(context);
        if (slot == null || !hasAlarmAccess(context)) {
            PendingIntent previous = alarm(context, null, 0, PendingIntent.FLAG_NO_CREATE);
            if (previous != null) manager.cancel(previous);
            return;
        }
        try {
            long due = Math.max(slot.time(), allowed);
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due,
                alarm(context, slot, due, PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (SecurityException ignored) {
            note(context, t(R.string.give_android_permission_for_exact_alarms));
        }
    }

    static void note(Context context, String message) {
        note(context, message, 0, 0, false);
    }

    enum Outcome { UNLOCKED, UNCONFIRMED, REFUSED, STOPPED, RULE }

    static void note(Context context, String message, long session, long end, boolean confirmed) {
        note(context, message, session, end, confirmed, null);
    }

    static void note(Context context, String message, long session, long end, boolean confirmed, Outcome outcome) {
        note(context, message, session, end, confirmed, outcome, null);
    }

    static synchronized void note(Context context, String message, long session, long end, boolean confirmed,
                                  Outcome outcome, StartLedger.Cause cause) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        var history = new org.json.JSONArray();
        try {
            var newest = new org.json.JSONObject().put("time", now).put("message", message);
            if (session > 0) newest.put("session", session).put("end", end).put("confirmed", confirmed);
            if (outcome != null) newest.put("outcome", outcome.name());
            if (cause != null) newest.put("cause", cause.name());
            history.put(newest);
            var previous = new org.json.JSONArray(prefs.getString("history", "[]"));
            if (previous.length() == 0 && prefs.getLong("statusTime", 0) > 0) {
                previous.put(new org.json.JSONObject().put("time", prefs.getLong("statusTime", 0))
                    .put("message", prefs.getString("status", "")));
            }
            for (int index = 0; index < previous.length(); index++) {
                var item = previous.optJSONObject(index);
                if (item != null && item.optLong("time", 0) >= now - HISTORY_AGE) history.put(item);
            }
        } catch (org.json.JSONException ignored) {
            // Keep the newest entry if an old history is unreadable.
        }
        prefs.edit().putString("status", message).putLong("statusTime", now)
            .putString("history", history.toString()).apply();
    }

    static void scheduleTest(Context context) {
        long time = System.currentTimeMillis() + 30_000;
        PendingIntent test = PendingIntent.getBroadcast(context, 2,
            new Intent(context, Planner.class).setAction(TEST).putExtra("time", time),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, test);
    }

    @Override public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        long time = intent.getLongExtra("time", 0);
        long due = intent.getLongExtra("due", time);
        if (ExtensionReminder.receive(context, intent)) return;
        if (VehicleWatch.ACTION.equals(intent.getAction())) {
            VehicleWatch.receive(context, intent);
            return;
        }
        if ("external_recheck".equals(intent.getAction())) {
            Schedule.Slot slot = VehicleWatch.readSlot(intent);
            if (now >= due && prefs(context).getLong("externalRecheckTime", 0) == slot.time()
                    && stillCurrent(context, slot)) launch(context, slot, 0);
            return;
        }
        if (WindowAlerts.END.equals(intent.getAction())) {
            finishWindow(context, intent, now);
            return;
        }
        if (TEST.equals(intent.getAction())) {
            if (now >= time && now - time <= MAX_DELAY && KiaCloud.linked(context)) {
                KiaStartService.enqueue(context, null, 0);
            }
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            BatterySampling.updateSchedule(context);
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            migrateDurations(context);
            KiaBackoff.restoreFromHistory(context, now);
            resumeClimateMonitoring(context, now);
        }
        schedule(context);
        if (!RUN.equals(intent.getAction())) {
            VehicleWatch.schedule(context);
            var saved = prefs(context);
            WindowAlerts.schedule(context, saved.getLong("weeklySession", 0), saved.getLong("weeklyEnd", 0), false);
            WindowAlerts.schedule(context, saved.getLong("manualStart", 0), saved.getLong("manualEnd", 0), true);
            WindowAlerts.scheduleOnce(context, saved.getLong("onceStart", 0), saved.getLong("onceEnd", 0));
            long session = saved.getLong("activeSession", 0);
            long end = saved.getLong("activeEnd", 0);
            if (countedRun(saved, session, end)) {
                if (now < runDeadline(saved)) scheduleMonitoredEnd(context, session, end, runDeadline(saved));
                else completeMonitoredRun(context, session, end, false);
            }
            return;
        }
        var prefs = prefs(context);
        if (now < due || now - due > MAX_DELAY || time <= prefs.getLong("handled", 0)) return;
        var slot = new Schedule.Slot(time, intent.getLongExtra("session", 0), intent.getLongExtra("end", 0));
        if (!stillCurrent(context, slot)) return;
        if (!isOnce(prefs, slot)
                && (slot.end() != prefs.getLong("manualEnd", 0) || slot.session() != prefs.getLong("manualFirst", 0))) {
            if (prefs.getLong("weeklyEnd", 0) != slot.end()) {
                prefs.edit().putLong("weeklySession", slot.session()).putLong("weeklyEnd", slot.end()).commit();
                WindowAlerts.schedule(context, slot.session(), slot.end(), false);
            }
        }
        long allowed = nextAllowedStart(context);
        if (allowed > now) {
            if (allowed < slot.end()) defer(context, slot, allowed);
            return;
        }
        prefs.edit().putLong("handled", time).commit();
        launch(context, slot, 0);
    }

    private static void defer(Context context, Schedule.Slot slot, long due) {
        Intent intent = new Intent(context, Planner.class).setAction(RUN)
            .putExtra("time", slot.time()).putExtra("session", slot.session())
            .putExtra("end", slot.end()).putExtra("due", due);
        PendingIntent request = PendingIntent.getBroadcast(context, 4, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (hasAlarmAccess(context)) {
            try {
                context.getSystemService(AlarmManager.class)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, request);
            } catch (SecurityException ignored) {
                note(context, t(R.string.give_android_permission_for_exact_alarms));
            }
        }
    }

    static boolean externalClimateActive(SharedPreferences prefs, long now) {
        long seen = prefs.getLong("externalClimateSeen", 0);
        return seen > 0 && now - seen <= 2 * Schedule.MINUTE;
    }

    static synchronized boolean waitForExternalClimate(Context context, Schedule.Slot slot, long measured, Boolean active) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        boolean fresh = measured > 0 && measured >= now - 2 * Schedule.MINUTE && measured <= now + 5_000;
        boolean waiting = prefs.getLong("externalRecheckTime", 0) == slot.time();
        boolean knownClimate = fresh && active != null && measured >= prefs.getLong("externalClimateChecked", 0);
        if (knownClimate) rememberExternalClimate(context, measured, active);
        if (knownClimate && !active || !waiting && !externalClimateActive(prefs, now)) {
            if (waiting) prefs.edit().remove("externalRecheckTime").apply();
            return false;
        }
        if (!waiting) note(context, t(R.string.climate_control_is_already_running_the));
        prefs.edit().putLong("externalRecheckTime", slot.time())
            .putLong("handled", Math.max(slot.time(), prefs.getLong("handled", 0))).commit();
        long due = now + Schedule.MINUTE;
        if (due < slot.end() && stillCurrent(context, slot) && hasAlarmAccess(context)) {
            var intent = VehicleWatch.putSlot(new Intent(context, Planner.class).setAction("external_recheck"), slot)
                .putExtra("due", due);
            var callback = PendingIntent.getBroadcast(context, 6, intent, PendingIntent.FLAG_IMMUTABLE
                | PendingIntent.FLAG_UPDATE_CURRENT);
            try {
                context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, callback);
            } catch (SecurityException ignored) {
                note(context, t(R.string.give_android_permission_for_exact_alarms));
            }
        }
        return true;
    }

    static synchronized void rememberExternalClimate(Context context, long measured, boolean active) {
        var prefs = prefs(context);
        if (measured <= 0 || measured < prefs.getLong("externalClimateChecked", 0)) return;
        var update = prefs.edit().putLong("externalClimateChecked", measured);
        if (active && measured > prefs.getLong("confirmedStart", 0) && cycleEnd(context) <= measured) {
            update.putLong("externalClimateSeen", measured);
        } else {
            update.remove("externalClimateSeen");
        }
        update.apply();
    }

    private static void launch(Context context, Schedule.Slot slot, long wait) {
        if (KiaCloud.linked(context)) {
            KiaStartService.enqueue(context, slot, wait);
        } else {
            note(context, t(R.string.missed_time_connect_kia_connect_or));
        }
    }

    private static synchronized void finishWindow(Context context, Intent intent, long now) {
        var prefs = prefs(context);
        long session = intent.getLongExtra("session", 0);
        long end = intent.getLongExtra("end", 0);
        boolean manual = intent.getBooleanExtra("manual", false);
        boolean once = intent.getBooleanExtra("once", false);
        String key = once ? "lastOnceEndNotice" : manual ? "lastManualEndNotice" : "lastWeeklyEndNotice";
        if (session <= 0 || end == prefs.getLong(key, 0)) return;
        long activeSession = prefs.getLong("activeSession", 0);
        if (countedRun(prefs, activeSession, end)
                && (session == activeSession || (manual && session == prefs.getLong("manualStart", 0)))) {
            if (now < runDeadline(prefs)) scheduleMonitoredEnd(context, activeSession, end, runDeadline(prefs));
            else completeMonitoredRun(context, activeSession, end, false);
            return;
        }
        if (end > now) return;
        if (once) {
            if (!isOnce(prefs, new Schedule.Slot(session, session, end))) return;
        } else if (manual) {
            if (prefs.getLong("manualEnd", 0) != end || prefs.getLong("manualStart", 0) != session) return;
        } else {
            if (prefs.getLong("weeklySession", 0) != session || prefs.getLong("weeklyEnd", 0) != end) return;
            var planned = nextWeekly(prefs, session - 1);
            if (planned == null || planned.session() != session || planned.end() != end) return;
        }
        var update = prefs.edit().putLong(key, end);
        if (once) update.remove("onceStart").remove("onceEnd");
        update.commit();
        notifyWindowFinished(context, prefs, manual, once, manual ? prefs.getLong("manualFirst", session) : session, end);
    }

    private static void scheduleMonitoredEnd(Context context, long session, long end, long due) {
        var prefs = prefs(context);
        boolean manual = session == prefs.getLong("manualFirst", 0) && end == prefs.getLong("manualEnd", 0);
        boolean once = isOnce(prefs, new Schedule.Slot(session, session, end));
        WindowAlerts.scheduleAt(context, manual ? prefs.getLong("manualStart", session) : session, end, due, manual, once);
    }

    private static void completeMonitoredRun(Context context, long session, long end, boolean verifiedOff) {
        var prefs = prefs(context);
        boolean manual = session == prefs.getLong("manualFirst", 0) && end == prefs.getLong("manualEnd", 0);
        boolean once = isOnce(prefs, new Schedule.Slot(session, session, end));
        int remaining = remainingCycles(prefs);
        notifyWindowFinished(context, prefs, manual, once, manual ? prefs.getLong("manualFirst", session) : session, end);
        if (!verifiedOff) {
            ClimateEnergy.finish(context, prefs.getLong("lastStart", 0), System.currentTimeMillis(),
                ClimateEnergy.End.UNKNOWN, null);
            note(context, remaining > 0
                ? t(R.string.period_ended_without_fresh_kia_status) + remaining + "."
                : t(R.string.period_ended_kia_has_not_yet));
        }
        prefs.edit().putLong(once ? "lastOnceEndNotice" : manual ? "lastManualEndNotice" : "lastWeeklyEndNotice", end).commit();
        completeWindow(context, session, end);
    }

    private static void notifyWindowFinished(Context context, SharedPreferences prefs, boolean manual, boolean once,
            long session, long end) {
        String message = once ? t(R.string.your_one_time_period_has_ended)
            : manual && prefs.contains(MANUAL_PARTS) ? t(R.string.your_combined_period_has_ended)
            : manual ? t(R.string.your_manual_time_slot_has_ended) : t(R.string.your_planned_period_has_ended);
        note(context, message, session, end, false);
        if (prefs.getBoolean("notifyWindowEnd", true) && !prefs.getBoolean("quietAlerts", false)) {
            WindowAlerts.show(context, once ? 23 : manual ? 21 : 20, t(R.string.period_ended), message);
        }
    }

    static boolean stillCurrent(Context context, Schedule.Slot slot) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        return slot.equals(pendingContinuation(prefs, now))
            || (now < slot.end() && slot.equals(next(prefs, slot.time() - 1)));
    }

    private static Schedule.Slot pendingContinuation(SharedPreferences prefs, long now) {
        long time = prefs.getLong("monitoredContinuation", 0);
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        if (time <= 0 || now - time > 2 * Schedule.MINUTE || session <= 0) return null;
        if (countedRun(prefs, session, end)) {
            if (remainingCycles(prefs) <= 0 || now >= runDeadline(prefs)) return null;
        } else if (now >= end) {
            return null;
        }
        var window = next(prefs, session - 1);
        return window != null && window.session() == session && window.end() == end
            ? new Schedule.Slot(time, session, end) : null;
    }

    static long cycleEnd(Context context) {
        return prefs(context).getLong("cycleEnd", 0);
    }

    static long cooldownEnd(Context context) {
        return cooldownEnd(context, false);
    }

    private static long cooldownEnd(Context context, boolean manual) {
        var prefs = prefs(context);
        long lastStart = prefs.getLong("lastStart", 0);
        long appCooldown = lastStart == 0 || prefs.getLong("observedOffFor", 0) == lastStart
            ? 0 : Math.max(lastStart, prefs.getLong("confirmedStart", 0)) + Schedule.INTERVAL;
        return manual ? appCooldown : Math.max(appCooldown, KiaBackoff.until(prefs));
    }

    static long nextAllowedStart(Context context) {
        return nextAllowedStart(context, false);
    }

    private static long nextAllowedStart(Context context, boolean manual) {
        var prefs = prefs(context);
        long hold = prefs.getLong("monitoringStart", 0) > 0 ? runDeadline(prefs) : 0;
        return Math.max(Math.max(cooldownEnd(context, manual), hold),
            Math.max(cycleEnd(context), prefs.getLong("unconfirmedHold", 0)));
    }

    static synchronized Schedule.Slot watchedSlot(Context context) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        long sentAt = prefs.getLong("lastStart", 0);
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        if (sentAt <= 0 || prefs.getLong("confirmedStart", 0) < sentAt
                || now >= watchDeadline(prefs, sentAt, end)
                || (now >= cycleEnd(context) && prefs.getLong("monitoringStart", 0) != sentAt)) return null;
        return new Schedule.Slot(sentAt, session, end);
    }

    private static boolean countedRun(SharedPreferences prefs, long session, long end) {
        return session > 0 && session == prefs.getLong("activeSession", 0)
            && end == prefs.getLong("activeEnd", 0) && prefs.getInt("activeConfirmedCycles", 0) > 0;
    }

    static int remainingCycles(SharedPreferences prefs) {
        return Schedule.cycleCount(prefs.getLong("activeSession", 0), prefs.getLong("activeEnd", 0))
            - prefs.getInt("activeConfirmedCycles", 0);
    }

    private static long forecastEnd(SharedPreferences prefs) {
        return Math.max(prefs.getLong("activeEnd", 0), prefs.getLong("activeRunEnd", 0));
    }

    private static long runDeadline(SharedPreferences prefs) {
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        return countedRun(prefs, session, end) ? forecastEnd(prefs) + STATUS_OVERRUN : end;
    }

    static long watchDeadline(SharedPreferences prefs, long sentAt, long end) {
        return prefs.getLong("monitoringStart", 0) == sentAt
            ? runDeadline(prefs) : Math.min(end, prefs.getLong("cycleEnd", 0));
    }

    static synchronized void resumeClimateMonitoring(Context context, long now) {
        var prefs = prefs(context);
        long sentAt = prefs.getLong("lastStart", 0);
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        long expectedEnd = cycleEnd(context);
        if (!KiaCloud.linked(context) || KiaCloud.needsLink(context) || sentAt <= 0 || session <= 0
                || prefs.getLong("confirmedStart", 0) < sentAt || manuallyStopped(prefs)
                || expectedEnd <= now - STATUS_OVERRUN || expectedEnd <= 0 || now >= end
                || prefs.getInt("activeConfirmedCycles", 0) > 0) return;
        var window = next(prefs, session - 1);
        if (window == null || window.session() != session || window.end() != end) return;
        int target = Schedule.cycleCount(session, end);
        int started = 0;
        try {
            var history = new org.json.JSONArray(prefs.getString("history", "[]"));
            for (int index = 0; index < history.length(); index++) {
                var item = history.optJSONObject(index);
                if (item != null && item.optLong("time", 0) >= session
                        && (item.optBoolean("confirmed") || "Kia bevestigt: klimaatregeling gestart.".equals(item.optString("message")))) started++;
            }
        } catch (org.json.JSONException ignored) {}
        int completed = Math.max(1, Math.min(target, started));
        prefs.edit().putLong("monitoringStart", sentAt).putInt("activeConfirmedCycles", completed)
            .putLong("activeRunEnd", expectedEnd + (target - completed) * Schedule.INTERVAL)
            .remove("lastClimateSample").remove("observedClimateOn").commit();
        note(context, t(R.string.climate_control_resumes_for_the_current));
        scheduleMonitoredEnd(context, session, end, runDeadline(prefs));
        VehicleWatch.schedule(context);
    }

    static boolean monitorsClimate(Context context, Schedule.Slot slot) {
        return prefs(context).getLong("monitoringStart", 0) == slot.time();
    }

    static Schedule.Slot recordClimateSnapshot(Context context, Schedule.Slot slot, long measured, Boolean active) {
        return recordClimateSnapshot(context, slot, measured, active, null);
    }

    static synchronized Schedule.Slot recordClimateSnapshot(Context context, Schedule.Slot slot,
            long measured, Boolean active, org.json.JSONObject reading) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (!monitorsClimate(context, slot) || !slot.equals(watchedSlot(context))) return null;
        if (active == null || measured <= prefs.getLong("confirmedStart", 0)
                || measured <= prefs.getLong("lastClimateSample", 0)
                || measured < now - 2 * Schedule.MINUTE || measured > now + 5_000) {
            note(context, t(R.string.climate_control_kia_does_not_provide_2), slot.session(), slot.end(), false);
            return null;
        }
        var update = prefs.edit().putLong("lastClimateSample", measured);
        if (active) {
            if (reading != null) ClimateEnergy.observe(context, slot.time(), reading);
            update.putBoolean("observedClimateOn", true).apply();
            if (countedRun(prefs, slot.session(), slot.end()) && now > cycleEnd(context)) {
                warnDepartureDelay(context, now + remainingCycles(prefs) * Schedule.INTERVAL);
            }
            note(context, t(R.string.climate_control_still_active_according_to), slot.session(), slot.end(), false);
            return null;
        }
        if (!prefs.getBoolean("observedClimateOn", false) && measured < cycleEnd(context)) {
            update.apply();
            note(context, t(R.string.climate_control_kia_reports_out_but), slot.session(), slot.end(), false);
            return null;
        }
        ClimateEnergy.finish(context, slot.time(), measured, ClimateEnergy.End.STATUS_OFF, reading);
        boolean counted = countedRun(prefs, slot.session(), slot.end());
        int remaining = remainingCycles(prefs);
        update.remove("monitoringStart").remove("cycleEnd").putLong("observedOffFor", slot.time());
        if (!counted || remaining > 0) {
            update.putLong("monitoredContinuation", now);
            if (counted) update.putLong("activeRunEnd", now + remaining * Schedule.INTERVAL);
        }
        update.commit();
        if (counted && remaining > 0) warnDepartureDelay(context, forecastEnd(prefs));
        if (counted && remaining <= 0) {
            note(context, t(R.string.climate_control_kia_confirms_out_all), slot.session(), slot.end(), false);
            completeMonitoredRun(context, slot.session(), slot.end(), true);
            return null;
        }
        var continuation = pendingContinuation(prefs, now);
        note(context, continuation != null
            ? t(R.string.climate_control_kia_confirms_out_the)
            : t(R.string.climate_control_kia_confirms_out_there), slot.session(), slot.end(), false);
        schedule(context);
        VehicleWatch.schedule(context);
        return continuation;
    }

    static boolean awaitingConfirmation(Context context, long now) {
        return prefs(context).getLong("confirmationDeadline", 0) > now;
    }

    static synchronized boolean confirmStart(Context context, long sentAt, long confirmedAt) {
        return confirmStart(context, sentAt, confirmedAt, false);
    }

    static synchronized boolean confirmStart(Context context, long sentAt, long confirmedAt, boolean monitor) {
        var prefs = prefs(context);
        if (prefs.getLong("lastStart", 0) != sentAt) return false;
        int minutes = prefs.getInt("pendingDuration", 0);
        if (minutes == 0 || prefs.getLong("unconfirmedHold", 0) == 0) return false;
        var update = prefs.edit().putLong("confirmedStart", confirmedAt)
            .putLong("cycleEnd", confirmedAt + minutes * Schedule.MINUTE)
            .remove("unconfirmedHold").remove("confirmationDeadline").remove("pendingDuration");
        long session = prefs.getLong("activeSession", 0);
        if (session == 0) update.putLong("activeEnd", confirmedAt + minutes * Schedule.MINUTE);
        if (session > 0 && monitor) {
            int completed = prefs.getInt("activeConfirmedCycles", 0) + 1;
            int remaining = Math.max(0, Schedule.cycleCount(session, prefs.getLong("activeEnd", 0)) - completed);
            update.putInt("activeConfirmedCycles", completed)
                .putLong("activeRunEnd", confirmedAt + minutes * Schedule.MINUTE + remaining * Schedule.INTERVAL)
                .putLong("monitoringStart", sentAt).remove("lastClimateSample").remove("observedClimateOn");
        }
        if (session > 0) {
            var confirmed = new HashSet<>(prefs.getStringSet(CONFIRMED_WINDOWS, Collections.emptySet()));
            String current = Long.toString(session);
            long cutoff = confirmedAt - DAY;
            confirmed.removeIf(window -> !window.equals(current) && Long.parseLong(window) < cutoff);
            confirmed.add(current);
            update.putStringSet(CONFIRMED_WINDOWS, confirmed);
        }
        update.commit();
        if (session > 0 && monitor) {
            warnDepartureDelay(context, forecastEnd(prefs));
            scheduleMonitoredEnd(context, session, prefs.getLong("activeEnd", 0), runDeadline(prefs));
        }
        schedule(context);
        VehicleWatch.schedule(context);
        return true;
    }

    static synchronized void confirmationMissing(Context context, long sentAt) {
        var prefs = prefs(context);
        if (prefs.getLong("lastStart", 0) == sentAt) {
            prefs.edit().remove("confirmationDeadline").apply();
        }
    }

    static long activeEnd(Context context, long now) {
        var prefs = prefs(context);
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        if (countedRun(prefs, session, end) && now < runDeadline(prefs)) return forecastEnd(prefs);
        if (onceActive(prefs, now)) return prefs.getLong("onceEnd", 0);
        long manual = prefs.getLong("manualEnd", 0);
        if (manual > now) return manual;
        long started = prefs.getLong("activeEnd", 0);
        if (started > now) return started;
        var next = next(prefs, now - 1);
        return next != null && next.session() <= now && next.end() > now ? next.end() : 0;
    }

    static boolean hasConfirmedWindow(Context context, long end) {
        var prefs = prefs(context);
        long session = end == prefs.getLong("manualEnd", 0) ? prefs.getLong("manualFirst", 0)
            : end == prefs.getLong("onceEnd", 0) ? prefs.getLong("onceStart", 0)
            : prefs.getLong("activeSession", 0);
        if (end == forecastEnd(prefs)
                && countedRun(prefs, prefs.getLong("activeSession", 0), prefs.getLong("activeEnd", 0))) return true;
        return (session != 0 && prefs.getStringSet(CONFIRMED_WINDOWS, Set.of()).contains(Long.toString(session)))
            || (session != 0 && end == prefs.getLong("activeEnd", 0) && prefs.getLong("lastStart", 0) > 0
                && prefs.getLong("confirmedStart", 0) >= prefs.getLong("lastStart", 0));
    }

    static void rateLimitReached(Context context) {
        rateLimitReached(context, t(R.string.climate_start));
    }

    static void rateLimitReached(Context context, String operation) {
        long now = System.currentTimeMillis();
        var prefs = prefs(context);
        boolean first = KiaBackoff.until(prefs) <= now;
        long until = KiaBackoff.record(context, now);
        schedule(context);
        VehicleWatch.schedule(context);
        String message = KiaBackoff.message(until);
        if (first) note(context, t(R.string.kia_application_limit_at) + operation + ". " + message + ".");
        if (first && prefs.getBoolean("notifyStartLimit", true)) {
            WindowAlerts.failed(context, 22, t(R.string.kia_request_limit_2), message, StartLedger.Cause.LIMIT);
        }
    }

    static synchronized boolean reserveStart(Context context, long now, int minutes, Schedule.Slot slot) {
        return reserveStart(context, now, minutes, slot, false);
    }

    static synchronized boolean reserveStart(Context context, long now, int minutes, Schedule.Slot slot, boolean manual) {
        var prefs = prefs(context);
        long manualFirst = prefs.getLong("manualFirst", 0);
        boolean restartAfterStop = slot != null && slot.time() == manualFirst && slot.session() == manualFirst
            && slot.end() == prefs.getLong("manualEnd", 0) && manualRestartAvailable(prefs, now)
            && (manual || !KiaBackoff.paused(context));
        if (nextAllowedStart(context, manual) > now && !restartAfterStop) {
            note(context, t(R.string.skipped_climate_start_the_waiting_period));
            return false;
        }

        long end = now + minutes * Schedule.MINUTE;
        var update = prefs.edit();
        if (slot == null || prefs.getLong("activeSession", 0) != slot.session()) {
            update.remove("activeRunEnd").remove("activeConfirmedCycles");
        }
        update.remove("starts").remove("confirmedManualStopAt").remove("monitoringStart")
            .remove("monitoredContinuation").remove("observedOffFor")
            .putLong("lastStart", now).putLong("cycleEnd", 0)
            .putLong("previousStart", prefs.getLong("lastStart", 0))
            .putInt("pendingDuration", minutes).putLong("unconfirmedHold", end + CONFIRMATION_GRACE)
            .putLong("confirmationDeadline", now + CONFIRMATION_GRACE)
            .putLong("activeSession", slot == null ? 0 : slot.session())
            .putLong("activeEnd", slot == null ? end : slot.end()).commit();
        schedule(context);
        return true;
    }

    static synchronized void stopUnavailable(Context context, long sentAt) {
        var prefs = prefs(context);
        if (prefs.getLong("lastStart", 0) != sentAt || prefs.getInt("pendingDuration", 0) == 0) return;
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        completeWindow(context, session, end);
        StartLedger.record(context, sentAt, StartLedger.Kind.FAILED, StartLedger.Cause.REFUSED);
        note(context, t(R.string.period_completed_the_car_has_refused), session, end, false, Outcome.REFUSED,
            StartLedger.Cause.REFUSED);
    }

    static synchronized void finishIfInUse(Context context, Schedule.Slot slot) {
        if (!stillCurrent(context, slot)) return;
        StartLedger.record(context, slot.time(), StartLedger.Kind.SKIPPED, StartLedger.Cause.IN_USE);
        completeWindow(context, slot.session(), slot.end());
        recordInUse(context, slot);
    }

    static synchronized void finishWatchedWindow(Context context, Schedule.Slot slot) {
        if (!slot.equals(watchedSlot(context))) return;
        ClimateEnergy.finish(context, slot.time(), System.currentTimeMillis(), ClimateEnergy.End.UNLOCKED, null);
        completeWindow(context, slot.session(), slot.end());
        recordInUse(context, slot);
    }

    private static void recordInUse(Context context, Schedule.Slot slot) {
        prefs(context).edit().putLong("finishedOnUnlock", System.currentTimeMillis()).apply();
        note(context, t(R.string.period_completed_the_car_is_unlocked), slot.session(), slot.end(), false, Outcome.UNLOCKED);
    }

    private static void completeWindow(Context context, long session, long end) {
        var prefs = prefs(context);
        var update = prefs.edit().putLong("cycleEnd", 0).remove("activeEnd").remove("activeSession")
            .remove("unconfirmedHold").remove("confirmationDeadline").remove("pendingDuration")
            .remove("monitoringStart").remove("monitoredContinuation").remove("activeRunEnd").remove("activeConfirmedCycles");
        if (isOnce(prefs, new Schedule.Slot(session, session, end))) {
            update.remove("onceStart").remove("onceEnd");
        } else if (session != 0 && session == prefs.getLong("manualFirst", 0)
                && end == prefs.getLong("manualEnd", 0)) {
            update.remove("manualFirst").remove("manualEnd").remove("manualStart")
                .remove(MANUAL_PARTS);
        } else if (session != 0) {
            update.putLong("cancelledWeeklySession", session);
            if (session == prefs.getLong("weeklySession", 0)) {
                update.remove("weeklySession").remove("weeklyEnd");
            }
        }
        update.commit();
        schedule(context);
        VehicleWatch.schedule(context);
    }

    static synchronized void startFailed(Context context, Schedule.Slot slot, long sentAt, boolean keepCooldown) {
        var prefs = prefs(context);
        var update = prefs.edit();
        if (sentAt != 0 && prefs.getLong("lastStart", 0) == sentAt
                && prefs.getLong("confirmedStart", 0) < sentAt) {
            if (!keepCooldown) update.putLong("lastStart", prefs.getLong("previousStart", 0));
            update.remove("unconfirmedHold").remove("confirmationDeadline").remove("pendingDuration")
                .remove("activeSession").remove("activeEnd").remove("cycleEnd").remove("activeRunEnd").remove("activeConfirmedCycles");
        }
        if (slot != null && slot.session() == prefs.getLong("manualFirst", 0)
                && slot.end() == prefs.getLong("manualEnd", 0)
                && (sentAt == 0 || prefs.getLong("confirmedStart", 0) < sentAt)) {
            update.remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove(MANUAL_PARTS);
        }
        update.commit();
        schedule(context);
        VehicleWatch.schedule(context);
    }

    static synchronized boolean cancelRunning(Context context) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        long cycle = cycleEnd(context);
        boolean sendStop = cycle > now || (prefs.getLong("monitoringStart", 0) > 0 && runDeadline(prefs) > now)
            || externalClimateActive(prefs, now) || prefs.getLong("unconfirmedHold", 0) > now
            || prefs.getLong("stopVehiclePending", 0) > now;
        long session = prefs.getLong("activeSession", 0);
        long end = prefs.getLong("activeEnd", 0);
        if (prefs.getLong("manualEnd", 0) > now) {
            session = prefs.getLong("manualFirst", 0);
            end = prefs.getLong("manualEnd", 0);
        } else if (onceActive(prefs, now)) {
            session = prefs.getLong("onceStart", 0);
            end = prefs.getLong("onceEnd", 0);
        }
        if (session == 0 && end == 0) {
            var slot = next(prefs, now - 1);
            if (slot != null && slot.session() <= now && slot.end() > now) {
                session = slot.session();
                end = slot.end();
            }
        }
        completeWindow(context, session, end);
        prefs.edit().putLong("manualStopAt", now).apply();
        if (sendStop) {
            prefs.edit().putLong("cycleEnd", cycle)
                .putLong("stopVehiclePending", Math.max(cycle, now + CONFIRMATION_GRACE)).commit();
        }
        schedule(context);
        if (end > now) note(context, t(R.string.current_period_cancelled), session, end, false, Outcome.STOPPED);
        return sendStop;
    }

    static boolean manuallyStopped(SharedPreferences prefs) {
        long stoppedAt = prefs.getLong("manualStopAt", 0);
        return stoppedAt > 0 && stoppedAt >= prefs.getLong("lastStart", 0);
    }

    static boolean manualRestartAvailable(SharedPreferences prefs, long now) {
        long stopped = prefs.getLong("confirmedManualStopAt", 0);
        return stopped > 0 && stopped == prefs.getLong("manualStopAt", 0)
            && stopped >= prefs.getLong("lastStart", 0) && prefs.getLong("cycleEnd", 0) <= now
            && prefs.getLong("unconfirmedHold", 0) <= now;
    }

    static synchronized void confirmStop(Context context) {
        var prefs = prefs(context);
        var update = prefs.edit().remove("cycleEnd").remove("stopVehiclePending").remove("externalClimateSeen")
            .putLong("externalClimateChecked", System.currentTimeMillis());
        if (prefs.contains("stopVehiclePending") && manuallyStopped(prefs)) {
            update.putLong("confirmedManualStopAt", prefs.getLong("manualStopAt", 0));
        }
        update.apply();
        schedule(context);
    }

    static void clearCycle(Context context, long sentAt) {
        var prefs = prefs(context);
        if (prefs.getLong("lastStart", 0) == sentAt) prefs.edit().putLong("cycleEnd", 0)
            .remove("unconfirmedHold").remove("confirmationDeadline").remove("pendingDuration").apply();
    }
}




