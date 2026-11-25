package nl.axlproductions.kiaklimaatplanner;

import static nl.axlproductions.kiaklimaatplanner.AppLanguage.t;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.json.JSONObject;

public final class KiaAccountActivity extends Activity {
    private EditText email;
    private EditText password;
    private EditText pin;
    private Button submit;
    private TextView state;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppLanguage.localized(base));
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int margin = Math.round(22 * getResources().getDisplayMetrics().density);
        page.setPadding(margin, margin, margin, margin);
        scroll.addView(page);
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText(t(R.string.connect_kia_connect));
        heading.setTextSize(26);
        page.addView(heading);
        TextView hint = new TextView(this);
        hint.setText(t(R.string.log_in_with_your_kia_account)
            + t(R.string.the_app_stores_the_connection_and));
        page.addView(hint);

        email = field(t(R.string.email_address), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field(t(R.string.kia_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin = field(t(R.string.kia_connect_pin_optional), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        page.addView(email);
        page.addView(password);
        page.addView(pin);

        submit = new Button(this);
        submit.setAllCaps(false);
        submit.setText(t(R.string.pairing));
        submit.setOnClickListener(view -> login());
        page.addView(submit);
        state = new TextView(this);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(state);
    }

    private EditText field(String label, int inputType) {
        EditText field = new EditText(this);
        field.setHint(label);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setSaveEnabled(false);
        return field;
    }

    private void login() {
        String address = email.getText().toString().trim();
        String secret = password.getText().toString();
        String vehiclePin = pin.getText().toString().trim();
        if (address.isEmpty() || secret.isEmpty()) {
            state.setText(t(R.string.enter_your_email_address_and_password));
            return;
        }
        password.setText("");
        pin.setText("");
        run(t(R.string.log_in_to_kia_connect), () -> KiaCloud.linkResponse(this,
            KiaCloud.module(this).callAttr("login", address, secret, vehiclePin)));
    }

    private void run(String progress, Callable<JSONObject> operation) {
        submit.setEnabled(false);
        state.setText(progress);
        KiaCloud.WORKER.execute(() -> {
            JSONObject response;
            try {
                if (KiaBackoff.paused(this)) throw new IllegalStateException("Local Kia pause");
                response = operation.call();
            } catch (Exception error) {
                if (KiaCloud.rateLimitingError(error)) Planner.rateLimitReached(this, t(R.string.sign_up));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    submit.setEnabled(true);
                    long until = KiaBackoff.until(Planner.prefs(this));
                    state.setText(KiaBackoff.paused(this) ? KiaBackoff.message(until) : KiaCloud.problem(error));
                });
                return;
            }
            runOnUiThread(() -> handle(response));
        });
    }

    private void handle(JSONObject response) {
        if (isFinishing() || isDestroyed()) return;
        submit.setEnabled(true);
        if (response.has("model")) {
            Planner.note(this, t(R.string.kia_connect_connected_automatic_starts_can));
            setResult(RESULT_OK);
            finish();
            return;
        }
        if (response.has("error")) {
            String error = response.optString("error");
            state.setText(switch (error) {
                case "no_vehicle_linked" -> t(R.string.no_vehicle_linked);
                case "login_session_missing" -> t(R.string.login_session_missing);
                case "selection_expired" -> t(R.string.selection_expired);
                case "selection_invalid" -> t(R.string.selection_invalid);
                default -> error;
            });
            return;
        }
        var vehicles = response.optJSONArray("vehicles");
        if (vehicles != null) {
            String[] labels = new String[vehicles.length()];
            for (int i = 0; i < labels.length; i++) labels[i] = vehicles.optString(i);
            new AlertDialog.Builder(this).setTitle(t(R.string.choose_a_kia))
                .setItems(labels, (dialog, position) -> run(t(R.string.connect_car), () -> KiaCloud.linkResponse(this,
                    KiaCloud.module(this).callAttr("select_vehicle", position))))
                .setNegativeButton(t(R.string.cancel), null)
                .show();
            return;
        }
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> channels = new ArrayList<>();
        if (response.optBoolean("email")) {
            labels.add(t(R.string.email));
            channels.add("EMAIL");
        }
        if (response.optBoolean("sms")) {
            labels.add("Sms");
            channels.add("SMS");
        }
        if (channels.isEmpty()) {
            state.setText(t(R.string.kia_did_not_offer_a_supported));
            return;
        }
        new AlertDialog.Builder(this).setTitle(t(R.string.kia_verification))
            .setItems(labels.toArray(new String[0]), (dialog, position) -> sendCode(channels.get(position)))
            .setNegativeButton(t(R.string.cancel), null)
            .show();
    }

    private void sendCode(String channel) {
        state.setText(t(R.string.request_verification_code));
        submit.setEnabled(false);
        KiaCloud.WORKER.execute(() -> {
            try {
                if (KiaBackoff.paused(this)) throw new IllegalStateException("Local Kia pause");
                KiaCloud.module(this).callAttr("send_code", channel);
                runOnUiThread(this::askCode);
            } catch (Exception error) {
                if (KiaCloud.rateLimitingError(error)) Planner.rateLimitReached(this, t(R.string.sign_up));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    submit.setEnabled(true);
                    long until = KiaBackoff.until(Planner.prefs(this));
                    state.setText(KiaBackoff.paused(this) ? KiaBackoff.message(until) : KiaCloud.problem(error));
                });
            }
        });
    }

    private void askCode() {
        if (isFinishing() || isDestroyed()) return;
        submit.setEnabled(true);
        state.setText(t(R.string.kia_has_asked_for_a_verification));
        EditText input = field(t(R.string.verification_code), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(t(R.string.enter_the_kia_code)).setView(input)
            .setPositiveButton(t(R.string.check), (dialog, which) -> {
                String code = input.getText().toString().trim();
                if (!code.isEmpty()) run(t(R.string.check_verification_code), () -> KiaCloud.linkResponse(this,
                    KiaCloud.module(this).callAttr("verify_code", code)));
            })
            .setNegativeButton(t(R.string.cancel), null)
            .show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            var app = getApplicationContext();
            KiaCloud.WORKER.execute(() -> {
                if (com.chaquo.python.Python.isStarted()) {
                    KiaCloud.module(app).callAttr("clear_login");
                }
            });
        }
    }
}


