package nl.axlproductions.kiaklimaatplanner;

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
        heading.setText("Kia Connect koppelen");
        heading.setTextSize(26);
        page.addView(heading);
        TextView hint = new TextView(this);
        hint.setText("Meld je aan met je Kia-account voor geplande starts met je Samsung vergrendeld. "
            + "De app bewaart de verbinding en je aanmeldgegevens versleuteld op dit toestel.");
        page.addView(hint);

        email = field("E-mailadres", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field("Kia-wachtwoord", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin = field("Kia Connect-PIN (optioneel)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        page.addView(email);
        page.addView(password);
        page.addView(pin);

        submit = new Button(this);
        submit.setAllCaps(false);
        submit.setText("Koppelen");
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
            state.setText("Vul je e-mailadres en wachtwoord in.");
            return;
        }
        password.setText("");
        pin.setText("");
        run("Aanmelden bij Kia Connect…", () -> KiaCloud.linkResponse(this,
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
                if (KiaCloud.rateLimitingError(error)) Planner.rateLimitReached(this, "aanmelden");
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
            Planner.note(this, "Kia Connect gekoppeld. Automatische starts kunnen via de internetverbinding.");
            setResult(RESULT_OK);
            finish();
            return;
        }
        if (response.has("error")) {
            state.setText(response.optString("error"));
            return;
        }
        var vehicles = response.optJSONArray("vehicles");
        if (vehicles != null) {
            String[] labels = new String[vehicles.length()];
            for (int i = 0; i < labels.length; i++) labels[i] = vehicles.optString(i);
            new AlertDialog.Builder(this).setTitle("Kies een Kia")
                .setItems(labels, (dialog, position) -> run("Auto koppelen…", () -> KiaCloud.linkResponse(this,
                    KiaCloud.module(this).callAttr("select_vehicle", position))))
                .setNegativeButton("Annuleren", null)
                .show();
            return;
        }
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> channels = new ArrayList<>();
        if (response.optBoolean("email")) {
            labels.add("E-mail");
            channels.add("EMAIL");
        }
        if (response.optBoolean("sms")) {
            labels.add("Sms");
            channels.add("SMS");
        }
        if (channels.isEmpty()) {
            state.setText("Kia bood geen ondersteunde verificatiemethode aan.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Kia-verificatie")
            .setItems(labels.toArray(new String[0]), (dialog, position) -> sendCode(channels.get(position)))
            .setNegativeButton("Annuleren", null)
            .show();
    }

    private void sendCode(String channel) {
        state.setText("Verificatiecode aanvragen…");
        submit.setEnabled(false);
        KiaCloud.WORKER.execute(() -> {
            try {
                if (KiaBackoff.paused(this)) throw new IllegalStateException("Local Kia pause");
                KiaCloud.module(this).callAttr("send_code", channel);
                runOnUiThread(this::askCode);
            } catch (Exception error) {
                if (KiaCloud.rateLimitingError(error)) Planner.rateLimitReached(this, "aanmelden");
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
        state.setText("Kia heeft om een verificatiecode gevraagd.");
        EditText input = field("Verificatiecode", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Vul de code van Kia in").setView(input)
            .setPositiveButton("Controleren", (dialog, which) -> {
                String code = input.getText().toString().trim();
                if (!code.isEmpty()) run("Verificatiecode controleren…", () -> KiaCloud.linkResponse(this,
                    KiaCloud.module(this).callAttr("verify_code", code)));
            })
            .setNegativeButton("Annuleren", null)
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


