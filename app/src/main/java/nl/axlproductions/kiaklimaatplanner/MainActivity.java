package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

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
    private static final int INK = 0xfff2fbfc;
    private static final int MUTED = 0xffc9dee2;
    private static final int ACCENT = 0xffc4edf8;
    private static final int ICE = 0xff50d4eb;
    private static final int CORAL = 0xffff665d;
    private static final int OBSIDIAN = 0xff050505;
    private final String[] DAYS = {
        t(R.string.monday), t(R.string.tuesday), t(R.string.wednesday), t(R.string.thursday), t(R.string.friday), t(R.string.saturday), t(R.string.sunday)
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

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppLanguage.localized(base));
    }

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

        var language = card(sections[2], AppLanguage.t(R.string.app_language));
        addButton(language, AppLanguage.choices()[AppLanguage.selectedIndex()], false, this::chooseLanguage);

        var profiles = card(sections[2], t(R.string.climate_profiles));
        generalProfileSetting = addButton(profiles, "", false, this::chooseGeneralProfile);
        generalProfileSetting.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        profiles.addView(text(t(R.string.applies_to_starts_without_their_own), 13, MUTED, false));
        addButton(profiles, t(R.string.manage_profiles), false, this::manageProfiles);
        addButton(profiles, t(R.string.profiles_per_season), false, this::editSeasons);

        LinearLayout heat = card(sections[2], t(R.string.standard_heating));
        temperatureControl(heat);
        heatSwitch(heat, t(R.string.steering_wheel_heating), "heatWheel", R.drawable.ic_ui_wheel);
        heatSwitch(heat, t(R.string.mirrors_and_rear_window), "heatMirrors", R.drawable.ic_ui_rear);
        windshieldSetting = addButton(heat, t(R.string.windshield_defrost), false, this::editDefaultWindshield);
        windshieldSetting.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        decorate(windshieldSetting, R.drawable.ic_ui_defrost, ACCENT, 20);
        heat.addView(text(t(R.string.windshield_only_in_the_first_cycle), 13, MUTED, false));
        addClimateWeatherSetting(heat);
        var rules = card(sections[2], t(R.string.conditions_for_planned_takeoffs));
        addButton(rules, t(R.string.battery_limit_and_comfort_range), false, this::editStartRules);
        addButton(rules, t(R.string.set_home_location), false, this::saveHomeLocation);
        var security = card(sections[2], t(R.string.manual_operation));
        var authentication = heatingChoice(security, t(R.string.biometric_confirmation), prefs.getBoolean("requireBiometric", false));
        authentication.setOnCheckedChangeListener((view, checked) -> {
            if (checked && !biometricAvailable()) {
                view.setChecked(false);
                Toast.makeText(this, t(R.string.first_set_up_biometrics_on_your), Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("requireBiometric", checked).apply();
        });
        var reports = card(sections[2], t(R.string.overview_and_diagnosis));
        addButton(reports, t(R.string.open_monthly_overview), false, this::showMonth);
        addButton(reports, t(R.string.export_private_log), false, this::choosePrivateLog);
        var energyLog = card(sections[2], t(R.string.climate_consumption));
        addButton(energyLog, t(R.string.charts_and_overview), false, this::showEnergyReport);
        addButton(energyLog, t(R.string.open_consumption_log), false, this::showEnergyLog);
        var autoBattery = heatingChoice(energyLog, t(R.string.update_battery_every_hour), BatterySampling.enabled(this));
        autoBattery.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(BatterySampling.ENABLED, checked).apply();
            BatterySampling.updateSchedule(this);
        });
        var backup = card(sections[2], t(R.string.backup_weekly_planning));
        addButton(backup, t(R.string.export_schedule), false, () -> openBackup(true));
        addButton(backup, t(R.string.reset_planning), false, () -> openBackup(false));

        LinearLayout planning = card(sections[1], t(R.string.weekly_schedule));
        enabled = new Switch(this);
        enabled.setText(t(R.string.weekly_schedule_enabled));
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
        pauseButton = addButton(planning, t(R.string.pause_weekly_planning), false, this::pausePlanning);
        addButton(planning, t(R.string.next_seven_days), false, this::showAgenda);
        addButton(planning, t(R.string.check_next_start), false, this::showPreflight);
        for (int day = 0; day < DAYS.length; day++) addDay(planning, day);

        LinearLayout notifications = card(sections[2], t(R.string.notifications));
        buildSettings(card(sections[2], "Kia Connect"), notifications);

        LinearLayout log = card(sections[0], t(R.string.recent_notifications));
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
        cycleLabel = text(t(R.string.cycle_remaining), 15, MUTED, false);
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
        manualButton = addButton(dashboard, t(R.string.start_manually), true, this::manual);
        dashboard.addView(actions, -1, -2);
        stopButton = addButton(actions, t(R.string.stop), true, () -> {
            authenticate(t(R.string.stop_climate), () -> {
                if (Planner.cancelRunning(this)) KiaStartService.enqueueStop(this);
                refresh();
            });
        });
        styleButton(stopButton, CORAL, 15);
        decorate(stopButton, R.drawable.ic_ui_stop, 0xff181012, 25);
        onceButton = addButton(actions, t(R.string.plan_once), false, this::planOnce);
        decorate(onceButton, R.drawable.ic_ui_calendar, INK, 20);
        stopButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stopButton.setTextSize(18);
        stopButton.setMinHeight(dp(64));
        onceButton.setMinHeight(dp(64));
        onceButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        onceButton.setTextSize(16);
        arrangeActions(actions);
        extendAction = addButton(dashboard, t(R.string.extend_by_15_minutes), false, () -> authenticate(t(R.string.extend_period), () -> {
            if (!Planner.extend(this)) Toast.makeText(this, t(R.string.this_time_period_is_no_longer), Toast.LENGTH_SHORT).show();
            refresh();
        }));
        repeatAction = addButton(dashboard, t(R.string.repeat_previous_start), false, () -> manual(true, false));

        LinearLayout summary = new LinearLayout(this);
        if (stackControls()) summary.setOrientation(LinearLayout.VERTICAL);
        page.addView(summary, -1, -2);
        LinearLayout energy = card(addMetric(summary, 1), null);
        batteryTile = energy;
        energy.setOnClickListener(view -> requestBattery(true));
        energy.setForeground(new RippleDrawable(ColorStateList.valueOf(0x35c4edf8), null, glassSurface(false, 18)));
        energy.setFocusable(true);
        LinearLayout batteryDetails = summaryTile(energy, t(R.string.traction_battery), R.drawable.ic_ui_battery, ICE);
        battery = clockReadout(33);
        batteryDetails.addView(battery);
        batteryMeasured = text("", 13, MUTED, false);
        batteryDetails.addView(batteryMeasured);
        LinearLayout next = card(addMetric(summary, 1), null);
        LinearLayout nextDetails = summaryTile(next, t(R.string.next_start), R.drawable.ic_ui_calendar, ACCENT);
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
        changeNext = addButton(previewRow, t(R.string.change), false, this::nextStartOptions);
        changeNext.setContentDescription(t(R.string.change_next_start));
        changeNext.setMinHeight(dp(48));
        if (!stacked) {
            var actionSize = new LinearLayout.LayoutParams(-2, -2);
            actionSize.setMarginStart(dp(10));
            changeNext.setLayoutParams(actionSize);
        }
        preflightAction = addButton(nextPreviewCard, t(R.string.check_next_start), false, this::showPreflight);
        undoBar = card(page, null);
        adjustmentMessage = text("", 14, INK, true);
        adjustmentMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        undoBar.addView(adjustmentMessage);
        addButton(undoBar, t(R.string.undo), false, this::undoWeeklyChange);
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
        String[] labels = {"Dashboard", t(R.string.weekly_schedule), t(R.string.settings)};
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
        daySwitches[day] = editorSwitch(heading, DAYS[day] + t(R.string.active));
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
            windowSwitches[day][slot] = editorSwitch(row, DAYS[day] + t(R.string.period) + (slot + 1) + t(R.string.active));
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
        decorate(addButton(pane, t(R.string.copy_day), false, () -> copyDay(day)), R.drawable.ic_ui_copy, INK, 18);
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
                        times.add((slot.ready() ? t(R.string.ready_by) : "") + clock(slot.start()));
                    }
                    windowSwitches[day][index].setChecked(slot.enabled());
                    String range = slot.duration() == 0 ? t(R.string.not_set)
                        : slot.ready() ? t(R.string.ready_by) + clock(slot.start())
                        : clock(slot.start()) + " – " + endTime(slot.start(), slot.duration());
                    String heat = slot.profile() >= 0 ? " · " + ClimateProfiles.get(prefs, slot.profile()).name()
                        : slot.heating() == Planner.DEFAULT_HEAT ? "" : t(R.string.own_heating);
                    if (slot.homeOnly()) heat += t(R.string.home_alone);
                    Button button = windowButtons[day][index];
                    set(button, (slot.duration() == 0 ? t(R.string.time_slot) + (index + 1) + t(R.string.add) : range) + heat);
                    button.setContentDescription(t(R.string.time_slot) + (index + 1) + ", " + range + heat);
                    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        active && slot.enabled() ? slotAccent : null, null, null, null);
                    button.setCompoundDrawablePadding(dp(8));
                }
                boolean expanded = dayPanes[day].getVisibility() == View.VISIBLE;
                String status = !active ? t(R.string.not_active) : times.isEmpty() ? t(R.string.no_active_time_slots)
                    : expanded ? times.size() + (times.size() == 1 ? t(R.string.active_period) : t(R.string.active_time_slots))
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
                    dayHeaders[day].setStateDescription(expanded ? t(R.string.expanded) : t(R.string.collapsed));
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
                ? t(R.string.replaces_existing_time_slots) : "");
            days[index++] = day;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(DAYS[source] + t(R.string.copy_to))
            .setMultiChoiceItems(labels, selected, (window, position, checked) -> {
                selected[position] = checked;
                setCopyEnabled(((AlertDialog) window).getButton(AlertDialog.BUTTON_POSITIVE), hasSelection(selected));
            })
            .setNegativeButton(t(R.string.cancel), null)
            .setPositiveButton(t(R.string.replace_time_slots), null).create();
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
                    Toast.makeText(this, t(R.string.time_slots_copied), Toast.LENGTH_SHORT).show();
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
        Switch ready = heatingChoice(form, t(R.string.ready_at_the_chosen_time), current.ready());
        LinearLayout controls = editorRow(form);
        NumberPicker hour = timePicker(t(R.string.hour), 23, current.start() / 60);
        NumberPicker minute = timePicker(t(R.string.start_minute), 59, current.start() % 60);
        NumberPicker duration = durationPicker(current.duration(), true);
        duration.setContentDescription(t(R.string.duration_in_minutes));
        addPicker(controls, t(R.string.hour), hour);
        addPicker(controls, t(R.string.minute), minute);
        addPicker(controls, t(R.string.duration_min), duration);

        TextView summary = text("", 17, INK, true);
        summary.setPadding(0, dp(4), 0, 0);
        form.addView(summary);
        Runnable showEnd = () -> {
            int start = hour.getValue() * 60 + minute.getValue();
            int minutes = pickedDuration(duration);
            int begin = start - (ready.isChecked() ? minutes : 0);
            set(summary, t(R.string.start_at) + clock(Math.floorMod(begin, 1440))
                + (begin < 0 ? t(R.string.previous_day) : "") + " · " + cycleLabel(minutes)
                + t(R.string.end) + endTime(begin, minutes));
        };
        ready.setOnCheckedChangeListener((view, selected) -> showEnd.run());
        hour.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        minute.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        duration.setOnValueChangedListener((picker, oldValue, newValue) -> showEnd.run());
        showEnd.run();

        var profile = profilePicker(form, current.profile());
        var home = heatingChoice(form, t(R.string.only_start_when_the_car_is), current.homeOnly());
        if (!prefs.contains("homeLat")) form.addView(text(t(R.string.first_set_the_home_location_under), 13, MUTED, false));
        Switch useCustomHeat = heatingChoice(form, t(R.string.own_heating_options),
            current.heating() != Planner.DEFAULT_HEAT);
        TextView heatHint = text(t(R.string.used) + generalProfileName() + t(R.string.from_settings), 14, MUTED, false);
        form.addView(heatHint);
        LinearLayout choices = column();
        form.addView(choices);
        int savedHeat = current.heating() == Planner.DEFAULT_HEAT
            ? Planner.climateOptions(prefs, null, ZoneId.systemDefault()).heat() : current.heating();
        Switch wheel = heatingChoice(choices, t(R.string.steering_wheel_heating), (savedHeat & Planner.HEAT_WHEEL) != 0);
        Switch mirrors = heatingChoice(choices, t(R.string.mirror_and_rear_window_heating), (savedHeat & Planner.HEAT_MIRRORS) != 0);
        choices.addView(text(t(R.string.windshield_defrost), 14, MUTED, false));
        var windshield = windshieldChoices(choices, savedHeat);
        choices.addView(text(t(R.string.slim_uses_the_limit_from_standard), 13, MUTED, false));
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
            .setTitle(DAYS[day] + t(R.string.period_2) + (index + 1)).setView(formScroll)
            .setNegativeButton(t(R.string.cancel), null)
            .setPositiveButton(t(R.string.save), null).create();
        if (current.duration() > 0) {
            Button clear = addButton(form, t(R.string.clear_time_slot), false, () -> new AlertDialog.Builder(this)
                .setTitle(t(R.string.delete_time_slot))
                .setMessage(DAYS[day] + t(R.string.period_2) + (index + 1)
                    + t(R.string.the_saved_time_and_heating_options))
                .setNegativeButton(t(R.string.cancel), null)
                .setPositiveButton(t(R.string.clear), (confirmation, choice) -> {
                    int defaultStart = index == 0 ? 17 * 60 + 30 : 19 * 60;
                    if (saveWindow(day, index, new SlotPlan(defaultStart, 0, false, Planner.DEFAULT_HEAT), error)) {
                        dialog.dismiss();
                        Toast.makeText(this, t(R.string.time_slot_deleted), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, t(R.string.time_slot_saved), Toast.LENGTH_SHORT).show();
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
        picker.setFormatter(number -> String.format(AppLanguage.locale(), "%02d", number));
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
        return clock(end % 1440) + (end >= 1440 ? t(R.string.text_1_day) : "");
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
            String message = t(R.string.time_slots_overlap_adjust_the_start);
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
        var override = heatingChoice(form, t(R.string.adjust_temperature_before_this_start), savedTemperature >= Planner.MIN_TEMP_HALVES);
        var temperature = temperaturePicker(savedTemperature >= Planner.MIN_TEMP_HALVES ? savedTemperature
            : Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature());
        form.addView(temperature, -1, -2);
        form.addView(text(t(R.string.expensive), 15, MUTED, true));
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
            set(profileTemp, t(R.string.target_temperature) + temperatureLabel(override.isChecked() ? temperature.getValue() : degrees));
            var timing = Planner.previewManual(this, minutes, System.currentTimeMillis());
            set(preview, timing.firstConflict() > 0 ? t(R.string.overlaps_with) + format(timing.firstConflict(), "EEE HH:mm")
                + t(R.string.together_until) + format(timing.end(), "EEE HH:mm") : t(R.string.expected_ending) + format(timing.end(), "HH:mm"));
        };
        for (int index = 0; index < buttons.length; index++) {
            int minutes = (index + 1) * 15;
            var button = addButton(quick, minutes + t(R.string.min), false, () -> {
                picker.setValue(minutes / 15 - 1);
                picker.setVisibility(View.GONE);
                update.run();
            });
            if (!stackControls()) button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            buttons[index] = button;
        }
        var more = addButton(form, t(R.string.choose_a_different_duration), false, () -> {
            picker.setVisibility(picker.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            update.run();
        });
        more.setContentDescription(t(R.string.select_a_different_climate_duration));
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
        var dialog = new AlertDialog.Builder(this).setTitle(retry ? t(R.string.try_again) : t(R.string.start_manually)).setView(body)
            .setNegativeButton(t(R.string.cancel), null)
            .setPositiveButton(t(R.string.start), (window, option) -> {
                picker.clearFocus();
                temperature.clearFocus();
                int duration = pickedDuration(picker);
                int selected = profile.getSelectedItemPosition() - 1;
                int requested = override.isChecked() ? temperature.getValue() : -1;
                authenticate(t(R.string.start_climate), () -> {
                    if (!Planner.manual(this, duration, selected, requested))
                        Toast.makeText(this, t(R.string.check_kia_connect_and_exact_alarms), Toast.LENGTH_LONG).show();
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
        Switch ready = heatingChoice(form, t(R.string.ready_at_the_chosen_time), savedReady);
        Button date = addButton(form, "", false, () -> {});
        Button time = addButton(form, "", false, () -> {});
        var duration = durationPicker(editing ? (int) ((savedEnd - saved) / Schedule.MINUTE) : Schedule.CYCLE_MINUTES, false);
        TextView schedulePreview = text("", 16, INK, true);
        schedulePreview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Runnable showChoice = () -> {
            set(date, choice.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", AppLanguage.locale())));
            set(time, (ready.isChecked() ? t(R.string.ready_by_2) : t(R.string.start_time)) + " · "
                + choice.start.format(DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale())));
            int minutes = pickedDuration(duration);
            long start = onceStart(choice.start, zone, minutes, ready.isChecked());
            set(schedulePreview, t(R.string.go) + format(start, "EEE d MMM · HH:mm") + " · " + cycleLabel(minutes));
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
        duration.setContentDescription(t(R.string.duration_in_minutes));
        form.addView(text(t(R.string.duration_in_minutes), 15, MUTED, false));
        form.addView(duration, -1, -2);
        form.addView(schedulePreview);
        var profile = profilePicker(form, editing ? prefs.getInt("onceProfile", -1) : -1);
        var home = heatingChoice(form, t(R.string.only_start_when_the_car_is), editing && prefs.getBoolean("onceHomeOnly", false));
        if (!prefs.contains("homeLat")) form.addView(text(t(R.string.first_set_the_home_location_under), 13, MUTED, false));
        addButton(form, t(R.string.departure_time_from_agenda), false, () -> pickCalendar(selected -> {
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
        var builder = new AlertDialog.Builder(this).setTitle(t(R.string.plan_once)).setView(scroll)
            .setNegativeButton(t(R.string.close), null).setPositiveButton(t(R.string.schedule), null);
        if (editing) builder.setNeutralButton(t(R.string.clear_schedule), (dialog, button) -> {
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
                set(error, start <= System.currentTimeMillis() ? t(R.string.choose_a_date_and_time_in)
                    : t(R.string.scheduling_was_not_possible_check_the));
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
        accountButton = addButton(accountDetails, t(R.string.link_kia_account), true,
            () -> openFromPlanner(new Intent(this, KiaAccountActivity.class)));
        unlink = addButton(accountDetails, t(R.string.disconnect_account), false, () -> new AlertDialog.Builder(this)
            .setTitle(t(R.string.disconnect_kia_account))
            .setMessage(t(R.string.the_schedule_is_disabled_and_the))
            .setNegativeButton(t(R.string.cancel), null)
            .setPositiveButton(t(R.string.disconnect), (dialog, choice) -> unlinkAccount())
            .show());
        access = text("", 14, MUTED, false);
        accountDetails.addView(access);
        alertToggle(notifications, t(R.string.end_of_period), "notifyWindowEnd");
        alertToggle(notifications, t(R.string.kia_request_limit), "notifyStartLimit");
        var late = heatingChoice(notifications, t(R.string.delay_at_ready_at), prefs.getBoolean("notifyDepartureDelay", false));
        late.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("notifyDepartureDelay", checked).apply());
        alertToggle(notifications, t(R.string.scheduled_start_failed), "notifyStartFailure");
        var quiet = heatingChoice(notifications, t(R.string.silent_notification_mode), prefs.getBoolean("quietAlerts", false));
        quiet.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("quietAlerts", checked).apply());
        notifications.addView(text(t(R.string.only_pop_ups_in_case_of), 13, MUTED, false));
        addButton(settings, t(R.string.connection_check), false, this::checkConnection);
        alertPermission = addButton(notifications, t(R.string.allow_android_notifications), false, this::allowAlerts);
        alarmPermission = addButton(accountDetails, t(R.string.allow_exact_alarms), false, () -> {
            if (Build.VERSION.SDK_INT >= 31) openFromPlanner(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName())));
        });
    }

    private void chooseLanguage() {
        new AlertDialog.Builder(this).setTitle(AppLanguage.t(R.string.choose_language))
            .setSingleChoiceItems(AppLanguage.choices(), AppLanguage.selectedIndex(), (dialog, index) -> {
                dialog.dismiss();
                if (AppLanguage.select(this, index)) {
                    ClimateWidget.refresh(this);
                    if (Build.VERSION.SDK_INT < 33) recreate();
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addClimateWeatherSetting(LinearLayout panel) {
        divider(panel);
        var toggle = heatingChoice(panel, t(R.string.use_weather_for_climate_icon),
            prefs.getBoolean(ClimateIndicator.WEATHER_ENABLED, true));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(ClimateIndicator.WEATHER_ENABLED, checked).apply();
            refresh();
            if (checked && !batteryBusy) {
                lastBatteryRequest = 0;
                requestBattery(false);
            }
        });
        var source = text(t(R.string.open_meteo_uses_the_last_known), 13, MUTED, false);
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
        var label = text(t(R.string.temperature), 16, INK, false);
        decorate(label, R.drawable.ic_ui_temp, ACCENT, 20);
        control.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        var value = text("", 18, INK, true);
        control.addView(value, -2, -2);
        Runnable updateTemperature = () -> {
            String degrees = temperatureLabel(Planner.temperatureHalves(prefs));
            set(value, degrees + "  ›");
            control.setContentDescription(t(R.string.temperature_2) + degrees + t(R.string.change_2));
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
            picker.setContentDescription(t(R.string.temperature_in_degrees_celsius));
            var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.desired_temperature)).setView(picker)
                .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), (window, choice) -> {
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
        return String.format(AppLanguage.locale(), halves % 2 == 0 ? "%.0f °C" : "%.1f °C", halves / 2.0);
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
        names[0] = t(R.string.general) + (active < 0 ? t(R.string.standard_heating) : ClimateProfiles.get(prefs, active).name());
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            names[index + 1] = ClimateProfiles.get(prefs, index).name();
        }
        return choicePicker(form, t(R.string.climate_profile), names, selected + 1);
    }

    private android.widget.RadioGroup windshieldChoices(LinearLayout form, int heat) {
        var group = new android.widget.RadioGroup(this);
        String[] options = {t(R.string.off), t(R.string.always_on_first_cycle), t(R.string.smart_in_the_cold)};
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
            case 1 -> t(R.string.always_on_first_cycle);
            case 2 -> t(R.string.smart) + threshold + " °C";
            default -> t(R.string.off);
        };
    }

    private static String cycleLabel(int minutes) {
        int count = Schedule.cycleCount(0, minutes * Schedule.MINUTE);
        return count == 1 ? t(R.string.text_1_cycle_planned) : count + t(R.string.cycles_planned);
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
        threshold.setContentDescription(t(R.string.enable_at_or_below));
        threshold.setWrapSelectorWheel(false);
        var box = column();
        form.addView(box);
        box.addView(text(t(R.string.enable_at_or_below), 14, MUTED, false));
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
        new AlertDialog.Builder(this).setTitle(t(R.string.windshield_defrost)).setView(form)
            .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), (dialog, which) -> {
                threshold.clearFocus();
                prefs.edit().putInt("windshieldMode", selectedWindshield(mode))
                    .putInt("windshieldThreshold", threshold.getValue() - 15).apply();
                refresh();
                Toast.makeText(this, t(R.string.windshield_position_saved), Toast.LENGTH_SHORT).show();
            }).show();
    }

    private String generalProfileName() {
        int index = ClimateProfiles.general(prefs);
        return index < 0 ? t(R.string.standard_heating) : ClimateProfiles.get(prefs, index).name();
    }

    private void chooseGeneralProfile() {
        String[] names = new String[ClimateProfiles.COUNT + 1];
        names[0] = t(R.string.standard_heating);
        for (int index = 0; index < ClimateProfiles.COUNT; index++) {
            var profile = ClimateProfiles.get(prefs, index);
            names[index + 1] = profile.name() + " · " + temperatureLabel(profile.temperature());
        }
        new AlertDialog.Builder(this).setTitle(t(R.string.general_climate_profile))
            .setSingleChoiceItems(names, ClimateProfiles.general(prefs) + 1, (dialog, position) -> {
                prefs.edit().putInt(ClimateProfiles.GENERAL, position - 1).apply();
                ClimateWidget.refresh(this);
                dialog.dismiss();
                refresh();
            }).setNegativeButton(t(R.string.cancel), null).show();
    }

    private void manageProfiles() {
        String[] names = new String[ClimateProfiles.COUNT];
        for (int index = 0; index < names.length; index++) {
            var profile = ClimateProfiles.get(prefs, index);
            names[index] = profile.name() + " · " + temperatureLabel(profile.temperature());
        }
        new AlertDialog.Builder(this).setTitle(t(R.string.climate_profiles)).setItems(names, (dialog, index) -> editProfile(index))
            .setNegativeButton(t(R.string.close), null).show();
    }

    private void editProfile(int index) {
        var saved = ClimateProfiles.get(prefs, index);
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(12));
        var name = new android.widget.EditText(this);
        name.setHint(t(R.string.profile_name));
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(30)});
        name.setText(saved.name());
        form.addView(name, -1, -2);
        var temperature = new NumberPicker(this);
        temperature.setMinValue(Planner.MIN_TEMP_HALVES);
        temperature.setMaxValue(Planner.MAX_TEMP_HALVES);
        temperature.setFormatter(MainActivity::temperatureLabel);
        temperature.setValue(saved.temperature());
        temperature.setContentDescription(t(R.string.target_temperature_2));
        form.addView(text(t(R.string.target_temperature_2), 14, MUTED, false));
        form.addView(temperature, -1, -2);
        var wheel = heatingChoice(form, t(R.string.steering_wheel_heating), (saved.heat() & Planner.HEAT_WHEEL) != 0);
        var mirrors = heatingChoice(form, t(R.string.mirrors_and_rear_window), (saved.heat() & Planner.HEAT_MIRRORS) != 0);
        form.addView(text(t(R.string.windshield_defrost), 14, MUTED, false));
        var mode = windshieldChoices(form, saved.heat());
        var threshold = thresholdPicker(form, saved.threshold(), mode);
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.edit_profile)).setView(body)
            .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) {
                name.setError(t(R.string.enter_a_profile_name));
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
        if (t(R.string.one_time_start).equals(Planner.startType(prefs, slot))) {
            planOnce();
            return;
        }
        if (!Planner.upcomingWeekly(prefs, slot, System.currentTimeMillis())) return;
        new AlertDialog.Builder(this).setTitle(format(slot.session(), "EEEE d MMM · HH:mm"))
            .setItems(new String[]{t(R.string.skip_this_start), t(R.string.this_starts_moving)}, (dialog, option) -> {
                if (option == 1) {
                    var zone = ZoneId.systemDefault();
                    var selected = Instant.ofEpochMilli(slot.session()).atZone(zone).toLocalDateTime();
                    pickDateTime(selected, t(R.string.new_start_time), start -> applyWeeklyChange(slot, start));
                } else {
                    applyWeeklyChange(slot, 0);
                }
            }).setNegativeButton(t(R.string.close), null).show();
    }

    private void applyWeeklyChange(Schedule.Slot slot, long start) {
        var change = Planner.adjustWeekly(this, slot, start);
        if (change == null) {
            Toast.makeText(this, t(R.string.choose_a_free_future_start_time),
                Toast.LENGTH_LONG).show();
        } else {
            undoChange = change;
            var accessibility = getSystemService(android.view.accessibility.AccessibilityManager.class);
            int timeout = Build.VERSION.SDK_INT >= 29 ? accessibility.getRecommendedTimeoutMillis(12_000,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
                | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT) : 12_000;
            undoUntil = android.os.SystemClock.elapsedRealtime() + timeout;
            set(adjustmentMessage, start == 0 ? t(R.string.start_skipped) : t(R.string.start_moved_to)
                + format(start, "EEE d MMM HH:mm"));
        }
        refresh();
    }

    private void undoWeeklyChange() {
        var change = undoChange;
        undoChange = null;
        if (change != null && !Planner.undoWeekly(this, change)) {
            Toast.makeText(this, t(R.string.this_change_cannot_be_undone), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void pausePlanning() {
        boolean paused = prefs.getLong(WeeklyAdjustment.PAUSE, 0) > System.currentTimeMillis();
        String[] options = paused
            ? new String[]{t(R.string.resume_now), t(R.string.take_a_break_until_tomorrow), t(R.string.select_date_and_time)}
            : new String[]{t(R.string.see_you_tomorrow), t(R.string.select_date_and_time)};
        var zone = ZoneId.systemDefault();
        var tomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay();
        new AlertDialog.Builder(this).setTitle(t(R.string.pause_weekly_planning)).setItems(options, (dialog, index) -> {
            int option = paused ? index : index + 1;
            if (option == 2) {
                pickDateTime(tomorrow, t(R.string.resume_at), until -> {
                    if (until > System.currentTimeMillis()) {
                        Planner.pauseWeekly(this, until);
                        refresh();
                    } else {
                        Toast.makeText(this, t(R.string.choose_a_time_in_the_future), Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            Planner.pauseWeekly(this, option == 0 ? 0 : tomorrow.atZone(zone).toInstant().toEpochMilli());
            refresh();
        }).setNegativeButton(t(R.string.close), null).show();
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
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.connection_check))
            .setMessage(t(R.string.checking_kia_and_android)).setPositiveButton(t(R.string.close), null).show();
        KiaCloud.WORKER.execute(() -> {
            String result = KiaCloud.connectionCheck(getApplicationContext());
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                dialog.setMessage(result + t(R.string.exact_alarms) + (Planner.hasAlarmAccess(this) ? t(R.string.allowed) : t(R.string.permission_required))
                    + t(R.string.android_notifications) + (WindowAlerts.permitted(this) ? t(R.string.allowed) : t(R.string.disabled)));
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
        String[] labels = {t(R.string.day), t(R.string.week), t(R.string.month)};
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
            set(dateButton, anchor[0].format(DateTimeFormatter.ofPattern("d MMM yyyy", AppLanguage.locale())));
            next.setEnabled(anchor[0].isBefore(LocalDate.now(zone)));
            if (loaded[0]) render.run();
        };
        var previous = addButton(dates, "‹", false, () -> {
            anchor[0] = shiftEnergyDate(anchor[0], period[0], -1);
            redraw.run();
        });
        previous.setContentDescription(t(R.string.previous_period));
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
        next.setContentDescription(t(R.string.next_period));
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
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.battery_and_climate_consumption)).setView(body)
            .setNegativeButton(t(R.string.close), null).create();
        lines.addView(text(t(R.string.loading_overview), 14, MUTED, false));
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
                        lines.addView(text(t(R.string.usage_data_could_not_be_opened), 14, MUTED, false));
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
        String range = period == 0 ? first.format(DateTimeFormatter.ofPattern("EEEE d MMMM", AppLanguage.locale()))
            : period == 1 ? first.format(DateTimeFormatter.ofPattern("d MMM", AppLanguage.locale())) + " – "
                + end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", AppLanguage.locale()))
                : first.format(DateTimeFormatter.ofPattern("MMMM yyyy", AppLanguage.locale()));
        var heading = text(range, 16, INK, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        page.addView(heading);
        page.addView(text(t(R.string.estimated_climate_consumption) + EnergyChart.format(energy) + " kWh · " + minutes(duration), 15, ACCENT, true));
        if (period != 0) {
            var today = LocalDate.now(zone);
            for (var day : days) {
                if (day.date().isAfter(today)) break;
                var row = editorRow(page);
                var date = text(day.date().format(DateTimeFormatter.ofPattern("EEE d MMM", AppLanguage.locale())), 14, MUTED, false);
                row.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
                var amount = text(EnergyChart.format(day.energy()) + " kWh", 14, INK, true);
                row.addView(amount);
                row.setPadding(0, dp(7), 0, dp(7));
                divider(page);
            }
            return;
        }
        var battery = history.batteryChart(anchor, zone);
        page.addView(text(t(R.string.traction_battery_2), 16, INK, true));
        if (battery.isEmpty()) {
            page.addView(text(t(R.string.no_kia_battery_measurement_on_this), 14, MUTED, false));
        } else {
            var firstPoint = battery.get(0);
            var lastPoint = battery.get(battery.size() - 1);
            page.addView(text(dashboardPercentage(firstPoint.value()) + t(R.string.om) + format(firstPoint.time(), "HH:mm")
                + (battery.size() > 1 ? " → " + dashboardPercentage(lastPoint.value()) + t(R.string.om) + format(lastPoint.time(), "HH:mm")
                : t(R.string.one_measurement)), 14, MUTED, false));
            page.addView(new EnergyChart(this, battery, anchor, zone, true), -1, dp(152));
        }
        page.addView(text(BatterySampling.enabled(this) ? t(R.string.measuring_points_for_kia_status_updates)
            : t(R.string.measuring_points_for_a_kia_status), 13, MUTED, false));
        page.addView(text(t(R.string.cumulative_climate_consumption_kwh), 16, INK, true));
        if (duration == 0) page.addView(text(t(R.string.no_recorded_climate_cycles), 14, MUTED, false));
        page.addView(new EnergyChart(this, history.cumulativeChart(anchor, zone, System.currentTimeMillis()), anchor, zone, false),
            -1, dp(152));
    }

    private void showEnergyLog() {
        ClimateEnergy.expire(this, System.currentTimeMillis());
        var average = ClimateEnergy.average(this);
        var log = ClimateEnergy.log(this);
        var header = column();
        header.setPadding(dp(16), dp(4), dp(16), dp(10));
        String method = average.learned() ? t(R.string.average_from_your_measurements) : t(R.string.provisional_calculation_value);
        header.addView(text(method + " · " + decimal(average.kilowatts()) + " kW", 16, INK, true));
        String samples = average.samples() == 1 ? t(R.string.text_1_suitable_cycle) : average.samples() + t(R.string.suitable_cycles);
        header.addView(text(samples + " · " + minutes(average.duration()), 14, MUTED, false));
        header.addView(text(t(R.string.calibration_uses_recent_kia_measurements_fro)
            + t(R.string.with_an_uncertain_end_time_running), 13, MUTED, false));
        var rows = new ArrayList<String>();
        for (int index = 0; index < log.length(); index++) {
            var record = log.optJSONObject(index);
            if (record == null) continue;
            String reason = switch (record.optString("reason")) {
                case "STATUS_OFF" -> t(R.string.received_off_status_from_kia);
                case "MANUAL" -> t(R.string.stop_confirmed_by_kia);
                case "UNLOCKED" -> t(R.string.terminated_after_unlocking);
                default -> t(R.string.end_time_estimated);
            };
            double estimated = record.optDouble("estimate");
            String value = format(record.optLong("start"), "EEE d MMM · HH:mm") + " · " + minutes(record.optLong("elapsed"))
                + t(R.string.estimated) + decimal(estimated) + t(R.string.kwh_approx) + decimal(estimated / ClimateEnergy.BATTERY_KWH * 100)
                + t(R.string.of_64_kwh) + decimal(record.optDouble("power"))
                + " kW (" + (record.optBoolean("learned") ? t(R.string.own_average) : t(R.string.calculation_value)) + ")"
                + "\n" + reason + (record.optBoolean("eligible") ? t(R.string.counts_towards_calibration) : "");
            if (record.has("before") && record.has("after")) {
                value += t(R.string.battery) + dashboardPercentage(record.optDouble("before")) + " → "
                    + dashboardPercentage(record.optDouble("after"));
            }
            rows.add(value);
        }
        if (rows.isEmpty()) rows.add(t(R.string.your_first_confirmed_climate_cycle_appears));
        var list = new ListView(this);
        list.addHeaderView(header, null, false);
        list.setDivider(new ColorDrawable(0x5482afc5));
        list.setDividerHeight(dp(1));
        list.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.consumption_log)).setView(list)
            .setNegativeButton(t(R.string.close), null).show();
        dialog.getWindow().setLayout(-1, getResources().getDisplayMetrics().heightPixels * 3 / 4);
    }

    private static String minutes(long milliseconds) {
        long minutes = Math.round(milliseconds / (double) Schedule.MINUTE);
        return minutes + t(R.string.min);
    }

    private static String decimal(double number) {
        return String.format(AppLanguage.locale(), "%.1f", number);
    }

    private static String dashboardPercentage(double number) {
        return String.format(AppLanguage.locale(), number == Math.rint(number) ? "%.0f%%" : "%.1f%%", number);
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
        limits.add(t(R.string.off));
        for (int percent = 5; percent <= 100; percent += 5) limits.add(percent + "%");
        var minimum = choicePicker(form, t(R.string.minimum_battery_when_disconnected),
            limits.toArray(new String[0]), prefs.getInt("minimumUnpluggedBattery", 0) / 5);
        var mild = heatingChoice(form, t(R.string.skip_in_mild_weather), prefs.getBoolean("skipMild", false));
        var range = editorRow(form);
        var low = boundedPicker(-10, 40, prefs.getInt("mildMin", 18), value -> value + " °C");
        var high = boundedPicker(-10, 40, prefs.getInt("mildMax", 22), value -> value + " °C");
        addPicker(range, t(R.string.from), low);
        addPicker(range, t(R.string.until), high);
        range.setVisibility(mild.isChecked() ? View.VISIBLE : View.GONE);
        mild.setOnCheckedChangeListener((view, checked) -> range.setVisibility(checked ? View.VISIBLE : View.GONE));
        var validation = text("", 14, CORAL, true);
        form.addView(validation);
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.battery_limit_and_comfort_range)).setView(body)
            .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            low.clearFocus();
            high.clearFocus();
            if (low.getValue() > high.getValue()) {
                set(validation, t(R.string.the_first_temperature_must_be_the));
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
        String saved = !prefs.contains("homeLat") ? t(R.string.no_home_location_has_been_saved)
            : t(R.string.home_location_saved) + ("phone".equals(current) ? t(R.string.via_your_phone) : t(R.string.through_kia));
        var form = paddedForm();
        form.addView(text(saved, 15, INK, true));
        form.addView(text(t(R.string.stand_at_home_when_you_save)
            + t(R.string.for_scheduled_starts_the_app_compares), 14, MUTED, false));
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.set_home_location)).setView(form)
            .setNegativeButton(t(R.string.close), null).create();
        addButton(form, t(R.string.use_phone_location), true, () -> {
            dialog.dismiss();
            requestPhonePosition();
        });
        addButton(form, t(R.string.use_kia_car_location), false, () -> {
            dialog.dismiss();
            readKiaHomePosition();
        });
        if (prefs.contains("homeLat")) {
            addButton(form, t(R.string.clear_home_location), false, () -> new AlertDialog.Builder(this)
                .setTitle(t(R.string.delete_home_location))
                .setMessage(t(R.string.home_only_time_slots_will_be))
                .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.clear), (child, option) -> {
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
            new AlertDialog.Builder(this).setTitle(t(R.string.location_is_off))
                .setMessage(t(R.string.turn_on_location_on_your_samsung))
                .setNegativeButton(t(R.string.close), null).setPositiveButton(t(R.string.location_settings), (dialog, option) ->
                    openFromPlanner(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
            return;
        }
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.find_phone_location))
            .setMessage(t(R.string.wait_for_an_accurate_location)).setNegativeButton(t(R.string.cancel), null).show();
        pendingGpsDialog = dialog;
        var lookup = new PhonePosition(manager, handler, fix -> {
            if (!dialog.isShowing() || isDestroyed()) return;
            dialog.dismiss();
            if (fix == null) {
                new AlertDialog.Builder(this).setTitle(t(R.string.location_not_yet_accurate_enough))
                    .setMessage(t(R.string.try_near_a_window_or_outside))
                    .setNegativeButton(t(R.string.close), null).setPositiveButton(t(R.string.search_again), (window, option) ->
                        requestPhonePosition()).show();
                return;
            }
            confirmHomePosition(fix.getLatitude(), fix.getLongitude(), "phone",
                t(R.string.phone_location_found_accurate_to_approx) + Math.round(fix.getAccuracy()) + t(R.string.meters));
        });
        dialog.setOnDismissListener(ignored -> {
            lookup.cancel();
            if (pendingGpsDialog == dialog) pendingGpsDialog = null;
        });
        lookup.start();
    }

    private void readKiaHomePosition() {
        if (showKiaPause()) return;
        var wait = new AlertDialog.Builder(this).setTitle(t(R.string.home_location))
            .setMessage(t(R.string.pick_up_car_location)).setNegativeButton(t(R.string.cancel), null).show();
        KiaCloud.WORKER.execute(() -> {
            var location = KiaCloud.readHomePosition(getApplicationContext());
            runOnUiThread(() -> {
                if (!wait.isShowing() || isDestroyed()) return;
                wait.dismiss();
                if (location == null) {
                    new AlertDialog.Builder(this).setTitle(t(R.string.no_recent_kia_car_location))
                        .setMessage(t(R.string.you_can_save_your_home_location))
                        .setNegativeButton(t(R.string.close), null).setPositiveButton(t(R.string.use_phone_location), (dialog, option) ->
                            requestPhonePosition()).show();
                } else {
                    confirmHomePosition(location[0], location[1], "kia", t(R.string.kia_has_returned_a_recent_car));
                }
            });
        });
    }

    private void confirmHomePosition(double latitude, double longitude, String source, String hint) {
        new AlertDialog.Builder(this).setTitle(t(R.string.save_as_home_location))
            .setMessage(hint + t(R.string.the_home_location_is_stored_on))
            .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), (dialog, option) -> {
                prefs.edit().putLong("homeLat", Double.doubleToLongBits(latitude))
                    .putLong("homeLon", Double.doubleToLongBits(longitude)).putString("homeSource", source).apply();
                Toast.makeText(this, t(R.string.home_location_saved_2), Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void editSeasons() {
        var form = paddedForm();
        var active = heatingChoice(form, t(R.string.automatically_switch_by_date), prefs.getBoolean("seasonalProfile", false));
        String[] names = new String[ClimateProfiles.COUNT];
        for (int index = 0; index < names.length; index++) names[index] = ClimateProfiles.get(prefs, index).name();
        int[] dates = {prefs.getInt("summerDate", 401), prefs.getInt("winterDate", 1001)};
        var summer = choicePicker(form, t(R.string.profile_from_the_first_date), names, prefs.getInt("summerProfile", 1));
        var fromSummer = addButton(form, monthDayLabel(dates[0]), false, () -> {});
        var winter = choicePicker(form, t(R.string.profile_from_the_second_date), names, prefs.getInt("winterProfile", 0));
        var fromWinter = addButton(form, monthDayLabel(dates[1]), false, () -> {});
        fromSummer.setOnClickListener(view -> chooseMonthDay(dates[0], selected -> {
            dates[0] = selected;
            set(fromSummer, monthDayLabel(selected));
        }));
        fromWinter.setOnClickListener(view -> chooseMonthDay(dates[1], selected -> {
            dates[1] = selected;
            set(fromWinter, monthDayLabel(selected));
        }));
        form.addView(text(t(R.string.starts_with_their_own_profile_retain), 13, MUTED, false));
        var body = new ScrollView(this);
        body.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.profiles_per_season)).setView(body)
            .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.save), null).create();
        dialog.setOnShowListener(ignored -> styleDialogAction(dialog).setOnClickListener(view -> {
            if (active.isChecked() && dates[0] == dates[1]) {
                Toast.makeText(this, t(R.string.choose_two_different_changeover_dates), Toast.LENGTH_LONG).show();
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
        return LocalDate.of(2000, code / 100, code % 100).format(DateTimeFormatter.ofPattern("d MMMM", AppLanguage.locale()));
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
            Toast.makeText(this, t(R.string.biometrics_are_not_available_check_your), Toast.LENGTH_LONG).show();
            return;
        }
        new android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(t(R.string.cancel), getMainExecutor(), (dialog, which) -> {})
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
        var wait = new AlertDialog.Builder(this).setTitle(t(R.string.departure_time_from_agenda))
            .setMessage(t(R.string.pick_up_appointments)).setNegativeButton(t(R.string.cancel), null).show();
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
                    labels.add(format(start, "EEE d MMM HH:mm") + " · " + (records.isNull(1) ? t(R.string.appointment) : records.getString(1)));
                }
            } catch (RuntimeException ignored) {
                // The calendar provider or permission can disappear while it opens.
            }
            runOnUiThread(() -> {
                if (!wait.isShowing() || isDestroyed()) return;
                wait.dismiss();
                if (labels.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle(t(R.string.departure_time_from_agenda))
                        .setMessage(t(R.string.no_visible_appointments_with_a_start))
                        .setPositiveButton(t(R.string.close), null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle(t(R.string.choose_an_appointment))
                        .setItems(labels.toArray(new String[0]), (dialog, index) -> selected.accept(times.get(index)))
                        .setNegativeButton(t(R.string.cancel), null).show();
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
                new AlertDialog.Builder(this).setTitle(t(R.string.accurate_location_needed))
                    .setMessage(t(R.string.choose_precise_location_access_to_save))
                    .setNegativeButton(t(R.string.close), null).setPositiveButton(t(R.string.app_settings), (dialog, option) ->
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
            Toast.makeText(this, t(R.string.calendar_access_is_required_to_choose), Toast.LENGTH_LONG).show();
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
            if (row.original() > 0) label += t(R.string.wash) + format(row.original(), "EEE d MMM HH:mm");
            labels.add(label);
        }
        var dialog = new AlertDialog.Builder(this).setTitle(t(R.string.next_seven_days)).setPositiveButton(t(R.string.close), null);
        if (labels.isEmpty()) dialog.setMessage(t(R.string.no_scheduled_starts_during_this_period));
        else dialog.setItems(labels.toArray(new String[0]), null);
        dialog.show();
    }

    private void showPreflight() {
        var slot = displayedNext;
        if (slot == null) slot = Planner.next(this);
        var status = new StringBuilder();
        if (slot != null) status.append(t(R.string.planned)).append(format(slot.time(), "EEE d MMM HH:mm")).append('\n');
        status.append(KiaCloud.linked(this) && !KiaCloud.needsLink(this) ? t(R.string.kia_connect_connected) : t(R.string.connect_kia_connect));
        status.append(Planner.hasAlarmAccess(this) ? t(R.string.exact_alarms_allowed) : t(R.string.exact_alarms_are_disabled));
        long measured = Math.max(prefs.getLong("batteryTime", 0), prefs.getLong("lastClimateSample", 0));
        status.append(measured > 0 ? t(R.string.latest_car_status) + format(measured, "d MMM HH:mm") : t(R.string.no_car_status_known_yet));
        if (slot != null && Planner.homeOnly(prefs, slot, ZoneId.systemDefault())) {
            status.append(prefs.contains("homeLat") ? t(R.string.home_zone_is_checked_at_the) : t(R.string.first_set_your_home_location));
        }
        new AlertDialog.Builder(this).setTitle(t(R.string.check_next_start)).setMessage(status.toString())
            .setPositiveButton(t(R.string.close), null).setNeutralButton(t(R.string.check_connection), (dialog, which) -> checkConnection())
            .show();
    }

    private void showFailureActions(String cause) {
        String recovery = switch (cause) {
            case "AUTH" -> t(R.string.reconnect_kia);
            case "ALARM" -> t(R.string.allow_alarms);
            default -> t(R.string.check_connection);
        };
        new AlertDialog.Builder(this).setTitle(t(R.string.what_do_you_want_to_do))
            .setPositiveButton(recovery, (dialog, option) -> recoverFailure(cause))
            .setNegativeButton(t(R.string.close), null).show();
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
        String message = t(R.string.confirmed) + window.confirmed() + t(R.string.skipped) + window.skipped() + t(R.string.failed)
            + window.failed() + (window.common().isEmpty() ? "" : t(R.string.most_common_cause)
                + window.common() + " (" + window.commonCount() + ")");
        double usage = 0;
        var energy = ClimateEnergy.log(this);
        for (int index = 0; index < energy.length(); index++) {
            var entry = energy.optJSONObject(index);
            if (entry != null && entry.optLong("start") > 0 && month.equals(StartLedger.month(entry.optLong("start"))))
                usage += entry.optDouble("estimate", 0);
        }
        message += t(R.string.estimated_climate_consumption_2) + decimal(usage) + " kWh";
        var dialog = new AlertDialog.Builder(this).setTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", AppLanguage.locale())))
            .setMessage(message).setPositiveButton(t(R.string.close), null);
        if (month.isAfter(java.time.YearMonth.now().minusMonths(12)))
            dialog.setNegativeButton(t(R.string.last_month), (windowDialog, choice) -> showMonth(month.minusMonths(1)));
        if (month.isBefore(java.time.YearMonth.now()))
            dialog.setNeutralButton(t(R.string.next_month), (windowDialog, choice) -> showMonth(month.plusMonths(1)));
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
                Toast.makeText(this, t(R.string.there_are_no_time_slots_for), Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[dates.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = format(dates.get(i), "EEE d MMM · HH:mm");
            new AlertDialog.Builder(this).setTitle(t(R.string.private_log_choose_a_time_period))
                .setItems(labels, (window, index) -> {
                    try {
                        pendingDiagnostic = StartLedger.safeExport(this, history, dates.get(index));
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "kia-klimaat-tijdvak.json"), EXPORT_DIAGNOSTIC);
                    } catch (JSONException error) {
                        Toast.makeText(this, t(R.string.failed_to_create_private_log), Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton(t(R.string.cancel), null).show();
        } catch (JSONException error) {
            Toast.makeText(this, t(R.string.no_readable_notification_log_available), Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, t(R.string.private_log_saved), Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | SecurityException error) {
                Toast.makeText(this, t(R.string.private_log_save_failed), Toast.LENGTH_LONG).show();
            } finally {
                pendingDiagnostic = null;
            }
        } else if (request == EXPORT_PLAN) {
            try (var out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new java.io.IOException();
                out.write(PlanningBackup.export(prefs).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this, t(R.string.weekly_schedule_exported), Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | JSONException | SecurityException error) {
                Toast.makeText(this, t(R.string.save_backup_failed), Toast.LENGTH_LONG).show();
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
                new AlertDialog.Builder(this).setTitle(t(R.string.reset_weekly_schedule))
                    .setMessage(backupPreview(backup))
                    .setNegativeButton(t(R.string.cancel), null).setPositiveButton(t(R.string.restore), (dialog, which) -> {
                        if (restore.commit()) {
                            Planner.schedule(this);
                            recreate();
                        } else {
                            Toast.makeText(this, t(R.string.backup_restore_failed), Toast.LENGTH_LONG).show();
                        }
                    }).show();
            } catch (java.io.IOException | JSONException | SecurityException error) {
                Toast.makeText(this, t(R.string.this_is_not_a_valid_kia), Toast.LENGTH_LONG).show();
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
                if (before.duration() > 0) previous.add((before.ready() ? t(R.string.done) : "") + clock(before.start())
                    + " (" + before.duration() + t(R.string.min) + (before.enabled() ? "" : t(R.string.out)) + ")");
                if (duration > 0) next.add((ready ? t(R.string.done) : "") + clock(time)
                    + " (" + duration + t(R.string.min) + (enabled ? "" : t(R.string.out)) + ")");
            }
            if (different) {
                changed++;
                String previousLabel = previous.isEmpty() ? t(R.string.empty) : String.join(" · ", previous);
                String nextLabel = next.isEmpty() ? t(R.string.empty) : String.join(" · ", next);
                message.append(DAYS[day]).append(": ").append(previousLabel);
                if (!previousLabel.equals(nextLabel)) message.append(" → ").append(nextLabel);
                else if (Planner.dayEnabled(prefs, day) == incoming.getBoolean("enabled"))
                    message.append(t(R.string.time_slot_options_changed));
                if (Planner.dayEnabled(prefs, day) != incoming.getBoolean("enabled"))
                    message.append(incoming.getBoolean("enabled") ? t(R.string.day_becomes_active) : t(R.string.day_is_plotted));
                message.append('\n');
            }
        }
        if (changed == 0) message.append(t(R.string.no_differences_in_time_periods));
        var profiles = backup.getJSONArray("profiles");
        message.append(t(R.string.profiles_to_recover));
        for (int index = 0; index < profiles.length(); index++) {
            var saved = profiles.getJSONObject(index);
            message.append(ClimateProfiles.get(prefs, index).name()).append(" → ")
                .append(ClimateProfiles.displayName(index, saved.getString("name")))
                .append(" · ").append(temperatureLabel(saved.getInt("temperature"))).append('\n');
        }
        message.append(t(R.string.temporary_weekly_deviations_are_canceled));
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
            ? (KiaStartService.stopInProgress() ? t(R.string.stopping_climate) : t(R.string.stop_not_attached))
            : running || vehicleStillOn ? t(R.string.climate_active) : awaiting ? t(R.string.wait_for_kia)
            : external ? t(R.string.climate_control_already_active)
            : unconfirmed ? t(R.string.climate_status_unknown) : end > now ? t(R.string.period_active) : t(R.string.climate_off));
        boolean ownClimate = running || vehicleStillOn;
        boolean activeClimate = ownClimate || (external && !stopPending && !awaiting);
        var temperature = ClimateIndicator.read(prefs, now, ownClimate);
        updateClimateIcon(temperature, activeClimate);
        String outside = t(R.string.outside) + dashboardTemperature(temperature.outdoor());
        if (temperature.measured() > 0) {
            outside += "\n" + (temperature.weather() ? t(R.string.weather) : "Kia ") + format(temperature.measured(), "HH:mm");
        } else {
            var last = ClimateIndicator.lastOutdoor(prefs, now);
            if (last != null) {
                boolean today = localDate(last.measured()).equals(localDate(now));
                outside = t(R.string.outside) + dashboardTemperature(last.value()) + "\n"
                    + t(R.string.obsolete) + (last.weather() ? t(R.string.again) : "Kia ")
                    + format(last.measured(), today ? "HH:mm" : "d MMM HH:mm");
            }
        }
        set(outsideTemperature, outside);
        set(windshieldSetting, t(R.string.windshield_defrost_2) + windshieldLabel(Planner.defaultHeat(prefs),
            prefs.getInt("windshieldThreshold", ClimateProfiles.DEFAULT_THRESHOLD)) + "  ›");
        int seasonal = ClimateRules.profile(prefs, now, ZoneId.systemDefault());
        String general = prefs.getBoolean("seasonalProfile", false) && seasonal >= 0
            ? ClimateProfiles.get(prefs, seasonal).name() + t(R.string.season) : generalProfileName();
        set(generalProfileSetting, t(R.string.general_climate_profile_2) + general + "  ›");
        double defaultTarget = Planner.climateOptions(prefs, null, ZoneId.systemDefault()).temperature() / 2.0;
        double target = activeClimate ? temperature.target() : defaultTarget;
        set(targetTemperature, t(R.string.set) + dashboardTemperature(target));
        set(statusDetail, external && !running && !awaiting && !stopPending ? t(R.string.remaining_time_unknown) : "");
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
        set(windowLabel, combined ? t(R.string.combined_period) : onceActive ? t(R.string.one_time_period) : t(R.string.time_slot_remaining));
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
        set(cooldown, kiaPaused ? t(R.string.kia_automatic_control_paused_until) + KiaBackoff.resumeLabel(kiaResume)
            : Planner.manualRestartAvailable(prefs, now) ? t(R.string.you_can_start_it_manually_immediately)
            : confirmedStart && !manualStop ? t(R.string.new_start_in_at_least) + countdown(Math.max(0, remainingCooldown))
            : t(R.string.new_order_possible) + format(Planner.nextAllowedStart(this), "HH:mm"));
        var next = Planner.next(this);
        boolean ready = linked && !needsLink && alarms;
        long nextStart = ready && next != null ? Math.max(next.time(), Planner.nextAllowedStart(this)) : 0;
        boolean continuation = ready && monitoring && Planner.remainingCycles(prefs) > 0;
        if (continuation) nextStart = running && !kiaPaused ? cycleEnd + 15_000 : 0;
        displayedNext = ready && !continuation ? next : null;
        boolean actionable = displayedNext != null && (Planner.upcomingWeekly(prefs, displayedNext, now)
            || t(R.string.one_time_start).equals(Planner.startType(prefs, displayedNext)));
        changeNext.setVisibility(actionable ? View.VISIBLE : View.GONE);
        var forecast = displayedNext != null ? upcomingClimateDetails(displayedNext, temperature) : "";
        set(nextPreview, forecast);
        nextPreviewCard.setVisibility(forecast.isEmpty() ? View.GONE : View.VISIBLE);
        if (android.os.SystemClock.elapsedRealtime() >= undoUntil) undoChange = null;
        undoBar.setVisibility(undoChange == null ? View.GONE : View.VISIBLE);
        long pausedUntil = prefs.getLong(WeeklyAdjustment.PAUSE, 0);
        set(pauseButton, pausedUntil > now ? t(R.string.paused_until) + format(pausedUntil, "EEE d MMM HH:mm") : t(R.string.pause_weekly_planning));
        String nextType = continuation ? t(R.string.next_cycle) : Planner.startType(prefs, next);
        nextView.setVisibility(nextStart > 0 ? View.VISIBLE : View.GONE);
        set(nextView, nextStart > 0 ? format(nextStart, "HH:mm") : "");
        set(nextDay, !linked || needsLink ? t(R.string.link_kia_connect) : !alarms ? t(R.string.exact_alarms_needed)
            : nextStart > 0 ? nextType + "\n" + format(nextStart, "EEE d MMM")
            : continuation ? (kiaPaused ? t(R.string.wait_for_the_end_of_the) : t(R.string.next_cycle_awaits_kia))
            : pausedUntil > now ? t(R.string.weekly_planning_paused) : prefs.getBoolean("enabled", false)
                ? t(R.string.no_start_planned) : t(R.string.weekly_planning_is_turned_off));
        String step = awaiting ? t(R.string.wait_for_confirmation_from_kia)
            : stopPending && !kiaPaused ? t(R.string.wait_for_stop_confirmation_from_kia)
            : stopPending ? t(R.string.stop_not_confirmed_by_kia)
            : monitoring && kiaPaused ? t(R.string.climate_control_temporarily_paused)
            : monitoring && Planner.remainingCycles(prefs) > 0 ? t(R.string.the_planner_checks_kia_around_the)
            : monitoring ? t(R.string.last_cycle_the_scheduler_checks_the)
            : unconfirmed ? t(R.string.kia_has_not_yet_confirmed_the)
            : !activeClimate && end <= now && now - prefs.getLong("finishedOnUnlock", 0) < 15 * Schedule.MINUTE
                ? t(R.string.completed_after_unlocking_or_switching_on) : "";
        set(liveStep, step);
        liveStep.setVisibility(step.isEmpty() ? View.GONE : View.VISIBLE);
        boolean canStop = linked && (running || external || awaiting || unconfirmed || end > now
            || prefs.getLong("stopVehiclePending", 0) > now);
        manualButton.setVisibility(canStop && manual && ready ? View.GONE : View.VISIBLE);
        manualButton.setEnabled(!manual || !ready);
        set(manualButton, ready ? t(R.string.start_manually) : t(R.string.to_settings));
        stopButton.setVisibility(canStop ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(!KiaStartService.stopInProgress());
        set(stopButton, KiaStartService.stopInProgress() ? "Stoppen…" : t(R.string.stop));
        onceButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        onceButton.setEnabled(!onceActive);
        set(onceButton, onceActive ? t(R.string.one_time_period_is_running) : onceStart > now
            ? t(R.string.one_off) + format(onceStart, "d MMM HH:mm") : t(R.string.plan_once));
        int percentage = prefs.getInt("battery", -1);
        long measured = prefs.getLong("batteryTime", 0);
        set(battery, percentage < 0 ? t(R.string.unknown) : percentage + "%");
        String batteryDetail = batteryBusy ? t(R.string.collect_battery) : measured > 0
            ? t(R.string.measured) + format(measured, "d MMM HH:mm") : t(R.string.no_measurement);
        set(batteryMeasured, batteryDetail);
        batteryTile.setEnabled(linked && !needsLink && !batteryBusy);
        batteryTile.setContentDescription(t(R.string.traction_battery_3) + (percentage < 0 ? t(R.string.unknown_2) : percentage + t(R.string.percent))
            + ", " + batteryDetail + t(R.string.refresh_battery_status));
        extendAction.setVisibility(!manualStop && showWindow && prefs.getInt("activeConfirmedCycles", 0) > 0
            ? View.VISIBLE : View.GONE);
        repeatAction.setVisibility(!canStop && ready && prefs.contains("previousManualMinutes") ? View.VISIBLE : View.GONE);
        preflightAction.setVisibility(displayedNext == null ? View.GONE : View.VISIBLE);
        nextPreviewCard.setVisibility(displayedNext != null || !forecast.isEmpty() ? View.VISIBLE : View.GONE);
        boolean showAccountDetails = accountExpanded || !linked || needsLink || !alarms;
        set(accountState, needsLink ? t(R.string.re_pairing_required) : linked
            ? t(R.string.linked) + prefs.getString("kiaModel", "e-Niro") + (showAccountDetails ? "   ▴" : "   ›")
            : t(R.string.kia_connect_not_yet_linked));
        accountDetails.setVisibility(showAccountDetails ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 30) {
            accountState.setStateDescription(showAccountDetails ? t(R.string.expanded) : t(R.string.collapsed));
        }
        set(accountButton, linked ? t(R.string.relink_kia_account) : t(R.string.link_kia_account));
        unlink.setVisibility(linked ? View.VISIBLE : View.GONE);
        alarmPermission.setVisibility(alarms ? View.GONE : View.VISIBLE);
        boolean alertsWanted = prefs.getBoolean("notifyDepartureDelay", false) || prefs.getBoolean("notifyWindowEnd", true)
            || prefs.getBoolean("notifyStartLimit", true) || prefs.getBoolean("notifyStartFailure", true);
        alertPermission.setVisibility(alertsWanted && !WindowAlerts.permitted(this) ? View.VISIBLE : View.GONE);
        set(access, alarms ? t(R.string.exact_alarms_are_allowed) : t(R.string.exact_alarms_require_permission));
        refreshHistory();
    }

    private String upcomingClimateDetails(Schedule.Slot slot, ClimateIndicator.Reading reading) {
        var options = Planner.climateOptions(prefs, slot, ZoneId.systemDefault());
        String name = options.profile() < 0 ? t(R.string.standard) : ClimateProfiles.get(prefs, options.profile()).name();
        if (options.customHeat()) name += t(R.string.own_heating);
        String rule = t(R.string.windshield) + windshieldLabel(options.heat(), options.threshold());
        if (ClimateProfiles.windshieldMode(options.heat()) == 2 && !Double.isFinite(reading.outdoor())) {
            rule += t(R.string.decision_at_start);
        }
        return name + " · " + temperatureLabel(options.temperature()) + "\n" + rule;
    }

    private static LocalDate localDate(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static String dashboardTemperature(double celsius) {
        if (!Double.isFinite(celsius)) return t(R.string.unknown_2);
        return String.format(AppLanguage.locale(), celsius == Math.rint(celsius) ? "%.0f °C" : "%.1f °C", celsius);
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
                    estimate = t(R.string.probably_heating);
                }
                case COOLING -> estimate = t(R.string.probably_refrigerate);
                case UNKNOWN -> {
                    icon = R.drawable.ic_ui_fan;
                    tint = ACCENT;
                    estimate = t(R.string.heating_or_cooling_unknown);
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
        if (entries.isEmpty()) entries.add(new HistoryEntry(0, t(R.string.no_notifications_yet)));

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
            if ("UNLOCKED".equals(type) || message.contains("auto is ontgrendeld")) return t(R.string.completed_after_unlocking);
            if ("STOPPED".equals(type) || message.equals("Lopend tijdvak geannuleerd.")) return t(R.string.terminated_manually);
            if ("RULE".equals(type)) return message;
            if ("REFUSED".equals(type) || message.contains("heeft de klimaatstart geweigerd")) return t(R.string.start_denied_by_kia);
            if ("UNCONFIRMED".equals(type) || message.equals("Geen bevestiging van de auto ontvangen."))
                return t(R.string.no_confirmation_from_kia);
            if (event.confirmed()) break;
        }
        if (planned == 1 && confirmed == 1) return t(R.string.text_1_of_1_cycle_confirmed);
        return confirmed + t(R.string.of) + planned + t(R.string.cycles_confirmed);
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
                ? expanded ? t(R.string.expanded) : t(R.string.collapsed) : null);
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
                Planner.note(this, t(R.string.kia_account_disconnected));
            } catch (Exception error) {
                Planner.note(this, t(R.string.the_saved_kia_session_could_not));
            }
        });
    }

    private static String clock(int minute) {
        return String.format(AppLanguage.locale(), "%02d:%02d", minute / 60, minute % 60);
    }

    private static String countdown(long millis) {
        long sec = Math.max(0, (millis + 999) / 1000);
        return sec >= 3600 ? String.format(AppLanguage.locale(), "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
            : String.format(AppLanguage.locale(), "%02d:%02d", sec / 60, sec % 60);
    }

    private static String format(long millis, String pattern) {
        return DateTimeFormatter.ofPattern(pattern, AppLanguage.locale()).withZone(ZoneId.systemDefault())
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
            boolean compact = title.equals(t(R.string.traction_battery)) || title.equals(t(R.string.next_start));
            var heading = text(title, compact ? 16 : 21, INK, true);
            int icon = 0;
            if (title.equals(t(R.string.traction_battery))) icon = R.drawable.ic_ui_battery;
            else if (title.equals(t(R.string.next_start)) || title.equals(t(R.string.weekly_schedule))) icon = R.drawable.ic_ui_calendar;
            else if (title.equals(t(R.string.recent_notifications))) icon = R.drawable.ic_ui_list;
            else if (title.equals(t(R.string.standard_heating))) icon = R.drawable.ic_ui_heat;
            else if (title.equals(t(R.string.notifications))) icon = R.drawable.ic_ui_bell;
            else if (title.equals("Kia Connect")) icon = R.drawable.ic_ui_link;
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



