package nl.axlproductions.kiaklimaatplanner;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.List;
import java.util.Locale;

public final class AppLanguage extends Application {
    private record Choice(String tag, String label) {}

    private static final List<Choice> CHOICES = List.of(
        new Choice("nl", "🇳🇱  Nederlands"), new Choice("en", "🇬🇧  English"),
        new Choice("de", "🇩🇪  Deutsch"), new Choice("fr", "🇫🇷  Français"), new Choice("es", "🇪🇸  Español")
    );
    private static volatile Locale selected = Locale.ENGLISH;
    private static volatile Resources strings;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        refreshLanguage(base);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        refreshLanguage(this);
    }

    static Context localized(Context base) {
        return localized(base, selected);
    }

    private static Context localized(Context base, Locale locale) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }

    static Locale locale() {
        return selected;
    }

    static String t(int resource) {
        return strings.getString(resource);
    }

    static boolean messageStartsWith(Context context, String message, int resource) {
        for (Choice choice : CHOICES) {
            if (message.startsWith(localized(context, Locale.forLanguageTag(choice.tag())).getString(resource))) return true;
        }
        return false;
    }

    static String[] choices() {
        return CHOICES.stream().map(Choice::label).toArray(String[]::new);
    }

    static int selectedIndex() {
        for (int i = 0; i < CHOICES.size(); i++) {
            if (CHOICES.get(i).tag().equals(selected.getLanguage())) return i;
        }
        return 1;
    }

    static boolean select(Context context, int index) {
        if (index == selectedIndex()) return false;
        String tag = CHOICES.get(index).tag();
        context.getSharedPreferences("app_language", MODE_PRIVATE).edit().putString("language", tag).apply();
        setLanguage(context.getApplicationContext(), tag);
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        }
        return true;
    }

    private static void refreshLanguage(Context base) {
        String fallback = base.getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = base.getSystemService(LocaleManager.class).getApplicationLocales();
            setLanguage(base, locales.isEmpty() ? fallback : locales.get(0).getLanguage());
        } else {
            setLanguage(base, base.getSharedPreferences("app_language", MODE_PRIVATE).getString("language", fallback));
        }
    }

    private static void setLanguage(Context base, String tag) {
        selected = CHOICES.stream().anyMatch(choice -> choice.tag().equals(tag))
            ? Locale.forLanguageTag(tag) : Locale.ENGLISH;
        strings = localized(base).getResources();
    }
}
