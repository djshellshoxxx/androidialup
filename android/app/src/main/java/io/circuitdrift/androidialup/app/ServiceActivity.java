package io.circuitdrift.androidialup.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Base for the developer screens: starts and binds {@link ModemService}, refreshes once per
 * second while visible, and offers tiny programmatic-view helpers (plain framework views, no
 * layouts or resources, so the module has no generated R dependency).
 */
abstract class ServiceActivity extends Activity {
    private static final long REFRESH_MS = 1_000;

    protected final Handler main = new Handler(Looper.getMainLooper());
    protected ModemService service;
    protected LinearLayout root;

    private boolean bound;
    private boolean tooltipsEnabled;
    private Button helpButton;
    private Button optionsButton;
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (service != null) refresh();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ModemService.LocalBinder) binder).service();
            onServiceBound();
            refresh();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            onServiceUnbinding();
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        tooltipsEnabled = getSharedPreferences("androidialup_ui", MODE_PRIVATE)
                .getBoolean("tooltips_enabled", true);
        LinearLayout assistance = new LinearLayout(this);
        assistance.setOrientation(LinearLayout.HORIZONTAL);
        helpButton = new Button(this);
        helpButton.setText("Help");
        helpButton.setOnClickListener(v -> showHelp());
        assistance.addView(helpButton);
        optionsButton = new Button(this);
        optionsButton.setText("Options");
        optionsButton.setOnClickListener(v -> showOptions());
        assistance.addView(optionsButton);
        root.addView(assistance);

        buildUi();
        applyTooltipsRecursive(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, ModemService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        main.post(refresher);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(refresher);
        if (bound) {
            if (service != null) onServiceUnbinding();
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    protected abstract void buildUi();

    protected abstract void refresh();

    protected void onServiceBound() {}

    protected void onServiceUnbinding() {}

    private void showOptions() {
        new AlertDialog.Builder(this)
                .setTitle("Interface options")
                .setMultiChoiceItems(new String[] {"Show tooltips"}, new boolean[] {tooltipsEnabled},
                        (dialog, which, checked) -> {
                            tooltipsEnabled = checked;
                            getSharedPreferences("androidialup_ui", MODE_PRIVATE)
                                    .edit().putBoolean("tooltips_enabled", checked).apply();
                            applyTooltipsRecursive(root);
                        })
                .setPositiveButton("Close", null)
                .show();
    }

    private void showHelp() {
        String screen = getClass().getSimpleName();
        String guide =
                "ANDROIDIALUP HELP\n\n" +
                "OVERVIEW\n" +
                "AndroidDialup presents an AT-command style modem service with audio/network transport support, " +
                "a developer dialer, call progress, TCP/USB DTE status and relay/network controls.\n\n" +
                "DIALER\n" +
                "Enter one destination, choose Tone/Pulse/Auto as available, set a per-call timeout and tap Dial. " +
                "Cancel sends the dialing cancel path; Hang up terminates an active call. The live Modem section shows " +
                "state, DCD/DSR/CTS, result, terminal reason, active call and selected network.\n\n" +
                "TEST LIST\n" +
                "The list accepts individual destinations only. Load the list after confirming the on-screen statement, " +
                "then explicitly tap each entry you want to dial. Call-progress history can be exported as NDJSON.\n\n" +
                "NETWORK AND RELAY\n" +
                "Choose Automatic, Wi-Fi-only, Cellular-only or a preference policy. Diagnostics show validation, metering, " +
                "roaming, relay RTT/loss, selection reason and network scores. Relay settings include host, TLS endpoint identity, " +
                "device secret, optional private CA and the localhost TCP DTE port.\n\n" +
                "DTE AND MODEM STATUS\n" +
                "TCP DTE reports the local AT-command endpoint status. USB DTE status is shown when supported. Modem status is " +
                "rendered from the controller snapshot rather than guessed by the UI.\n\n" +
                "TOOLTIPS\n" +
                "Long-press or hover supported controls to see contextual help. Options > Show tooltips enables or disables " +
                "these descriptions across all AndroidDialup screens and remembers the preference.\n\n" +
                "TROUBLESHOOTING\n" +
                "If Dial is disabled, wait until the modem returns to COMMAND state and no call is active. If networking fails, " +
                "review validation, policy, relay configuration and the diagnostics selection reason. If relay settings are marked " +
                "incomplete, fill the missing field reported after Save.\n\n" +
                "Current screen: " + screen;
        new AlertDialog.Builder(this)
                .setTitle("AndroidDialup Help")
                .setMessage(guide)
                .setPositiveButton("Close", null)
                .show();
    }

    private void applyTooltipsRecursive(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (view != root) {
                CharSequence description = tooltipFor(view);
                view.setTooltipText(tooltipsEnabled ? description : null);
                if (description != null && view.getContentDescription() == null)
                    view.setContentDescription(description);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                applyTooltipsRecursive(group.getChildAt(i));
        }
    }

    private CharSequence tooltipFor(View view) {
        if (view == helpButton) return "Open the complete AndroidDialup operating guide.";
        if (view == optionsButton) return "Open interface options, including the global tooltip switch.";
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            return hint == null ? "Enter or edit this value." : "Enter " + hint + ".";
        }
        if (view instanceof CompoundButton) {
            CharSequence label = ((CompoundButton) view).getText();
            return label == null ? "Toggle this option." : "Toggle " + label + ".";
        }
        if (view instanceof Button) {
            CharSequence label = ((Button) view).getText();
            return label == null ? "Activate this control." : "Activate " + label + ".";
        }
        return view.getContentDescription();
    }

    // ---- view helpers -------------------------------------------------------------------

    protected int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    protected TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(12), 0, dp(4));
        root.addView(view);
        return view;
    }

    protected TextView text(String initial) {
        TextView view = new TextView(this);
        view.setText(initial);
        view.setTextIsSelectable(true);
        root.addView(view);
        return view;
    }

    protected TextView mono(String initial) {
        TextView view = text(initial);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    protected Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(onClick);
        root.addView(button);
        return button;
    }
}
