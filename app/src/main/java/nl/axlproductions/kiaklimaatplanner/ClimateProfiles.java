package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.content.SharedPreferences;

final class ClimateProfiles {
    static final int SMART = 8;
    static final int COUNT = 3;
    static final int DEFAULT_THRESHOLD = 3;
    static final String GENERAL = "generalClimateProfile";

    static int general(SharedPreferences prefs) {
        return prefs.getInt(GENERAL, -1);
    }
    private static final int[] NAMES = {R.string.winter, R.string.summer, R.string.own_profile};
    private static final String[] LEGACY_NAMES = {"Winter", "Zomer", "Eigen profiel"};
    private static final int[] TEMPERATURES = {45, 40, 42};
    private static final int[] HEATING = {Planner.HEAT_WHEEL | Planner.HEAT_MIRRORS | SMART, 0, 0};

    record Profile(String name, int temperature, int heat, int threshold) {}

    static String windowKey(int day, int index) {
        return "slotProfile" + day + "_" + index;
    }

    static String readyKey(int day, int index) {
        return "slotReady" + day + "_" + index;
    }

    static Profile get(SharedPreferences prefs, int index) {
        String key = "profile" + index;
        return new Profile(displayName(index, prefs.getString(key + "Name", null)),
            prefs.getInt(key + "Temperature", TEMPERATURES[index]),
            prefs.getInt(key + "Heat", HEATING[index]),
            prefs.getInt(key + "Threshold", DEFAULT_THRESHOLD));
    }

    static void save(SharedPreferences.Editor edit, int index, Profile profile) {
        String key = "profile" + index;
        if (LEGACY_NAMES[index].equals(profile.name()) || t(NAMES[index]).equals(profile.name())) edit.remove(key + "Name");
        else edit.putString(key + "Name", profile.name());
        edit.putInt(key + "Temperature", profile.temperature())
            .putInt(key + "Heat", profile.heat()).putInt(key + "Threshold", profile.threshold());
    }

    static String displayName(int index, String saved) {
        return saved == null || LEGACY_NAMES[index].equals(saved) ? t(NAMES[index]) : saved;
    }

    static String backupName(int index, Profile profile) {
        return t(NAMES[index]).equals(profile.name()) ? LEGACY_NAMES[index] : profile.name();
    }

    static int windshieldMode(int heat) {
        if ((heat & SMART) != 0) return 2;
        return (heat & Planner.HEAT_WINDSHIELD) != 0 ? 1 : 0;
    }

    static int windshieldFlag(int mode) {
        return mode == 2 ? SMART : mode == 1 ? Planner.HEAT_WINDSHIELD : 0;
    }

    static int applySmart(int heat, int threshold, double outside) {
        if ((heat & SMART) == 0) return heat;
        int withoutWindshield = heat & ~(SMART | Planner.HEAT_WINDSHIELD);
        return Double.isFinite(outside) && outside <= threshold
            ? withoutWindshield | Planner.HEAT_WINDSHIELD : withoutWindshield;
    }
}
