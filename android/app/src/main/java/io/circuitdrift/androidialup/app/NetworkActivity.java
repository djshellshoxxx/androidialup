package io.circuitdrift.androidialup.app;

import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import io.circuitdrift.androidialup.network.NetworkDiagnosticsSnapshot;
import io.circuitdrift.androidialup.network.NetworkPolicy;

import java.util.Map;

/**
 * Developer network screen (I1 network-control plan Task 5): policy picker
 * (AUTOMATIC / WIFI_ONLY / CELLULAR_ONLY / PREFER_WIFI / PREFER_CELLULAR), the developer
 * override for unvalidated networks, the live {@link NetworkDiagnosticsSnapshot}, and the relay
 * / TCP DTE settings.
 */
public final class NetworkActivity extends ServiceActivity {
    private RadioGroup policies;
    private CheckBox override;
    private TextView diagnostics;
    private EditText host;
    private EditText port;
    private EditText endpointId;
    private EditText secret;
    private EditText caPem;
    private EditText dtePort;
    private boolean populating;

    @Override
    protected void buildUi() {
        setTitle("Network and relay");
        heading("Network policy");
        policies = new RadioGroup(this);
        for (NetworkPolicy policy : NetworkPolicy.values()) {
            RadioButton option = new RadioButton(this);
            option.setText(policy.name());
            option.setId(2000 + policy.ordinal());
            policies.addView(option);
        }
        policies.setOnCheckedChangeListener((group, checkedId) -> {
            if (populating || service == null) return;
            service.setPolicy(NetworkPolicy.values()[checkedId - 2000]);
            refresh();
        });
        root.addView(policies);

        override = new CheckBox(this);
        override.setText("Developer override: allow unvalidated networks");
        override.setOnCheckedChangeListener((view, checked) -> {
            if (populating || service == null) return;
            service.setDeveloperOverride(checked);
            refresh();
        });
        root.addView(override);

        heading("Diagnostics");
        diagnostics = mono("service not bound");

        heading("Relay");
        host = field("relay host (certificate name)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field("relay port", InputType.TYPE_CLASS_NUMBER);
        endpointId = field("endpoint id (64 hex chars)", InputType.TYPE_CLASS_TEXT);
        secret = field("device secret (hex; not shown once saved)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        caPem = field("private relay CA certificate (PEM, optional; empty = system CAs)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        caPem.setMinLines(2);
        dtePort = field("TCP DTE port on 127.0.0.1", InputType.TYPE_CLASS_NUMBER);
        button("Save", v -> save());
        button("Stop modem service", v -> {
            stopService(new android.content.Intent(this, ModemService.class));
            finishAffinity();
        });
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        root.addView(field);
        return field;
    }

    @Override
    protected void onServiceBound() {
        populating = true;
        RelaySettings settings = service.settings();
        policies.check(2000 + service.policy().ordinal());
        override.setChecked(service.diagnostics().developerOverride());
        host.setText(settings.host);
        port.setText(Integer.toString(settings.port));
        endpointId.setText(settings.endpointIdHex);
        secret.setText(""); // never echo the stored secret
        caPem.setText(settings.caPem);
        dtePort.setText(Integer.toString(settings.dtePort));
        populating = false;
    }

    private void save() {
        if (service == null) return;
        RelaySettings current = service.settings();
        try {
            String newSecret = secret.getText().toString().trim();
            RelaySettings next = new RelaySettings(
                    host.getText().toString(),
                    Integer.parseInt(port.getText().toString().trim()),
                    endpointId.getText().toString(),
                    newSecret.isEmpty() ? current.deviceSecretHex : newSecret,
                    caPem.getText().toString(),
                    Integer.parseInt(dtePort.getText().toString().trim()),
                    service.policy());
            if (next.dtePort < 1024 || next.dtePort > 65535) throw new IllegalArgumentException("DTE port must be 1024..65535");
            service.updateSettings(next);
            secret.setText("");
            String incomplete = next.incompleteReason();
            Toast.makeText(this, incomplete == null ? "Saved" : "Saved (incomplete: " + incomplete + ")",
                    Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "Not saved: " + invalid.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void refresh() {
        if (service == null) return;
        NetworkDiagnosticsSnapshot d = service.diagnostics();
        StringBuilder out = new StringBuilder()
                .append("policy: ").append(d.policy())
                .append("\nselected: ").append(d.selectedId() == null ? "none" : d.selectedId())
                .append(" ").append(d.selectedTransport() == null ? "" : d.selectedTransport())
                .append("\nvalidated: ").append(d.validated())
                .append("  metered: ").append(d.metered())
                .append("  roaming: ").append(d.roaming())
                .append("\nrelay probe rtt/loss: ").append(d.relayProbeRttMs()).append(" / ").append(d.relayProbeLoss())
                .append("\nreason: ").append(d.selectionReason())
                .append("\nactive call: ").append(d.activeCall())
                .append("  developer override: ").append(d.developerOverride())
                .append("\nbetter network: ").append(d.betterNetworkAvailable() ? d.betterNetworkId() : "no")
                .append("\nscores:");
        for (Map.Entry<String, Double> score : d.scores().entrySet()) {
            out.append("\n  ").append(score.getKey()).append(" = ").append(score.getValue());
        }
        out.append("\nTCP DTE: ").append(service.dteStatus());
        diagnostics.setText(out.toString());
    }
}
