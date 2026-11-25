package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class KiaCloud {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static PyObject module(Context context) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        return Python.getInstance().getModule("kia_mobile");
    }

    static JSONObject linkResponse(Context context, PyObject value) throws Exception {
        JSONObject response = new JSONObject(value.toString());
        JSONObject session = response.optJSONObject("session");
        if (session != null) {
            if (linked(context)) {
                JSONObject current = new JSONObject(KiaSessionStore.read(context));
                if (!current.getString("vehicle").equals(session.getString("vehicle"))) {
                    return new JSONObject().put("error", t(R.string.to_switch_cars_first_disconnect_the));
                }
            }
            KiaSessionStore.save(context, session.toString());
            Planner.prefs(context).edit().putString("kiaModel", response.getString("model"))
                .remove("cloudNeedsLink").commit();
            BatterySampling.updateSchedule(context);
        }
        return response;
    }

    static boolean linked(Context context) {
        return Planner.prefs(context).contains("kiaModel");
    }

    static boolean needsLink(Context context) {
        return Planner.prefs(context).getBoolean("cloudNeedsLink", false);
    }

    static void start(Context context, Schedule.Slot slot) {
        start(context, slot, false);
    }

    static void start(Context context, Schedule.Slot slot, boolean manual) {
        boolean paused = KiaBackoff.paused(context);
        if (needsLink(context) || (paused && !manual)) return;
        long submittedAt = 0;
        boolean accepted = false;
        try {
            if (slot != null && !Planner.stillCurrent(context, slot)) return;
            PyObject api = module(context);
            PyObject prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            var before = new JSONObject();
            if (slot != null && !paused) {
                if (!Planner.stillCurrent(context, slot)) return;
                if (vehicleInUse(api, prepared)) {
                    Planner.finishIfInUse(context, slot);
                    return;
                }
                if (!Planner.stillCurrent(context, slot)) return;
                before = cachedClimate(api, prepared);
                EnergyHistory.captureBattery(context, before);
                long measured = before.optLong("time", 0);
                if (Planner.waitForExternalClimate(context, slot, measured, climateActive(before))) return;
                var settings = Planner.prefs(context);
                if (Planner.firstPlannedAttempt(settings, slot)) {
                    boolean home = Planner.homeOnly(settings, slot, java.time.ZoneId.systemDefault());
                    if (home || ClimateRules.conditional(settings)) {
                        var environment = climateEnvironment(api, prepared);
                        if (ClimateRules.refreshNeeded(settings, home, before, environment, System.currentTimeMillis())) {
                            try {
                                before = new JSONObject(api.callAttr("force_climate_snapshot", prepared).toString());
                                EnergyHistory.captureBattery(context, before);
                                environment = climateEnvironment(api, prepared);
                            } catch (Exception error) {
                                if (rateLimitingError(error)) throw error;
                                // The configured rules will use whichever readings are still timely.
                            }
                        }
                        if (before.optBoolean("inUse")) {
                            Planner.finishIfInUse(context, slot);
                            return;
                        }
                        if (Planner.waitForExternalClimate(context, slot, before.optLong("time", 0), climateActive(before))) return;
                        if (settings.getBoolean("skipMild", false) && !Double.isFinite(environment.optDouble("outside"))) {
                            ClimateIndicator.update(context, environment, true);
                            var weather = ClimateIndicator.read(settings, System.currentTimeMillis(), false);
                            if (weather.weather() && Double.isFinite(weather.outdoor())) {
                                environment.put("time", weather.measured()).put("outside", weather.outdoor()).put("fromWeather", true);
                            }
                        }
                        var skipped = ClimateRules.skip(settings, home, before, environment, System.currentTimeMillis());
                        if (skipped != null) {
                            Planner.skipByRule(context, slot, skipped);
                            return;
                        }
                    }
                }
            }

            int duration = Schedule.CYCLE_MINUTES;
            var prefs = Planner.prefs(context);
            var zone = java.time.ZoneId.systemDefault();
            var options = Planner.climateOptions(prefs, slot, zone);
            int heating = Planner.heatForCycle(prefs, slot, options.heat());
            if ((heating & ClimateProfiles.SMART) != 0) {
                if (!paused) updateClimateIndicator(context, api, prepared, true);
                var reading = ClimateIndicator.read(prefs, System.currentTimeMillis(), false);
                int threshold = options.threshold();
                heating = ClimateProfiles.applySmart(heating, threshold, reading.outdoor());
                startNote(context, slot, Double.isFinite(reading.outdoor())
                    ? t(R.string.smart_windshield_position) + requested(heating, Planner.HEAT_WINDSHIELD) + t(R.string.at)
                        + reading.outdoor() + t(R.string.c_limit) + threshold + " °C)."
                    : t(R.string.smart_windshield_mode_off_no_recent), false);
            }
            if (slot != null && !Planner.stillCurrent(context, slot)) return;
            submittedAt = System.currentTimeMillis();
            if (!Planner.reserveStart(context, submittedAt, duration, slot, manual)) return;
            double temperature = options.temperature() / 2.0;
            PyObject action = api.callAttr("start", prepared, temperature, duration,
                    (heating & Planner.HEAT_WHEEL) != 0, (heating & Planner.HEAT_MIRRORS) != 0,
                    (heating & Planner.HEAT_WINDSHIELD) != 0);
            if (action == null || action.toString().trim().isEmpty()) throw new IllegalStateException("Missing Kia action ID");
            accepted = true;
            startNote(context, slot, t(R.string.climate_start_sent_requested_windshield) + requested(heating, Planner.HEAT_WINDSHIELD)
                + t(R.string.send) + requested(heating, Planner.HEAT_WHEEL)
                + t(R.string.rear_window_mirrors) + requested(heating, Planner.HEAT_MIRRORS) + ".", false);
            String result = api.callAttr("result", prepared, action).toString();
            if ("FAILED".equals(result)) {
                Planner.stopUnavailable(context, submittedAt);
                return;
            }
            if ("SUCCESS".equals(result)) {
                long confirmed = System.currentTimeMillis();
                if (Planner.confirmStart(context, submittedAt, confirmed, slot != null)) {
                    StartLedger.record(context, submittedAt, StartLedger.Kind.CONFIRMED, null);
                    ClimateEnergy.begin(context, submittedAt, slot == null ? confirmed : slot.session(), confirmed,
                        duration * Schedule.MINUTE, before);
                    prefs.edit().putLong("climateTargetRequest", submittedAt)
                        .putFloat("climateRequestedTarget", (float) temperature).apply();
                    if (!paused) updateClimateIndicator(context, api, prepared);
                    startNote(context, slot, t(R.string.kia_confirms_climate_control_started), true);
                }
            } else {
                Planner.startFailed(context, slot, submittedAt, true);
                startProblem(context, slot, submittedAt, t(R.string.no_confirmation_received_from_the_car), StartLedger.Cause.NO_REPLY);
            }
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                Planner.rateLimitReached(context, submittedAt == 0 ? t(R.string.status_before_climate_start) : t(R.string.climate_start));
                Planner.startFailed(context, slot, submittedAt, accepted);
                saveStartProblem(context, slot, submittedAt, t(R.string.kia_has_temporarily_refused_the_climate),
                    StartLedger.Cause.LIMIT);
                return;
            }
            if (submittedAt != 0 && !authenticationError(error) && climateUnavailable(error.getMessage())) {
                Planner.stopUnavailable(context, submittedAt);
                return;
            }
            Planner.startFailed(context, slot, submittedAt, accepted || deliveryUncertain(error));
            if (authenticationError(error) || error instanceof java.security.GeneralSecurityException) {
                Planner.prefs(context).edit().putBoolean("cloudNeedsLink", true).apply();
            }
            startProblem(context, slot, submittedAt, problem(error),
                authenticationError(error) || error instanceof java.security.GeneralSecurityException
                    ? StartLedger.Cause.AUTH : StartLedger.Cause.NETWORK);
        }
    }

    private static void startNote(Context context, Schedule.Slot slot, String message, boolean confirmed) {
        if (slot == null) Planner.note(context, message);
        else Planner.note(context, message, slot.session(), slot.end(), confirmed);
    }

    static void startProblem(Context context, Schedule.Slot slot, long attemptedAt, String message,
                             StartLedger.Cause cause) {
        if (saveStartProblem(context, slot, attemptedAt, message, cause)
                && Planner.prefs(context).getBoolean("notifyStartFailure", true)) {
            WindowAlerts.failed(context, message, cause);
        }
    }

    private static boolean saveStartProblem(Context context, Schedule.Slot slot, long attemptedAt, String message,
                                            StartLedger.Cause cause) {
        long key = slot == null ? attemptedAt : slot.time();
        if (key <= 0) key = System.currentTimeMillis();
        StartLedger.record(context, key, StartLedger.Kind.FAILED, cause);
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long session = slot == null ? 0 : slot.session();
        long end = slot == null ? 0 : slot.end();
        boolean manual = slot != null && session == prefs.getLong("lastManualRequest", 0);
        var options = Planner.climateOptions(prefs, slot, java.time.ZoneId.systemDefault());
        int minutes = slot == null ? Schedule.CYCLE_MINUTES : Math.max(Schedule.CYCLE_MINUTES,
            Schedule.cycleCount(session, end) * Schedule.CYCLE_MINUTES);
        prefs.edit().putLong("lastFailure", now).putInt("retryMinutes", manual ? prefs.getInt("previousManualMinutes", minutes) : minutes)
            .putInt("retryProfile", manual ? prefs.getInt("previousManualProfile", options.profile()) : options.profile())
            .putInt("retryTemperature", manual ? prefs.getInt("previousManualTemperature", -1) : options.temperature())
            .putString("retryCause", cause.name()).apply();
        Planner.note(context, message, session, end, false, Planner.Outcome.UNCONFIRMED, cause);
        return slot != null && !manual;
    }

    static void stop(Context context) {
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            var action = api.callAttr("stop", prepared);
            if (action == null || action.toString().trim().isEmpty()) throw new IllegalStateException("Missing Kia action ID");
            String result = api.callAttr("result", prepared, action).toString();
            if ("SUCCESS".equals(result)) {
                ClimateEnergy.finish(context, 0, System.currentTimeMillis(), ClimateEnergy.End.MANUAL, null);
                Planner.confirmStop(context);
                Planner.note(context, t(R.string.kia_confirms_climate_control_stopped));
            } else {
                Planner.note(context, t(R.string.kia_does_not_confirm_the_stop));
            }
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                Planner.rateLimitReached(context, t(R.string.climate_stop));
                Planner.note(context, t(R.string.climate_stop_not_confirmed_kia_reports));
            } else Planner.note(context, t(R.string.climate_stop_not_confirmed) + problem(error));
        }
    }

    static String diagnostic(Exception error) {
        String detail = error.getMessage();
        if (detail != null) {
            var code = java.util.regex.Pattern.compile("Server returned:\\s*'([0-9-]+)'").matcher(detail);
            if (code.find()) return t(R.string.kia_code) + code.group(1);
            var type = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9_.]*(?:Error|Exception|Timeout)):").matcher(detail);
            if (type.find()) return type.group(1);
        }
        return error.getClass().getSimpleName();
    }

    private static boolean deliveryUncertain(Exception error) {
        String detail = error.getMessage();
        return detail != null && (detail.contains("ReadTimeout") || detail.contains("RequestTimeoutError")
            || detail.contains("DuplicateRequestError") || detail.contains("InvalidAPIResponseError"));
    }

    private static boolean vehicleInUse(PyObject api, PyObject prepared) {
        try {
            return api.callAttr("vehicle_in_use", prepared).toBoolean();
        } catch (com.chaquo.python.PyException error) {
            if (rateLimitingError(error)) throw error;
            return false;
        }
    }

    static void checkVehicleUse(Context context, Schedule.Slot slot) {
        if (!linked(context) || needsLink(context) || KiaBackoff.paused(context)
                || !slot.equals(Planner.watchedSlot(context))) return;
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            if (!linked(context) || !slot.equals(Planner.watchedSlot(context))) return;
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            if (vehicleInUse(api, prepared)) {
                Planner.finishWatchedWindow(context, slot);
                return;
            }
            if (!slot.equals(Planner.watchedSlot(context))) return;
            try {
                logObservedHeating(context, api, prepared, slot.time());
            } catch (Exception ignored) {
                // Heating-option telemetry is independent from the climate state.
            }
            if (!Planner.monitorsClimate(context, slot)) return;
            var reading = cachedClimate(api, prepared);
            var prefs = Planner.prefs(context);
            long now = System.currentTimeMillis();
            long measured = reading.optLong("time", 0);
            if (climateActive(reading) == null || measured <= Math.max(prefs.getLong("lastClimateSample", 0),
                    prefs.getLong("confirmedStart", 0)) || measured < now - 2 * Schedule.MINUTE
                    || (now >= Planner.cycleEnd(context) && measured < Planner.cycleEnd(context))) {
                reading = new JSONObject(api.callAttr("force_climate_snapshot", prepared).toString());
            }
            updateClimateIndicator(context, api, prepared);
            consumeMonitoredStatus(context, slot, reading);
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                Planner.rateLimitReached(context, t(R.string.climate_control));
                return;
            }
            if (Planner.monitorsClimate(context, slot) && slot.equals(Planner.watchedSlot(context))) {
                Planner.note(context, t(R.string.climate_control_collection_failed) + diagnostic(error)
                    + t(R.string.the_scheduler_is_waiting_for_a));
            }
        }
    }

    private static String requested(int heating, int flag) {
        return (heating & flag) != 0 ? t(R.string.on) : t(R.string.out_2);
    }

    private static void logObservedHeating(Context context, PyObject api, PyObject prepared, long sentAt) throws Exception {
        var prefs = Planner.prefs(context);
        if (prefs.getLong("heaterStatusLoggedFor", 0) == sentAt) return;
        var reading = new JSONObject(api.callAttr("observed_heating", prepared).toString());
        long measured = reading.optLong("time", 0);
        if (measured < sentAt / 1_000 * 1_000 || measured > System.currentTimeMillis() + 5_000) return;
        prefs.edit().putLong("heaterStatusLoggedFor", sentAt).apply();
        Planner.note(context, t(R.string.heating_status_according_to_kia_windshield) + heaterState(reading, "windshield")
            + t(R.string.send) + heaterState(reading, "wheel") + t(R.string.rear_window) + heaterState(reading, "rear")
            + t(R.string.mirrors) + heaterState(reading, "mirrors") + ".");
    }

    private static String heaterState(JSONObject reading, String key) {
        Object value = reading.opt(key);
        if (Boolean.TRUE.equals(value) || Integer.valueOf(1).equals(value)) return t(R.string.on);
        if (Boolean.FALSE.equals(value) || Integer.valueOf(0).equals(value)) return t(R.string.out_2);
        return t(R.string.unknown_2);
    }

    private static JSONObject cachedClimate(PyObject api, PyObject prepared) {
        try {
            return new JSONObject(api.callAttr("cached_climate", prepared).toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static Boolean climateActive(JSONObject reading) {
        return reading.opt("climate") instanceof Boolean active ? active : null;
    }

    private static void publishBattery(Context context, JSONObject reading) {
        var prefs = Planner.prefs(context);
        int percentage = reading.optInt("percentage", -1);
        long time = reading.optLong("time", 0);
        if (percentage >= 0 && percentage <= 100 && time > 0 && time <= System.currentTimeMillis() + 5_000
                && time >= prefs.getLong("batteryTime", 0) && linked(context)) {
            prefs.edit().putInt("battery", percentage).putLong("batteryTime", time).apply();
        }
    }

    private static void consumeMonitoredStatus(Context context, Schedule.Slot slot, JSONObject reading) {
        EnergyHistory.captureBattery(context, reading);
        publishBattery(context, reading);
        long measured = reading.optLong("time", 0);
        long now = System.currentTimeMillis();
        if (reading.optBoolean("inUse") && measured >= now - 2 * Schedule.MINUTE && measured <= now + 5_000) {
            Planner.finishWatchedWindow(context, slot);
            return;
        }
        var continuation = Planner.recordClimateSnapshot(context, slot, measured, climateActive(reading), reading);
        if (continuation != null) start(context, continuation);
    }

    private static void updateClimateIndicator(Context context, PyObject api, PyObject prepared) {
        updateClimateIndicator(context, api, prepared, false);
    }

    private static void updateClimateIndicator(Context context, PyObject api, PyObject prepared, boolean immediateWeather) {
        try {
            var environment = new JSONObject(api.callAttr("climate_environment", prepared).toString());
            ClimateIndicator.update(context, environment, immediateWeather);
        } catch (Exception ignored) {
            // The indicator is independent of vehicle control and battery status.
        }
    }

    private static JSONObject climateEnvironment(PyObject api, PyObject prepared) {
        try {
            return new JSONObject(api.callAttr("climate_environment", prepared).toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static double[] readHomePosition(Context context) {
        if (!linked(context) || needsLink(context) || KiaBackoff.paused(context)) return null;
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            vehicleInUse(api, prepared);
            return ClimateRules.position(climateEnvironment(api, prepared), System.currentTimeMillis());
        } catch (Exception error) {
            if (rateLimitingError(error)) Planner.rateLimitReached(context, t(R.string.car_location));
            return null;
        }
    }

    static String connectionCheck(Context context) {
        if (!linked(context) || needsLink(context)) return t(R.string.kia_account_relink);
        if (KiaBackoff.paused(context)) return KiaBackoff.message(KiaBackoff.until(Planner.prefs(context)));
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            var reading = new JSONObject(api.callAttr("battery", prepared, false).toString());
            EnergyHistory.captureBattery(context, reading);
            long measured = reading.optLong("time", 0);
            if (measured <= 0) return t(R.string.kia_accessible_no_vehicle_status_received);
            var stamp = java.time.Instant.ofEpochMilli(measured).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd-MM HH:mm"));
            return t(R.string.kia_accessible_receive_vehicle_status) + stamp + ").";
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                Planner.rateLimitReached(context, "verbindingscheck");
                return KiaBackoff.message(KiaBackoff.until(Planner.prefs(context)));
            }
            return t(R.string.kia_check_failed) + diagnostic(error) + ").";
        }
    }

    static void sampleBattery(Context context) {
        var prefs = Planner.prefs(context);
        if (prefs.getLong("monitoringStart", 0) > 0 || Planner.cycleEnd(context) > System.currentTimeMillis()) return;
        long last = prefs.getLong("batteryTime", 0);
        long now = System.currentTimeMillis();
        if (last > 0 && last <= now && now - last < 10 * Schedule.MINUTE) return;
        readBattery(context, false, true);
    }

    static String refreshDashboard(Context context, boolean requested) {
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        if (KiaBackoff.until(prefs) > now) return requested ? refreshWhilePaused(context, prefs, now) : null;
        var slot = Planner.watchedSlot(context);
        if (slot != null && Planner.monitorsClimate(context, slot)) {
            if (!requested) return null;
            var check = VehicleWatch.claim(context, slot, now, -1);
            if (check.allowed()) {
                VehicleWatch.schedule(context);
                checkVehicleUse(context, slot);
                return null;
            }
            if (check.nextTime() == 0) return t(R.string.the_automatic_checks_for_this_cycle);
            var time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(check.nextTime()));
            return t(R.string.last_status_shown_kia_inspection_possible) + time + ".";
        }
        if (Planner.awaitingConfirmation(context, now) || Planner.cycleEnd(context) > now
                || prefs.getLong("stopVehiclePending", 0) > now) {
            return requested ? t(R.string.last_status_shown_the_planner_is) : null;
        }
        long last = prefs.getLong("lastDashboardKiaRequest", 0);
        if (last > 0 && last <= now && now - last < Schedule.MINUTE) {
            return requested ? t(R.string.last_status_shown_you_can_refresh) : null;
        }
        prefs.edit().putLong("lastDashboardKiaRequest", now).apply();
        readBattery(context, requested);
        return null;
    }

    private static String refreshWhilePaused(Context context, android.content.SharedPreferences prefs, long now) {
        if (!linked(context) || needsLink(context)) return t(R.string.kia_account_relink);
        long expectedPause = KiaBackoff.until(prefs);
        long next = KiaBackoff.reserveManualRefresh(prefs, now);
        if (next > now) {
            if (next == KiaBackoff.until(prefs)) return KiaBackoff.message(next);
            var stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(next));
            return t(R.string.next_manual_status_check_from) + stamp + ".";
        }
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            var reading = new JSONObject(api.callAttr("battery", prepared, false).toString());
            long received = System.currentTimeMillis();
            long time = reading.optLong("time", 0);
            if (time <= 0 || time > received + 5_000) return t(R.string.kia_has_not_provided_a_valid);
            Boolean active = climateActive(reading);
            double percentage = reading.optDouble("percentage", -1);
            if (active == null && !(percentage >= 0 && percentage <= 100)) {
                return t(R.string.kia_did_not_provide_usable_vehicle);
            }
            EnergyHistory.captureBattery(context, reading);
            publishBattery(context, reading);
            if (active != null && !reading.optBoolean("inUse")) {
                Planner.rememberExternalClimate(context, time, active);
            }
            var stamp = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(time));
            if (linked(context) && !needsLink(context) && KiaBackoff.resumeAfterStatus(prefs, expectedPause, received)) {
                Planner.schedule(context);
                VehicleWatch.schedule(context);
                Planner.note(context, t(R.string.kia_responds_again_pause_lifted_latest) + stamp + ".");
                return t(R.string.kia_responds_again_pause_lifted);
            }
            return t(R.string.latest_vehicle_status_at_kia) + stamp + ".";
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                long until = KiaBackoff.manualRefreshLimited(prefs, System.currentTimeMillis());
                Planner.schedule(context);
                VehicleWatch.schedule(context);
                Planner.note(context, t(R.string.kia_request_limit_on_manual_refresh) + KiaBackoff.message(until) + ".");
                return KiaBackoff.message(until);
            }
            return t(R.string.kia_status_retrieval_failed_the_last);
        }
    }

    static void readBattery(Context context, boolean force) {
        readBattery(context, force, false);
    }

    private static void readBattery(Context context, boolean force, boolean hourly) {
        if (!linked(context) || needsLink(context) || KiaBackoff.paused(context)) return;
        try {
            var api = module(context);
            var prepared = api.callAttr("prepare_start", KiaSessionStore.read(context));
            KiaSessionStore.save(context, prepared.asList().get(2).toString());
            var reading = new JSONObject(api.callAttr("battery", prepared, force).toString());
            EnergyHistory.captureBattery(context, reading);
            long previous = Planner.prefs(context).getLong("batteryTime", 0);
            publishBattery(context, reading);
            if (hourly) return;
            updateClimateIndicator(context, api, prepared);
            int percentage = reading.optInt("percentage", -1);
            long time = reading.optLong("time", 0);
            if (force && (percentage < 0 || percentage > 100 || time <= previous)) {
                Planner.note(context, t(R.string.kia_has_not_yet_provided_a));
            }
            var watched = force ? Planner.watchedSlot(context) : null;
            if (watched != null && Planner.monitorsClimate(context, watched)) {
                consumeMonitoredStatus(context, watched, reading);
            } else {
                Boolean active = climateActive(reading);
                long now = System.currentTimeMillis();
                boolean fresh = active != null && time >= now - 2 * Schedule.MINUTE && time <= now + 5_000;
                if (active != null && time <= now + 5_000 && !reading.optBoolean("inUse")) {
                    Planner.rememberExternalClimate(context, time, active);
                }
                if (force) {
                    Planner.note(context, !fresh ? t(R.string.climate_control_kia_does_not_provide)
                        : active ? t(R.string.climate_control_active_according_to_kia) : t(R.string.climate_control_off_according_to_kia));
                }
            }
        } catch (Exception error) {
            if (rateLimitingError(error)) {
                Planner.rateLimitReached(context, hourly ? "accu-uurcheck" : t(R.string.replace_battery));
                return;
            }
            if (hourly) return;
            Planner.note(context, t(R.string.failed_to_get_battery_percentage_the));
            if (force) Planner.note(context, t(R.string.climate_control_refresh_failed) + diagnostic(error) + ").");
        }
    }

    static boolean climateUnavailable(String detail) {
        if (detail == null) return false;
        String value = detail.toLowerCase(java.util.Locale.ROOT);
        boolean climate = value.contains("klimaat") || value.contains("climate") || value.contains("hvac")
            || value.contains("air conditioning");
        return (climate && (value.contains("niet beschikbaar") || value.contains("niet mogelijk")
                || value.contains("not available") || value.contains("unavailable")
                || value.contains("geweigerd") || value.contains("refused") || value.contains("rejected")))
            || value.contains("engine is running") || value.contains("ignition is on")
            || value.contains("vehicle is already started");
    }

    private static boolean authenticationError(Exception error) {
        String detail = error.getMessage();
        return detail != null && (detail.contains("Authentication") || detail.contains("PINMissingError"));
    }

    static boolean rateLimitingError(Exception error) {
        String detail = error.getMessage();
        return detail != null && (detail.contains("RateLimitingError") || detail.contains("HTTP 429")
            || detail.contains("429 Client Error"));
    }

    static String problem(Exception error) {
        if (rateLimitingError(error)) {
            return t(R.string.kia_reports_that_the_request_limit);
        }
        if (authenticationError(error)) {
            return t(R.string.kia_has_refused_the_registration_please);
        }
        if (error instanceof java.security.GeneralSecurityException) {
            return t(R.string.the_saved_kia_session_is_not);
        }
        String cause = diagnostic(error);
        if (cause.contains("Connection") || cause.contains("Timeout") || cause.contains("NameResolution")) {
            return t(R.string.the_connection_to_kia_connect_failed) + cause + ").";
        }
        return t(R.string.kia_connect_could_not_complete_the) + cause + ").";
    }
}






