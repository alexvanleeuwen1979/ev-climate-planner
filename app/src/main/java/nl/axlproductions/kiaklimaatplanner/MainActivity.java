package nl.axlproductions.kiaklimaatplanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.Gravity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final Locale NL = Locale.forLanguageTag("nl-NL");
    private static final int INK = 0xfff2fbfc;
    private static final int MUTED = 0xffc9dee2;
    private static final int ACCENT = 0xffc4edf8;
    private static final int ICE = 0xff50d4eb;
    private static final int CORAL = 0xffff665d;
    private static final int OBSIDIAN = 0xff050505;
    private static final String[] DAYS = {
        "Maandag", "Dinsdag", "Woensdag", "Donderdag", "Vrijdag", "Zaterdag", "Zondag"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button[][] windowButtons = new Button[7][2];
    private final Button[] dayHeaders = new Button[7];
    private final TextView[] dayChevrons = new TextView[7];
    private final LinearLayout[] dayCards = new LinearLayout[7];
    private final Switch[] daySwitches = new Switch[7];
    private final Switch[][] windowSwitches = new Switch[7][2];
    private final LinearLayout[] dayPanes = new LinearLayout[7];
    private boolean updatingWeek;
    private SharedPreferences prefs;
    private final LinearLayout[] sections = new LinearLayout[3];
    private final Button[] tabs = new Button[3];
    private FrameLayout root;
    private boolean reopeningFromBackground;
    private ScrollView scroll;
    private ListView historyScroll;
    private HistoryAdapter historyAdapter;
    private int section;
    private SwipeRefreshLayout swipe;
    private TextView overall, statusDetail, windowClock, windowLabel, cycleClock, cycleLabel, cooldown;
    private TextView battery, batteryMeasured, nextView, nextDay, accountState, access;
    private TextView outsideTemperature, targetTemperature, liveStep;
    private Button pauseButton, windshieldSetting, changeNext, generalProfileSetting;
    private TextView nextPreview, adjustmentMessage;
    private LinearLayout nextPreviewCard, undoBar;
    private Planner.WeeklyChange undoChange;
    private long undoUntil;
    private Schedule.Slot displayedNext;
    static final String OPEN_DASHBOARD = "nl.axlproductions.kiaklimaatplanner.DASHBOARD";
    static final String OPEN_RETRY = "nl.axlproductions.kiaklimaatplanner.RETRY";
    static final String OPEN_RECOVERY = "nl.axlproductions.kiaklimaatplanner.RECOVERY";
    private static final int CALENDAR_PERMISSION = 46, EXPORT_DIAGNOSTIC = 47, LOCATION_PERMISSION = 48;
    private AlertDialog pendingGpsDialog;
    private Runnable calendarPermissionGranted;
    private String pendingDiagnostic;
    private Button extendAction, repeatAction, preflightAction;
    private static final String MANUAL_SHORTCUT = "nl.axlproductions.kiaklimaatplanner.MANUAL_SHORTCUT";
    private static final int EXPORT_PLAN = 41, IMPORT_PLAN = 42;
    private LinearLayout clocks, windowMetric, cycleMetric;
    private View clockDivider;
    private ClimateTimeline timeline;
    private GradientDrawable slotAccent;
    private Button manualButton, onceButton, stopButton, alarmPermission, unlink, accountButton, alertPermission;
    private View batteryTile;
    private LinearLayout accountDetails;
    private boolean accountExpanded;
    private LinearLayout screenControls;
    private Switch enabled;
    private String shownHistory;
    private final java.util.Set<Long> expandedHistory = new java.util.HashSet<>();
    private long historyReloadAt = Long.MAX_VALUE;
    private long lastBatteryRequest;
    private boolean batteryBusy, batteryForce, batteryForcePending;
    private int shownClimateIcon = R.drawable.ic_ui_snow;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Planner.migrateDurations(this);
        prefs = Planner.prefs(this);
        KiaBackoff.restoreFromHistory(this, System.currentTimeMillis());
        slotAccent = new GradientDrawable();
        slotAccent.setColor(ICE);
        slotAccent.setSize(dp(4), dp(23));
        slotAccent.setCornerRadius(dp(3));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setNestedScrollingEnabled(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(16), dp(16), dp(16), dp(18));
        scroll.addView(page);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(scroll);
        swipe.setColorSchemeColors(ACCENT);
        swipe.setProgressBackgroundColorSchemeColor(0xff232a34);
        swipe.setOnRefreshListener(() -> {
            refresh();
            requestBattery(true);
            if (!batteryBusy) swipe.setRefreshing(false);
        });
        root = withBackdrop(swipe);
        setContentView(root);
        TextView title = text(getString(R.string.app_name), 32, INK, true);
        title.setPadding(0, 0, 0, dp(4));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        page.addView(title);
        addNavigation(page);
        for (int index = 0; index < sections.length; index++) {
            sections[index] = column();
            page.addView(sections[index]);
        }

        buildDashboard(sections[0]);

        var profiles = card(sections[2], "Klimaatprofielen");
        generalProfileSetting = addButton(profiles, "", false, this::chooseGeneralProfile);
        generalProfileSetting.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        profiles.addView(text("Geldt voor starts zonder een eigen profiel.", 13, MUTED, false));
        addButton(profiles, "Profielen beheren", false, this::manageProfiles);
        addButton(profiles, "Profielen per seizoen", false, this::editSeasons);

        LinearLayout heat = card(sections[2], "Standaardverwarming");
        temperatureControl(heat);
        heatSwitch(heat, "Stuurverwarming", "heatWheel", R.drawable.ic_ui_wheel);
        heatSwitch(heat, "Spiegels en achterruit", "heatMirrors", R.drawable.ic_ui_rear);
        windshieldSetting = addButton(heat, "Voorruit ontdooien", false, this::editDefaultWindshield);
        windshieldSetting.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        decorate(windshieldSetting, R.drawable.ic_ui_defrost, ACCENT, 20);
        heat.addView(text("Voorruit alleen in de eerste cyclus.", 13, MUTED, false));
        addClimateWeatherSetting(heat);
        var rules = card(sections[2], "Voorwaarden voor geplande starts");
        addButton(rules, "Accugrens en comfortbereik", false, this::editStartRules);
        addButton(rules, "Thuislocatie instellen", false, this::saveHomeLocation);
        var security = card(sections[2], "Handmatige bediening");
        var authentication = heatingChoice(security, "Biometrische bevestiging", prefs.getBoolean("requireBiometric", false));
        authentication.setOnCheckedChangeListener((view, checked) -> {
            if (checked && !biometricAvailable()) {
                view.setChecked(false);
                Toast.makeText(this, "Stel eerst biometrie in op je Samsung.", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("requireBiometric", checked).apply();
        });
        var reports = card(sections[2], "Overzicht en diagnose");
        addButton(reports, "Maandoverzicht openen", false, this::showMonth);
        addButton(reports, "Privélog exporteren", false, this::choosePrivateLog);
        var energyLog = card(sections[2], "Klimaatverbruik");
        addButton(energyLog, "Grafieken en overzicht", false, this::showEnergyReport);
        addButton(energyLog, "Verbruikslog openen", false, this::showEnergyLog);
        var autoBattery = heatingChoice(energyLog, "Accu elk uur bijwerken", BatterySampling.enabled(this));
        autoBattery.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(BatterySampling.ENABLED, checked).apply();
            BatterySampling.updateSchedule(this);
        });
        var backup = card(sections[2], "Back-up weekplanning");
        addButton(backup, "Planning exporteren", false, () -> openBackup(true));
        addButton(backup, "Planning terugzetten", false, () -> openBackup(false));

        LinearLayout planning = card(sections[1], "Weekplanning");
        enabled = new Switch(this);
        enabled.setText("Weekplanning actief");
        enabled.setPadding(dp(10), 0, dp(10), 0);
        enabled.setBackground(glassSurface(false, 12));
        styleSwitch(enabled);
        enabled.setMinHeight(dp(48));
        enabled.setChecked(prefs.getBoolean("enabled", false));
        planning.addView(enabled);
        enabled.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean("enabled", checked).commit();
            Planner.schedule(this);
            refresh();
        });
        pauseButton = addButton(planning, "Weekplanning pauzeren", false, this::pausePlanning);
        addButton(planning, "Komende zeven dagen", false, this::showAgenda);
        addButton(planning, "Volgende start controleren", false, this::showPreflight);
        for (int day = 0; day < DAYS.length; day++) addDay(planning, day);

        LinearLayout notifications = card(sections[2], "Meldingen");
        buildSettings(card(sections[2], "Kia Connect"), notifications);

        LinearLayout log = card(sections[0], "Laatste meldingen");
        historyScroll = new ListView(this);
        historyAdapter = new HistoryAdapter();
        historyScroll.setAdapter(historyAdapter);
        historyScroll.setOnItemClickListener((parent, row, position, id) -> {
            long session = historyAdapter.getItem(position).session();
            var item = historyAdapter.getItem(position);
            if (!item.header()) {
                if (!item.cause().isEmpty() && ("UNCONFIRMED".equals(item.outcome()) || "REFUSED".equals(item.outcome())))
                    showFailureActions(item.cause());
                return;
            }
            if (session <= 0) return;
            if (!expandedHistory.add(session)) expandedHistory.remove(session);
            shownHistory = null;
            refreshHistory();
        });
        historyScroll.setDivider(new ColorDrawable(0x5482afc5));
        historyScroll.setDividerHeight(dp(1));
        historyScroll.setSelector(new ColorDrawable(0x00000000));
        historyScroll.setScrollbarFadingEnabled(false);
        historyScroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (view.canScrollVertically(1) || view.canScrollVertically(-1))) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        log.addView(historyScroll, -1, dp(112));
        page.getViewTreeObserver().addOnGlobalLayoutListener(() -> fitHistory(page, log));

        refreshWindows();
        showSection(state == null ? 0 : state.getInt("section", 0));
        Planner.resumeClimateMonitoring(this, System.currentTimeMillis());
        Planner.schedule(this);
        if ((state == null || state.getBoolean("reopeningFromBackground")) && isLauncherIntent(getIntent())) {
            LaunchIntro.showIn(root);
        }
        if (state != null) pendingDiagnostic = state.getString("pendingDiagnostic");
        if (state == null) handleAction(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("section", section);
        state.putString("pendingDiagnostic", pendingDiagnostic);
        state.putBoolean("reopeningFromBackground", reopeningFromBackground);
        super.onSaveInstanceState(state);
    }

    private boolean stackControls() {
        var settings = getResources().getConfiguration();
        return settings.fontScale >= 1.35f || settings.screenWidthDp < 355;
    }

    private void buildDashboard(LinearLayout page) {
        LinearLayout dashboard = card(page, null, true);
        dashboard.setPadding(dp(16), dp(17), dp(16), dp(16));
        overall = text("", 32, INK, true);
        if (Build.VERSION.SDK_INT >= 28) overall.setAccessibilityHeading(true);
        decorate(overall, R.drawable.ic_ui_snow, ICE, 28);
        dashboard.addView(overall);
        statusDetail = text("", 14, MUTED, false);
        dashboard.addView(statusDetail);

        var temperatures = new LinearLayout(this);
        if (stackControls()) temperatures.setOrientation(LinearLayout.VERTICAL);
        var temperatureSize = new LinearLayout.LayoutParams(-1, -2);
        temperatureSize.topMargin = dp(8);
        temperatureSize.bottomMargin = dp(4);
        dashboard.addView(temperatures, temperatureSize);
        outsideTemperature = text("", 14, ACCENT, false);
        targetTemperature = text("", 14, ACCENT, false);
        addMetric(temperatures, 1).addView(outsideTemperature);
        addMetric(temperatures, 1).addView(targetTemperature);

        clocks = new LinearLayout(this);
        if (stackControls()) clocks.setOrientation(LinearLayout.VERTICAL);
        clocks.setGravity(Gravity.CENTER_VERTICAL);
        windowMetric = addMetric(clocks, 3);
        clockDivider = new View(this);
        clockDivider.setBackgroundColor(0x7f87bcce);
        clocks.addView(clockDivider, dp(1), dp(66));
        cycleMetric = addMetric(clocks, 2);
        windowClock = clockReadout(55);
        cycleClock = clockReadout(38);
        windowLabel = text("", 15, MUTED, false);
        cycleLabel = text("Cyclus resterend", 15, MUTED, false);
        windowMetric.addView(windowClock);
        windowMetric.addView(windowLabel);
        cycleMetric.addView(cycleClock);
        cycleMetric.addView(cycleLabel);
        var clockSize = new LinearLayout.LayoutParams(-1, -2);
        clockSize.topMargin = dp(24);
        clocks.setBaselineAligned(false);
        dashboard.addView(clocks, clockSize);

        timeline = new ClimateTimeline(this);
        var timelineSize = new LinearLayout.LayoutParams(-1, dp(52));
        timelineSize.topMargin = dp(23);
        timelineSize.bottomMargin = dp(16);
        dashboard.addView(timeline, timelineSize);
        liveStep = text("", 14, MUTED, false);
        dashboard.addView(liveStep);
        cooldown = text("", 13, MUTED, false);
        dashboard.addView(cooldown);

        LinearLayout actions = new LinearLayout(this);
        if (stackControls()) actions.setOrientation(LinearLayout.VERTICAL);
        manualButton = addButton(dashboard, "Handmatig starten", true, this::manual);
        dashboard.addView(actions, -1, -2);
        stopButton = addButton(actions, "Stoppen", true, () -> {
            authenticate("Klimaat stoppen", () -> {
                if (Planner.cancelRunning(this)) KiaStartService.enqueueStop(this);
                refresh();
            });
        });
        styleButton(stopButton, CORAL, 15);
        decorate(stopButton, R.drawable.ic_ui_stop, 0xff181012, 25);
        onceButton = addButton(actions, "Eenmalig plannen", false, this::planOnce);
        decorate(onceButton, R.drawable.ic_ui_calendar, INK, 20);
        stopButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stopButton.setTextSize(18);
        stopButton.setMinHeight(dp(64));
        onceButton.setMinHeight(dp(64));
        onceButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        onceButton.setTextSize(16);
        arrangeActions(actions);
        extendAction = addButton(dashboard, "Verleng met 15 min", false, () -> authenticate("Tijdvak verlengen", () -> {
            if (!Planner.extend(this)) Toast.makeText(this, "Dit tijdvak is niet meer actief.", Toast.LENGTH_SHORT).show();
            refresh();
        }));
        repeatAction = addButton(dashboard, "Vorige start herhalen", false, () -> manual(true, false));

        LinearLayout summary = new LinearLayout(this);
        if (stackControls()) summary.setOrientation(LinearLayout.VERTICAL);
        page.addView(summary, -1, -2);
        LinearLayout energy = card(addMetric(summary, 1), null);
        batteryTile = energy;
        energy.setOnClickListener(view -> requestBattery(true));
        energy.setForeground(new RippleDrawable(ColorStateList.valueOf(0x35c4edf8), null, glassSurface(false, 18)));
        energy.setFocusable(true);
        LinearLayout batteryDetails = summaryTile(energy, "Tractieaccu", R.drawable.ic_ui_battery, ICE);
        battery = clockReadout(33);
        batteryDetails.addView(battery);
        batteryMeasured = text("", 13, MUTED, false);
        batteryDetails.addView(batteryMeasured);
        LinearLayout next = card(addMetric(summary, 1), null);
        LinearLayout nextDetails = summaryTile(next, "Volgende start", R.drawable.ic_ui_calendar, ACCENT);
        nextView = clockReadout(33);
        nextDetails.addView(nextView);
        nextDay = text("", 13, MUTED, false);
        nextDetails.addView(nextDay);
        nextPreviewCard = card(page, null);
        var previewRow = new LinearLayout(this);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        boolean stacked = stackControls();
        previewRow.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        nextPreviewCard.addView(previewRow, -1, -2);
        nextPreview = text("", 14, MUTED, false);
        previewRow.addView(nextPreview, stacked ? new LinearLayout.LayoutParams(-1, -2)
            : new LinearLayout.LayoutParams(0, -2, 1));
        changeNext = addButton(previewRow, "Wijzigen", false, this::nextStartOptions);
        changeNext.setContentDescription("Volgende start wijzigen");
        changeNext.setMinHeight(dp(48));
        if (!stacked) {
            var actionSize = new LinearLayout.LayoutParams(-2, -2);
            actionSize.setMarginStart(dp(10));
            changeNext.setLayoutParams(actionSize);
        }
        preflightAction = addButton(nextPreviewCard, "Volgende start controleren", false, this::showPreflight);
        undoBar = card(page, null);
        adjustmentMessage = text("", 14, INK, true);
        adjustmentMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        undoBar.addView(adjustmentMessage);
        addButton(undoBar, "Ongedaan maken", false, this::undoWeeklyChange);
        if (!stackControls()) {
            energy.setMinimumHeight(dp(101));
            next.setMinimumHeight(dp(101));
        }
    }

    private LinearLayout summaryTile(LinearLayout card, String label, int resource, int tint) {
        var row = editorRow(card);
        var icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconSize = stackControls() ? 29 : 34;
        var size = new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize));
        size.setMarginEnd(dp(8));
        row.addView(icon, size);
        var details = column();
        details.addView(text(label, 15, INK, false));
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        return details;
    }

    private LinearLayout addMetric(LinearLayout row, int weight) {
        var panel = column();
        boolean vertical = row.getOrientation() == LinearLayout.VERTICAL;
        var size = vertical ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, weight);
        if (!vertical && row.getChildCount() > 0) size.setMarginStart(dp(8));
        row.addView(panel, size);
        return panel;
    }

    private TextView clockReadout(int size) {
        var readout = text("", size, INK, true);
        readout.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        readout.setFontFeatureSettings("tnum");
        readout.setIncludeFontPadding(false);
        return readout;
    }

    private void arrangeActions(LinearLayout actions) {
        if (actions.getOrientation() == LinearLayout.VERTICAL) return;
        for (int index = 0; index < actions.getChildCount(); index++) {
            var size = new LinearLayout.LayoutParams(0, -2, 1);
            if (index > 0) size.setMarginStart(dp(5));
            actions.getChildAt(index).setLayoutParams(size);
        }
    }

    private void addNavigation(LinearLayout page) {
        LinearLayout bar = new LinearLayout(this);
        boolean stack = getResources().getConfiguration().fontScale >= 1.5f;
        if (stack) bar.setOrientation(LinearLayout.VERTICAL);
        page.addView(bar);
        String[] labels = {"Dashboard", "Weekplanning", "Instellingen"};
        for (int index = 0; index < labels.length; index++) {
            int target = index;
            Button tab = new Button(this);
            tab.setText(labels[index]);
            tab.setAllCaps(false);
            tab.setTextSize(15);
            tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tab.setMinWidth(0);
            tab.setOnClickListener(view -> showSection(target));
            tab.setMinHeight(dp(48));
            var size = stack ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (!stack && index > 0) size.setMarginStart(dp(4));
            bar.addView(tab, size);
            tabs[index] = tab;
        }
    }

    private void showSection(int selected) {
        section = selected;
        for (int index = 0; index < sections.length; index++) {
            boolean active = index == selected;
            sections[index].setVisibility(active ? View.VISIBLE : View.GONE);
            tabs[index].setSelected(active);
            styleButton(tabs[index], active, 4);
        }
        swipe.setEnabled(selected == 0);
        scroll.scrollTo(0, 0);
    }

    private static boolean isLauncherIntent(Intent opening) {
        return Intent.ACTION_MAIN.equals(opening.getAction()) && opening.hasCategory(Intent.CATEGORY_LAUNCHER);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        reopeningFromBackground = false;
        if (!handleAction(intent) && isLauncherIntent(intent)) LaunchIntro.showIn(root);
    }

    private boolean handleAction(Intent intent) {
        String action = intent.getAction();
        if (MANUAL_SHORTCUT.equals(action)) {
            openManualShortcut();
        } else if (OPEN_RETRY.equals(action)) {
            showSection(0);
            handler.post(() -> manual(false, true));
        } else if (OPEN_RECOVERY.equals(action)) {
            handler.post(this::recoverFailure);
        } else if (OPEN_DASHBOARD.equals(action)) {
            showSection(0);
        } else {
            return false;
        }
        LaunchIntro.removeFrom(root);
        return true;
    }

    private void openManualShortcut() {
        showSection(0);
        LaunchIntro.removeFrom(root);
        handler.post(this::manual);
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        reopeningFromBackground = true;
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (reopeningFromBackground) LaunchIntro.showIn(root);
        reopeningFromBackground = false;
    }

    @Override protected void onStop() {
        if (pendingGpsDialog != null) pendingGpsDialog.dismiss();
        LaunchIntro.removeFrom(root);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        BatterySampling.updateSchedule(this);
        reopeningFromBackground = false;
        handler.removeCallbacks(tick);
        tick.run();
        requestBattery(false);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void addDay(LinearLayout parent, int day) {
        LinearLayout card = card(parent, null);
        dayCards[day] = card;
        card.setPadding(dp(8), dp(4), dp(8), dp(5));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(7);
        LinearLayout pane = column();
        dayPanes[day] = pane;
        boolean today = day == LocalDate.now().getDayOfWeek().getValue() - 1;
        pane.setVisibility(today ? View.VISIBLE : View.GONE);

        LinearLayout heading = editorRow(card);
        Button header = editorButton(heading, "", () -> {
            pane.setVisibility(pane.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refreshWindows();
        });
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33c4edf8), null, null));
        header.setPadding(dp(4), 0, dp(4), 0);
        header.setTextSize(17);
        dayHeaders[day] = header;
        daySwitches[day] = editorSwitch(heading, DAYS[day] + " actief");
        var chevron = text("", 20, ACCENT, true);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(chevron, dp(24), -2);
        dayChevrons[day] = chevron;
        daySwitches[day].setOnCheckedChangeListener((view, checked) -> {
            if (updatingWeek) return;
            DayPlan[] week = readWeek();
            week[day] = new DayPlan(checked, week[day].slots());
            applyWeek(week, 1 << day);
        });
        card.addView(pane);

        for (int index = 0; index < 2; index++) {
            int slot = index;
            LinearLayout row = editorRow(pane);
            windowButtons[day][slot] = editorButton(row, "",
                () -> editWindow(day, slot, readSlot(day, slot).enabled()));
            windowSwitches[day][slot] = editorSwitch(row, DAYS[day] + ", tijdvak " + (slot + 1) + " actief");
            windowSwitches[day][slot].setOnCheckedChangeListener((view, checked) -> {
                if (updatingWeek) return;
                SlotPlan current = readSlot(day, slot);
                if (checked && current.duration() == 0) {
                    refreshWindows();
                    editWindow(day, slot, true);
                    return;
                }
                saveWindow(day, slot, new SlotPlan(current.start(), current.duration(), checked, current.heating(),
                    current.ready(), current.profile(), current.homeOnly()));
            });
        }
        decorate(addButton(pane, "Kopieer dag", false, () -> copyDay(day)), R.drawable.ic_ui_copy, INK, 18);
    }

    private LinearLayout editorRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, -1, -2);
        return row;
    }

    private Button editorButton(LinearLayout row, String label, Runnable action) {
        Button button = addButton(row, label, false, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(16);
        styleButton(button, 0, 10);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private Switch editorSwitch(LinearLayout row, String description) {
        Switch toggle = new Switch(this);
        toggle.setContentDescription(description);
        styleSwitch(toggle);
        toggle.setMinWidth(dp(48));
        toggle.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(10));
        row.addView(toggle, params);
        return toggle;
    }

    private void refreshWindows() {
        updatingWeek = true;
        try {
            for (int day = 0; day < DAYS.length; day++) {
                boolean active = Planner.dayEnabled(prefs, day);
                daySwitches[day].setChecked(active);
                var times = new ArrayList<String>();
                for (int index = 0; index < 2; index++) {
                    SlotPlan slot = readSlot(day, index);
                    if (slot.enabled() && slot.duration() > 0) {
                        times.add((slot.ready() ? "Klaar om " : "") + clock(slot.start()));
                    }
                    windowSwitches[day][index].setChecked(slot.enabled());
                    String range = slot.duration() == 0 ? "Niet ingesteld"
                        : slot.ready() ? "Klaar om " + clock(slot.start())
                        : clock(slot.start()) + " – " + endTime(slot.start(), slot.duration());
                    String heat = slot.profile() >= 0 ? " · " + ClimateProfiles.get(prefs, slot.profile()).name()
                        : slot.heating() == Planner.DEFAULT_HEAT ? "" : " · Eigen verwarming";
                    if (slot.homeOnly()) heat += " · Alleen thuis";
                    Button button = windowButtons[day][index];
                    set(button, (slot.duration() == 0 ? "Tijdvak " + (index + 1) + " toevoegen" : range) + heat);
                    button.setContentDescription("Tijdvak " + (index + 1) + ", " + range + heat);
                    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        active && slot.enabled() ? slotAccent : null, null, null, null);
                    button.setCompoundDrawablePadding(dp(8));
                }
                boolean expanded = dayPanes[day].getVisibility() == View.VISIBLE;
                String status = !active ? "Niet actief" : times.isEmpty() ? "Geen actieve tijdvakken"
                    : expanded ? times.size() + (times.size() == 1 ? " actief tijdvak" : " actieve tijdvakken")
                    : String.join(" · ", times);
                String dayTitle = DAYS[day];
                set(dayChevrons[day], expanded ? "⌃" : "⌄");
                dayCards[day].setBackground(glassSurface(expanded, 15));
                dayHeaders[day].setCompoundDrawablesRelativeWithIntrinsicBounds(
                    expanded && active ? slotAccent : null, null, null, null);
                dayHeaders[day].setCompoundDrawablePadding(dp(6));
                var label = new android.text.SpannableString(dayTitle + "\n" + status);
                label.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, dayTitle.length(), 0);
                label.setSpan(new android.text.style.ForegroundColorSpan(MUTED), dayTitle.length() + 1, label.length(), 0);
                dayHeaders[day].setText(label);
                dayHeaders[day].setContentDescription(DAYS[day] + ", " + status);
                if (Build.VERSION.SDK_INT >= 30) {
                    dayHeaders[day].setStateDescription(expanded ? "Uitgeklapt" : "Ingeklapt");
                }
            }
        } finally {
            updatingWeek = false;
        }
    }

    private void copyDay(int source) {
        String[] labels = new String[DAYS.length - 1];
        int[] days = new int[labels.length];
        boolean[] selected = new boolean[labels.length];
        int index = 0;
        for (int day = 0; day < DAYS.length; day++) {
            if (day == source) continue;
            labels[index] = DAYS[day] + (readSlot(day, 0).duration() > 0 || readSlot(day, 1).duration() > 0
                ? " · vervangt bestaande tijdvakken" : "");
            days[index++] = day;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(DAYS[source] + " kopiëren naar")
            .setMultiChoiceItems(labels, selected, (window, position, checked) -> {
                selected[position] = checked;
                setCopyEnabled(((AlertDialog) window).getButton(AlertDialog.BUTTON_POSITIVE), hasSelection(selected));
            })
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Tijdvakken vervangen", null).create();
        dialog.setOnShowListener(ignored -> {
            Button confirm = styleDialogAction(dialog);
            setCopyEnabled(confirm, false);
            confirm.setOnClickListener(view -> {
                int targets = 0;
                for (int position = 0; position < days.length; position++) {
                    if (selected[position]) targets |= 1 << days[position];
                }
                if (saveDayCopy(source, targets)) {
                    dialog.dismiss();
                    Toast.makeText(this, "Tijdvakken gekopieerd.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private static void setCopyEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.48f);
    }

    private static boolean hasSelection(boolean[] selected) {
        for (boolean choice : selected) {
            if (choice) return true;
        }
        return false;
    }

    private Button styleDialogAction(AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
        Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        action.setAllCaps(false);
        action.setMinHeight(dp(48));
        styleButton(action, true, 12);
        return action;
    }

    private boolean saveDayCopy(int source, int targets) {
        DayPlan[] week = readWeek();
        for (int day = 0; day < DAYS.length; day++) {
            if ((targets & (1 << day)) == 0) continue;
            boolean active = prefs.contains("dayEnabled" + day) ? week[day].enabled() : week[source].enabled();
            week[day] = new DayPlan(active, week[source].slots().clone());
        }
        return applyWeek(week, targets);
    }

    private void editWindow(int day, int index, boolean enabled) {
        SlotPlan current = readSlot(day, index);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        Switch ready = heatingChoice(form, "Klaar om de gekozen tijd", current.ready());
        LinearLayout controls = editorRow(form);
        NumberPicker hour = timePicker("Uur", 23, current.start() / 60);
        NumberPicker minute = timePicker("Startminuut", 59, current.start() % 60);
        NumberPicker duration = durationPicker(current.duration(), true);
        duration.setContentDescription("Duur in minuten");
        addPicker(controls, "Uur", hour);
        addPicker(controls, "Minuut", minute);
        addPicker(controls, "Duur (min)", duration);

        TextView summary = text("", 17, INK, true);
        summary.setPadding(0, dp(4), 0, 0);
        form.addView(summary);
        Runnable showEnd = () -> {
            int start = hour.getValue() * 60 + minute.getValue();
            int minutes = pickedDuration(duration);
            int begin = start - (ready.isChecked() ? minutes : 0);
            set(summary, "Start om " + clock(Math.floorMod(begin, 1440))
                + (begin < 0 ? " (vorige dag)" : "") + " · " + cycleLabel(minutes)
                + "\nEinde " + endTime(begin, minutes));
        };
        ready.setOnCheckedChangeListener((view, selected) -> showEnd.run());
        hour.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        minute.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        duration.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        showEnd.run();

        var profile = profilePicker(form, current.profile());
        var home = heatingChoice(form, "Alleen starten als de auto thuis staat", current.homeOnly());
        if (!prefs.contains("homeLat")) form.addView(text("Stel de thuislocatie eerst in onder Instellingen.", 13, MUTED, false));
        Switch useCustomHeat = heatingChoice(form, "Eigen verwarmingsopties",
            current.heating() != Planner.DEFAULT_HEAT);
        TextView heatHint = text("Gebruikt " + generalProfileName() + " uit Instellingen.", 14, MUTED, false);
        form.addView(heatHint);
        LinearLayout choices = column();
        form.addView(choices);
        int savedHeat = current.heating() == Planner.DEFAULT_HEAT
            ? Planner.climateOptions(prefs, null, ZoneId.systemDefault()).heat() : current.heating();
        Switch wheel = heatingChoice(choices, "Stuurverwarming", (savedHeat & Planner.HEAT_WHEEL) != 0);
        Switch mirrors = heatingChoice(choices, "Spiegel- en achterruitverwarming", (savedHeat & Planner.HEAT_MIRRORS) != 0);
        choices.addView(text("Voorruit ontdooien", 14, MUTED, false));
        var windshield = windshieldChoices(choices, savedHeat);
        choices.addView(text("Slim gebruikt de grens uit Standaardverwarming.", 13, MUTED, false));
        Runnable showHeat = () -> {
            boolean usesProfile = profile.getSelectedItemPosition() > 0;
            boolean custom = !usesProfile && useCustomHeat.isChecked();
            useCustomHeat.setVisibility(usesProfile ? View.GONE : View.VISIBLE);
            choices.setVisibility(custom ? View.VISIBLE : View.GONE);
            heatHint.setVisibility(usesProfile || custom ? View.GONE : View.VISIBLE);
        };
        onSelect(profile, showHeat);
        showHeat.run();
        useCustomHeat.setOnCheckedChangeListener((view, checked) -> showHeat.run());
        TextView error = text("", 14, 0xffffc6be, true);
        error.setPadding(0, dp(8), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        form.addView(error);
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(DAYS[day] + " · Tijdvak " + (index + 1)).setView(formScroll)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Opslaan", null).create();
        if (current.duration() > 0) {
            Button clear = addButton(form, "Tijdvak wissen", false, () -> new AlertDialog.Builder(this)
                .setTitle("Tijdvak wissen?")
                .setMessage(DAYS[day] + " · Tijdvak " + (index + 1)
                    + "\nDe opgeslagen tijd en verwarmingsopties worden gewist.")
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Wissen", (confirmation, choice) -> {
                    int defaultStart = index == 0 ? 17 * 60 + 30 : 19 * 60;
                    if (saveWindow(day, index, new SlotPlan(defaultStart, 0, false, Planner.DEFAULT_HEAT), error)) {
                        dialog.dismiss();
                        Toast.makeText(this, "Tijdvak gewist.", Toast.LENGTH_SHORT).show();
                    }
                }).show());
            clear.setTextColor(CORAL);
        }
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            hour.clearFocus();
            minute.clearFocus();
            duration.clearFocus();
            int start = hour.getValue() * 60 + minute.getValue();
            int heating = useCustomHeat.isChecked()
                ? (wheel.isChecked() ? Planner.HEAT_WHEEL : 0)
                    | (mirrors.isChecked() ? Planner.HEAT_MIRRORS : 0)
                    | ClimateProfiles.windshieldFlag(selectedWindshield(windshield))
                : Planner.DEFAULT_HEAT;
            if (saveWindow(day, index, new SlotPlan(start, pickedDuration(duration),
                    enabled || current.duration() == 0, heating, ready.isChecked(),
                    profile.getSelectedItemPosition() - 1, home.isChecked()), error)) {
                dialog.dismiss();
                Toast.makeText(this, "Tijdvak opgeslagen.", Toast.LENGTH_SHORT).show();
            } else {
                error.post(() -> formScroll.smoothScrollTo(0, error.getBottom()));
            }
        }));
        dialog.show();
    }

    private NumberPicker timePicker(String description, int max, int value) {
        NumberPicker picker = new NumberPicker(this);
        picker.setContentDescription(description);
        picker.setMinValue(0);
        picker.setMaxValue(max);
        picker.setFormatter(number -> String.format(NL, "%02d", number));
        picker.setValue(value);
        return picker;
    }

    private void addPicker(LinearLayout row, String label, NumberPicker picker) {
        LinearLayout container = column();
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heading = text(label, 14, MUTED, false);
        heading.setGravity(Gravity.CENTER);
        container.addView(heading, -1, -2);
        container.addView(picker, -2, -2);
        row.addView(container, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static String endTime(int start, int minutes) {
        int end = start + minutes;
        return clock(end % 1440) + (end >= 1440 ? " (+1 dag)" : "");
    }

    private boolean saveWindow(int day, int index, SlotPlan slot) {
        return saveWindow(day, index, slot, null);
    }

    private boolean saveWindow(int day, int index, SlotPlan slot, TextView error) {
        DayPlan[] week = readWeek();
        week[day].slots()[index] = slot;
        if (!prefs.contains("dayEnabled" + day) && slot.enabled()) {
            week[day] = new DayPlan(true, week[day].slots());
        }
        return applyWeek(week, 1 << day, error);
    }

    private DayPlan[] readWeek() {
        DayPlan[] week = new DayPlan[DAYS.length];
        for (int day = 0; day < DAYS.length; day++) {
            week[day] = new DayPlan(Planner.dayEnabled(prefs, day),
                new SlotPlan[]{readSlot(day, 0), readSlot(day, 1)});
        }
        return week;
    }

    private SlotPlan readSlot(int day, int index) {
        return new SlotPlan(Planner.startMinute(prefs, day, index), Planner.duration(prefs, day, index),
            Planner.windowEnabled(prefs, day, index), Planner.windowHeat(prefs, day, index),
            prefs.getBoolean(ClimateProfiles.readyKey(day, index), false),
            prefs.getInt(ClimateProfiles.windowKey(day, index), -1), prefs.getBoolean("homeOnly" + day + "_" + index, false));
    }

    private boolean applyWeek(DayPlan[] week, int changed) {
        return applyWeek(week, changed, null);
    }

    private boolean applyWeek(DayPlan[] week, int changed, TextView error) {
        var windows = new ArrayList<Schedule.Window>();
        for (int day = 0; day < DAYS.length; day++) {
            if (!week[day].enabled()) continue;
            for (SlotPlan slot : week[day].slots()) {
                if (slot.enabled() && slot.duration() > 0) {
                    windows.add(Planner.rule(day, slot.start(), slot.duration(), slot.ready()));
                }
            }
        }
        if (Schedule.overlaps(windows)) {
            String message = "Tijdvakken overlappen. Pas de starttijd of duur aan, of zet een overlappend tijdvak uit.";
            if (error == null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            } else {
                error.setVisibility(View.VISIBLE);
                set(error, message);
            }
            refreshWindows();
            return false;
        }

        var update = prefs.edit();
        for (int day = 0; day < DAYS.length; day++) {
            if ((changed & (1 << day)) == 0) continue;
            update.putBoolean("dayEnabled" + day, week[day].enabled());
            for (int index = 0; index < 2; index++) {
                SlotPlan slot = week[day].slots()[index];
                update.putInt("start" + day + "_" + index, slot.start())
                    .putInt("duration" + day + "_" + index, slot.duration())
                    .putBoolean("slotEnabled" + day + "_" + index, slot.enabled())
                    .putInt("slotHeat" + day + "_" + index, slot.heating())
                    .putBoolean(ClimateProfiles.readyKey(day, index), slot.ready())
                    .putInt(ClimateProfiles.windowKey(day, index), slot.profile())
                    .putBoolean("homeOnly" + day + "_" + index, slot.homeOnly());
            }
        }
        update.commit();
        Planner.schedule(this);
        refreshWindows();
        refresh();
        return true;
    }

    private record DayPlan(boolean enabled, SlotPlan[] slots) {}
    private record SlotPlan(int start, int duration, boolean enabled, int heating, boolean ready, int profile, boolean homeOnly) {
        SlotPlan(int start, int duration, boolean enabled, int heating) {
            this(start, duration, enabled, heating, false, -1, false);
        }
    }

    private NumberPicker durationPicker(int savedMinutes, boolean keepExistingDuration) {
        int step = Schedule.CYCLE_MINUTES;
        int maxMinutes = 24 * 60;
        var labels = new ArrayList<String>();
        for (int minutes = step; minutes <= maxMinutes; minutes += step) {
            labels.add(Integer.toString(minutes));
        }
        int initial = Math.min(maxMinutes, Schedule.nearestDuration(savedMinutes));
        if (keepExistingDuration && savedMinutes > 0 && savedMinutes < maxMinutes && savedMinutes % step != 0) {
            labels.add(savedMinutes / step, Integer.toString(savedMinutes));
            initial = savedMinutes;
        }
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(labels.size() - 1);
        picker.setDisplayedValues(labels.toArray(new String[0]));
        picker.setValue(labels.indexOf(Integer.toString(initial)));
        picker.setWrapSelectorWheel(false);
        return picker;
    }

    private static int pickedDuration(NumberPicker picker) {
        return Integer.parseInt(picker.getDisplayedValues()[picker.getValue()]);
    }

    private void manual() {
        manual(false, false);
    }

    private void manual(boolean repeat, boolean retry) {
        if (retry && prefs.getLong("lastFailure", 0) == 0) return;
        if (!KiaCloud.linked(this) || KiaCloud.needsLink(this) || !Planner.hasAlarmAccess(this)) {
            if (retry) recoverFailure();
            else showSection(2);
            return;
        }
        String key = retry ? "retry" : "previousManual";
        int savedDuration = repeat || retry ? prefs.getInt(key + "Minutes", Schedule.CYCLE_MINUTES)
            : Schedule.CYCLE_MINUTES;
        int selectedProfile = repeat || retry ? prefs.getInt(key + "Profile", -1) : -1;
        int savedTemperature = repeat || retry ? prefs.getInt(key + "Temperature", -1) : -1;
        NumberPicker picker = durationPicker(savedDuration, false);
        var form = column();
        form.setPadding(dp(20), 0, dp(20), dp(10));
        var profile = profilePicker(form, selectedProfile);
        var profileTemp = text("", 14, MUTED, false);
        form.addView(profileTemp);
        var override = heatingChoice(form, "Temperatuur voor deze start aanpassen", savedTemperature >= Planner.MIN_TEMP_HALVES);
        var temperature = temperaturePicker(savedTemperature >= Planner.MIN_TEMP_HALVES ? savedTemperature
            : Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature());
        form.addView(temperature, -1, -2);
        form.addView(text("Duur", 15, MUTED, true));
        var quick = new LinearLayout(this);
        quick.setOrientation(stackControls() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        form.addView(quick, -1, -2);
        var buttons = new Button[3];
        var preview = text("", 14, MUTED, false);
        Runnable update = () -> {
            int minutes = pickedDuration(picker);
            for (int index = 0; index < buttons.length; index++) {
                if (buttons[index] != null) styleButton(buttons[index], minutes == (index + 1) * 15, 5);
            }
            int selection = profile.getSelectedItemPosition() - 1;
            int degrees = selection >= 0 ? ClimateProfiles.get(prefs, selection).temperature()
                : Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature();
            set(profileTemp, "Doeltemperatuur " + temperatureLabel(override.isChecked() ? temperature.getValue() : degrees));
            var timing = Planner.previewManual(this, minutes, System.currentTimeMillis());
            set(preview, timing.firstConflict() > 0 ? "Overlapt met " + format(timing.firstConflict(), "EEE HH:mm")
                + " · samen tot " + format(timing.end(), "EEE HH:mm") : "Verwacht einde " + format(timing.end(), "HH:mm"));
        };
        for (int index = 0; index < buttons.length; index++) {
            int minutes = (index + 1) * 15;
            var button = addButton(quick, minutes + " min", false, () -> {
                picker.setValue(minutes / 15 - 1);
                picker.setVisibility(View.GONE);
                update.run();
            });
            if (!stackControls()) button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            buttons[index] = button;
        }
        var more = addButton(form, "Andere duur kiezen", false, () -> {
            picker.setVisibility(picker.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            update.run();
        });
        more.setContentDescription("Andere klimaatduur kiezen");
        form.addView(picker, -1, -2);
        picker.setVisibility(savedDuration > 45 ? View.VISIBLE : View.GONE);
        form.addView(preview);
        picker.setOnValueChangedListener((control, oldValue, newValue) -> update.run());
        temperature.setOnValueChangedListener((control, oldValue, newValue) -> update.run());
        onSelect(profile, update);
        override.setOnCheckedChangeListener((view, checked) -> {
            temperature.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && savedTemperature < Planner.MIN_TEMP_HALVES) {
                int selection = profile.getSelectedItemPosition() - 1;
                temperature.setValue(selection >= 0 ? ClimateProfiles.get(prefs, selection).temperature()
                    : Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature());
            }
            update.run();
        });
        temperature.setVisibility(override.isChecked() ? View.VISIBLE : View.GONE);
        update.run();
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle(retry ? "Opnieuw proberen" : "Handmatig starten").setView(body)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Starten", (window, option) -> {
                picker.clearFocus();
                temperature.clearFocus();
                int duration = pickedDuration(picker);
                int selected = profile.getSelectedItemPosition() - 1;
                int requested = override.isChecked() ? temperature.getValue() : -1;
                authenticate("Klimaat starten", () -> {
                    if (!Planner.manual(this, duration, selected, requested))
                        Toast.makeText(this, "Controleer Kia Connect en exacte alarmen.", Toast.LENGTH_LONG).show();
                    refresh();
                });
            }).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog));
        dialog.show();
    }

    private void planOnce() {
        if (!KiaCloud.linked(this) || KiaCloud.needsLink(this) || !Planner.hasAlarmAccess(this)) {
            showSection(2);
            return;
        }
        long saved = prefs.getLong("onceStart", 0);
        long savedEnd = prefs.getLong("onceEnd", 0);
        boolean editing = saved > System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        boolean savedReady = editing && prefs.getBoolean("onceReady", false);
        LocalDateTime initial = editing ? Instant.ofEpochMilli(savedReady ? savedEnd : saved).atZone(zone).toLocalDateTime()
            : LocalDateTime.now(zone).plusMinutes(30).truncatedTo(ChronoUnit.MINUTES);
        var choice = new Object() {
            LocalDateTime start = initial;
        };
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(8));
        Switch ready = heatingChoice(form, "Klaar om de gekozen tijd", savedReady);
        Button date = addButton(form, "", false, () -> {});
        Button time = addButton(form, "", false, () -> {});
        var duration = durationPicker(editing ? (int) ((savedEnd - saved) / Schedule.MINUTE) : Schedule.CYCLE_MINUTES, false);
        TextView schedulePreview = text("", 16, INK, true);
        schedulePreview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Runnable showChoice = () -> {
            set(date, choice.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", NL)));
            set(time, (ready.isChecked() ? "Klaar om" : "Starttijd") + " · "
                + choice.start.format(DateTimeFormatter.ofPattern("HH:mm", NL)));
            int minutes = pickedDuration(duration);
            long start = onceStart(choice.start, zone, minutes, ready.isChecked());
            set(schedulePreview, "Start " + format(start, "EEE d MMM 'om' HH:mm") + " · " + cycleLabel(minutes));
        };
        date.setOnClickListener(view -> {
            var current = choice.start.toLocalDate();
            var picker = new DatePickerDialog(this, (control, year, month, day) -> {
                choice.start = java.time.LocalDate.of(year, month + 1, day).atTime(choice.start.toLocalTime());
                showChoice.run();
            }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
            picker.getDatePicker().setMinDate(System.currentTimeMillis());
            picker.show();
        });
        time.setOnClickListener(view -> new TimePickerDialog(this, (control, hour, minute) -> {
            choice.start = choice.start.toLocalDate().atTime(hour, minute);
            showChoice.run();
        }, choice.start.getHour(), choice.start.getMinute(), true).show());
        ready.setOnCheckedChangeListener((view, checked) -> showChoice.run());
        duration.setOnValueChangedListener((picker, oldValue, newValue) -> showChoice.run());
        showChoice.run();
        duration.setContentDescription("Duur in minuten");
        form.addView(text("Duur in minuten", 15, MUTED, false));
        form.addView(duration, -1, -2);
        form.addView(schedulePreview);
        var profile = profilePicker(form, editing ? prefs.getInt("onceProfile", -1) : -1);
        var home = heatingChoice(form, "Alleen starten als de auto thuis staat", editing && prefs.getBoolean("onceHomeOnly", false));
        if (!prefs.contains("homeLat")) form.addView(text("Stel de thuislocatie eerst in onder Instellingen.", 13, MUTED, false));
        addButton(form, "Vertrektijd uit agenda", false, () -> pickCalendar(selected -> {
            choice.start = Instant.ofEpochMilli(selected).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
            ready.setChecked(true);
            showChoice.run();
        }));
        TextView error = text("", 14, 0xffffc6be, true);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error);
        var scroll = new ScrollView(this);
        scroll.addView(form);
        var builder = new AlertDialog.Builder(this).setTitle("Eenmalig plannen").setView(scroll)
            .setNegativeButton("Sluiten", null).setPositiveButton("Inplannen", null);
        if (editing) builder.setNeutralButton("Planning wissen", (dialog, button) -> {
            Planner.cancelOnce(this);
            refresh();
        });
        var dialog = builder.create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            duration.clearFocus();
            long start = onceStart(choice.start, zone, pickedDuration(duration), ready.isChecked());
            if (Planner.planOnce(this, start, pickedDuration(duration), profile.getSelectedItemPosition() - 1,
                    ready.isChecked(), home.isChecked())) {
                dialog.dismiss();
                refresh();
            } else {
                set(error, start <= System.currentTimeMillis() ? "Kies een datum en tijd in de toekomst."
                    : "Inplannen lukte niet. Controleer de Kia-koppeling en de toestemming voor exacte alarmen.");
                error.setVisibility(View.VISIBLE);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }));
        dialog.show();
    }

    private boolean showKiaPause() {
        long until = KiaBackoff.until(prefs);
        if (until <= System.currentTimeMillis()) return false;
        Toast.makeText(this, KiaBackoff.message(until), Toast.LENGTH_LONG).show();
        return true;
    }

    private void requestBattery(boolean requested) {
        if (!requested && KiaBackoff.paused(this)) return;
        long now = System.currentTimeMillis();
        if (!KiaCloud.linked(this) || KiaCloud.needsLink(this)
                || (!requested && now - lastBatteryRequest < 300_000)) return;
        if (batteryBusy) {
            if (requested && !batteryForce) batteryForcePending = true;
            return;
        }
        lastBatteryRequest = now;
        batteryBusy = true;
        batteryForce = requested;
        refresh();
        KiaCloud.WORKER.execute(() -> {
            String result = null;
            try {
                result = KiaCloud.refreshDashboard(getApplicationContext(), requested);
            } finally {
                String notice = result;
                runOnUiThread(() -> {
                    batteryBusy = false;
                    boolean pending = batteryForcePending;
                    batteryForcePending = false;
                    if (isDestroyed()) return;
                    if (pending) requestBattery(true);
                    if (!batteryBusy) swipe.setRefreshing(false);
                    if (notice != null) Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
                    refresh();
                });
            }
        });
    }

    private void buildSettings(LinearLayout settings, LinearLayout notifications) {
        var account = addButton(settings, "", false, () -> {
            accountExpanded = !accountExpanded;
            refresh();
        });
        account.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        decorate(account, R.drawable.ic_ui_check, ICE, 20);
        accountState = account;
        accountDetails = column();
        settings.addView(accountDetails);
        accountButton = addButton(accountDetails, "Kia-account koppelen", true,
            () -> openFromPlanner(new Intent(this, KiaAccountActivity.class)));
        unlink = addButton(accountDetails, "Account loskoppelen", false, () -> new AlertDialog.Builder(this)
            .setTitle("Kia-account loskoppelen?")
            .setMessage("De planning wordt uitgezet en de opgeslagen aanmeldgegevens worden gewist.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Loskoppelen", (dialog, choice) -> unlinkAccount())
            .show());
        access = text("", 14, MUTED, false);
        accountDetails.addView(access);
        alertToggle(notifications, "Einde tijdvak", "notifyWindowEnd");
        alertToggle(notifications, "Startlimiet Kia", "notifyStartLimit");
        var late = heatingChoice(notifications, "Vertraging bij Klaar om", prefs.getBoolean("notifyDepartureDelay", false));
        late.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("notifyDepartureDelay", checked).apply());
        alertToggle(notifications, "Geplande start mislukt", "notifyStartFailure");
        var quiet = heatingChoice(notifications, "Stille meldingsstand", prefs.getBoolean("quietAlerts", false));
        quiet.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("quietAlerts", checked).apply());
        notifications.addView(text("Alleen pop-ups bij problemen. Tijdvakken blijven in het log.", 13, MUTED, false));
        addButton(settings, "Verbindingscheck", false, this::checkConnection);
        alertPermission = addButton(notifications, "Android-meldingen toestaan", false, this::allowAlerts);
        alarmPermission = addButton(accountDetails, "Exacte alarmen toestaan", false, () -> {
            if (Build.VERSION.SDK_INT >= 31) openFromPlanner(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName())));
        });
        screenControls = column();
        accountDetails.addView(screenControls);
        addButton(screenControls, "Toegankelijkheid openen", false,
            () -> openFromPlanner(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(screenControls, "Kia-knop controleren", false, () -> {
            if (KiaControls.active != null) KiaControls.active.preview();
            else Toast.makeText(this, "Schakel eerst toegankelijkheid in.", Toast.LENGTH_LONG).show();
        });
    }

    private void addClimateWeatherSetting(LinearLayout panel) {
        divider(panel);
        var toggle = heatingChoice(panel, "Weer gebruiken voor klimaaticoontje",
            prefs.getBoolean(ClimateIndicator.WEATHER_ENABLED, true));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(ClimateIndicator.WEATHER_ENABLED, checked).apply();
            refresh();
            if (checked && !batteryBusy) {
                lastBatteryRequest = 0;
                requestBattery(false);
            }
        });
        var source = text("Open-Meteo gebruikt de laatst bekende autolocatie voor deze schatting.", 13, MUTED, false);
        source.setPadding(0, 0, 0, dp(4));
        panel.addView(source);
    }

    private void temperatureControl(LinearLayout panel) {
        LinearLayout control = editorRow(panel);
        control.setPadding(dp(10), 0, dp(10), 0);
        control.setMinimumHeight(dp(48));
        control.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2fc4edf8), glassSurface(false, 12), null));
        control.setFocusable(true);
        androidx.core.view.ViewCompat.setAccessibilityDelegate(control, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View view,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(Button.class.getName());
            }
        });
        var label = text("Temperatuur", 16, INK, false);
        decorate(label, R.drawable.ic_ui_temp, ACCENT, 20);
        control.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        var value = text("", 18, INK, true);
        control.addView(value, -2, -2);
        Runnable updateTemperature = () -> {
            String degrees = temperatureLabel(Planner.temperatureHalves(prefs));
            set(value, degrees + "  ›");
            control.setContentDescription("Temperatuur, " + degrees + ", wijzigen");
        };
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateTemperature.run();
        control.setOnClickListener(view -> {
            NumberPicker picker = new NumberPicker(this);
            picker.setMinValue(Planner.MIN_TEMP_HALVES);
            picker.setMaxValue(Planner.MAX_TEMP_HALVES);
            String[] temperatures = new String[Planner.MAX_TEMP_HALVES - Planner.MIN_TEMP_HALVES + 1];
            for (int index = 0; index < temperatures.length; index++) {
                temperatures[index] = temperatureLabel(Planner.MIN_TEMP_HALVES + index);
            }
            picker.setDisplayedValues(temperatures);
            picker.setValue(Planner.temperatureHalves(prefs));
            picker.setWrapSelectorWheel(false);
            picker.setContentDescription("Temperatuur in graden Celsius");
            var dialog = new AlertDialog.Builder(this).setTitle("Gewenste temperatuur").setView(picker)
                .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", (window, choice) -> {
                    picker.clearFocus();
                    int selected = picker.getValue();
                    prefs.edit().putInt("temperatureHalves", selected).apply();
                    updateTemperature.run();
                }).create();
            dialog.setOnShowListener(ignored -> styleDialogAction(dialog));
            dialog.show();
        });
    }

    private static String temperatureLabel(int halves) {
        return halves / 2 + (halves % 2 == 0 ? " °C" : ",5 °C");
    }

    private Switch heatingChoice(LinearLayout panel, String label, boolean checked) {
        Switch option = new Switch(this);
        option.setText(label);
        option.setTextSize(16);
        styleSwitch(option);
        option.setMinHeight(dp(48));
        option.setChecked(checked);
        panel.addView(option);
        return option;
    }

    private void divider(LinearLayout panel) {
        View line = new View(this);
        line.setBackgroundColor(0x5482afc5);
        panel.addView(line, -1, dp(1));
    }

    private void heatSwitch(LinearLayout panel, String label, String key, int icon) {
        var toggle = heatingChoice(panel, label, prefs.getBoolean(key, false));
        decorate(toggle, icon, ACCENT, 19);
        toggle.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean(key, checked).apply());
        divider(panel);
    }

    private void alertToggle(LinearLayout settings, String label, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(16);
        styleSwitch(toggle);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(prefs.getBoolean(key, true));
        settings.addView(toggle);
        decorate(toggle, R.drawable.ic_ui_bell, ACCENT, 18);
        divider(settings);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            refresh();
        });
    }

    private android.widget.Spinner profilePicker(LinearLayout form, int selected) {
        String[] names = new String[ClimateProfiles.COUNT + 1];
        int active = ClimateRules.profile(prefs, System.currentTimeMillis(), ZoneId.systemDefault());
        names[0] = "Algemeen · " + (active < 0 ? "Standaardverwarming" : ClimateProfiles.get(prefs, active).name());
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            names[index + 1] = ClimateProfiles.get(prefs, index).name();
        }
        return choicePicker(form, "Klimaatprofiel", names, selected + 1);
    }

    private android.widget.RadioGroup windshieldChoices(LinearLayout form, int heat) {
        var group = new android.widget.RadioGroup(this);
        String[] options = {"Uit", "Altijd bij eerste cyclus", "Slim bij kou"};
        for (String option : options) {
            var radio = new android.widget.RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setText(option);
            radio.setTextColor(INK);
            radio.setMinHeight(dp(48));
            radio.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ICE, MUTED}));
            group.addView(radio, -1, -2);
        }
        group.check(group.getChildAt(ClimateProfiles.windshieldMode(heat)).getId());
        form.addView(group);
        return group;
    }

    private static int selectedWindshield(android.widget.RadioGroup group) {
        return group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId()));
    }

    private static String windshieldLabel(int heat, int threshold) {
        return switch (ClimateProfiles.windshieldMode(heat)) {
            case 1 -> "Altijd bij eerste cyclus";
            case 2 -> "Slim ≤ " + threshold + " °C";
            default -> "Uit";
        };
    }

    private static String cycleLabel(int minutes) {
        int count = Schedule.cycleCount(0, minutes * Schedule.MINUTE);
        return count == 1 ? "1 cyclus gepland" : count + " cyclussen gepland";
    }

    private static long onceStart(LocalDateTime selected, ZoneId zone, int minutes, boolean ready) {
        return selected.atZone(zone).toInstant().toEpochMilli() - (ready ? minutes * Schedule.MINUTE : 0);
    }

    private android.widget.Spinner choicePicker(LinearLayout form, String title, String[] choices, int selected) {
        form.addView(text(title, 14, MUTED, false));
        var picker = new android.widget.Spinner(this, android.widget.Spinner.MODE_DROPDOWN);
        picker.setContentDescription(title);
        picker.setMinimumHeight(dp(48));
        var adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        picker.setSelection(selected);
        form.addView(picker, -1, -2);
        return picker;
    }

    private static void onSelect(android.widget.Spinner picker, Runnable change) {
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                change.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private NumberPicker thresholdPicker(LinearLayout form, int selected, android.widget.RadioGroup mode) {
        var threshold = new NumberPicker(this);
        threshold.setMinValue(0);
        threshold.setMaxValue(30);
        threshold.setFormatter(value -> (value - 15) + " °C");
        threshold.setValue(selected + 15);
        threshold.setContentDescription("Inschakelen bij of onder");
        threshold.setWrapSelectorWheel(false);
        var box = column();
        form.addView(box);
        box.addView(text("Inschakelen bij of onder", 14, MUTED, false));
        box.addView(threshold, -1, -2);
        Runnable show = () -> box.setVisibility(selectedWindshield(mode) == 2 ? View.VISIBLE : View.GONE);
        mode.setOnCheckedChangeListener((group, checkedId) -> show.run());
        show.run();
        return threshold;
    }

    private void editDefaultWindshield() {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(8));
        var mode = windshieldChoices(form, Planner.defaultHeat(prefs));
        var threshold = thresholdPicker(form, prefs.getInt("windshieldThreshold", ClimateProfiles.DEFAULT_THRESHOLD), mode);
        new AlertDialog.Builder(this).setTitle("Voorruit ontdooien").setView(form)
            .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", (dialog, which) -> {
                threshold.clearFocus();
                prefs.edit().putInt("windshieldMode", selectedWindshield(mode))
                    .putInt("windshieldThreshold", threshold.getValue() - 15).apply();
                refresh();
                Toast.makeText(this, "Voorruitstand opgeslagen.", Toast.LENGTH_SHORT).show();
            }).show();
    }

    private String generalProfileName() {
        int index = ClimateProfiles.general(prefs);
        return index < 0 ? "Standaardverwarming" : ClimateProfiles.get(prefs, index).name();
    }

    private void chooseGeneralProfile() {
        String[] names = new String[ClimateProfiles.COUNT + 1];
        names[0] = "Standaardverwarming";
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            var profile = ClimateProfiles.get(prefs, index);
            names[index + 1] = profile.name() + " · " + temperatureLabel(profile.temperature());
        }
        new AlertDialog.Builder(this).setTitle("Algemeen klimaatprofiel")
            .setSingleChoiceItems(names, ClimateProfiles.general(prefs) + 1, (dialog, position) -> {
                prefs.edit().putInt(ClimateProfiles.GENERAL, position - 1).apply();
                ClimateWidget.refresh(this);
                dialog.dismiss();
                refresh();
            }).setNegativeButton("Annuleren", null).show();
    }

    private void manageProfiles() {
        String[] names = new String[ClimateProfiles.COUNT];
        for (int index = 0; index < names.length; index++) {
            var profile = ClimateProfiles.get(prefs, index);
            names[index] = profile.name() + " · " + temperatureLabel(profile.temperature());
        }
        new AlertDialog.Builder(this).setTitle("Klimaatprofielen").setItems(names, (dialog, index) -> editProfile(index))
            .setNegativeButton("Sluiten", null).show();
    }

    private void editProfile(int index) {
        var saved = ClimateProfiles.get(prefs, index);
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(12));
        var name = new android.widget.EditText(this);
        name.setHint("Profielnaam");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(30)});
        name.setText(saved.name());
        form.addView(name, -1, -2);
        var temperature = new NumberPicker(this);
        temperature.setMinValue(Planner.MIN_TEMP_HALVES);
        temperature.setMaxValue(Planner.MAX_TEMP_HALVES);
        temperature.setFormatter(MainActivity::temperatureLabel);
        temperature.setValue(saved.temperature());
        temperature.setContentDescription("Doeltemperatuur");
        form.addView(text("Doeltemperatuur", 14, MUTED, false));
        form.addView(temperature, -1, -2);
        var wheel = heatingChoice(form, "Stuurverwarming", (saved.heat() & Planner.HEAT_WHEEL) != 0);
        var mirrors = heatingChoice(form, "Spiegels en achterruit", (saved.heat() & Planner.HEAT_MIRRORS) != 0);
        form.addView(text("Voorruit ontdooien", 14, MUTED, false));
        var mode = windshieldChoices(form, saved.heat());
        var threshold = thresholdPicker(form, saved.threshold(), mode);
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle("Profiel bewerken").setView(body)
            .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) {
                name.setError("Vul een profielnaam in");
                return;
            }
            temperature.clearFocus();
            threshold.clearFocus();
            int heat = (wheel.isChecked() ? Planner.HEAT_WHEEL : 0) | (mirrors.isChecked() ? Planner.HEAT_MIRRORS : 0)
                | ClimateProfiles.windshieldFlag(selectedWindshield(mode));
            var edit = prefs.edit();
            ClimateProfiles.save(edit, index, new ClimateProfiles.Profile(label, temperature.getValue(), heat,
                threshold.getValue() - 15));
            edit.apply();
            ClimateWidget.refresh(this);
            refreshWindows();
            refresh();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void nextStartOptions() {
        var slot = displayedNext;
        if (slot == null) return;
        if ("Eenmalige start".equals(Planner.startType(prefs, slot))) {
            planOnce();
            return;
        }
        if (!Planner.upcomingWeekly(prefs, slot, System.currentTimeMillis())) return;
        new AlertDialog.Builder(this).setTitle(format(slot.session(), "EEEE d MMM · HH:mm"))
            .setItems(new String[]{"Deze start overslaan", "Deze start verplaatsen"}, (dialog, option) -> {
                if (option == 1) {
                    var zone = ZoneId.systemDefault();
                    var selected = Instant.ofEpochMilli(slot.session()).atZone(zone).toLocalDateTime();
                    pickDateTime(selected, "Nieuwe starttijd", start -> applyWeeklyChange(slot, start));
                } else {
                    applyWeeklyChange(slot, 0);
                }
            }).setNegativeButton("Sluiten", null).show();
    }

    private void applyWeeklyChange(Schedule.Slot slot, long start) {
        var change = Planner.adjustWeekly(this, slot, start);
        if (change == null) {
            Toast.makeText(this, "Kies een vrije toekomstige starttijd of open Volgende start opnieuw.",
                Toast.LENGTH_LONG).show();
        } else {
            undoChange = change;
            var accessibility = getSystemService(android.view.accessibility.AccessibilityManager.class);
            int timeout = Build.VERSION.SDK_INT >= 29 ? accessibility.getRecommendedTimeoutMillis(12_000,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
                | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT) : 12_000;
            undoUntil = android.os.SystemClock.elapsedRealtime() + timeout;
            set(adjustmentMessage, start == 0 ? "Start overgeslagen" : "Start verplaatst naar "
                + format(start, "EEE d MMM HH:mm"));
        }
        refresh();
    }

    private void undoWeeklyChange() {
        var change = undoChange;
        undoChange = null;
        if (change != null && !Planner.undoWeekly(this, change)) {
            Toast.makeText(this, "Deze wijziging kan niet meer ongedaan worden gemaakt.", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void pausePlanning() {
        boolean paused = prefs.getLong(WeeklyAdjustment.PAUSE, 0) > System.currentTimeMillis();
        String[] options = paused
            ? new String[]{"Nu hervatten", "Pauzeren tot morgen", "Datum en tijd kiezen"}
            : new String[]{"Tot morgen", "Datum en tijd kiezen"};
        var zone = ZoneId.systemDefault();
        var tomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay();
        new AlertDialog.Builder(this).setTitle("Weekplanning pauzeren").setItems(options, (dialog, index) -> {
            int option = paused ? index : index + 1;
            if (option == 2) {
                pickDateTime(tomorrow, "Hervatten om", until -> {
                    if (until > System.currentTimeMillis()) {
                        Planner.pauseWeekly(this, until);
                        refresh();
                    } else {
                        Toast.makeText(this, "Kies een tijd in de toekomst.", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            Planner.pauseWeekly(this, option == 0 ? 0 : tomorrow.atZone(zone).toInstant().toEpochMilli());
            refresh();
        }).setNegativeButton("Sluiten", null).show();
    }

    private void pickDateTime(LocalDateTime initial, String title, java.util.function.LongConsumer confirm) {
        var picker = new DatePickerDialog(this, (view, year, month, day) -> {
            var clock = new TimePickerDialog(this, (control, hour, minute) -> confirm.accept(
                LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                initial.getHour(), initial.getMinute(), true);
            clock.setTitle(title);
            clock.show();
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.getDatePicker().setMinDate(System.currentTimeMillis());
        picker.show();
    }

    private void checkConnection() {
        var dialog = new AlertDialog.Builder(this).setTitle("Verbindingscheck")
            .setMessage("Kia en Android controleren…").setPositiveButton("Sluiten", null).show();
        KiaCloud.WORKER.execute(() -> {
            String result = KiaCloud.connectionCheck(getApplicationContext());
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                dialog.setMessage(result + "\nExacte alarmen: " + (Planner.hasAlarmAccess(this) ? "toegestaan" : "toestemming nodig")
                    + "\nAndroid-meldingen: " + (WindowAlerts.permitted(this) ? "toegestaan" : "uitgeschakeld"));
            });
        });
    }

    private void showEnergyReport() {
        var zone = ZoneId.systemDefault();
        var anchor = new LocalDate[]{LocalDate.now(zone)};
        int[] period = {0};
        boolean[] loaded = {false};
        var page = paddedForm();
        var body = new ScrollView(this);
        body.addView(page);
        var toolbar = editorRow(page);
        var radios = new android.widget.RadioGroup(this);
        radios.setOrientation(stackControls() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        toolbar.addView(radios, -1, -2);
        String[] labels = {"Dag", "Week", "Maand"};
        int[] ids = new int[labels.length];
        for (int index = 0; index < labels.length; index++) {
            var option = new android.widget.RadioButton(this);
            ids[index] = View.generateViewId();
            option.setId(ids[index]);
            option.setText(labels[index]);
            option.setTextColor(INK);
            option.setMinHeight(dp(48));
            radios.addView(option, stackControls() ? new android.widget.RadioGroup.LayoutParams(-1, -2)
                : new android.widget.RadioGroup.LayoutParams(0, -2, 1));
        }
        radios.check(ids[0]);
        var dates = editorRow(page);
        var lines = column();
        Runnable render = () -> renderEnergyPeriod(lines, anchor[0], period[0], zone);
        var dateButton = new Button(this);
        var next = new Button(this);
        Runnable redraw = () -> {
            set(dateButton, anchor[0].format(DateTimeFormatter.ofPattern("d MMM yyyy", NL)));
            next.setEnabled(anchor[0].isBefore(LocalDate.now(zone)));
            if (loaded[0]) render.run();
        };
        var previous = addButton(dates, "‹", false, () -> {
            anchor[0] = shiftEnergyDate(anchor[0], period[0], -1);
            redraw.run();
        });
        previous.setContentDescription("Vorige periode");
        previous.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        var center = new LinearLayout.LayoutParams(0, dp(48), 1);
        center.setMarginStart(dp(8));
        center.setMarginEnd(dp(8));
        dates.addView(dateButton, center);
        styleButton(dateButton, false, 6);
        dateButton.setMinHeight(dp(48));
        dateButton.setOnClickListener(view -> {
            var day = anchor[0];
            var picker = new DatePickerDialog(this, (control, year, month, date) -> {
                anchor[0] = LocalDate.of(year, month + 1, date);
                redraw.run();
            }, day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        });
        next.setText("›");
        next.setContentDescription("Volgende periode");
        styleButton(next, false, 10);
        next.setMinHeight(dp(48));
        dates.addView(next, dp(48), dp(48));
        next.setOnClickListener(view -> {
            var candidate = shiftEnergyDate(anchor[0], period[0], 1);
            var today = LocalDate.now(zone);
            anchor[0] = candidate.isAfter(today) ? today : candidate;
            redraw.run();
        });
        page.addView(lines);
        radios.setOnCheckedChangeListener((group, checked) -> {
            for (int index = 0; index < ids.length; index++) if (ids[index] == checked) period[0] = index;
            redraw.run();
        });
        var dialog = new AlertDialog.Builder(this).setTitle("Accu en klimaatverbruik").setView(body)
            .setNegativeButton("Sluiten", null).create();
        lines.addView(text("Overzicht laden…", 14, MUTED, false));
        dialog.show();
        SharedPreferences.OnSharedPreferenceChangeListener batteryChanged = (stored, key) -> {
            if ("batteryTime".equals(key)) handler.post(() -> {
                if (dialog.isShowing() && !isDestroyed() && period[0] == 0) redraw.run();
            });
        };
        prefs.registerOnSharedPreferenceChangeListener(batteryChanged);
        dialog.setOnDismissListener(ignored -> prefs.unregisterOnSharedPreferenceChangeListener(batteryChanged));
        new Thread(() -> {
            try {
                EnergyHistory.open(getApplicationContext()).inherit(getApplicationContext());
                runOnUiThread(() -> {
                    if (dialog.isShowing() && !isDestroyed()) {
                        loaded[0] = true;
                        redraw.run();
                    }
                });
            } catch (android.database.sqlite.SQLiteException error) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        lines.removeAllViews();
                        lines.addView(text("Verbruiksgegevens konden niet worden geopend.", 14, MUTED, false));
                    }
                });
            }
        }, "kia-energy-report").start();
    }

    private static LocalDate shiftEnergyDate(LocalDate date, int period, int amount) {
        return switch (period) {
            case 1 -> date.plusWeeks(amount);
            case 2 -> date.plusMonths(amount);
            default -> date.plusDays(amount);
        };
    }

    private void renderEnergyPeriod(LinearLayout page, LocalDate anchor, int period, ZoneId zone) {
        page.removeAllViews();
        var history = EnergyHistory.open(this);
        LocalDate first = switch (period) {
            case 1 -> anchor.minusDays(anchor.getDayOfWeek().getValue() - 1);
            case 2 -> anchor.withDayOfMonth(1);
            default -> anchor;
        };
        LocalDate end = shiftEnergyDate(first, period, 1);
        var days = history.days(first, end);
        double energy = 0;
        long duration = 0;
        for (var day : days) {
            energy += day.energy();
            duration += day.duration();
        }
        String range = period == 0 ? first.format(DateTimeFormatter.ofPattern("EEEE d MMMM", NL))
            : period == 1 ? first.format(DateTimeFormatter.ofPattern("d MMM", NL)) + " – "
                + end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", NL))
                : first.format(DateTimeFormatter.ofPattern("MMMM yyyy", NL));
        var heading = text(range, 16, INK, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        page.addView(heading);
        page.addView(text("Geschat klimaatverbruik  " + EnergyChart.format(energy) + " kWh · " + minutes(duration), 15, ACCENT, true));
        if (period != 0) {
            var today = LocalDate.now(zone);
            for (var day : days) {
                if (day.date().isAfter(today)) break;
                var row = editorRow(page);
                var date = text(day.date().format(DateTimeFormatter.ofPattern("EEE d MMM", NL)), 14, MUTED, false);
                row.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
                var amount = text(EnergyChart.format(day.energy()) + " kWh", 14, INK, true);
                row.addView(amount);
                row.setPadding(0, dp(7), 0, dp(7));
                divider(page);
            }
            return;
        }
        var battery = history.batteryChart(anchor, zone);
        page.addView(text("Tractieaccu (%)", 16, INK, true));
        if (battery.isEmpty()) {
            page.addView(text("Geen Kia-accumeting op deze dag.", 14, MUTED, false));
        } else {
            var firstPoint = battery.get(0);
            var lastPoint = battery.get(battery.size() - 1);
            page.addView(text(dashboardPercentage(firstPoint.value()) + " om " + format(firstPoint.time(), "HH:mm")
                + (battery.size() > 1 ? " → " + dashboardPercentage(lastPoint.value()) + " om " + format(lastPoint.time(), "HH:mm")
                : " · één meting"), 14, MUTED, false));
            page.addView(new EnergyChart(this, battery, anchor, zone, true), -1, dp(152));
        }
        page.addView(text(BatterySampling.enabled(this) ? "Meetpunten bij Kia-statusupdates, met een automatische uurcheck."
            : "Meetpunten bij een Kia-statusupdate.", 13, MUTED, false));
        page.addView(text("Cumulatief klimaatverbruik (kWh)", 16, INK, true));
        if (duration == 0) page.addView(text("Geen geregistreerde klimaatcyclussen.", 14, MUTED, false));
        page.addView(new EnergyChart(this, history.cumulativeChart(anchor, zone, System.currentTimeMillis()), anchor, zone, false),
            -1, dp(152));
    }

    private void showEnergyLog() {
        ClimateEnergy.expire(this, System.currentTimeMillis());
        var average = ClimateEnergy.average(this);
        var log = ClimateEnergy.log(this);
        var header = column();
        header.setPadding(dp(16), dp(4), dp(16), dp(10));
        String method = average.learned() ? "Gemiddelde uit je metingen" : "Voorlopige rekenwaarde";
        header.addView(text(method + " · " + decimal(average.kilowatts()) + " kW", 16, INK, true));
        String samples = average.samples() == 1 ? "1 geschikte cyclus" : average.samples() + " geschikte cyclussen";
        header.addView(text(samples + " · " + minutes(average.duration()), 14, MUTED, false));
        header.addView(text("Kalibratie gebruikt recente Kia-metingen van geparkeerde, niet-aangesloten cyclussen."
            + " Bij een onzekere eindtijd blijven looptijd en verbruik schattingen.", 13, MUTED, false));
        var rows = new ArrayList<String>();
        for (int index = 0; index < log.length(); index++) {
            var record = log.optJSONObject(index);
            if (record == null) continue;
            String reason = switch (record.optString("reason")) {
                case "STATUS_OFF" -> "Uitstatus van Kia ontvangen";
                case "MANUAL" -> "Stop bevestigd door Kia";
                case "UNLOCKED" -> "Beëindigd na ontgrendelen";
                default -> "Eindtijd geschat";
            };
            double estimated = record.optDouble("estimate");
            String value = format(record.optLong("start"), "EEE d MMM · HH:mm") + " · " + minutes(record.optLong("elapsed"))
                + "\nGeschat " + decimal(estimated) + " kWh · circa " + decimal(estimated / ClimateEnergy.BATTERY_KWH * 100)
                + "% van 64 kWh\n" + decimal(record.optDouble("power"))
                + " kW (" + (record.optBoolean("learned") ? "eigen gemiddelde" : "rekenwaarde") + ")"
                + "\n" + reason + (record.optBoolean("eligible") ? " · telt mee voor kalibratie" : "");
            if (record.has("before") && record.has("after")) {
                value += "\nAccu " + dashboardPercentage(record.optDouble("before")) + " → "
                    + dashboardPercentage(record.optDouble("after"));
            }
            rows.add(value);
        }
        if (rows.isEmpty()) rows.add("Je eerste bevestigde klimaatcyclus verschijnt hier.");
        var list = new ListView(this);
        list.addHeaderView(header, null, false);
        list.setDivider(new ColorDrawable(0x5482afc5));
        list.setDividerHeight(dp(1));
        list.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        var dialog = new AlertDialog.Builder(this).setTitle("Verbruikslog").setView(list)
            .setNegativeButton("Sluiten", null).show();
        dialog.getWindow().setLayout(-1, getResources().getDisplayMetrics().heightPixels * 3 / 4);
    }

    private static String minutes(long milliseconds) {
        long minutes = Math.round(milliseconds / (double) Schedule.MINUTE);
        return minutes + " min";
    }

    private static String decimal(double number) {
        return String.format(NL, "%.1f", number);
    }

    private static String dashboardPercentage(double number) {
        return String.format(NL, number == Math.rint(number) ? "%.0f%%" : "%.1f%%", number);
    }

    private NumberPicker temperaturePicker(int value) {
        var picker = new NumberPicker(this);
        picker.setMinValue(Planner.MIN_TEMP_HALVES);
        picker.setMaxValue(Planner.MAX_TEMP_HALVES);
        picker.setFormatter(MainActivity::temperatureLabel);
        picker.setValue(value);
        picker.setWrapSelectorWheel(false);
        return picker;
    }

    private NumberPicker boundedPicker(int min, int max, int current, java.util.function.IntFunction<String> label) {
        var picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(max - min);
        if (label != null) {
            String[] display = new String[max - min + 1];
            for (int index = 0; index < display.length; index++) display[index] = label.apply(min + index);
            picker.setDisplayedValues(display);
        }
        picker.setValue(current - min);
        picker.setWrapSelectorWheel(false);
        return picker;
    }

    private LinearLayout paddedForm() {
        var form = column();
        form.setPadding(dp(20), 0, dp(20), dp(10));
        return form;
    }

    private void editStartRules() {
        var form = paddedForm();
        var limits = new ArrayList<String>();
        limits.add("Uit");
        for (int percent = 5; percent <= 100; percent += 5) limits.add(percent + "%");
        var minimum = choicePicker(form, "Minimale accu wanneer losgekoppeld",
            limits.toArray(new String[0]), prefs.getInt("minimumUnpluggedBattery", 0) / 5);
        var mild = heatingChoice(form, "Overslaan bij mild weer", prefs.getBoolean("skipMild", false));
        var range = editorRow(form);
        var low = boundedPicker(-10, 40, prefs.getInt("mildMin", 18), value -> value + " °C");
        var high = boundedPicker(-10, 40, prefs.getInt("mildMax", 22), value -> value + " °C");
        addPicker(range, "Vanaf", low);
        addPicker(range, "Tot en met", high);
        range.setVisibility(mild.isChecked() ? View.VISIBLE : View.GONE);
        mild.setOnCheckedChangeListener((view, checked) -> range.setVisibility(checked ? View.VISIBLE : View.GONE));
        var validation = text("", 14, CORAL, true);
        form.addView(validation);
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle("Accugrens en comfortbereik").setView(body)
            .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            low.clearFocus();
            high.clearFocus();
            if (low.getValue() > high.getValue()) {
                set(validation, "De eerste temperatuur moet gelijk of lager zijn.");
                return;
            }
            prefs.edit().putInt("minimumUnpluggedBattery", minimum.getSelectedItemPosition() * 5)
                .putBoolean("skipMild", mild.isChecked()).putInt("mildMin", low.getValue() - 10)
                .putInt("mildMax", high.getValue() - 10).apply();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void saveHomeLocation() {
        String current = prefs.getString("homeSource", "");
        String saved = !prefs.contains("homeLat") ? "Er is nog geen thuislocatie opgeslagen."
            : "Thuislocatie opgeslagen" + ("phone".equals(current) ? " via je telefoon." : " via Kia.");
        var form = paddedForm();
        form.addView(text(saved, 15, INK, true));
        form.addView(text("Sta thuis als je de locatie van je telefoon opslaat. De thuiszone heeft een straal van 150 meter."
            + " Bij geplande starts vergelijkt de app dit punt met de autolocatie van Kia.", 14, MUTED, false));
        var dialog = new AlertDialog.Builder(this).setTitle("Thuislocatie instellen").setView(form)
            .setNegativeButton("Sluiten", null).create();
        addButton(form, "Telefoonlocatie gebruiken", true, () -> {
            dialog.dismiss();
            requestPhonePosition();
        });
        addButton(form, "Kia-autolocatie gebruiken", false, () -> {
            dialog.dismiss();
            readKiaHomePosition();
        });
        if (prefs.contains("homeLat")) {
            addButton(form, "Thuislocatie wissen", false, () -> new AlertDialog.Builder(this)
                .setTitle("Thuislocatie wissen?")
                .setMessage("Alleen-thuis-tijdvakken worden overgeslagen totdat er weer een thuislocatie is.")
                .setNegativeButton("Annuleren", null).setPositiveButton("Wissen", (child, option) -> {
                    prefs.edit().remove("homeLat").remove("homeLon").remove("homeSource").apply();
                    dialog.dismiss();
                }).show());
        }
        dialog.show();
    }

    private void requestPhonePosition() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                LOCATION_PERMISSION);
            return;
        }
        var manager = getSystemService(android.location.LocationManager.class);
        if (Build.VERSION.SDK_INT >= 28 && !manager.isLocationEnabled()) {
            new AlertDialog.Builder(this).setTitle("Locatie staat uit")
                .setMessage("Zet locatie aan op je Samsung om je thuispunt op te halen.")
                .setNegativeButton("Sluiten", null).setPositiveButton("Locatie-instellingen", (dialog, option) ->
                    openFromPlanner(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
            return;
        }
        var dialog = new AlertDialog.Builder(this).setTitle("Telefoonlocatie zoeken")
            .setMessage("Wacht op een nauwkeurige locatie…").setNegativeButton("Annuleren", null).show();
        pendingGpsDialog = dialog;
        var lookup = new PhonePosition(manager, handler, fix -> {
            if (!dialog.isShowing() || isDestroyed()) return;
            dialog.dismiss();
            if (fix == null) {
                new AlertDialog.Builder(this).setTitle("Locatie nog niet nauwkeurig genoeg")
                    .setMessage("Probeer bij een raam of buiten. Je kunt ook de Kia-autolocatie kiezen.")
                    .setNegativeButton("Sluiten", null).setPositiveButton("Opnieuw zoeken", (window, option) ->
                        requestPhonePosition()).show();
                return;
            }
            confirmHomePosition(fix.getLatitude(), fix.getLongitude(), "phone",
                "Telefoonlocatie gevonden, nauwkeurig tot circa " + Math.round(fix.getAccuracy()) + " meter.");
        });
        dialog.setOnDismissListener(ignored -> {
            lookup.cancel();
            if (pendingGpsDialog == dialog) pendingGpsDialog = null;
        });
        lookup.start();
    }

    private void readKiaHomePosition() {
        if (showKiaPause()) return;
        var wait = new AlertDialog.Builder(this).setTitle("Thuislocatie")
            .setMessage("Autolocatie ophalen…").setNegativeButton("Annuleren", null).show();
        KiaCloud.WORKER.execute(() -> {
            var location = KiaCloud.readHomePosition(getApplicationContext());
            runOnUiThread(() -> {
                if (!wait.isShowing() || isDestroyed()) return;
                wait.dismiss();
                if (location == null) {
                    new AlertDialog.Builder(this).setTitle("Geen recente Kia-autolocatie")
                        .setMessage("Je kunt je thuislocatie met je telefoon opslaan. De geplande thuiscontrole heeft wel een Kia-autolocatie nodig.")
                        .setNegativeButton("Sluiten", null).setPositiveButton("Telefoonlocatie gebruiken", (dialog, option) ->
                            requestPhonePosition()).show();
                } else {
                    confirmHomePosition(location[0], location[1], "kia", "Kia heeft een recente autolocatie teruggegeven.");
                }
            });
        });
    }

    private void confirmHomePosition(double latitude, double longitude, String source, String hint) {
        new AlertDialog.Builder(this).setTitle("Opslaan als thuislocatie?")
            .setMessage(hint + "\nDe thuislocatie wordt op dit toestel bewaard.")
            .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", (dialog, option) -> {
                prefs.edit().putLong("homeLat", Double.doubleToLongBits(latitude))
                    .putLong("homeLon", Double.doubleToLongBits(longitude)).putString("homeSource", source).apply();
                Toast.makeText(this, "Thuislocatie opgeslagen.", Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void editSeasons() {
        var form = paddedForm();
        var active = heatingChoice(form, "Wissel automatisch op datum", prefs.getBoolean("seasonalProfile", false));
        String[] names = new String[ClimateProfiles.COUNT];
        for (int index = 0; index < names.length; index++) names[index] = ClimateProfiles.get(prefs, index).name();
        int[] dates = {prefs.getInt("summerDate", 401), prefs.getInt("winterDate", 1001)};
        var summer = choicePicker(form, "Profiel vanaf de eerste datum", names, prefs.getInt("summerProfile", 1));
        var fromSummer = addButton(form, monthDayLabel(dates[0]), false, () -> {});
        var winter = choicePicker(form, "Profiel vanaf de tweede datum", names, prefs.getInt("winterProfile", 0));
        var fromWinter = addButton(form, monthDayLabel(dates[1]), false, () -> {});
        fromSummer.setOnClickListener(view -> chooseMonthDay(dates[0], selected -> {
            dates[0] = selected;
            set(fromSummer, monthDayLabel(selected));
        }));
        fromWinter.setOnClickListener(view -> chooseMonthDay(dates[1], selected -> {
            dates[1] = selected;
            set(fromWinter, monthDayLabel(selected));
        }));
        form.addView(text("Starts met een eigen profiel behouden die keuze.", 13, MUTED, false));
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle("Profielen per seizoen").setView(body)
            .setNegativeButton("Annuleren", null).setPositiveButton("Opslaan", null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            if (active.isChecked() && dates[0] == dates[1]) {
                Toast.makeText(this, "Kies twee verschillende wisseldatums.", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("seasonalProfile", active.isChecked()).putInt("summerDate", dates[0])
                .putInt("winterDate", dates[1]).putInt("summerProfile", summer.getSelectedItemPosition())
                .putInt("winterProfile", winter.getSelectedItemPosition()).apply();
            ClimateWidget.refresh(this);
            refresh();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static String monthDayLabel(int code) {
        return LocalDate.of(2000, code / 100, code % 100).format(DateTimeFormatter.ofPattern("d MMMM", NL));
    }

    private void chooseMonthDay(int selected, java.util.function.IntConsumer result) {
        new DatePickerDialog(this, (view, year, month, day) -> result.accept((month + 1) * 100 + day),
            LocalDate.now().getYear(), selected / 100 - 1, selected % 100).show();
    }

    private boolean biometricAvailable() {
        return Build.VERSION.SDK_INT >= 30 && getSystemService(android.hardware.biometrics.BiometricManager.class)
            .canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void authenticate(String title, Runnable command) {
        if (!prefs.getBoolean("requireBiometric", false)) {
            command.run();
            return;
        }
        if (!biometricAvailable()) {
            Toast.makeText(this, "Biometrie is niet beschikbaar. Controleer je Samsung-instellingen.", Toast.LENGTH_LONG).show();
            return;
        }
        new android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Annuleren", getMainExecutor(), (dialog, which) -> {})
            .build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(
                            android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                        if (!isFinishing() && !isDestroyed()) command.run();
                    }
                });
    }

    private void pickCalendar(java.util.function.LongConsumer selected) {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            calendarPermissionGranted = () -> pickCalendar(selected);
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, CALENDAR_PERMISSION);
            return;
        }
        var wait = new AlertDialog.Builder(this).setTitle("Vertrektijd uit agenda")
            .setMessage("Afspraken ophalen…").setNegativeButton("Annuleren", null).show();
        new Thread(() -> {
            var labels = new ArrayList<String>();
            var times = new ArrayList<Long>();
            long now = System.currentTimeMillis();
            String[] fields = {"begin", "title", "allDay"};
            try (var records = android.provider.CalendarContract.Instances.query(getContentResolver(), fields,
                    now, now + 21L * 24 * ClimateEnergy.HOUR)) {
                while (records != null && labels.size() < 60 && records.moveToNext()) {
                    long start = records.getLong(0);
                    if (start <= now || records.getInt(2) != 0) continue;
                    times.add(start);
                    labels.add(format(start, "EEE d MMM HH:mm") + " · " + (records.isNull(1) ? "Afspraak" : records.getString(1)));
                }
            } catch (RuntimeException ignored) {
                // The calendar provider or permission can disappear while it opens.
            }
            runOnUiThread(() -> {
                if (!wait.isShowing() || isDestroyed()) return;
                wait.dismiss();
                if (labels.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("Vertrektijd uit agenda")
                        .setMessage("Geen zichtbare afspraken met een begintijd in de komende drie weken.")
                        .setPositiveButton("Sluiten", null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("Kies een afspraak")
                        .setItems(labels.toArray(new String[0]), (dialog, index) -> selected.accept(times.get(index)))
                        .setNegativeButton("Annuleren", null).show();
                }
            });
        }, "kia-calendar").start();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(request, permissions, result);
        if (request == LOCATION_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPhonePosition();
            } else {
                new AlertDialog.Builder(this).setTitle("Nauwkeurige locatie nodig")
                    .setMessage("Kies nauwkeurige locatietoegang om het middelpunt van de thuiszone op te slaan.")
                    .setNegativeButton("Sluiten", null).setPositiveButton("App-instellingen", (dialog, option) ->
                        openFromPlanner(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))).show();
            }
            return;
        }
        if (request != CALENDAR_PERMISSION) return;
        var action = calendarPermissionGranted;
        calendarPermissionGranted = null;
        if (result.length > 0 && result[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && action != null) {
            action.run();
        } else {
            Toast.makeText(this, "Agenda-toegang is nodig om een afspraak te kiezen.", Toast.LENGTH_LONG).show();
        }
    }

    private void showAgenda() {
        var zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long end = LocalDate.now(zone).plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli();
        var rows = Planner.agenda(prefs, now, end, zone);
        var labels = new ArrayList<String>();
        for (var row : rows) {
            String label = format(row.start(), "EEEE d MMM · HH:mm") + "–" + format(row.end(), "HH:mm")
                + "\n" + row.type();
            if (row.original() > 0) label += " · was " + format(row.original(), "EEE d MMM HH:mm");
            labels.add(label);
        }
        var dialog = new AlertDialog.Builder(this).setTitle("Komende zeven dagen").setPositiveButton("Sluiten", null);
        if (labels.isEmpty()) dialog.setMessage("Geen geplande starts in deze periode.");
        else dialog.setItems(labels.toArray(new String[0]), null);
        dialog.show();
    }

    private void showPreflight() {
        var slot = displayedNext;
        if (slot == null) slot = Planner.next(this);
        var status = new StringBuilder();
        if (slot != null) status.append("Gepland ").append(format(slot.time(), "EEE d MMM HH:mm")).append('\n');
        status.append(KiaCloud.linked(this) && !KiaCloud.needsLink(this) ? "Kia Connect gekoppeld" : "Kia Connect koppelen");
        status.append(Planner.hasAlarmAccess(this) ? "\nExacte alarmen toegestaan" : "\nExacte alarmen zijn uitgeschakeld");
        long measured = Math.max(prefs.getLong("batteryTime", 0), prefs.getLong("lastClimateSample", 0));
        status.append(measured > 0 ? "\nLaatste autostatus " + format(measured, "d MMM HH:mm") : "\nNog geen autostatus bekend");
        if (slot != null && Planner.homeOnly(prefs, slot, ZoneId.systemDefault())) {
            status.append(prefs.contains("homeLat") ? "\nThuiszone wordt bij de start gecontroleerd" : "\nStel eerst je thuislocatie in");
        }
        new AlertDialog.Builder(this).setTitle("Volgende start controleren").setMessage(status.toString())
            .setPositiveButton("Sluiten", null).setNeutralButton("Verbinding controleren", (dialog, which) -> checkConnection())
            .show();
    }

    private void showFailureActions(String cause) {
        String recovery = switch (cause) {
            case "AUTH" -> "Kia opnieuw koppelen";
            case "ALARM" -> "Alarmen toestaan";
            default -> "Verbinding controleren";
        };
        new AlertDialog.Builder(this).setTitle("Wat wil je doen?")
            .setPositiveButton(recovery, (dialog, option) -> recoverFailure(cause))
            .setNegativeButton("Sluiten", null).show();
    }

    private void recoverFailure() {
        recoverFailure(prefs.getString("retryCause", ""));
    }

    private void recoverFailure(String cause) {
        if ("AUTH".equals(cause)) {
            openFromPlanner(new Intent(this, KiaAccountActivity.class));
        } else if ("ALARM".equals(cause) && !Planner.hasAlarmAccess(this) && Build.VERSION.SDK_INT >= 31) {
            openFromPlanner(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        } else {
            checkConnection();
        }
    }

    private void showMonth() {
        showMonth(java.time.YearMonth.now());
    }

    private void showMonth(java.time.YearMonth month) {
        var window = StartLedger.summary(this, month);
        String message = "Bevestigd  " + window.confirmed() + "\nOvergeslagen  " + window.skipped() + "\nMislukt  "
            + window.failed() + (window.common().isEmpty() ? "" : "\n\nMeest voorkomende oorzaak: "
                + window.common() + " (" + window.commonCount() + ")");
        double usage = 0;
        var energy = ClimateEnergy.log(this);
        for (int index = 0; index < energy.length(); index++) {
            var entry = energy.optJSONObject(index);
            if (entry != null && entry.optLong("start") > 0 && month.equals(StartLedger.month(entry.optLong("start"))))
                usage += entry.optDouble("estimate", 0);
        }
        message += "\n\nGeschat klimaatverbruik  " + decimal(usage) + " kWh";
        var dialog = new AlertDialog.Builder(this).setTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", NL)))
            .setMessage(message).setPositiveButton("Sluiten", null);
        if (month.isAfter(java.time.YearMonth.now().minusMonths(12)))
            dialog.setNegativeButton("Vorige maand", (windowDialog, choice) -> showMonth(month.minusMonths(1)));
        if (month.isBefore(java.time.YearMonth.now()))
            dialog.setNeutralButton("Volgende maand", (windowDialog, choice) -> showMonth(month.plusMonths(1)));
        dialog.show();
    }

    private void choosePrivateLog() {
        try {
            var history = new JSONArray(prefs.getString("history", "[]"));
            var sessions = new java.util.LinkedHashSet<Long>();
            for (int index = 0; index < history.length(); index++) {
                var item = history.optJSONObject(index);
                if (item != null && item.optLong("time") >= System.currentTimeMillis() - Planner.HISTORY_AGE
                        && item.optLong("session") > 0) sessions.add(item.optLong("session"));
            }
            var dates = new ArrayList<>(sessions);
            if (dates.isEmpty()) {
                Toast.makeText(this, "Er zijn nog geen tijdvakken om te exporteren.", Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[dates.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = format(dates.get(i), "EEE d MMM · HH:mm");
            new AlertDialog.Builder(this).setTitle("Privélog · kies een tijdvak")
                .setItems(labels, (window, index) -> {
                    try {
                        pendingDiagnostic = StartLedger.safeExport(history, dates.get(index));
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "kia-klimaat-tijdvak.json"), EXPORT_DIAGNOSTIC);
                    } catch (JSONException error) {
                        Toast.makeText(this, "Privélog maken is mislukt.", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Annuleren", null).show();
        } catch (JSONException error) {
            Toast.makeText(this, "Geen leesbaar meldingenlog beschikbaar.", Toast.LENGTH_LONG).show();
        }
    }

    private void openBackup(boolean export) {
        var intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "kia-weekplanning.json");
        startActivityForResult(intent, export ? EXPORT_PLAN : IMPORT_PLAN);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT_DIAGNOSTIC) pendingDiagnostic = null;
            return;
        }
        Uri file = data.getData();
        if (request == EXPORT_DIAGNOSTIC) {
            if (pendingDiagnostic == null) return;
            try (var out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new java.io.IOException();
                out.write(pendingDiagnostic.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this, "Privélog opgeslagen.", Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | SecurityException error) {
                Toast.makeText(this, "Privélog opslaan is mislukt.", Toast.LENGTH_LONG).show();
            } finally {
                pendingDiagnostic = null;
            }
        } else if (request == EXPORT_PLAN) {
            try (var out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new java.io.IOException();
                out.write(PlanningBackup.export(prefs).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this, "Weekplanning geëxporteerd.", Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | JSONException | SecurityException error) {
                Toast.makeText(this, "Back-up opslaan is mislukt.", Toast.LENGTH_LONG).show();
            }
        } else if (request == IMPORT_PLAN) {
            try (var in = getContentResolver().openInputStream(file)) {
                if (in == null) throw new java.io.IOException();
                var bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int length; (length = in.read(buffer)) >= 0;) {
                    if (bytes.size() + length > PlanningBackup.MAX_BYTES) throw new java.io.IOException();
                    bytes.write(buffer, 0, length);
                }
                var backup = new org.json.JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                var restore = PlanningBackup.restore(prefs, backup);
                new AlertDialog.Builder(this).setTitle("Weekplanning terugzetten?")
                    .setMessage(backupPreview(backup))
                    .setNegativeButton("Annuleren", null).setPositiveButton("Terugzetten", (dialog, which) -> {
                        if (restore.commit()) {
                            Planner.schedule(this);
                            recreate();
                        } else {
                            Toast.makeText(this, "Back-up terugzetten is mislukt.", Toast.LENGTH_LONG).show();
                        }
                    }).show();
            } catch (java.io.IOException | JSONException | SecurityException error) {
                Toast.makeText(this, "Dit is geen geldige back-up van Kia Climate Control.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String backupPreview(org.json.JSONObject backup) throws JSONException {
        var message = new StringBuilder();
        var days = backup.getJSONArray("days");
        int changed = 0;
        for (int day = 0; day < DAYS.length; day++) {
            var incoming = days.getJSONObject(day);
            var slots = incoming.getJSONArray("slots");
            var previous = new ArrayList<String>();
            var next = new ArrayList<String>();
            boolean different = Planner.dayEnabled(prefs, day) != incoming.getBoolean("enabled");
            for (int index = 0; index < slots.length(); index++) {
                var before = readSlot(day, index);
                var after = slots.getJSONObject(index);
                int time = after.getInt("clock");
                int duration = after.getInt("duration");
                boolean ready = after.getBoolean("ready");
                boolean enabled = after.getBoolean("enabled");
                if ((duration > 0 || before.duration() > 0) && (time != before.start() || duration != before.duration()
                        || ready != before.ready() || enabled != before.enabled() || after.getInt("profile") != before.profile()
                        || after.getInt("heat") != before.heating()
                        || after.optBoolean("homeOnly") != before.homeOnly())) different = true;
                if (before.duration() > 0) previous.add((before.ready() ? "klaar " : "") + clock(before.start())
                    + " (" + before.duration() + " min" + (before.enabled() ? "" : ", uit") + ")");
                if (duration > 0) next.add((ready ? "klaar " : "") + clock(time)
                    + " (" + duration + " min" + (enabled ? "" : ", uit") + ")");
            }
            if (different) {
                changed++;
                String previousLabel = previous.isEmpty() ? "leeg" : String.join(" · ", previous);
                String nextLabel = next.isEmpty() ? "leeg" : String.join(" · ", next);
                message.append(DAYS[day]).append(": ").append(previousLabel);
                if (!previousLabel.equals(nextLabel)) message.append(" → ").append(nextLabel);
                else if (Planner.dayEnabled(prefs, day) == incoming.getBoolean("enabled"))
                    message.append(" (tijdvakopties gewijzigd)");
                if (Planner.dayEnabled(prefs, day) != incoming.getBoolean("enabled"))
                    message.append(incoming.getBoolean("enabled") ? " (dag wordt actief)" : " (dag wordt uitgezet)");
                message.append('\n');
            }
        }
        if (changed == 0) message.append("Geen verschillen in tijdvakken.\n");
        var profiles = backup.getJSONArray("profiles");
        message.append("\nTe herstellen profielen\n");
        for (int index = 0; index < profiles.length(); index++) {
            var saved = profiles.getJSONObject(index);
            message.append(ClimateProfiles.get(prefs, index).name()).append(" → ").append(saved.getString("name"))
                .append(" · ").append(temperatureLabel(saved.getInt("temperature"))).append('\n');
        }
        message.append("\nTijdelijke weekafwijkingen vervallen.");
        return message.toString();
    }

    private void openFromPlanner(Intent intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
    }

    private void allowAlerts() {
        if (Build.VERSION.SDK_INT >= 33 && (!prefs.getBoolean("askedAlerts", false)
                || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit().putBoolean("askedAlerts", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        } else {
            openFromPlanner(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void refresh() {
        boolean linked = KiaCloud.linked(this);
        boolean needsLink = KiaCloud.needsLink(this);
        boolean alarms = Planner.hasAlarmAccess(this);
        long now = System.currentTimeMillis();
        long end = Planner.activeEnd(this, now);
        long cycleEnd = Planner.cycleEnd(this);
        boolean manual = prefs.getLong("manualEnd", 0) > now;
        boolean combined = Planner.isCombined(prefs, now);
        boolean onceActive = Planner.onceActive(prefs, now);
        long onceStart = prefs.getLong("onceStart", 0);
        boolean manualStop = Planner.manuallyStopped(prefs);
        boolean stopPending = prefs.getLong("stopVehiclePending", 0) > now;
        boolean running = cycleEnd > now && !manualStop;
        boolean monitoring = prefs.getLong("monitoringStart", 0) > 0 && end > now;
        boolean vehicleStillOn = monitoring && prefs.getBoolean("observedClimateOn", false)
            && now - prefs.getLong("lastClimateSample", 0) <= 3 * Schedule.MINUTE;
        boolean external = linked && Planner.externalClimateActive(prefs, now);
        boolean awaiting = Planner.awaitingConfirmation(this, now);
        boolean unconfirmed = prefs.getLong("unconfirmedHold", 0) > now;
        set(overall, manualStop && (stopPending || KiaStartService.stopInProgress())
            ? (KiaStartService.stopInProgress() ? "Klimaat stoppen…" : "Stop niet bevestigd")
            : running || vehicleStillOn ? "Klimaat actief" : awaiting ? "Wacht op Kia"
            : external ? "Klimaatregeling al actief"
            : unconfirmed ? "Klimaatstatus onbekend" : end > now ? "Tijdvak actief" : "Klimaat uit");
        boolean ownClimate = running || vehicleStillOn;
        boolean activeClimate = ownClimate || (external && !stopPending && !awaiting);
        var temperature = ClimateIndicator.read(prefs, now, ownClimate);
        updateClimateIcon(temperature, activeClimate);
        String outside = "Buiten " + dashboardTemperature(temperature.outdoor());
        if (temperature.measured() > 0) {
            outside += "\n" + (temperature.weather() ? "Weer " : "Kia ") + format(temperature.measured(), "HH:mm");
        } else {
            var last = ClimateIndicator.lastOutdoor(prefs, now);
            if (last != null) {
                boolean today = localDate(last.measured()).equals(localDate(now));
                outside = "Buiten " + dashboardTemperature(last.value()) + "\n"
                    + "Verouderd · " + (last.weather() ? "weer " : "Kia ")
                    + format(last.measured(), today ? "HH:mm" : "d MMM HH:mm");
            }
        }
        set(outsideTemperature, outside);
        set(windshieldSetting, "Voorruit ontdooien\n" + windshieldLabel(Planner.defaultHeat(prefs),
            prefs.getInt("windshieldThreshold", ClimateProfiles.DEFAULT_THRESHOLD)) + "  ›");
        int seasonal = ClimateRules.profile(prefs, now, ZoneId.systemDefault());
        String general = prefs.getBoolean("seasonalProfile", false) && seasonal >= 0
            ? ClimateProfiles.get(prefs, seasonal).name() + " · seizoen" : generalProfileName();
        set(generalProfileSetting, "Algemeen klimaatprofiel\n" + general + "  ›");
        double defaultTarget = Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature() / 2.0;
        double target = activeClimate ? temperature.target() : defaultTarget;
        set(targetTemperature, "Ingesteld " + dashboardTemperature(target));
        set(statusDetail, external && !running && !awaiting && !stopPending ? "Resterende tijd onbekend" : "");
        statusDetail.setVisibility(statusDetail.length() == 0 ? View.GONE : View.VISIBLE);
        boolean showWindow = !manualStop && end > now && Planner.hasConfirmedWindow(this, end);
        clocks.setVisibility(running || showWindow ? View.VISIBLE : View.GONE);
        windowMetric.setVisibility(showWindow ? View.VISIBLE : View.GONE);
        cycleMetric.setVisibility(running ? View.VISIBLE : View.GONE);
        clockDivider.setVisibility(showWindow && running && !stackControls() ? View.VISIBLE : View.GONE);
        String windowTime = showWindow ? countdown(end - now) : "";
        float clockSize = windowTime.length() >= 7 ? 42
            : getResources().getConfiguration().fontScale < 0.95f ? 74 : 58;
        if (windowClock.getTag() == null || !windowClock.getTag().equals(clockSize)) {
            windowClock.setTextSize(clockSize);
            windowClock.setTag(clockSize);
        }
        set(windowClock, windowTime);
        set(windowLabel, combined ? "Gecombineerd tijdvak" : onceActive ? "Eenmalig tijdvak" : "Tijdvak resterend");
        set(cycleClock, running ? countdown(cycleEnd - now) : "");
        long windowStart = onceActive ? onceStart : manual ? prefs.getLong("manualFirst", 0)
            : prefs.getLong("activeSession", 0);
        if (showWindow && windowStart > 0 && end > windowStart) {
            timeline.setVisibility(View.VISIBLE);
            timeline.update(windowStart, end, now);
        } else {
            timeline.setVisibility(View.GONE);
        }
        long kiaResume = KiaBackoff.until(prefs);
        boolean kiaPaused = kiaResume > now;
        long remainingCooldown = Planner.cooldownEnd(this) - now;
        boolean confirmedStart = prefs.getLong("lastStart", 0) > 0
            && prefs.getLong("confirmedStart", 0) >= prefs.getLong("lastStart", 0);
        boolean separateCooldown = !monitoring && (!running || Planner.cooldownEnd(this) > cycleEnd + 1_000);
        cooldown.setVisibility(kiaPaused || (separateCooldown && remainingCooldown > 0 && (confirmedStart || !awaiting))
            ? View.VISIBLE : View.GONE);
        set(cooldown, kiaPaused ? "Automatische Kia-controle gepauzeerd tot " + KiaBackoff.resumeLabel(kiaResume)
            : Planner.manualRestartAvailable(prefs, now) ? "Handmatig starten kan direct"
            : confirmedStart && !manualStop ? "Nieuwe start over minimaal " + countdown(Math.max(0, remainingCooldown))
            : "Nieuwe opdracht mogelijk om " + format(Planner.nextAllowedStart(this), "HH:mm"));
        var next = Planner.next(this);
        boolean ready = linked && !needsLink && alarms;
        long nextStart = ready && next != null ? Math.max(next.time(), Planner.nextAllowedStart(this)) : 0;
        boolean continuation = ready && monitoring && Planner.remainingCycles(prefs) > 0;
        if (continuation) nextStart = running && !kiaPaused ? cycleEnd + 15_000 : 0;
        displayedNext = ready && !continuation ? next : null;
        boolean actionable = displayedNext != null && (Planner.upcomingWeekly(prefs, displayedNext, now)
            || "Eenmalige start".equals(Planner.startType(prefs, displayedNext)));
        changeNext.setVisibility(actionable ? View.VISIBLE : View.GONE);
        var forecast = displayedNext != null ? upcomingClimateDetails(displayedNext, temperature) : "";
        set(nextPreview, forecast);
        nextPreviewCard.setVisibility(forecast.isEmpty() ? View.GONE : View.VISIBLE);
        if (android.os.SystemClock.elapsedRealtime() >= undoUntil) undoChange = null;
        undoBar.setVisibility(undoChange == null ? View.GONE : View.VISIBLE);
        long pausedUntil = prefs.getLong(WeeklyAdjustment.PAUSE, 0);
        set(pauseButton, pausedUntil > now ? "Gepauzeerd tot " + format(pausedUntil, "EEE d MMM HH:mm") : "Weekplanning pauzeren");
        String nextType = continuation ? "Volgende cyclus" : Planner.startType(prefs, next);
        nextView.setVisibility(nextStart > 0 ? View.VISIBLE : View.GONE);
        set(nextView, nextStart > 0 ? format(nextStart, "HH:mm") : "");
        set(nextDay, !linked || needsLink ? "Koppel Kia Connect" : !alarms ? "Exacte alarmen nodig"
            : nextStart > 0 ? nextType + "\n" + format(nextStart, "EEE d MMM")
            : continuation ? (kiaPaused ? "Wacht op einde Kia-pauze" : "Volgende cyclus wacht op Kia")
            : pausedUntil > now ? "Weekplanning gepauzeerd" : prefs.getBoolean("enabled", false)
                ? "Geen start gepland" : "Weekplanning staat uit");
        String step = awaiting ? "Wacht op bevestiging van Kia"
            : stopPending && !kiaPaused ? "Wacht op stopbevestiging van Kia"
            : stopPending ? "Stop niet bevestigd door Kia"
            : monitoring && kiaPaused ? "Klimaatcontrole tijdelijk gepauzeerd"
            : monitoring && Planner.remainingCycles(prefs) > 0 ? "De planner controleert Kia rond het einde van de cyclus"
            : monitoring ? "Laatste cyclus. De planner controleert de uitstatus rond het einde."
            : unconfirmed ? "Kia heeft de start nog niet bevestigd"
            : !activeClimate && end <= now && now - prefs.getLong("finishedOnUnlock", 0) < 15 * Schedule.MINUTE
                ? "Afgerond na ontgrendelen of inschakelen" : "";
        set(liveStep, step);
        liveStep.setVisibility(step.isEmpty() ? View.GONE : View.VISIBLE);
        boolean canStop = linked && (running || external || awaiting || unconfirmed || end > now
            || prefs.getLong("stopVehiclePending", 0) > now);
        manualButton.setVisibility(canStop && manual && ready ? View.GONE : View.VISIBLE);
        manualButton.setEnabled(!manual || !ready);
        set(manualButton, ready ? "Handmatig starten" : "Naar instellingen");
        stopButton.setVisibility(canStop ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(!KiaStartService.stopInProgress());
        set(stopButton, KiaStartService.stopInProgress() ? "Stoppen…" : "Stoppen");
        onceButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        onceButton.setEnabled(!onceActive);
        set(onceButton, onceActive ? "Eenmalig tijdvak loopt" : onceStart > now
            ? "Eenmalig · " + format(onceStart, "d MMM HH:mm") : "Eenmalig plannen");
        int percentage = prefs.getInt("battery", -1);
        long measured = prefs.getLong("batteryTime", 0);
        set(battery, percentage < 0 ? "Onbekend" : percentage + "%");
        String batteryDetail = batteryBusy ? "Accu ophalen…" : measured > 0
            ? "Gemeten " + format(measured, "d MMM HH:mm") : "Geen meting";
        set(batteryMeasured, batteryDetail);
        batteryTile.setEnabled(linked && !needsLink && !batteryBusy);
        batteryTile.setContentDescription("Tractieaccu, " + (percentage < 0 ? "onbekend" : percentage + " procent")
            + ", " + batteryDetail + ", accustatus verversen");
        extendAction.setVisibility(!manualStop && showWindow && prefs.getInt("activeConfirmedCycles", 0) > 0
            ? View.VISIBLE : View.GONE);
        repeatAction.setVisibility(!canStop && ready && prefs.contains("previousManualMinutes") ? View.VISIBLE : View.GONE);
        preflightAction.setVisibility(displayedNext == null ? View.GONE : View.VISIBLE);
        nextPreviewCard.setVisibility(displayedNext != null || !forecast.isEmpty() ? View.VISIBLE : View.GONE);
        boolean showAccountDetails = accountExpanded || !linked || needsLink || !alarms;
        set(accountState, needsLink ? "Opnieuw koppelen vereist" : linked
            ? "Gekoppeld · " + prefs.getString("kiaModel", "e-Niro") + (showAccountDetails ? "   ▴" : "   ›")
            : "Kia Connect nog niet gekoppeld");
        accountDetails.setVisibility(showAccountDetails ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 30) {
            accountState.setStateDescription(showAccountDetails ? "Uitgeklapt" : "Ingeklapt");
        }
        set(accountButton, linked ? "Kia-account opnieuw koppelen" : "Kia-account koppelen");
        unlink.setVisibility(linked ? View.VISIBLE : View.GONE);
        screenControls.setVisibility(linked ? View.GONE : View.VISIBLE);
        alarmPermission.setVisibility(alarms ? View.GONE : View.VISIBLE);
        boolean alertsWanted = prefs.getBoolean("notifyDepartureDelay", false) || prefs.getBoolean("notifyWindowEnd", true)
            || prefs.getBoolean("notifyStartLimit", true) || prefs.getBoolean("notifyStartFailure", true);
        alertPermission.setVisibility(alertsWanted && !WindowAlerts.permitted(this) ? View.VISIBLE : View.GONE);
        set(access, alarms ? "Exacte alarmen zijn toegestaan." : "Exacte alarmen hebben toestemming nodig.");
        refreshHistory();
    }

    private String upcomingClimateDetails(Schedule.Slot slot, ClimateIndicator.Reading reading) {
        var options = Planner.climateOptions(prefs, slot, ZoneId.systemDefault());
        String name = options.profile() < 0 ? "Standaard" : ClimateProfiles.get(prefs, options.profile()).name();
        if (options.customHeat()) name += " · Eigen verwarming";
        String rule = "Voorruit " + windshieldLabel(options.heat(), options.threshold());
        if (ClimateProfiles.windshieldMode(options.heat()) == 2 && !Double.isFinite(reading.outdoor())) {
            rule += " · beslissing bij start";
        }
        return name + " · " + temperatureLabel(options.temperature()) + "\n" + rule;
    }

    private static LocalDate localDate(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static String dashboardTemperature(double celsius) {
        if (!Double.isFinite(celsius)) return "onbekend";
        return String.format(NL, celsius == Math.rint(celsius) ? "%.0f °C" : "%.1f °C", celsius);
    }

    private void updateClimateIcon(ClimateIndicator.Reading reading, boolean active) {
        int icon = R.drawable.ic_ui_snow;
        int tint = ICE;
        String estimate = null;
        if (active) {
            switch (reading.mode()) {
                case HEATING -> {
                    icon = R.drawable.ic_ui_flame;
                    tint = 0xffffb747;
                    estimate = "Waarschijnlijk verwarmen";
                }
                case COOLING -> estimate = "Waarschijnlijk koelen";
                case UNKNOWN -> {
                    icon = R.drawable.ic_ui_fan;
                    tint = ACCENT;
                    estimate = "Verwarmen of koelen onbekend";
                }
            }
            if (reading.weather() && reading.mode() != ClimateIndicator.Mode.UNKNOWN) estimate += " · Open-Meteo";
        }
        if (shownClimateIcon != icon) {
            shownClimateIcon = icon;
            decorate(overall, icon, tint, 28);
        }
        if (!java.util.Objects.equals(overall.getTooltipText(), estimate)) overall.setTooltipText(estimate);
        String description = estimate == null ? overall.getText().toString() : overall.getText() + " · " + estimate;
        if (!description.contentEquals(overall.getContentDescription() == null ? "" : overall.getContentDescription())) {
            overall.setContentDescription(description);
        }
    }

    private void fitHistory(LinearLayout page, LinearLayout log) {
        if (section != 0 || historyScroll.getHeight() == 0) return;
        Rect position = new Rect();
        page.offsetDescendantRectToMyCoords(log, position);
        int available = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom()
            - position.top - historyScroll.getTop() - log.getPaddingBottom() - page.getPaddingBottom();
        int height = Math.max(dp(104), Math.min(dp(136), available));
        if (historyScroll.getLayoutParams().height == height) return;
        var size = historyScroll.getLayoutParams();
        size.height = height;
        historyScroll.setLayoutParams(size);
    }

    private void refreshHistory() {
        String records = prefs.getString("history", "[]");
        long now = System.currentTimeMillis();
        if (records.equals(shownHistory) && now < historyReloadAt) return;
        shownHistory = records;
        historyReloadAt = Long.MAX_VALUE;
        int first = historyScroll.getFirstVisiblePosition();
        View topRow = historyScroll.getChildAt(0);
        int top = topRow == null ? 0 : topRow.getTop();
        boolean scrolled = first > 0 || top < 0;
        var previous = historyAdapter.items;
        HistoryEntry anchor = scrolled && first < previous.size() ? previous.get(first) : null;

        var entries = new ArrayList<HistoryEntry>();
        long cutoff = now - Planner.HISTORY_AGE;
        try {
            var items = new JSONArray(records);
            for (int index = 0; index < items.length(); index++) {
                var item = items.optJSONObject(index);
                if (item == null) continue;
                long time = item.optLong("time", 0);
                if (time >= cutoff) entries.add(new HistoryEntry(time, item.optString("message"),
                    item.optLong("session"), item.optLong("end"), item.optBoolean("confirmed"), false,
                    item.optString("outcome"), item.optString("cause")));
            }
        } catch (JSONException ignored) {}
        if (entries.isEmpty()) {
            long last = prefs.getLong("statusTime", 0);
            if (last >= cutoff) entries.add(new HistoryEntry(last, prefs.getString("status", "")));
        }
        for (var entry : entries) {
            historyReloadAt = Math.min(historyReloadAt, entry.time() + Planner.HISTORY_AGE + 1);
        }
        if (entries.isEmpty()) entries.add(new HistoryEntry(0, "Nog geen meldingen"));

        var groups = new java.util.LinkedHashMap<Long, ArrayList<HistoryEntry>>();
        for (var entry : entries) {
            if (entry.session() > 0) groups.computeIfAbsent(entry.session(), key -> new ArrayList<>()).add(entry);
        }
        var visible = new ArrayList<HistoryEntry>();
        for (var entry : entries) {
            if (entry.session() == 0) {
                visible.add(entry);
                continue;
            }
            var group = groups.remove(entry.session());
            if (group == null) continue;
            int confirmed = 0;
            long end = entry.end();
            for (var event : group) {
                if (event.confirmed()) confirmed++;
                end = Math.max(end, event.end());
            }
            boolean expanded = expandedHistory.contains(entry.session());
            boolean crossesMidnight = !localDate(entry.session()).equals(localDate(end));
            String range = format(entry.session(), "HH:mm") + " – " + format(end, crossesMidnight ? "d MMM HH:mm" : "HH:mm");
            int planned = Math.max(confirmed, Schedule.cycleCount(entry.session(), end));
            String result = historyResult(group, confirmed, planned);
            visible.add(new HistoryEntry(entry.time(), range + "\n" + result,
                entry.session(), end, false, true, "", ""));
            if (expanded) visible.addAll(group);
        }
        entries = visible;
        historyAdapter.items = entries;
        historyAdapter.notifyDataSetChanged();
        int position = anchor == null ? 0 : entries.indexOf(anchor);
        if (position < 0 && anchor.header()) {
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                if (entry.header() && entry.session() == anchor.session()) {
                    position = index;
                    break;
                }
            }
        }
        historyScroll.setSelectionFromTop(position >= 0 ? position : Math.min(first, entries.size() - 1), scrolled ? top : 0);
    }

    private static String historyResult(java.util.List<HistoryEntry> group, int confirmed, int planned) {
        for (var event : group) {
            String type = event.outcome();
            String message = event.message();
            if ("UNLOCKED".equals(type) || message.contains("auto is ontgrendeld")) return "Afgerond na ontgrendelen";
            if ("STOPPED".equals(type) || message.equals("Lopend tijdvak geannuleerd.")) return "Handmatig beëindigd";
            if ("RULE".equals(type)) return message;
            if ("REFUSED".equals(type) || message.contains("heeft de klimaatstart geweigerd")) return "Start geweigerd door Kia";
            if ("UNCONFIRMED".equals(type) || message.equals("Geen bevestiging van de auto ontvangen."))
                return "Geen bevestiging van Kia";
            if (event.confirmed()) break;
        }
        if (planned == 1 && confirmed == 1) return "1 van 1 cyclus bevestigd";
        return confirmed + " van " + planned + " cyclussen bevestigd";
    }

    private record HistoryEntry(long time, String message, long session, long end, boolean confirmed, boolean header,
                                String outcome, String cause) {
        HistoryEntry(long time, String message) { this(time, message, 0, 0, false, false, "", ""); }
    }

    private final class HistoryAdapter extends BaseAdapter {
        private java.util.List<HistoryEntry> items = java.util.Collections.emptyList();

        @Override public int getCount() { return items.size(); }
        @Override public HistoryEntry getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
            var row = recycled instanceof TextView label ? label : text("", 15, INK, false);
            var entry = getItem(position);
            String value = entry.time() == 0 ? entry.message() : entry.header()
                ? format(entry.session(), "EEE d MMM") + "  " + entry.message()
                : format(entry.time(), "EEE d MMM · HH:mm") + " · " + entry.message();
            row.setTypeface(Typeface.DEFAULT, entry.header() ? Typeface.BOLD : Typeface.NORMAL);
            set(row, value);
            boolean expanded = entry.header() && expandedHistory.contains(entry.session());
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, entry.header()
                ? expanded ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float : 0, 0);
            row.setCompoundDrawablePadding(dp(12));
            if (Build.VERSION.SDK_INT >= 30) row.setStateDescription(entry.header()
                ? expanded ? "Uitgeklapt" : "Ingeklapt" : null);
            row.setTextColor(entry.time() == 0 ? MUTED : INK);
            row.setPadding(entry.session() > 0 && !entry.header() ? dp(12) : 0, dp(9), 0, dp(9));
            return row;
        }
    }

    private void unlinkAccount() {
        ClimateEnergy.resetLearning(this);
        prefs.edit().putBoolean("enabled", false).remove("kiaModel").remove("cloudNeedsLink")
            .remove("manualStart").remove("manualFirst").remove("manualEnd")
            .remove("externalClimateSeen").remove("externalClimateChecked").remove("externalRecheckTime")
            .remove("monitoringStart").remove("monitoredContinuation").remove("observedOffFor")
            .remove("kiaStartLimitedUntil").remove("confirmedManualStopAt").remove("activeRunEnd").remove("activeConfirmedCycles")
            .remove("onceStart").remove("onceEnd")
            .remove("battery").remove("batteryTime").remove("climateEnvironmentTime").remove("climateOutside")
            .remove("climateVehicleTarget").remove("climateTargetRequest").remove("climateRequestedTarget")
            .remove("climateLastOutdoorTime").remove("climateLastOutdoor")
            .remove("climateWeatherCell").remove("climateWeatherReadingCell").remove("climateWeatherDegrees")
            .remove("climateWeatherTime").apply();
        enabled.setChecked(false);
        BatterySampling.updateSchedule(this);
        Planner.schedule(this);
        refresh();
        KiaCloud.WORKER.execute(() -> {
            try {
                KiaSessionStore.clear(getApplicationContext());
                Planner.note(this, "Kia-account losgekoppeld.");
            } catch (Exception error) {
                Planner.note(this, "De opgeslagen Kia-sessie kon niet worden gewist.");
            }
        });
    }

    private static String clock(int minute) {
        return String.format(NL, "%02d:%02d", minute / 60, minute % 60);
    }

    private static String countdown(long millis) {
        long sec = Math.max(0, (millis + 999) / 1000);
        return sec >= 3600 ? String.format(NL, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
            : String.format(NL, "%02d:%02d", sec / 60, sec % 60);
    }

    private static String format(long millis, String pattern) {
        return DateTimeFormatter.ofPattern(pattern, NL).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis));
    }

    private static void set(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private LinearLayout disclosure(LinearLayout parent, String label) {
        Button toggle = addButton(parent, label + "  ▾", false, () -> {});
        LinearLayout body = column();
        body.setVisibility(View.GONE);
        parent.addView(body);
        toggle.setOnClickListener(view -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(label + (open ? "  ▴" : "  ▾"));
        });
        return body;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private FrameLayout withBackdrop(View content) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{OBSIDIAN, 0xff181a1d, 0xff000000}));
        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.obsidian_background);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0.32f);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(art, -1, -1);
        View shade = new View(this);
        shade.setBackgroundColor(0x6500101e);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(shade, -1, -1);
        View sheen = new View(this);
        sheen.setBackground(new GlassSheen(false));
        sheen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(sheen, -1, -1);
        frame.addView(content, -1, -1);
        return frame;
    }

    private LinearLayout card(LinearLayout page, String title) {
        return card(page, title, false);
    }

    private GradientDrawable glassSurface(boolean prominent, int radius) {
        int[] tint = prominent ? new int[]{0xf1234052, 0xfa0c2434, 0xf0071a2a}
            : new int[]{0xed244453, 0xe1132d3d, 0xe70c202f};
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR, tint);
        surface.setCornerRadius(dp(radius));
        surface.setStroke(dp(1), prominent ? 0xbd83d6ef : 0x839bc6db);
        return surface;
    }

    private LinearLayout card(LinearLayout page, String title, boolean prominent) {
        LinearLayout card = column();
        var surface = glassSurface(prominent, 18);
        if (prominent) {
            card.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{surface, new GlassSheen(true)}));
        } else {
            card.setBackground(surface);
        }
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        if (title != null) {
            boolean compact = title.equals("Tractieaccu") || title.equals("Volgende start");
            var heading = text(title, compact ? 16 : 21, INK, true);
            int icon = switch (title) {
                case "Tractieaccu" -> R.drawable.ic_ui_battery;
                case "Volgende start", "Weekplanning" -> R.drawable.ic_ui_calendar;
                case "Laatste meldingen" -> R.drawable.ic_ui_list;
                case "Standaardverwarming" -> R.drawable.ic_ui_heat;
                case "Meldingen" -> R.drawable.ic_ui_bell;
                case "Kia Connect" -> R.drawable.ic_ui_link;
                default -> 0;
            };
            if (icon != 0) decorate(heading, icon, ACCENT, compact ? 18 : 24);
            heading.setPadding(0, 0, 0, dp(4));
            card.addView(heading);
        }
        var layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(12);
        page.addView(card, layout);
        return card;
    }

    private Button addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        styleButton(button, primary, 12);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, -1, -2);
        return button;
    }

    private void styleSwitch(Switch toggle) {
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        var track = new GradientDrawable();
        track.setColor(0xffffffff);
        track.setSize(dp(38), dp(20));
        track.setCornerRadius(dp(10));
        toggle.setTrackDrawable(track);
        toggle.setSplitTrack(false);
        toggle.setTrackTintList(new ColorStateList(states, new int[]{0xff21bcd7, 0xff435f70}));
        toggle.setThumbTintList(new ColorStateList(states, new int[]{0xffeafcff, 0xffd5e0e7}));
        toggle.setSwitchMinWidth(dp(42));
        toggle.setSwitchPadding(dp(8));
        toggle.setTextColor(INK);
    }

    private void styleButton(Button button, boolean primary, int horizontalPadding) {
        styleButton(button, primary ? ACCENT : 0, horizontalPadding);
    }

    private void styleButton(Button button, int color, int horizontalPadding) {
        boolean primary = color != 0;
        GradientDrawable surface = primary ? new GradientDrawable() : new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x882b4758, 0x53132637});
        if (color == CORAL) {
            surface.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            surface.setColors(new int[]{0xffff9281, 0xfff55450});
        } else if (primary) {
            surface.setColor(color);
        }
        surface.setStroke(dp(1), color == CORAL ? 0xffffa99a : primary ? color : 0x739bbdcd);
        surface.setCornerRadius(dp(13));
        int ink = primary ? 0xff0b141d : INK;
        var ripple = new RippleDrawable(ColorStateList.valueOf(primary ? 0x22050a10 : 0x33e6e8ec), surface, null);
        button.setBackgroundTintList(null);
        button.setBackground(new InsetDrawable(ripple, 0, dp(3), 0, dp(3)));
        button.setPadding(dp(horizontalPadding), 0, dp(horizontalPadding), 0);
        button.setTextColor(new ColorStateList(
            new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{primary ? 0xff65707a : 0xffa3b4bd, ink}));
    }

    private void decorate(TextView label, int resource, int color, int size) {
        var icon = getDrawable(resource);
        icon.setTint(color);
        icon.setBounds(0, 0, dp(size), dp(size));
        label.setCompoundDrawablesRelative(icon, null, null, null);
        label.setCompoundDrawablePadding(dp(7));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}



