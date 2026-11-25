package nl.axlproductions.kiaklimaatplanner;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public final class ScheduleCheck {
    public static void main(String[] args) {
        ZoneId zone = ZoneId.of("Europe/Amsterdam");
        long monday = ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, zone).toInstant().toEpochMilli();
        var morning = new Schedule.Window(0, 8 * 60, 50);
        var evening = new Schedule.Window(0, 18 * 60, 20);
        var windows = List.of(evening, morning);
        long after = monday - 1;
        for (int index = 0; index < 3; index++) {
            var slot = Schedule.next(after, zone, windows);
            check(slot.time() == monday + index * Schedule.INTERVAL, "20-minute cycle");
            check(slot.end() == monday + 50 * Schedule.MINUTE, "Time window end");
            check(Schedule.cycleMinutes(slot.time(), slot.end()) == (index == 2 ? 10 : 20), "Final short cycle");
            after = slot.time();
        }
        check(Schedule.next(after, zone, windows).time() == monday + 600 * Schedule.MINUTE, "Second window");
        check(Schedule.nextCycle(monday + 20 * Schedule.MINUTE, monday, monday + 39 * Schedule.MINUTE) == null,
            "No manual restart after the duration");
        check(!Schedule.overlaps(windows), "Independent windows");
        check(Schedule.next(monday, zone, windows, monday).time() == monday + 600 * Schedule.MINUTE,
            "Cancel just the morning window");
        check(Schedule.next(monday, zone, List.of(morning), monday).time() == monday + 7 * 24 * 60 * Schedule.MINUTE,
            "The next week still runs");

        var night = new Schedule.Window(6, 23 * 60 + 50, 60);
        check(Schedule.overlaps(List.of(night, new Schedule.Window(0, 10, 20))), "Overlap across the week boundary");
        check(!Schedule.overlaps(List.of(night, new Schedule.Window(0, 50, 20))), "Adjacent windows");
        long midnight = monday - 480 * Schedule.MINUTE;
        check(Schedule.next(midnight, zone, List.of(night)).time() == midnight + 10 * Schedule.MINUTE,
            "Previous day cycles continue after midnight");
        System.out.println("ScheduleCheck geslaagd");
    }

    private static void check(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }
}

