package nl.axlproductions.kiaklimaatplanner;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import com.chaquo.python.Python;

public final class RuntimeCheck extends Instrumentation {
    private static final String FAKE = "test-only-session-value";
    private String phase;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args.getString("phase");
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            Context context = getTargetContext();
            if (!Build.PRODUCT.contains("sdk_gphone")) throw new AssertionError("Emulator test only");
            if ("persist".equals(phase)) {
                if (KiaCloud.linked(context)) throw new AssertionError("Unexpected linked account");
                KiaSessionStore.save(context, FAKE);
                String stored = context.getSharedPreferences("kia_session", Context.MODE_PRIVATE).getString("sealed", "");
                if (stored.contains(FAKE)) throw new AssertionError("Session is stored in plaintext");
                results.putString("stream", "\nEncrypted session stored.\n");
            } else if ("widget".equals(phase)) {
                verifyWidget(context);
                results.putString("stream", "\nAndroid widget bound and rendered.\n");
            } else if ("paused-refresh".equals(phase)) {
                verifyPausedManualRefresh(context);
                results.putString("stream", "\nManual paused refresh reads cached Kia data once, lifts the current pause on valid data, and stops after another rate limit.\n");
            } else if ("paused-start".equals(phase)) {
                verifyPausedManualStart(context);
                results.putString("stream", "\nManual start and stop reach Kia during a status pause. Automatic requests stay paused, and an API rejection does not start the timer.\n");
            } else if ("features".equals(phase)) {
                verifyPlanningOptions(context);
                verifyEnergyMeter(context);
                verifyAdditionalFeatures(context);
                verifyPhonePositionAndEnergyHistory(context);
                verifyBatterySchedule(context);
                verifyRatePauseAndEndChecks(context);
                verifyDashboardRefreshAndIntro(context);
                results.putString("stream", "\nSmart defrost, profiles, ready-by, recurring overrides, pause, backup, energy calibration, conditions, agenda, extensions, recovery, diagnostics, GPS, energy history, hourly battery scheduling, end-of-cycle timing, Kia backoff, shared manual refresh and intro timing passed.\n");
            } else {
                KiaCloud.module(context);
                var builtins = Python.getInstance().getBuiltins();
                var namespace = builtins.callAttr("dict");
                builtins.callAttr("exec", """
                    from Crypto.PublicKey import RSA
                    from Crypto.Cipher import PKCS1_v1_5
                    key = RSA.generate(1024)
                    cipher = PKCS1_v1_5.new(key)
                    assert cipher.decrypt(cipher.encrypt(b'klimaat'), b'error') == b'klimaat'
                    """, namespace);
                if (!FAKE.equals(KiaSessionStore.read(context))) {
                    throw new AssertionError("Encrypted session lost after process restart");
                }
                builtins.callAttr("exec", """
                    import kia_mobile
                    from types import SimpleNamespace

                    def capture_options(vehicle, options):
                        assert vehicle == 'emulator'
                        return options

                    prepared = (SimpleNamespace(start_climate=capture_options), 'emulator', '')
                    for wheel, mirrors, expected in [(False, False, 0), (False, True, 2),
                                                     (True, False, 3), (True, True, 1)]:
                        options = kia_mobile.start(prepared, 21.0, 15, wheel, mirrors, True)
                        assert options.heating == expected and options.defrost is True
                        assert options.steering_wheel == (1 if wheel else None)
                        assert options.side_rear_mirror_heating == (1 if mirrors else None)
                        assert options.duration == 15

                    kia_mobile.prepare_start = lambda session: (None, None, session)
                    kia_mobile.vehicle_in_use = lambda prepared: False
                    def mock_start(prepared, temperature, duration, wheel, mirrors, windshield):
                        assert 0 < duration <= 20 and temperature == 22.5
                        assert wheel is True and mirrors is False and windshield is True
                        return 'local-emulator-action'
                    kia_mobile.start = mock_start
                    import json, time
                    kia_mobile.observed_heating = lambda prepared: json.dumps({
                        'time': int(time.time() * 1000), 'windshield': True, 'wheel': False,
                        'rear': None, 'mirrors': 1, 'driver_seat': 'High Heat',
                    })
                    kia_mobile.battery = lambda prepared, force: (
                        json.dumps({'percentage': 65, 'time': int(time.time() * 1000), 'climate': False}) if force
                        else '{"percentage":64,"time":1770000000000}')
                    kia_mobile.result = lambda prepared, action: 'SUCCESS'
                    """, namespace);
                long historyNow = System.currentTimeMillis();
                var seeded = new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("time", historyNow - 6 * 24 * 60 * Schedule.MINUTE)
                        .put("message", "Binnen zeven dagen"))
                    .put(new org.json.JSONObject().put("time", historyNow - Planner.HISTORY_AGE - 60_000)
                        .put("message", "Buiten zeven dagen"));
                Planner.prefs(context).edit().putString("history", seeded.toString()).commit();
                for (int index = 0; index < 55; index++) Planner.note(context, "Emulator " + index);
                var history = new org.json.JSONArray(Planner.prefs(context).getString("history", "[]"));
                if (history.length() != 56 || !history.getJSONObject(0).getString("message").equals("Emulator 54")
                        || !history.getJSONObject(55).getString("message").equals("Binnen zeven dagen")) {
                    throw new AssertionError("Meldingshistorie bewaart geen volledige zeven dagen");
                }
                Planner.note(context, "Wachten op emulatoralarm");
                if (Planner.temperatureHalves(Planner.prefs(context)) != 42) {
                    throw new AssertionError("Existing installations must default to 21 °C");
                }
                Planner.prefs(context).edit().putString("kiaModel", "e-Niro emulator").putInt("temperatureHalves", 45)
                    .putBoolean("heatWheel", true).putBoolean("heatMirrors", false)
                    .putBoolean("heatWindshield", true).commit();
                try {
                    verifyDaySwitches(context);
                    verifyWindowHeat(context);
                    verifyFirstCycleDefrost(context);
                    verifyOneTime(context);
                    verifyCombinedWindows(context);
                    verifyConfirmationTiming(context);
                    verifyStartFailureAndStop(context);
                    verifyExternalClimate(context, builtins, namespace);
                    verifyMonitoredContinuation(context);
                    verifyQuarterHourCycleCount(context);
                    verifyClimateIndicator(context);
                    verifyHeatingReadback(context);
                    verifyAlerts(context);
                    builtins.callAttr("exec", "kia_mobile.vehicle_in_use = lambda prepared: True", namespace);
                    verifyLastCycleWatch(context);
                    builtins.callAttr("exec", "kia_mobile.vehicle_in_use = lambda prepared: True", namespace);
                    verifyEarlyCompletion(context, false, "Tijdvak afgerond. De auto is ontgrendeld of ingeschakeld.");
                    builtins.callAttr("exec", """
                        kia_mobile.vehicle_in_use = lambda prepared: False
                        kia_mobile.result = lambda prepared, action: 'FAILED'
                        """, namespace);
                    verifyEarlyCompletion(context, true, "Tijdvak afgerond. De auto heeft de klimaatstart geweigerd.");
                    verifyWeeklyRefusal(context);
                    builtins.callAttr("exec", "kia_mobile.result = lambda prepared, action: 'SUCCESS'", namespace);
                    KiaCloud.readBattery(context, false);
                    if (Planner.prefs(context).getInt("battery", -1) != 64) {
                        throw new AssertionError("Accustatus ontbreekt");
                    }
                    KiaCloud.readBattery(context, true);
                    if (!Planner.prefs(context).getString("status", "").contains("Uit volgens Kia")) {
                        throw new AssertionError("A dashboard refresh did not add the climate status to history");
                    }
                    KiaCloud.readBattery(context, false);
                    if (Planner.prefs(context).getInt("battery", -1) != 65) {
                        throw new AssertionError("De verse accumeting is niet behouden");
                    }
                    verifyHourlyBattery(context, builtins, namespace);
                    Planner.scheduleTest(context);
                    getUiAutomation().executeShellCommand("input keyevent 223").close();
                    long deadline = SystemClock.elapsedRealtime() + 60_000;
                    boolean verified = false;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        String status = Planner.prefs(context).getString("status", "");
                        if (status.equals("Kia bevestigt: klimaatregeling gestart.")) {
                            PowerManager power = context.getSystemService(PowerManager.class);
                            if (power.isInteractive()) throw new AssertionError("Screen is still on");
                            results.putString("stream", "\nAndroid RSA, encrypted session, battery status, heat options, "
                                + "temperature, first-cycle defrost, day switches, confirmation timing, early completion, last-cycle watch, alert switches, cooldown "
                                + "and screen-off alarm passed.\n");
                            verified = true;
                            break;
                        }
                        SystemClock.sleep(300);
                    }
                    if (!verified) throw new AssertionError(Planner.prefs(context).getString("status", "No alarm status"));
                } finally {
                    Planner.prefs(context).edit().remove("kiaModel")
                        .remove("heatWheel").remove("heatMirrors").remove("heatWindshield").commit();
                    KiaSessionStore.clear(context);
                }
            }
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "\n" + error + "\n");
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private static void verifyDaySwitches(Context context) {
        var prefs = Planner.prefs(context);
        var previous = prefs.getAll();
        var update = prefs.edit().putBoolean("enabled", true);
        for (int day = 0; day < 7; day++) update.putBoolean("dayEnabled" + day, false);
        update.putBoolean("slotEnabled0_0", true).putBoolean("slotEnabled0_1", false)
            .putInt("start0_0", 8 * 60).putInt("duration0_0", 30).commit();
        try {
            if (Planner.next(context) != null) throw new AssertionError("Disabled day was scheduled");
            prefs.edit().putBoolean("dayEnabled0", true).commit();
            if (Planner.next(context) == null) throw new AssertionError("Enabled day was not scheduled");
            prefs.edit().putBoolean("slotEnabled0_0", false).commit();
            if (Planner.next(context) != null || Planner.duration(prefs, 0, 0) != 30
                    || Planner.startMinute(prefs, 0, 0) != 8 * 60) {
                throw new AssertionError("Disabled slot was scheduled or its times were lost");
            }
        } finally {
            var restore = prefs.edit();
            var keys = new java.util.ArrayList<>(java.util.List.of(
                "enabled", "slotEnabled0_0", "slotEnabled0_1", "start0_0", "duration0_0"));
            for (int day = 0; day < 7; day++) keys.add("dayEnabled" + day);
            for (String key : keys) {
                Object value = previous.get(key);
                if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                else if (value instanceof Integer number) restore.putInt(key, number);
                else restore.remove(key);
            }
            restore.commit();
        }
    }

    private static void verifyWindowHeat(Context context) {
        var prefs = Planner.prefs(context);
        var zone = java.time.ZoneId.of("Europe/Amsterdam");
        long monday = java.time.LocalDate.of(2026, 11, 23).atStartOfDay(zone).toInstant().toEpochMilli();
        long morning = monday + 8 * 60 * Schedule.MINUTE;
        long late = monday + (23 * 60 + 45) * Schedule.MINUTE;
        var morningSlot = new Schedule.Slot(morning, morning, morning + 30 * Schedule.MINUTE);
        var overnightCycle = new Schedule.Slot(late + Schedule.INTERVAL, late, late + 45 * Schedule.MINUTE);
        int defaults = Planner.HEAT_WHEEL | Planner.HEAT_WINDSHIELD;
        prefs.edit().putInt("start0_0", 8 * 60).putInt("duration0_0", 30)
            .putInt("start0_1", 23 * 60 + 45).putInt("duration0_1", 45)
            .putInt("slotHeat0_1", Planner.HEAT_MIRRORS).commit();
        try {
            if (Planner.heatFor(prefs, morningSlot, zone) != defaults
                    || Planner.heatFor(prefs, overnightCycle, zone) != Planner.HEAT_MIRRORS) {
                throw new AssertionError("Window heat must inherit defaults or keep its own settings after midnight");
            }
            prefs.edit().putInt("slotHeat0_0", 0).commit();
            if (Planner.heatFor(prefs, morningSlot, zone) != 0) {
                throw new AssertionError("Turning all options off must override the defaults");
            }
            prefs.edit().putLong("manualFirst", morningSlot.session()).putLong("manualEnd", morningSlot.end()).commit();
            if (Planner.heatFor(prefs, morningSlot, zone) != defaults || Planner.heatFor(prefs, null, zone) != defaults) {
                throw new AssertionError("Manual and test starts must use global heat settings");
            }
        } finally {
            prefs.edit().remove("start0_0").remove("duration0_0").remove("slotHeat0_0")
                .remove("start0_1").remove("duration0_1").remove("slotHeat0_1")
                .remove("manualFirst").remove("manualEnd").commit();
        }
    }

    private static void verifyFirstCycleDefrost(Context context) {
        var prefs = Planner.prefs(context);
        long sent = System.currentTimeMillis() - 1_000;
        long end = sent + 40 * Schedule.MINUTE;
        var first = new Schedule.Slot(sent, sent, end);
        var later = new Schedule.Slot(sent + Schedule.INTERVAL, sent, end);
        var nextWindow = new Schedule.Slot(end, end, end + Schedule.INTERVAL);
        var zone = java.time.ZoneId.systemDefault();
        int defrost = Planner.HEAT_WHEEL | Planner.HEAT_WINDSHIELD;
        prefs.edit().putLong("manualStart", sent).putLong("manualFirst", sent).putLong("manualEnd", end).commit();
        try {
            if (Planner.heatForCycle(prefs, first, zone) != defrost || !Planner.reserveStart(context, sent, 20, first)
                    || Planner.heatForCycle(prefs, later, zone) != defrost) {
                throw new AssertionError("An unconfirmed request must not consume first-cycle defrost");
            }
            if (!Planner.confirmStart(context, sent, sent + 500)
                    || Planner.heatForCycle(prefs, later, zone) != Planner.HEAT_WHEEL
                    || Planner.heatForCycle(prefs, nextWindow, zone) != defrost) {
                throw new AssertionError("Defrost must be cleared only on subsequent cycles of the same window");
            }
            prefs.edit().putBoolean("heatWindshield", false).commit();
            if (Planner.heatForCycle(prefs, nextWindow, zone) != Planner.HEAT_WHEEL) {
                throw new AssertionError("A disabled windshield option cannot be enabled by the first cycle");
            }
        } finally {
            prefs.edit().putBoolean("heatWindshield", true).remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("confirmedClimateWindows").commit();
            clearClimateState(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyConfirmationTiming(Context context) {
        clearClimateState(context);
        long confirmed = System.currentTimeMillis();
        long sent = confirmed - 30_000;
        long expectedEnd = confirmed + Schedule.INTERVAL;
        try {
            if (!Planner.reserveStart(context, sent, Schedule.CYCLE_MINUTES, null)
                    || Planner.cycleEnd(context) != 0
                    || !Planner.awaitingConfirmation(context, confirmed)
                    || Planner.nextAllowedStart(context) <= sent + Schedule.INTERVAL) {
                throw new AssertionError("Timer started before Kia confirmation or next start was too early");
            }
            if (!Planner.confirmStart(context, sent, confirmed)
                    || Planner.cycleEnd(context) != expectedEnd
                    || Planner.nextAllowedStart(context) != expectedEnd
                    || Planner.awaitingConfirmation(context, confirmed)) {
                throw new AssertionError("Cycle was not timed from Kia confirmation");
            }
            if (Planner.reserveStart(context, expectedEnd - 1, Schedule.CYCLE_MINUTES, null)
                    || !Planner.reserveStart(context, expectedEnd, Schedule.CYCLE_MINUTES, null)) {
                throw new AssertionError("The next start did not wait for the confirmed cycle to end");
            }
        } finally {
            clearClimateState(context);
        }
    }

    private static void verifyStartFailureAndStop(Context context) {
        clearClimateState(context);
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long previous = now - 2 * Schedule.INTERVAL;
        long first = now - 1_000;
        long end = first + 2 * Schedule.INTERVAL;
        var slot = new Schedule.Slot(first, first, end);
        prefs.edit().putLong("manualStart", first).putLong("manualFirst", first)
            .putLong("manualEnd", end).putLong("lastStart", previous).commit();
        try {
            if (!Planner.reserveStart(context, now, Schedule.CYCLE_MINUTES, slot)
                    || Planner.hasConfirmedWindow(context, end)) {
                throw new AssertionError("An unconfirmed manual window became visible");
            }
            Planner.startFailed(context, slot, now, false);
            if (Planner.activeEnd(context, now) != 0 || Planner.cycleEnd(context) != 0
                    || prefs.getLong("lastStart", 0) != previous || Planner.nextAllowedStart(context) >= now) {
                throw new AssertionError("Failed start held the timer or prevented retry");
            }
            long retry = now + 1;
            if (!Planner.reserveStart(context, retry, Schedule.CYCLE_MINUTES, null)
                    || !Planner.confirmStart(context, retry, retry)
                    || !Planner.cancelRunning(context)
                    || !Planner.manuallyStopped(prefs)
                    || Planner.manualRestartAvailable(prefs, System.currentTimeMillis())
                    || Planner.cycleEnd(context) != retry + Schedule.INTERVAL) {
                throw new AssertionError("Stopping a confirmed cycle cleared its timer before Kia confirmation");
            }
            Planner.confirmStop(context);
            if (Planner.cycleEnd(context) != 0 || !Planner.manuallyStopped(prefs)
                    || Planner.nextAllowedStart(context) != retry + Schedule.INTERVAL) {
                throw new AssertionError("Confirmed stop did not clear the cycle or preserve the cooldown");
            }
            long manual = System.currentTimeMillis() + 1;
            if (!Planner.manualRestartAvailable(prefs, manual)
                    || Planner.reserveStart(context, manual, Schedule.CYCLE_MINUTES, null)) {
                throw new AssertionError("Only an explicit manual restart may bypass the cooldown");
            }
            var restart = new Schedule.Slot(manual, manual, manual + Schedule.INTERVAL);
            prefs.edit().putLong("manualStart", manual).putLong("manualFirst", manual)
                .putLong("manualEnd", restart.end()).commit();
            if (!Planner.reserveStart(context, manual, Schedule.CYCLE_MINUTES, restart)
                    || Planner.manualRestartAvailable(prefs, manual)) {
                throw new AssertionError("The confirmed stop did not permit exactly one manual restart");
            }
        } finally {
            clearClimateState(context);
            prefs.edit().remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("stopVehiclePending").remove("previousStart").remove("manualStopAt")
                .remove("confirmedManualStopAt").commit();
        }
    }

    private static void verifyExternalClimate(Context context, com.chaquo.python.PyObject builtins,
                                              com.chaquo.python.PyObject namespace) {
        var prefs = Planner.prefs(context);
        long first = System.currentTimeMillis() - 1_000;
        long end = first + 20 * Schedule.MINUTE + 50_000;
        var slot = new Schedule.Slot(first, first, end);
        prefs.edit().putLong("manualStart", first).putLong("manualFirst", first)
            .putLong("manualEnd", end).commit();
        try {
            builtins.callAttr("exec", """
                kia_mobile.cached_climate = lambda prepared: json.dumps({
                    'time': int(time.time() * 1000), 'climate': True})
                """, namespace);
            KiaCloud.start(context, slot);
            if (Planner.cycleEnd(context) != 0 || prefs.getLong("lastStart", 0) != 0
                    || !Planner.externalClimateActive(prefs, System.currentTimeMillis())
                    || prefs.getLong("externalRecheckTime", 0) != first) {
                throw new AssertionError("Active external climate should postpone the planned start");
            }
            builtins.callAttr("exec", "kia_mobile.cached_climate = lambda prepared: '{}'", namespace);
            KiaCloud.start(context, slot);
            if (Planner.cycleEnd(context) != 0) throw new AssertionError("Missing status did not keep waiting");
            builtins.callAttr("exec", """
                kia_mobile.cached_climate = lambda prepared: json.dumps({
                    'time': int(time.time() * 1000), 'climate': False})
                """, namespace);
            KiaCloud.start(context, slot);
            if (Planner.cycleEnd(context) <= System.currentTimeMillis()
                    || Planner.externalClimateActive(prefs, System.currentTimeMillis())
                    || prefs.getLong("externalRecheckTime", 0) != 0) {
                throw new AssertionError("Fresh climate-off status did not let the planner take over");
            }
        } finally {
            builtins.callAttr("exec", "kia_mobile.cached_climate = lambda prepared: '{}'", namespace);
            prefs.edit().remove("externalClimateSeen").remove("externalClimateChecked").remove("externalRecheckTime")
                .remove("manualStart").remove("manualFirst").remove("manualEnd").remove("handled")
                .remove("confirmedClimateWindows").commit();
            clearClimateState(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyMonitoredContinuation(Context context) {
        clearClimateState(context);
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long sent = now - 14 * Schedule.MINUTE;
        long confirmed = sent + 2_000;
        long end = sent + 40 * Schedule.MINUTE;
        var planned = new Schedule.Slot(sent, sent, end);
        prefs.edit().putLong("manualStart", sent).putLong("manualFirst", sent).putLong("manualEnd", end).commit();
        try {
            if (!Planner.reserveStart(context, sent, Schedule.MONITORED_MINUTES, planned)
                    || !Planner.confirmStart(context, sent, confirmed)) {
                throw new AssertionError("The monitored cycle could not start");
            }
            Planner.resumeClimateMonitoring(context, now);
            var watched = Planner.watchedSlot(context);
            if (watched == null || Planner.nextAllowedStart(context) < end
                    || Planner.recordClimateSnapshot(context, watched, confirmed - 1, false) != null
                    || Planner.recordClimateSnapshot(context, watched, now - 70_000, false) != null
                    || Planner.recordClimateSnapshot(context, watched, now - 60_000, true) != null
                    || Planner.recordClimateSnapshot(context, watched, now - 60_001, false) != null
                    || !prefs.getString("status", "").contains("geen verse")) {
                throw new AssertionError("Unconfirmed or stale climate status incorrectly allowed a start");
            }
            var continuation = Planner.recordClimateSnapshot(context, watched, now, false);
            if (continuation == null || continuation.session() != sent || continuation.end() != end
                    || !prefs.getString("status", "").contains("volgende cyclus vrij")
                    || Planner.cycleEnd(context) != 0 || Planner.nextAllowedStart(context) > now
                    || !Planner.reserveStart(context, System.currentTimeMillis(), Schedule.MONITORED_MINUTES, continuation)
                    || Planner.stillCurrent(context, continuation)) {
                throw new AssertionError("Fresh OFF did not release exactly one continuation within the active window");
            }
        } finally {
            prefs.edit().remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("confirmedClimateWindows").remove("handled").commit();
            clearClimateState(context);
            Planner.schedule(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyQuarterHourCycleCount(Context context) {
        clearClimateState(context);
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long session = now - 46 * Schedule.MINUTE;
        long nominalEnd = session + 45 * Schedule.MINUTE;
        long initial = now - 33 * Schedule.MINUTE;
        var slot = new Schedule.Slot(session, session, nominalEnd);
        prefs.edit().putLong("manualStart", session).putLong("manualFirst", session)
            .putLong("manualEnd", nominalEnd).commit();
        try {
            if (Schedule.CYCLE_MINUTES != 15 || Schedule.cycleCount(session, nominalEnd) != 3
                    || !Planner.reserveStart(context, initial, 15, slot)
                    || !Planner.confirmStart(context, initial, initial + 1_000, true)
                    || Planner.activeEnd(context, now) <= now
                    || Planner.remainingCycles(prefs) != 2) {
                throw new AssertionError("Late confirmation lost part of the 45-minute window");
            }
            var nominalAlarm = new android.content.Intent(WindowAlerts.END)
                .putExtra("manual", true).putExtra("session", session).putExtra("end", nominalEnd);
            new Planner().onReceive(context, nominalAlarm);
            if (!prefs.contains("manualEnd") || prefs.contains("lastManualEndNotice")) {
                throw new AssertionError("Original end timestamp ended the run before the remaining cycles");
            }
            var watched = Planner.watchedSlot(context);
            slot = Planner.recordClimateSnapshot(context, watched, now + 1, false);
            for (int cycle = 2; cycle <= 3; cycle++) {
                if (slot == null || slot.time() <= nominalEnd || !Planner.stillCurrent(context, slot)) {
                    throw new AssertionError("Cycle " + cycle + " was lost after the original end");
                }
                long sent = System.currentTimeMillis();
                if (!Planner.reserveStart(context, sent, 15, slot)
                        || !Planner.confirmStart(context, sent, sent, true)
                        || Planner.remainingCycles(prefs) != 3 - cycle) {
                    throw new AssertionError("Wrong number of confirmed cycles");
                }
                watched = Planner.watchedSlot(context);
                Planner.recordClimateSnapshot(context, watched, sent + 10, true);
                slot = Planner.recordClimateSnapshot(context, watched, sent + 20, false);
            }
            if (slot != null || prefs.contains("manualEnd") || Planner.watchedSlot(context) != null
                    || prefs.getLong("lastManualEndNotice", 0) != nominalEnd) {
                throw new AssertionError("The planner did not stop after exactly three confirmed cycles");
            }
        } finally {
            prefs.edit().remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("lastManualEndNotice").remove("handled").remove("confirmedClimateWindows").commit();
            clearClimateState(context);
            Planner.schedule(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyPausedManualRefresh(Context context) throws Exception {
        var prefs = Planner.prefs(context);
        var api = KiaCloud.module(context);
        var builtins = Python.getInstance().getBuiltins();
        var scope = builtins.callAttr("dict");
        try {
            prefs.edit().clear().putString("kiaModel", "Test").commit();
            KiaSessionStore.save(context, FAKE);
            long now = System.currentTimeMillis();
            long pauseEnd = KiaBackoff.record(context, now);
            builtins.callAttr("exec", """
                import json, time, kia_mobile
                kia_mobile.prepare_start = lambda session: (None, None, session)
                def cached(prepared, force):
                    assert not force, "A manual request during a pause must not wake the vehicle"
                    return json.dumps({'percentage': 72, 'time': int(time.time() * 1000), 'climate': True})
                kia_mobile.battery = cached
                """, scope);
            if (KiaCloud.refreshDashboard(context, false) != null || prefs.contains("battery")) {
                throw new AssertionError("Automatic refresh ran during the pause");
            }
            String manual = KiaCloud.refreshDashboard(context, true);
            if (!manual.contains("Pauze opgeheven") || prefs.getInt("battery", -1) != 72
                    || KiaBackoff.until(prefs) != 0 || prefs.getInt("kiaRateLimitCount", 0) != 1) {
                throw new AssertionError("Manual cached refresh failed to clear the pause and preserve rate history: " + manual);
            }
            if (!KiaCloud.refreshDashboard(context, true).contains("na één minuut")) {
                throw new AssertionError("A second manual refresh was allowed too soon");
            }
            long retryPause = KiaBackoff.record(context, System.currentTimeMillis());
            if (retryPause <= pauseEnd || KiaBackoff.resumeAfterStatus(prefs, pauseEnd, System.currentTimeMillis())) {
                throw new AssertionError("A successful read for an older pause must not clear a newer one");
            }
            builtins.callAttr("exec", """
                kia_mobile.battery = lambda prepared, force: json.dumps({'time': int(time.time() * 1000)})
                """, scope);
            if (!KiaCloud.refreshDashboard(context, true).contains("geen bruikbare") || KiaBackoff.until(prefs) != retryPause) {
                throw new AssertionError("An incomplete status must not clear a pause");
            }
            if (!KiaCloud.refreshDashboard(context, true).contains("Volgende handmatige statuscontrole")) {
                throw new AssertionError("An incomplete status must keep the five-minute manual interval");
            }
            builtins.callAttr("exec", """
                def limited(prepared, force):
                    assert not force
                    raise RuntimeError("HTTP 429")
                kia_mobile.battery = limited
                """, scope);
            prefs.edit().putLong("kiaPausedManualRefreshNext", now - 1).commit();
            if (!KiaCloud.refreshDashboard(context, true).contains("gepauzeerd") ||
                    KiaBackoff.reserveManualRefresh(prefs, System.currentTimeMillis()) != KiaBackoff.until(prefs)) {
                throw new AssertionError("Another provider limit did not stop subsequent manual refreshes");
            }
        } finally {
            prefs.edit().clear().commit();
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyPausedManualStart(Context context) throws Exception {
        var prefs = Planner.prefs(context);
        KiaCloud.module(context);
        var builtins = Python.getInstance().getBuiltins();
        var scope = builtins.callAttr("dict");
        try {
            prefs.edit().clear().putString("kiaModel", "Test").commit();
            KiaSessionStore.save(context, FAKE);
            long now = System.currentTimeMillis();
            long until = KiaBackoff.record(context, now);
            builtins.callAttr("exec", """
                import kia_mobile
                from threading import Event
                allow_result = Event()
                calls = []
                def preflight(*args):
                    calls.append('unexpected status check')
                    raise RuntimeError('unexpected status check')
                kia_mobile.prepare_start = lambda session: (None, None, session)
                kia_mobile.battery = kia_mobile.vehicle_in_use = kia_mobile.climate_environment = preflight
                def start(*args):
                    calls.append('start')
                    if calls.count('start') > 1:
                        raise RuntimeError('HTTP 429')
                    return 'test-start'
                def result(prepared, action):
                    if action == 'test-start':
                        assert allow_result.wait(15)
                    return 'SUCCESS'
                def stop(*args):
                    calls.append('stop')
                    return 'test-stop'
                kia_mobile.start, kia_mobile.result, kia_mobile.stop = start, result, stop
                """, scope);
            var calls = scope.callAttr("__getitem__", "calls");
            KiaCloud.start(context, null);
            if (!"[]".equals(calls.toString())) throw new AssertionError("Automatic request during Kia pause");
            if (!Planner.manual(context, 15)) throw new AssertionError("Manual start unavailable during Kia pause");
            waitForState(() -> calls.callAttr("count", "start").toInt() == 1, "Manual request did not reach Kia");
            if (Planner.cycleEnd(context) != 0) throw new AssertionError("Timer started before Kia confirmed");
            scope.callAttr("__getitem__", "allow_result").callAttr("set");
            waitForState(() -> Planner.cycleEnd(context) > 0, "Kia confirmation did not start the cycle timer");
            if (KiaBackoff.until(prefs) != until || !Planner.cancelRunning(context)) {
                throw new AssertionError("Confirmed start should preserve status pause and allow stopping");
            }
            KiaStartService.enqueueStop(context);
            waitForState(() -> !KiaStartService.stopInProgress() && Planner.manualRestartAvailable(prefs, System.currentTimeMillis()),
                "Manual stop did not receive confirmation");
            if (!Planner.manual(context, 15)) throw new AssertionError("Manual restart unavailable after confirmation");
            waitForState(() -> "LIMIT".equals(prefs.getString("retryCause", "")), "Kia rejection was not logged");
            if (Planner.cycleEnd(context) != 0 || KiaBackoff.until(prefs) != until
                    || !"['start', 'stop', 'start']".equals(calls.toString())) {
                throw new AssertionError("Rejected start changed the timer or made extra Kia requests: " + calls);
            }
        } finally {
            prefs.edit().clear().commit();
            Planner.schedule(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void waitForState(java.util.function.BooleanSupplier state, String error) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (!state.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(40);
        }
        if (!state.getAsBoolean()) throw new AssertionError(error);
    }

    private static void verifyDashboardRefreshAndIntro(Context context) throws Exception {
        var prefs = Planner.prefs(context);
        var hold = LaunchIntro.class.getDeclaredField("AFTER_SHEEN_MS");
        hold.setAccessible(true);
        if (hold.getLong(null) != 300) throw new AssertionError("Incorrect splash hold after the sheen");

        try {
            long now = System.currentTimeMillis();
            long sent = now - Schedule.INTERVAL - 18_000;
            long confirmed = sent + 1_000;
            long expected = confirmed + Schedule.INTERVAL;
            long end = expected + Schedule.INTERVAL;
            prefs.edit().clear().putString("kiaModel", "Test").putLong("lastStart", sent)
                .putLong("confirmedStart", confirmed).putLong("cycleEnd", expected)
                .putLong("activeSession", sent).putLong("activeEnd", end).putLong("activeRunEnd", end)
                .putInt("activeConfirmedCycles", 1).putLong("monitoringStart", sent).commit();
            var slot = Planner.watchedSlot(context);
            if (slot == null) throw new AssertionError("Test cycle was not monitored");
            long first = expected + 15_000;
            var early = VehicleWatch.claim(context, slot, first - 1, -1);
            if (early.allowed() || early.nextTime() != first || prefs.contains("watchPollCount"))
                throw new AssertionError("Manual dashboard refresh polled before the expected end");
            if (!VehicleWatch.claim(context, slot, first, -1).allowed())
                throw new AssertionError("Manual refresh could not take over the first automatic check");
            var duplicate = VehicleWatch.claim(context, slot, first, 0);
            if (duplicate.allowed() || duplicate.nextTime() != first + Schedule.MINUTE)
                throw new AssertionError("Automatic and manual paths did not share the same first check");
            if (VehicleWatch.claim(context, slot, first + Schedule.MINUTE - 1, -1).allowed()
                    || !VehicleWatch.claim(context, slot, first + Schedule.MINUTE, 1).allowed()
                    || VehicleWatch.claim(context, slot, first + 5 * Schedule.MINUTE, -1).allowed()) {
                throw new AssertionError("The retry must wait 60 seconds and remain the final check");
            }
            prefs.edit().clear().putLong("lastDashboardKiaRequest", now).commit();
            String notice = KiaCloud.refreshDashboard(context, true);
            if (notice == null || !notice.contains("na één minuut"))
                throw new AssertionError("Repeated manual refresh outside a cycle was not throttled");
        } finally {
            prefs.edit().clear().commit();
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyRatePauseAndEndChecks(Context context) throws Exception {
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long expected = now + Schedule.INTERVAL;
        long first = VehicleWatch.due(now, expected, 0, 0, 0);
        long second = VehicleWatch.due(first, expected, 1, first, 0);
        if (first != expected + 15_000 || second != first + Schedule.MINUTE
                || VehicleWatch.due(second, expected, 2, second, 0) != 0
                || VehicleWatch.due(now, expected, 0, 0, now + KiaBackoff.HOUR) != now + KiaBackoff.HOUR) {
            throw new AssertionError("The two status checks must follow the confirmed end and honor the pause");
        }
        try {
            long oldLimit = now - 9 * Schedule.MINUTE;
            var oldLog = new org.json.JSONArray().put(new org.json.JSONObject().put("time", oldLimit)
                .put("message", "Ophalen mislukt (RateLimitingError)."));
            prefs.edit().clear().putString("history", oldLog.toString()).putString("kiaModel", "Test").commit();
            KiaBackoff.restoreFromHistory(context, now);
            long until = oldLimit + KiaBackoff.HOUR;
            if (!KiaBackoff.paused(context) || KiaBackoff.until(prefs) != until
                    || KiaBackoff.record(context, now) != until || Planner.nextAllowedStart(context) < until) {
                throw new AssertionError("Upgrade must respect recent limits without extending a pause on a duplicate");
            }
            if (Planner.reserveStart(context, now, 15, null)) throw new AssertionError("Automatic start bypassed an active Kia pause");
            String before = prefs.getString("history", "");
            KiaCloud.readBattery(context, true);
            KiaCloud.sampleBattery(context);
            if (!before.equals(prefs.getString("history", "")) || !KiaCloud.connectionCheck(context).contains("gepauzeerd")) {
                throw new AssertionError("A paused request reached the network or generated another error");
            }
            if (KiaBackoff.record(context, until) != until + 2 * KiaBackoff.HOUR) {
                throw new AssertionError("A new limit after the first pause should double the wait");
            }
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static void verifyBatterySchedule(Context context) {
        var prefs = Planner.prefs(context);
        var scheduler = context.getSystemService(android.app.job.JobScheduler.class);
        prefs.edit().putString("kiaModel", "Test").commit();
        try {
            BatterySampling.updateSchedule(context);
            var job = scheduler.getPendingJob(BatterySampling.JOB_ID);
            if (job == null || !job.isPeriodic() || !job.isPersisted()
                    || job.getIntervalMillis() != 60 * Schedule.MINUTE || job.getRequiredNetwork() == null) {
                throw new AssertionError("Missing persistent, network-constrained hourly battery job");
            }
            prefs.edit().putBoolean(BatterySampling.ENABLED, false).commit();
            BatterySampling.updateSchedule(context);
            if (scheduler.getPendingJob(BatterySampling.JOB_ID) != null)
                throw new AssertionError("Disabled battery sampling must cancel the hourly job");
            prefs.edit().remove(BatterySampling.ENABLED).putBoolean("cloudNeedsLink", true).commit();
            BatterySampling.updateSchedule(context);
            if (scheduler.getPendingJob(BatterySampling.JOB_ID) != null)
                throw new AssertionError("Battery sampling must wait for Kia to be linked");
        } finally {
            scheduler.cancel(BatterySampling.JOB_ID);
            prefs.edit().remove("kiaModel").remove("cloudNeedsLink").remove(BatterySampling.ENABLED).commit();
        }
    }

    private static void verifyHourlyBattery(Context context, com.chaquo.python.PyObject builtins,
                                             com.chaquo.python.PyObject namespace) {
        var prefs = Planner.prefs(context);
        String history = prefs.getString("history", "");
        prefs.edit().putLong("batteryTime", System.currentTimeMillis() - 11 * Schedule.MINUTE).commit();
        builtins.callAttr("exec", """
            def hourly_battery(prepared, force):
                assert force is False
                return json.dumps({'percentage': 71, 'time': int(time.time() * 1000), 'climate': True})
            kia_mobile.battery = hourly_battery
            """, namespace);
        KiaCloud.sampleBattery(context);
        var zone = java.time.ZoneId.systemDefault();
        var chart = EnergyHistory.open(context).batteryChart(java.time.LocalDate.now(zone), zone);
        if (prefs.getInt("battery", -1) != 71 || !history.equals(prefs.getString("history", ""))
                || chart.stream().noneMatch(point -> point.value() == 71)) {
            throw new AssertionError("Hourly check must add a real Kia measure silently and without a forced refresh");
        }
        builtins.callAttr("exec", """
            kia_mobile.battery = lambda prepared, force: json.dumps({'percentage': 72, 'time': int(time.time() * 1000)})
            """, namespace);
        KiaCloud.sampleBattery(context);
        if (prefs.getInt("battery", -1) != 71) throw new AssertionError("Nearby readings should suppress duplicate polling");
    }

    private static void verifyPhonePositionAndEnergyHistory(Context context) throws Exception {
        long current = android.os.SystemClock.elapsedRealtimeNanos();
        var fix = new android.location.Location("gps");
        fix.setLatitude(51);
        fix.setLongitude(4);
        fix.setElapsedRealtimeNanos(current);
        fix.setAccuracy(18);
        if (!PhonePosition.suitable(fix, current) || PhonePosition.suitable(fix, current + 31_000_000_000L))
            throw new AssertionError("Precise or stale phone location mishandled");
        fix.setAccuracy(200);
        if (PhonePosition.suitable(fix, current)) throw new AssertionError("Imprecise home position accepted");

        var zone = java.time.ZoneId.of("Europe/Amsterdam");
        var date = java.time.LocalDate.of(2026, 9, 3);
        long midnight = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long minute = Schedule.MINUTE;
        var store = EnergyHistory.open(context);
        var database = store.getWritableDatabase();
        try {
            store.battery(midnight - minute, 40, midnight + 10 * minute);
            store.battery(midnight + minute, 40, midnight + 10 * minute);
            store.battery(midnight + 8 * minute, 52, midnight + 10 * minute);
            store.battery(midnight + 8 * minute, 51, midnight + 10 * minute);
            var battery = store.batteryChart(date, zone);
            if (battery.size() != 2 || battery.get(1).value() != 52)
                throw new AssertionError("Charging increase, time boundary or duplicate handling is wrong");
            var cycle = new org.json.JSONObject().put("start", midnight - 5 * minute).put("elapsed", 15 * minute)
                .put("estimate", 0.6);
            store.cycle(cycle, zone);
            store.cycle(cycle, zone);
            var days = store.days(date.minusDays(1), date.plusDays(2));
            if (days.size() != 3 || Math.abs(days.get(0).energy() - 0.2) > 1e-6
                    || Math.abs(days.get(1).energy() - 0.4) > 1e-6 || days.get(2).energy() != 0
                    || days.get(0).duration() != 5 * minute || days.get(1).duration() != 10 * minute)
                throw new AssertionError("Cycle did not split, persist or deduplicate across midnight");
            var daily = store.cumulativeChart(date, zone, midnight + 30 * minute);
            if (daily.get(0).time() != midnight || daily.get(0).value() != 0
                    || Math.abs(daily.get(daily.size() - 1).value() - 0.4) > 1e-6)
                throw new AssertionError("Daily cumulative graph did not reset");
        } finally {
            database.delete("battery", "time >= ? AND time <= ?", new String[]{Long.toString(midnight - minute), Long.toString(midnight + 8 * minute)});
            database.delete("cycle_day", "cycle = ?", new String[]{Long.toString(midnight - 5 * minute)});
        }
    }

    private void verifyWidget(Context context) {
        var prefs = Planner.prefs(context);
        var manager = android.appwidget.AppWidgetManager.getInstance(context);
        var component = new android.content.ComponentName(context, ClimateWidget.class);
        var host = new android.appwidget.AppWidgetHost(context, 525);
        var info = manager.getInstalledProviders().stream().filter(item -> component.equals(item.provider))
            .findFirst().orElseThrow(() -> new AssertionError("Widget provider not registered"));
        int id = host.allocateAppWidgetId();
        try {
            if (!manager.bindAppWidgetIdIfAllowed(id, component)) throw new AssertionError("Widget test cannot bind");
            prefs.edit().putString("kiaModel", "e-Niro test").commit();
            var view = new java.util.concurrent.atomic.AtomicReference<android.appwidget.AppWidgetHostView>();
            runOnMainSync(() -> {
                view.set(host.createView(context, id, info));
                host.startListening();
            });
            ClimateWidget.refresh(context);
            waitForIdleSync();
            SystemClock.sleep(700);
            runOnMainSync(() -> {
                var title = (android.widget.TextView) view.get().findViewById(R.id.widget_state);
                var timer = view.get().findViewById(R.id.widget_timer);
                if (title == null || !title.getText().toString().equals("Volgende start") || timer == null) {
                    throw new AssertionError("Widget did not render its state and timer");
                }
            });
            long now = System.currentTimeMillis();
            long start = now - Schedule.MINUTE;
            long end = now + Schedule.INTERVAL;
            prefs.edit().putLong("activeSession", start).putLong("activeEnd", end).putLong("activeRunEnd", end)
                .putLong("lastStart", start).putLong("confirmedStart", start).putInt("activeConfirmedCycles", 1)
                .putLong("cycleEnd", end).commit();
            ClimateWidget.refresh(context);
            waitForIdleSync();
            SystemClock.sleep(700);
            runOnMainSync(() -> {
                var title = (android.widget.TextView) view.get().findViewById(R.id.widget_state);
                var timer = view.get().findViewById(R.id.widget_timer);
                if (!"Klimaat actief".contentEquals(title.getText()) || timer.getVisibility() != android.view.View.VISIBLE)
                    throw new AssertionError("Widget did not render the active countdown");
                host.stopListening();
            });
        } finally {
            host.deleteAppWidgetId(id);
            prefs.edit().clear().commit();
        }
    }

    private static void verifyAdditionalFeatures(Context context) throws Exception {
        var prefs = Planner.prefs(context);
        var stats = context.getSharedPreferences("start_ledger", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        stats.edit().clear().commit();
        var zone = java.time.ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long minute = Schedule.MINUTE;
        try {
            prefs.edit().putBoolean("seasonalProfile", true).putInt("summerProfile", 1).putInt("winterProfile", 0)
                .putInt("summerDate", 401).putInt("winterDate", 1001).commit();
            long july = java.time.LocalDate.of(2026, 7, 10).atStartOfDay(zone).toInstant().toEpochMilli();
            long january = java.time.LocalDate.of(2026, 1, 10).atStartOfDay(zone).toInstant().toEpochMilli();
            if (ClimateRules.profile(prefs, july, zone) != 1 || ClimateRules.profile(prefs, january, zone) != 0)
                throw new AssertionError("Season boundary did not select the correct profile");
            prefs.edit().putInt("summerDate", 1001).putInt("winterDate", 401).commit();
            if (ClimateRules.profile(prefs, july, zone) != 0 || ClimateRules.profile(prefs, january, zone) != 1)
                throw new AssertionError("Season crossing January did not select the correct profile");
            prefs.edit().putBoolean("seasonalProfile", false).putInt("minimumUnpluggedBattery", 25)
                .putBoolean("skipMild", true).putLong("homeLat", Double.doubleToLongBits(51.0))
                .putLong("homeLon", Double.doubleToLongBits(4.0)).commit();
            var vehicle = new org.json.JSONObject().put("time", now).put("percentage", 20).put("plugged", false);
            var env = new org.json.JSONObject().put("time", now).put("locationTime", now)
                .put("weatherLocation", "51.000100,4.000100").put("outside", 20);
            if (ClimateRules.skip(prefs, true, vehicle, env, now) != ClimateRules.Reason.LOW_BATTERY
                    || ClimateRules.skip(prefs, true, vehicle.put("plugged", true), env, now) != ClimateRules.Reason.MILD
                    || ClimateRules.skip(prefs, true, vehicle, env.put("weatherLocation", "51.010000,4.010000"), now)
                        != ClimateRules.Reason.AWAY
                    || ClimateRules.skip(prefs, true, vehicle, env, now + 16 * minute) != ClimateRules.Reason.LOCATION_UNKNOWN
                    || ClimateRules.skip(prefs, false, vehicle, env, now + 16 * minute) != null) {
                throw new AssertionError("Conditions did not distinguish plugged, low battery, mild weather and stale location");
            }
            prefs.edit().putInt("minimumUnpluggedBattery", 0).putBoolean("skipMild", false).commit();
            long once = now + 10 * minute, onceEnd = once + Schedule.INTERVAL;
            prefs.edit().putLong("onceStart", once).putLong("onceEnd", onceEnd).putInt("onceProfile", 1).commit();
            var preview = Planner.previewManual(context, 15, now);
            if (preview.firstConflict() != once || preview.end() != now + 30 * minute) {
                throw new AssertionError("Manual overlap did not preview the combined end");
            }
            prefs.edit().putBoolean("onceHomeOnly", true).commit();
            if (Planner.previewManual(context, 15, now).firstConflict() != 0)
                throw new AssertionError("Guarded starts must be evaluated independently");
            long manual = now - minute, manualEnd = now + 14 * minute;
            prefs.edit().remove("onceStart").remove("onceEnd").putLong("manualFirst", manual).putLong("manualEnd", manualEnd)
                .putLong("manualStart", manual).putInt("manualProfile", 0).putInt("manualTemperature", 46)
                .putLong("activeSession", manual).putLong("activeEnd", manualEnd)
                .putLong("activeRunEnd", manualEnd).putInt("activeConfirmedCycles", 1).commit();
            var manualSlot = new Schedule.Slot(manual, manual, manualEnd);
            if (Planner.climateOptions(prefs, manualSlot, zone).temperature() != 46 || !Planner.extend(context)
                    || prefs.getLong("manualEnd", 0) != manualEnd + Schedule.INTERVAL || Planner.remainingCycles(prefs) != 1) {
                throw new AssertionError("Temporary target or live extension did not survive planner state");
            }
            prefs.edit().clear().commit();
            var monday = java.time.LocalDate.now(zone).plusDays(2);
            int day = monday.getDayOfWeek().getValue() - 1;
            long first = monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli();
            long last = first + Schedule.INTERVAL;
            prefs.edit().putBoolean("enabled", true).putInt("days", 0).putBoolean("dayEnabled" + day, true)
                .putInt("start" + day + "_0", 480).putInt("duration" + day + "_0", 15)
                .putBoolean("slotEnabled" + day + "_0", true).commit();
            var rows = Planner.agenda(prefs, now, now + 7 * 24 * ClimateEnergy.HOUR, zone);
            if (rows.size() != 1 || rows.get(0).start() != first) throw new AssertionError("Week agenda missing occurrence");
            var original = Planner.next(context);
            var change = Planner.adjustWeekly(context, original, first + 2 * ClimateEnergy.HOUR);
            rows = Planner.agenda(prefs, now, now + 7 * 24 * ClimateEnergy.HOUR, zone);
            if (change == null || rows.size() != 1 || rows.get(0).original() != first)
                throw new AssertionError("Moved occurrence missing from agenda");
            var skip = Planner.adjustWeekly(context, Planner.next(context), 0);
            if (skip == null || !"Overgeslagen".equals(Planner.agenda(prefs, now, now + 7 * 24 * ClimateEnergy.HOUR, zone).get(0).type()))
                throw new AssertionError("Skipped occurrence missing from agenda");
            if (!Planner.undoWeekly(context, skip)) throw new AssertionError("Skip could not be undone");
            prefs.edit().clear().commit();
            prefs.edit().putLong("onceStart", first).putLong("onceEnd", last).putInt("onceProfile", 0).commit();
            var slot = new Schedule.Slot(first, first, last);
            var fakeStart = now - 8 * minute;
            var fakeEnd = fakeStart + Schedule.INTERVAL;
            var late = new Schedule.Slot(fakeStart, fakeStart, fakeEnd);
            prefs.edit().putLong("onceStart", fakeStart).putLong("onceEnd", fakeEnd)
                .putBoolean("onceReady", true).putBoolean("notifyDepartureDelay", true).commit();
            if (!Planner.reserveStart(context, now, 15, late) || !Planner.confirmStart(context, now, now, true)
                    || prefs.getLong("reportedDepartureDelay", 0) != fakeStart
                    || prefs.getLong("onceEnd", 0) != fakeEnd) {
                throw new AssertionError("A delayed departure was not reported without changing the plan");
            }
            context.getSystemService(android.app.NotificationManager.class).cancel(25);
            prefs.edit().clear().putLong("onceStart", first).putLong("onceEnd", last).putInt("onceProfile", 0).commit();
            KiaCloud.startProblem(context, slot, now, "Test failure", StartLedger.Cause.NETWORK);
            var notification = context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
            boolean actionable = false;
            for (var item : notification) {
                var actions = item.getNotification().actions;
                if (item.getId() == 24 && actions != null && actions.length == 2) actionable = true;
            }
            if (!actionable || prefs.getInt("retryProfile", -1) != 0 || prefs.getInt("retryMinutes", 0) != 15
                    || StartLedger.summary(context, StartLedger.month(now)).failed() != 1) {
                throw new AssertionError("Failure ledger or notification actions missing");
            }
            var exported = StartLedger.safeExport(new org.json.JSONArray()
                .put(new org.json.JSONObject().put("time", now).put("session", first).put("cause", "NETWORK")
                    .put("message", "private-vin-or-location"))
                .put(new org.json.JSONObject().put("time", now).put("session", first).put("cause", "private-vin-or-location")),
                first);
            if (exported.contains("private-vin-or-location") || !exported.contains("NETWORK") || !exported.contains("WINDOW_EVENT"))
                throw new AssertionError("Private log includes unverified text");
            context.getSystemService(android.app.NotificationManager.class).cancel(24);
        } finally {
            prefs.edit().clear().commit();
            stats.edit().clear().commit();
            Planner.schedule(context);
        }
    }

    private static void verifyEnergyMeter(Context context) throws org.json.JSONException {
        var prefs = context.getSharedPreferences("climate_energy", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try {
            long minute = Schedule.MINUTE;
            long start = System.currentTimeMillis() - 10 * ClimateEnergy.HOUR;
            if (ClimateEnergy.average(context).kilowatts() != 2.0) throw new AssertionError("Missing initial estimate");
            for (int index = 0; index < 9; index++) {
                long time = start + index * ClimateEnergy.HOUR;
                long request = time - 5_000;
                boolean plugged = index == 5;
                boolean driven = index == 6;
                var before = index == 8 ? new org.json.JSONObject() : energySample(time - minute, 60, 1000, plugged, false);
                ClimateEnergy.begin(context, request, time, time, Schedule.INTERVAL, before);
                if (index != 2) {
                    for (int elapsed = 2; elapsed <= 14; elapsed += 2) {
                        ClimateEnergy.observe(context, request, energySample(time + elapsed * minute,
                            60, driven ? 1001 : 1000, plugged, true));
                    }
                }
                double after = index == 3 ? 60 : 59;
                var reason = index == 7 ? ClimateEnergy.End.MANUAL : ClimateEnergy.End.STATUS_OFF;
                var result = energySample(time + 15 * minute, after, 1000, plugged, false);
                ClimateEnergy.finish(context, request, time + 15 * minute, reason, result);
                ClimateEnergy.finish(context, request, time + 15 * minute, reason, result);
            }
            var log = ClimateEnergy.log(context);
            var average = ClimateEnergy.average(context);
            double expected = 5 * .64 * 60 / (5 * 15 + 13);
            if (log.length() != 9 || average.samples() != 6 || !average.learned()
                    || Math.abs(average.kilowatts() - expected) > 0.001) {
                throw new AssertionError("Calibration, exclusions or duplicate protection incorrect");
            }
            var first = log.getJSONObject(8);
            if (first.getBoolean("learned") || Math.abs(first.getDouble("estimate") - 0.5) > .0001
                    || !log.getJSONObject(0).getBoolean("learned")
                    || log.getJSONObject(0).getLong("calibrationDuration") != 13 * minute) {
                throw new AssertionError("Log did not preserve estimates or account for a late baseline");
            }
            ClimateEnergy.resetLearning(context);
            if (ClimateEnergy.average(context).learned() || ClimateEnergy.log(context).length() != 9) {
                throw new AssertionError("Calibration reset should retain previous log entries");
            }
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static org.json.JSONObject energySample(long time, double percent, double odometer,
            boolean plugged, boolean active) throws org.json.JSONException {
        return new org.json.JSONObject().put("time", time).put("percentage", percent).put("odometer", odometer)
            .put("plugged", plugged).put("charging", false).put("ignition", false).put("climate", active);
    }

    private static void verifyPlanningOptions(Context context) throws org.json.JSONException {
        var prefs = Planner.prefs(context);
        var zone = java.time.ZoneId.systemDefault();
        var amsterdam = java.time.ZoneId.of("Europe/Amsterdam");
        checkReadyAcrossClockChange(amsterdam, java.time.LocalDate.of(2026, 3, 29),
            new Schedule.Window(6, 210, 45, true), "2026-03-29T00:45:00Z", "2026-03-29T01:30:00Z");
        checkReadyAcrossClockChange(amsterdam, java.time.LocalDate.of(2026, 10, 25),
            new Schedule.Window(6, 195, 90, true), "2026-10-25T00:45:00Z", "2026-10-25T02:15:00Z");
        var departure = java.time.LocalDate.now(zone).plusDays(2).atTime(0, 10);
        int day = departure.getDayOfWeek().getValue() - 1;
        long expected = departure.minusMinutes(45).atZone(zone).toInstant().toEpochMilli();
        prefs.edit().clear().putBoolean("enabled", true).putInt("days", 0)
            .putBoolean("dayEnabled" + day, true).putInt("start" + day + "_0", 10)
            .putInt("duration" + day + "_0", 45).putBoolean("slotEnabled" + day + "_0", true)
            .putBoolean(ClimateProfiles.readyKey(day, 0), true).putInt(ClimateProfiles.windowKey(day, 0), 0).commit();
        try {
            var initial = Planner.next(context);
            if (initial == null || initial.time() != expected || initial.end() != expected + 45 * Schedule.MINUTE
                    || Planner.climateOptions(prefs, initial, zone).profile() != 0)
                throw new AssertionError("Ready-by did not start on the previous day with the selected profile");
            prefs.edit().putInt(ClimateProfiles.GENERAL, 1).commit();
            verifyGeneralProfile(prefs, initial, zone, day);
            int smart = Planner.heatForCycle(prefs, initial, zone);
            if (smart != (Planner.HEAT_WHEEL | Planner.HEAT_MIRRORS | ClimateProfiles.SMART)
                    || (ClimateProfiles.applySmart(smart, 3, 3) & Planner.HEAT_WINDSHIELD) == 0
                    || (ClimateProfiles.applySmart(smart, 3, 3.1) & Planner.HEAT_WINDSHIELD) != 0
                    || (ClimateProfiles.applySmart(smart, 3, Double.NaN) & Planner.HEAT_WINDSHIELD) != 0) {
                throw new AssertionError("Smart windshield did not honor threshold and missing data");
            }
            prefs.edit().putLong("activeSession", initial.session()).putLong("lastStart", expected)
                .putLong("confirmedStart", expected).commit();
            if ((Planner.heatForCycle(prefs, initial, zone) & ClimateProfiles.SMART) != 0)
                throw new AssertionError("Smart windshield must be off after the first confirmed cycle");
            prefs.edit().remove("activeSession").remove("lastStart").remove("confirmedStart").commit();
            long moved = expected + 90 * Schedule.MINUTE;
            var move = Planner.adjustWeekly(context, initial, moved);
            if (move == null) throw new AssertionError("Move rejected");
            var movedSlot = Planner.next(context);
            if (movedSlot == null || movedSlot.time() != moved || Planner.climateOptions(prefs, movedSlot, zone).profile() != 0)
                throw new AssertionError("Moved occurrence lost its time or profile");
            var skip = Planner.adjustWeekly(context, movedSlot, 0);
            if (skip == null || Planner.undoWeekly(context, move) || !Planner.undoWeekly(context, skip)
                    || !movedSlot.equals(Planner.next(context))) {
                throw new AssertionError("Skipping or undoing a moved occurrence failed");
            }
            if (!Planner.undoWeekly(context, move) || !initial.equals(Planner.next(context))
                    || Planner.adjustWeekly(context, initial, 0) == null) {
                throw new AssertionError("Undo did not restore the original occurrence");
            }
            long next = departure.plusDays(7).minusMinutes(45).atZone(zone).toInstant().toEpochMilli();
            if (Planner.next(context).time() != next) throw new AssertionError("Skip affected the next week");
            Planner.pauseWeekly(context, next + Schedule.MINUTE);
            if (Planner.next(context).time() <= next) throw new AssertionError("Pause did not skip planned start");
            Planner.pauseWeekly(context, 0);
            if (Planner.next(context).time() != next) throw new AssertionError("Resume did not restore next start");

            var exported = PlanningBackup.export(prefs);
            if (exported.has("history") || exported.has("onceStart"))
                throw new AssertionError("Backup contains runtime data");
            var other = context.getSharedPreferences("backupTest", Context.MODE_PRIVATE);
            other.edit().clear().putString("kiaModel", "unchanged").commit();
            try {
                if (!PlanningBackup.restore(other, exported).commit()
                        || !"unchanged".equals(other.getString("kiaModel", ""))
                        || !other.getBoolean(ClimateProfiles.readyKey(day, 0), false)
                        || other.getInt(ClimateProfiles.windowKey(day, 0), -1) != 0
                        || ClimateProfiles.general(other) != 1)
                    throw new AssertionError("Backup did not restore the schedule and profiles");
                exported.getJSONObject("defaults").remove("profile");
                if (!PlanningBackup.restore(other, exported).commit() || ClimateProfiles.general(other) != -1)
                    throw new AssertionError("Older backups must still use standard heating");
            } finally {
                other.edit().clear().commit();
            }
        } finally {
            prefs.edit().clear().commit();
            Planner.schedule(context);
        }
    }

    private static void verifyGeneralProfile(android.content.SharedPreferences prefs, Schedule.Slot weekly,
                                             java.time.ZoneId zone, int day) {
        var inherited = Planner.climateOptions(prefs, null, zone);
        var explicit = Planner.climateOptions(prefs, weekly, zone);
        if (inherited.profile() != 1 || inherited.temperature() != 40 || inherited.heat() != 0
                || explicit.profile() != 0 || explicit.temperature() != 45) {
            throw new AssertionError("General profile did not inherit or respect the weekly override");
        }
        String weeklyProfile = ClimateProfiles.windowKey(day, 0);
        prefs.edit().remove(weeklyProfile).putInt("slotHeat" + day + "_0", Planner.HEAT_WINDSHIELD).commit();
        var custom = Planner.climateOptions(prefs, weekly, zone);
        if (!custom.customHeat() || custom.profile() != 1 || custom.temperature() != 40
                || custom.heat() != Planner.HEAT_WINDSHIELD) {
            throw new AssertionError("Custom heating must survive a general profile change");
        }
        prefs.edit().remove("slotHeat" + day + "_0").commit();
        if (!Planner.climateOptions(prefs, weekly, zone).equals(inherited)) {
            throw new AssertionError("Unassigned weekly window did not use the general profile");
        }
        long start = weekly.session() + Schedule.INTERVAL;
        long end = start + Schedule.INTERVAL;
        var slot = new Schedule.Slot(start, start, end);
        prefs.edit().putLong("manualFirst", start).putLong("manualEnd", end).commit();
        if (!Planner.climateOptions(prefs, slot, zone).equals(inherited))
            throw new AssertionError("Manual start did not use the general profile");
        prefs.edit().remove("manualFirst").remove("manualEnd")
            .putLong("onceStart", start).putLong("onceEnd", end).commit();
        if (!Planner.climateOptions(prefs, slot, zone).equals(inherited))
            throw new AssertionError("One-time start did not use the general profile");
        prefs.edit().putInt("onceProfile", 0).commit();
        if (!Planner.climateOptions(prefs, slot, zone).equals(explicit))
            throw new AssertionError("Explicit one-time profile did not override the general profile");
        prefs.edit().remove("onceStart").remove("onceEnd").remove("onceProfile").putInt(weeklyProfile, 0).commit();
    }

    private static void checkReadyAcrossClockChange(java.time.ZoneId zone, java.time.LocalDate day,
                                                    Schedule.Window plan, String beginning, String departure) {
        var usableAt = day.atStartOfDay(zone).toInstant().toEpochMilli();
        var slot = Schedule.next(usableAt, zone, java.util.Collections.singletonList(plan));
        if (slot == null || slot.time() != java.time.Instant.parse(beginning).toEpochMilli()
                || slot.end() != java.time.Instant.parse(departure).toEpochMilli()) {
            throw new AssertionError("Ready-by did not end at the selected local time across a clock change");
        }
    }

    private static void verifyClimateIndicator(Context context) throws org.json.JSONException {
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        try {
            ClimateIndicator.update(context, new org.json.JSONObject().put("time", now).put("target", 22)
                .put("outside", 5));
            var vehicle = ClimateIndicator.read(prefs, now, false);
            if (vehicle.mode() != ClimateIndicator.Mode.HEATING || vehicle.outdoor() != 5
                    || vehicle.target() != 22 || vehicle.weather()) {
                throw new AssertionError("Cold ambient reading did not show heating");
            }
            ClimateIndicator.update(context, new org.json.JSONObject().put("time", now + 1).put("target", 22)
                .put("outside", 31));
            if (ClimateIndicator.read(prefs, now + 1, false).mode() != ClimateIndicator.Mode.COOLING
                    || ClimateIndicator.estimate(22.5, 22) != ClimateIndicator.Mode.UNKNOWN) {
                throw new AssertionError("Cooling or neutral indicator was incorrect");
            }
            prefs.edit().remove("climateOutside").putString("climateWeatherCell", "51.5,4.3")
                .putString("climateWeatherReadingCell", "51.5,4.3").putLong("climateWeatherTime", now)
                .putFloat("climateWeatherDegrees", 7).commit();
            if (!ClimateIndicator.read(prefs, now + 1, false).weather()) {
                throw new AssertionError("Weather fallback was not enabled by default");
            }
            prefs.edit().putBoolean(ClimateIndicator.WEATHER_ENABLED, false).commit();
            if (ClimateIndicator.read(prefs, now + 1, false).mode() != ClimateIndicator.Mode.UNKNOWN) {
                throw new AssertionError("Weather was used after the user disabled it");
            }
            prefs.edit().putBoolean(ClimateIndicator.WEATHER_ENABLED, true).commit();
            var weather = ClimateIndicator.read(prefs, now + 1, false);
            var stale = ClimateIndicator.read(prefs, now + 60 * Schedule.MINUTE, false);
            var displayedStale = ClimateIndicator.lastOutdoor(prefs, now + 60 * Schedule.MINUTE);
            if (displayedStale == null || displayedStale.value() != 31 || displayedStale.weather())
                throw new AssertionError("Last known outdoor reading is missing after freshness expires");
            if (!weather.weather() || weather.mode() != ClimateIndicator.Mode.HEATING || weather.outdoor() != 7
                    || weather.target() != 22 || Double.isFinite(stale.outdoor()) || Double.isFinite(stale.target())) {
                throw new AssertionError("Weather fallback did not respect source and freshness");
            }
        } finally {
            prefs.edit().remove(ClimateIndicator.WEATHER_ENABLED).remove("climateEnvironmentTime").remove("climateOutside")
                .remove("climateVehicleTarget").remove("climateWeatherCell").remove("climateWeatherReadingCell")
                .remove("climateWeatherTime").remove("climateWeatherDegrees")
                .remove("climateLastOutdoorTime").remove("climateLastOutdoor").commit();
        }
    }

    private static void clearClimateState(Context context) {
        Planner.prefs(context).edit().remove("starts").remove("lastStart").remove("confirmedStart")
            .remove("cycleEnd").remove("activeEnd").remove("activeSession").remove("unconfirmedHold")
            .remove("confirmationDeadline").remove("pendingDuration").remove("monitoringStart")
            .remove("monitoredContinuation").remove("observedOffFor").remove("observedClimateOn")
            .remove("lastClimateSample").remove("kiaStartLimitedUntil").remove("activeRunEnd")
            .remove("activeConfirmedCycles").commit();
    }

    private static void verifyEarlyCompletion(Context context, boolean submitted, String reason) {
        if (!KiaCloud.climateUnavailable("Klimaatregeling op afstand is momenteel niet beschikbaar.")
                || KiaCloud.climateUnavailable("Service Temporary Unavailable")) {
            throw new AssertionError("Wrong outcome for a Kia error");
        }
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis() - 1_000;
        long end = now + 45 * Schedule.MINUTE;
        prefs.edit().putLong("manualStart", now).putLong("manualFirst", now)
            .putLong("manualEnd", end).commit();
        var slot = new Schedule.Slot(now, now, end);
        var manager = context.getSystemService(android.app.NotificationManager.class);
        try {
            KiaCloud.start(context, slot);
            var followingCycle = new Schedule.Slot(now + Schedule.INTERVAL, now, end);
            long checkedAt = System.currentTimeMillis();
            if (Planner.stillCurrent(context, followingCycle) || prefs.contains("manualEnd")
                    || Planner.activeEnd(context, checkedAt) > 0 || Planner.cycleEnd(context) != 0
                    || (Planner.cooldownEnd(context) > checkedAt) != submitted
                    || !reason.equals(prefs.getString("status", ""))) {
                throw new AssertionError("Early completion failed: " + prefs.getString("status", ""));
            }
            waitForNotification(manager, 21, false);
        } finally {
            prefs.edit().remove("manualStart").remove("manualFirst").remove("manualEnd").commit();
            clearClimateState(context);
        }
    }

    private static void verifyHeatingReadback(Context context) {
        var prefs = Planner.prefs(context);
        long sent = System.currentTimeMillis() - 1_000;
        var slot = new Schedule.Slot(sent, sent, sent + Schedule.INTERVAL);
        prefs.edit().putLong("manualFirst", slot.session()).putLong("manualEnd", slot.end()).commit();
        try {
            if (!Planner.reserveStart(context, sent, 20, slot)
                    || !Planner.confirmStart(context, sent, sent + 100)) {
                throw new AssertionError("Heating read-back fixture did not start");
            }
            KiaCloud.checkVehicleUse(context, slot);
            String reason = "Verwarmingsstatus volgens Kia: voorruit aan, stuur uit, achterruit onbekend, spiegels aan.";
            if (!reason.equals(prefs.getString("status", ""))) {
                throw new AssertionError("Heating read-back did not preserve all states");
            }
            String history = prefs.getString("history", "");
            KiaCloud.checkVehicleUse(context, slot);
            if (!history.equals(prefs.getString("history", ""))) throw new AssertionError("Duplicate read-back entry");
        } finally {
            prefs.edit().remove("heaterStatusLoggedFor").remove("confirmedClimateWindows")
                .remove("manualFirst").remove("manualEnd").commit();
            clearClimateState(context);
            VehicleWatch.schedule(context);
        }
    }

    private void verifyLastCycleWatch(Context context) throws java.io.IOException {
        var prefs = Planner.prefs(context);
        long sent = System.currentTimeMillis() - Schedule.INTERVAL - 12_000;
        long confirmed = sent + 1_000;
        long nextStart = confirmed + Schedule.INTERVAL;
        long end = sent + 15 * Schedule.MINUTE;
        var finalCycle = new Schedule.Slot(sent, sent, end);
        prefs.edit().putLong("manualStart", sent).putLong("manualFirst", sent).putLong("manualEnd", end).commit();
        try {
            if (!Planner.reserveStart(context, sent, 15, finalCycle)
                    || !Planner.confirmStart(context, sent, confirmed, true)
                    || !finalCycle.equals(Planner.watchedSlot(context)) || Planner.next(context) != null) {
                throw new AssertionError("The final cycle must be watched without a following start");
            }
            KiaCloud.checkVehicleUse(context, new Schedule.Slot(sent - 1, sent, end));
            if (Planner.watchedSlot(context) == null) throw new AssertionError("A stale check ended the cycle");
            VehicleWatch.schedule(context);
            getUiAutomation().executeShellCommand("input keyevent 223").close();
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (Planner.watchedSlot(context) != null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100);
            }
            String reason = "Tijdvak afgerond. De auto is ontgrendeld of ingeschakeld.";
            if (Planner.watchedSlot(context) != null || Planner.activeEnd(context, System.currentTimeMillis()) > 0
                    || prefs.contains("manualEnd") || !reason.equals(prefs.getString("status", ""))
                    || context.getSystemService(PowerManager.class).isInteractive()
                    || Planner.cooldownEnd(context) != nextStart
                    || Planner.reserveStart(context, nextStart - 1, 20, null)
                    || !Planner.reserveStart(context, nextStart, 20, null)) {
                throw new AssertionError("Unlock did not complete the final cycle and preserve the cooldown");
            }
        } finally {
            prefs.edit().remove("manualStart").remove("manualFirst").remove("manualEnd").commit();
            clearClimateState(context);
            VehicleWatch.schedule(context);
        }
    }

    private static void verifyCombinedWindows(Context context) throws ReflectiveOperationException {
        var prefs = Planner.prefs(context);
        var zone = java.time.ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        var planned = java.time.LocalDateTime.now(zone).plusMinutes(10)
            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        long plannedStart = planned.atZone(zone).toInstant().toEpochMilli();
        int day = planned.getDayOfWeek().getValue() - 1;
        String suffix = day + "_0";
        long handStart = now - Schedule.MINUTE;
        long handEnd = handStart + Schedule.INTERVAL;
        long total = handEnd + 40 * Schedule.MINUTE;
        try {
            prefs.edit().putBoolean("enabled", true).putInt("days", 0)
                .putInt("start" + suffix, planned.getHour() * 60 + planned.getMinute())
                .putInt("duration" + suffix, 40).putInt("slotHeat" + suffix, Planner.HEAT_MIRRORS)
                .putLong("manualStart", handStart).putLong("manualFirst", handStart).putLong("manualEnd", handEnd)
                .putLong("activeSession", handStart).putLong("activeEnd", handEnd)
                .putLong("cycleEnd", handEnd + 1500).commit();
            Planner.schedule(context);
            Planner.schedule(context);
            if (!Planner.isCombined(prefs, now) || Planner.activeEnd(context, now) != total
                    || Planner.cycleEnd(context) != handEnd + 1500
                    || prefs.getLong("activeEnd", 0) != total
                    || !prefs.getString("status", "").contains("Tijdvakken opgeteld")) {
                throw new AssertionError("The two durations were not added exactly once");
            }
            var next = Planner.next(context);
            if (next == null || next.session() != handStart || next.end() != total
                    || Planner.heatFor(prefs, next, zone) != Planner.HEAT_MIRRORS) {
                throw new AssertionError("The scheduled settings were not preserved in the appended cycles");
            }
            var finish = Planner.class.getDeclaredMethod("finishWindow",
                Context.class, android.content.Intent.class, long.class);
            finish.setAccessible(true);
            var oldEnd = new android.content.Intent(WindowAlerts.END).putExtra("manual", true)
                .putExtra("session", handStart).putExtra("end", handEnd);
            finish.invoke(null, context, oldEnd, handEnd + 1);
            if (prefs.contains("lastManualEndNotice")) throw new AssertionError("The old end alert was not suppressed");
            if (plannedStart >= handEnd) throw new AssertionError("Test fixture must overlap");
        } finally {
            prefs.edit().remove("enabled").remove("days").remove("start" + suffix).remove("duration" + suffix)
                .remove("slotHeat" + suffix).remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("manualPlannedParts").remove("mergedWeeklySessions").commit();
            clearClimateState(context);
            VehicleWatch.schedule(context);
            Planner.schedule(context);
        }

        long onceStart = now - 10 * Schedule.MINUTE;
        long onceEnd = onceStart + 40 * Schedule.MINUTE;
        try {
            prefs.edit().putLong("onceStart", onceStart).putLong("onceEnd", onceEnd)
                .putLong("manualStart", handStart).putLong("manualFirst", handStart).putLong("manualEnd", handEnd)
                .putLong("activeSession", onceStart).putLong("activeEnd", onceEnd).commit();
            Planner.schedule(context);
            if (Planner.activeEnd(context, now) != onceEnd + Schedule.INTERVAL || prefs.contains("onceStart")
                    || prefs.getLong("manualFirst", 0) != onceStart || prefs.getLong("activeEnd", 0) != onceEnd + Schedule.INTERVAL) {
                throw new AssertionError("A manual window must extend an active one-time window");
            }
        } finally {
            prefs.edit().remove("onceStart").remove("onceEnd")
                .remove("manualStart").remove("manualFirst").remove("manualEnd")
                .remove("manualPlannedParts").remove("mergedWeeklySessions").commit();
            clearClimateState(context);
            Planner.schedule(context);
        }
    }

    private static void verifyOneTime(Context context) throws ReflectiveOperationException {
        var prefs = Planner.prefs(context);
        long start = System.currentTimeMillis() + 10 * Schedule.MINUTE;
        long end = start + 40 * Schedule.MINUTE;
        try {
            if (!Planner.planOnce(context, start, 40)) throw new AssertionError("One-time scheduling failed");
            var first = new Schedule.Slot(start, start, end);
            if (!first.equals(Planner.next(context))) throw new AssertionError("One-time alarm missing");
            var nextCycle = new Schedule.Slot(start + Schedule.INTERVAL, start, end);
            if (!Planner.stillCurrent(context, nextCycle)) throw new AssertionError("One-time follow-up cycle missing");
            Planner.cancelOnce(context);
            if (Planner.stillCurrent(context, first)) throw new AssertionError("One-time cancellation failed");
            if (!Planner.planOnce(context, start, 40)) throw new AssertionError("One-time rescheduling failed");
            var alarm = new android.content.Intent(WindowAlerts.END)
                .putExtra("once", true).putExtra("session", start).putExtra("end", end);
            var finish = Planner.class.getDeclaredMethod("finishWindow",
                Context.class, android.content.Intent.class, long.class);
            finish.setAccessible(true);
            finish.invoke(null, context, alarm, end + 1);
            if (prefs.contains("onceStart") || prefs.contains("onceEnd")) {
                throw new AssertionError("One-time schedule did not clear after completion");
            }
        } finally {
            prefs.edit().remove("onceStart").remove("onceEnd").remove("lastOnceEndNotice").commit();
            Planner.schedule(context);
        }
    }

    private static void verifyWeeklyRefusal(Context context) throws ReflectiveOperationException {
        var prefs = Planner.prefs(context);
        var zone = java.time.ZoneId.systemDefault();
        var localStart = java.time.LocalDateTime.now(zone).minusMinutes(2)
            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        long first = localStart.atZone(zone).toInstant().toEpochMilli();
        long end = first + 45 * Schedule.MINUTE;
        int day = localStart.getDayOfWeek().getValue() - 1;
        String suffix = day + "_0";
        prefs.edit().putBoolean("enabled", true).putInt("days", 0)
            .putInt("start" + suffix, localStart.getHour() * 60 + localStart.getMinute())
            .putInt("duration" + suffix, 45).putLong("weeklySession", first).putLong("weeklyEnd", end).commit();
        WindowAlerts.schedule(context, first, end, false);
        var slot = new Schedule.Slot(first, first, end);
        try {
            KiaCloud.start(context, slot);
            String reason = "Tijdvak afgerond. De auto heeft de klimaatstart geweigerd.";
            long expectedNext = localStart.plusWeeks(1).atZone(zone).toInstant().toEpochMilli();
            var next = Planner.next(context);
            if (!reason.equals(prefs.getString("status", "")) || next == null || next.session() != expectedNext
                    || Planner.activeEnd(context, System.currentTimeMillis()) > 0) {
                throw new AssertionError("Weekly refusal did not preserve just the next occurrence");
            }

            // Deliver the already registered end event with its original deadline elapsed.
            var finish = new android.content.Intent(WindowAlerts.END)
                .putExtra("session", first).putExtra("end", end).putExtra("manual", false);
            var finishWindow = Planner.class.getDeclaredMethod("finishWindow",
                Context.class, android.content.Intent.class, long.class);
            finishWindow.setAccessible(true);
            finishWindow.invoke(null, context, finish, end + 1);
            if (!reason.equals(prefs.getString("status", "")) || prefs.contains("lastWeeklyEndNotice")) {
                throw new AssertionError("A refused weekly window still generated an end alert");
            }
        } finally {
            prefs.edit().remove("enabled").remove("days").remove("start" + suffix).remove("duration" + suffix)
                .remove("weeklySession").remove("weeklyEnd").remove("cancelledWeeklySession")
                .remove("lastWeeklyEndNotice").commit();
            clearClimateState(context);
            Planner.schedule(context);
        }
    }

    private static void waitForNotification(android.app.NotificationManager manager, int id, boolean expected) {
        long deadline = SystemClock.elapsedRealtime() + 3_000;
        do {
            boolean posted = java.util.Arrays.stream(manager.getActiveNotifications())
                .anyMatch(alert -> alert.getId() == id);
            if (posted == expected) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Notification " + id + " did not reach the expected state");
    }

    private static void verifyAlerts(Context context) {
        var prefs = Planner.prefs(context);
        var manager = context.getSystemService(android.app.NotificationManager.class);
        long now = System.currentTimeMillis();
        prefs.edit().putBoolean("notifyStartLimit", false).commit();
        Planner.rateLimitReached(context);
        waitForNotification(manager, 22, false);
        prefs.edit().putBoolean("notifyStartLimit", true).commit();
        Planner.rateLimitReached(context);
        waitForNotification(manager, 22, false);
        prefs.edit().remove("kiaStartLimitedUntil").remove("kiaRateLimitLast").remove("kiaRateLimitCount").commit();
        Planner.rateLimitReached(context);
        waitForNotification(manager, 22, true);
        prefs.edit().remove("kiaStartLimitedUntil").remove("kiaRateLimitLast").remove("kiaRateLimitCount").commit();
        manager.cancel(22);
        waitForNotification(manager, 22, false);

        long end = now - 1_000;
        long session = end - 20 * Schedule.MINUTE;
        prefs.edit().putLong("manualStart", session).putLong("manualEnd", end).commit();
        var finish = new android.content.Intent(WindowAlerts.END)
            .putExtra("manual", true).putExtra("session", session).putExtra("end", end);
        new Planner().onReceive(context, finish);
        if (!prefs.getString("status", "").contains("afgelopen")) {
            throw new AssertionError("End is missing from history");
        }
        waitForNotification(manager, 21, true);
        manager.cancel(21);
        waitForNotification(manager, 21, false);
        prefs.edit().putBoolean("notifyWindowEnd", false).putLong("manualEnd", end + 1).commit();
        new Planner().onReceive(context, finish.putExtra("end", end + 1));
        if (java.util.Arrays.stream(manager.getActiveNotifications()).anyMatch(alert -> alert.getId() == 21)) {
            throw new AssertionError("Muted end posted a notification");
        }

        clearClimateState(context);
        prefs.edit().remove("manualStart").remove("manualEnd")
            .remove("notifyStartLimit").remove("notifyWindowEnd").commit();
    }
}
