package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

public final class KiaControls extends AccessibilityService {
    static final String KIA = "com.kia.oneapp.eu";
    static KiaControls active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int request;
    private boolean busy;

    @Override protected void onServiceConnected() {
        active = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                || !KIA.contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        var prefs = Planner.prefs(this);
        long lastStart = prefs.getLong("lastStart", 0);
        if (now < lastStart || now - lastStart > 120_000
                || prefs.getLong("confirmedStart", 0) >= lastStart) return;
        List<CharSequence> message = new ArrayList<>(event.getText());
        if (event.getParcelableData() instanceof Notification notification) {
            message.add(notification.extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        for (CharSequence part : message) {
            if (part != null && KiaCloud.climateUnavailable(part.toString())) {
                Planner.stopUnavailable(this, lastStart);
                return;
            }
            if (part != null && part.toString().trim().equalsIgnoreCase("Klimaatregeling gestart.")) {
                if (Planner.confirmStart(this, lastStart, now)) {
                    Planner.note(this, t(R.string.kia_reports_climate_control_started));
                }
                return;
            }
            if (part != null && part.toString().contains("maximaal 3 keer binnen 1 uur")) {
                Planner.rateLimitReached(this);
                return;
            }
        }
    }
    @Override public void onInterrupt() { cancel(); }

    @Override public void onDestroy() {
        cancel();
        if (active == this) active = null;
        super.onDestroy();
    }

    void cancel() {
        request++;
        busy = false;
        handler.removeCallbacksAndMessages(null);
    }

    void runSlot(Schedule.Slot slot, long wait) {
        if (busy) {
            Planner.note(this, t(R.string.start_skipped_another_kia_order_is));
            return;
        }
        busy = true;
        int id = ++request;
        handler.postDelayed(() -> open(id, slot), wait);
    }

    void preview() {
        cancel();
        busy = true;
        open(++request, null);
    }

    private void open(int id, Schedule.Slot slot) {
        if (id != request) return;
        if (!interactive()) {
            finish(t(R.string.missed_time_the_phone_screen_was));
            return;
        }
        if (slot != null && System.currentTimeMillis() >= slot.end()) {
            finish(t(R.string.the_planned_period_is_over));
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(KIA);
        if (launch == null) {
            finish(t(R.string.the_official_european_kia_app_could));
            return;
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) {
            finish(t(R.string.android_could_not_open_the_kia));
            return;
        }
        poll(id, slot, SystemClock.uptimeMillis() + 16_000, false);
    }

    private void poll(int id, Schedule.Slot slot, long deadline, boolean homeTapped) {
        if (id != request) return;
        if (!interactive()) {
            finish(t(R.string.screen_locked_or_disabled_during_kia));
            return;
        }
        if (slot != null && System.currentTimeMillis() >= slot.end()) {
            finish(t(R.string.the_planned_period_is_over));
            return;
        }

        AccessibilityNodeInfo root = kiaRoot();
        boolean openedHome = homeTapped;
        if (root != null && KIA.contentEquals(root.getPackageName())) {
            List<Label> labels = new ArrayList<>();
            collect(root, labels);
            Label target = onButton(labels);
            if (target != null) {
                if (slot == null) {
                    finish(t(R.string.test_passed_the_fan_button_on));
                    return;
                }
                long now = System.currentTimeMillis();
                if (!Planner.stillCurrent(this, slot) || !Planner.reserveStart(this, now, Schedule.CYCLE_MINUTES, slot)) {
                    busy = false;
                    return;
                }
                if (target.action().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    busy = false;
                    return;
                }
                Planner.clearCycle(this, now);
                finish(t(R.string.on_was_found_but_android_was));
                return;
            }
            if (offButton(labels) && (slot == null || SystemClock.uptimeMillis() >= deadline)) {
                finish(t(R.string.the_kia_app_shows_off_no));
                return;
            }
            if (!homeTapped) {
                Label home = unique(labels, "Home");
                Label map = unique(labels, "Kaart");
                Label management = unique(labels, "Beheer");
                Label vehicle = unique(labels, "Voertuig");
                if (home != null && home.action() != null && orderedRow(home, map, management, vehicle)) {
                    openedHome = home.action().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }

        if (SystemClock.uptimeMillis() >= deadline) {
            finish(t(R.string.the_kia_app_does_not_show));
            return;
        }
        boolean nextHomeTapped = openedHome;
        handler.postDelayed(() -> poll(id, slot, deadline, nextHomeTapped), 450);
    }

    private AccessibilityNodeInfo kiaRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && KIA.contentEquals(root.getPackageName())) return root;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (!window.isActive() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo candidate = window.getRoot();
            if (candidate != null && KIA.contentEquals(candidate.getPackageName())) return candidate;
        }
        return null;
    }

    private boolean interactive() {
        return getSystemService(PowerManager.class).isInteractive()
            && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private void finish(String message) {
        busy = false;
        Planner.note(this, message);
    }

    private record Label(String text, Rect bounds, AccessibilityNodeInfo action) {}

    private static void collect(AccessibilityNodeInfo node, List<Label> labels) {
        if (!node.isVisibleToUser() || !KIA.contentEquals(node.getPackageName())) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        addLabel(labels, node.getText(), bounds, node);
        if (node.getContentDescription() != null
                && !node.getContentDescription().toString().contentEquals(
                    node.getText() == null ? "" : node.getText())) {
            addLabel(labels, node.getContentDescription(), bounds, node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, labels);
        }
    }

    private static void addLabel(List<Label> labels, CharSequence text, Rect bounds, AccessibilityNodeInfo node) {
        if (text == null || bounds.isEmpty() || text.length() > 45) return;
        String value = text.toString().trim();
        AccessibilityNodeInfo target = actionableParent(node);
        for (Label label : labels) {
            if (label.text().equals(value) && label.bounds().equals(bounds)) return;
        }
        labels.add(new Label(value, bounds, target));
    }

    private static AccessibilityNodeInfo actionableParent(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 4; depth++, node = node.getParent()) {
            if (!KIA.contentEquals(node.getPackageName())) return null;
            if (node.isClickable() && node.isEnabled()) return node;
        }
        return null;
    }

    private static Label unique(List<Label> labels, String... names) {
        Label found = null;
        for (Label label : labels) {
            for (String name : names) {
                if (!name.equalsIgnoreCase(label.text())) continue;
                if (found != null) return null;
                found = label;
            }
        }
        return found;
    }

    private static boolean orderedRow(Label first, Label second, Label third, Label fourth) {
        if (first == null || second == null || third == null || fourth == null) return false;
        Rect a = first.bounds();
        Rect b = second.bounds();
        Rect c = third.bounds();
        Rect d = fourth.bounds();
        int tolerance = Math.max(a.height(), Math.max(b.height(), Math.max(c.height(), d.height())));
        return a.centerX() < b.centerX() && b.centerX() < c.centerX() && c.centerX() < d.centerX()
            && Math.abs(a.centerY() - b.centerY()) <= tolerance
            && Math.abs(b.centerY() - c.centerY()) <= tolerance
            && Math.abs(c.centerY() - d.centerY()) <= tolerance;
    }

    private static boolean remoteRow(List<Label> labels, Label climate) {
        return orderedRow(climate, unique(labels, "Vergrend.", "Ontgrend.", "Vergrendelen", "Ontgrendelen"),
            unique(labels, "Start"), unique(labels, "Stop"));
    }

    private static Label onButton(List<Label> labels) {
        Label on = unique(labels, "Aan", "Klimaatregeling inschakelen");
        if (on == null || on.action() == null || !remoteRow(labels, on)) return null;
        Rect button = new Rect();
        on.action().getBoundsInScreen(button);
        Label neighbor = unique(labels, "Vergrend.", "Ontgrend.", "Vergrendelen", "Ontgrendelen");
        return button.contains(on.bounds()) && button.right <= neighbor.bounds().centerX() ? on : null;
    }

    private static boolean offButton(List<Label> labels) {
        return remoteRow(labels, unique(labels, "Uit", "Klimaatregeling uitschakelen"));
    }
}





