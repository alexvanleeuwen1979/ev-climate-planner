package nl.axlproductions.kiaklimaatplanner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ClimateWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
    }

    static void refresh(Context context) {
        var manager = AppWidgetManager.getInstance(context);
        var ids = manager.getAppWidgetIds(new ComponentName(context, ClimateWidget.class));
        if (ids.length > 0) render(context, manager, ids);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        var prefs = Planner.prefs(context);
        long now = System.currentTimeMillis();
        long end = Planner.activeEnd(context, now);
        boolean active = end > now && Planner.hasConfirmedWindow(context, end) && !Planner.manuallyStopped(prefs);
        var next = Planner.next(context);
        var zone = ZoneId.systemDefault();
        var format = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.forLanguageTag("nl-NL")).withZone(zone);
        var view = new RemoteViews(context.getPackageName(), R.layout.climate_widget);
        view.setTextViewText(R.id.widget_state, active ? "Klimaat actief" : "Volgende start");
        view.setTextViewText(R.id.widget_value, active ? "Einde " + format.format(Instant.ofEpochMilli(end))
            : next == null ? "Geen start gepland" : format.format(Instant.ofEpochMilli(next.time())));
        view.setViewVisibility(R.id.widget_timer, active ? View.VISIBLE : View.GONE);
        view.setChronometerCountDown(R.id.widget_timer, true);
        view.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + Math.max(0, end - now), "Resterend %s", active);
        String detail;
        if (!KiaCloud.linked(context) || KiaCloud.needsLink(context)) {
            detail = "Koppel Kia Connect";
            view.setTextViewText(R.id.widget_value, "Planning wacht");
        } else if (!Planner.hasAlarmAccess(context)) {
            detail = "Exacte alarmen toestaan";
            view.setTextViewText(R.id.widget_value, "Planning wacht");
        } else {
            var slot = active ? new Schedule.Slot(now, prefs.getLong("activeSession", 0), prefs.getLong("activeEnd", 0)) : next;
            var options = Planner.climateOptions(prefs, slot, zone);
            detail = options.profile() < 0 ? "Standaardverwarming" : ClimateProfiles.get(prefs, options.profile()).name();
            if (!active && next != null) detail += " · einde " + format.format(Instant.ofEpochMilli(next.end()));
        }
        view.setTextViewText(R.id.widget_detail, detail);
        var open = PendingIntent.getActivity(context, 60, new Intent(context, MainActivity.class)
            .setAction(MainActivity.OPEN_DASHBOARD).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, open);
        manager.updateAppWidget(ids, view);
    }
}
