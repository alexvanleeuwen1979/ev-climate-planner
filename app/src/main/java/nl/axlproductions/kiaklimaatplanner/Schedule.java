package nl.axlproductions.kiaklimaatplanner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.LongPredicate;

final class Schedule {
    static final long MINUTE = 60_000;
    static final int CYCLE_MINUTES = 15;
    static final long INTERVAL = CYCLE_MINUTES * MINUTE;
    static final int MONITORED_MINUTES = CYCLE_MINUTES;
    static final long MONITORED_INTERVAL = INTERVAL;
    record Window(int day, int minute, int duration, boolean readyBy) {
        Window(int day, int minute, int duration) {
            this(day, minute, duration, false);
        }
    }
    record Slot(long time, long session, long end) {}

    static Slot next(long after, ZoneId zone, List<Window> windows) {
        return next(after, zone, windows, 0);
    }

    static Slot next(long after, ZoneId zone, List<Window> windows, long cancelledSession) {
        return next(after, zone, windows, time -> time == cancelledSession);
    }

    static Slot next(long after, ZoneId zone, List<Window> windows, LongPredicate excluded) {
        var today = Instant.ofEpochMilli(after).atZone(zone).toLocalDate();
        Slot earliest = null;
        for (int offset = -2; offset <= 14; offset++) {
            var date = today.plusDays(offset);
            int day = date.getDayOfWeek().getValue() - 1;
            for (Window window : windows) {
                if (window.day() != day) continue;
                long start = startOn(date, window, zone);
                if (excluded.test(start)) continue;
                Slot candidate = nextCycle(after, start, start + window.duration() * MINUTE);
                if (candidate != null && (earliest == null || candidate.time() < earliest.time())) {
                    earliest = candidate;
                }
            }
        }
        return earliest;
    }

    static long startOn(LocalDate date, Window window, ZoneId zone) {
        long chosen = date.atStartOfDay().plusMinutes(window.minute()).atZone(zone).toInstant().toEpochMilli();
        return chosen - (window.readyBy() ? window.duration() * MINUTE : 0);
    }

    static Slot nextCycle(long after, long start, long end) {
        long time = after < start ? start : start + ((after - start) / INTERVAL + 1) * INTERVAL;
        return time < end ? new Slot(time, start, end) : null;
    }

    static int cycleCount(long start, long end) {
        return (int) Math.max(0, (end - start + INTERVAL - 1) / INTERVAL);
    }

    static int nearestDuration(int minutes) {
        int step = CYCLE_MINUTES;
        return Math.max(step, (minutes + (step - 1) / 2) / step * step);
    }

    static boolean overlaps(List<Window> windows) {
        boolean[] occupied = new boolean[7 * 1440];
        for (Window window : windows) {
            int start = window.day() * 1440 + window.minute() - (window.readyBy() ? window.duration() : 0);
            for (int minute = 0; minute < window.duration(); minute++) {
                int index = Math.floorMod(start + minute, occupied.length);
                if (occupied[index]) return true;
                occupied[index] = true;
            }
        }
        return false;
    }
}
