package io.circuitdrift.androidialup.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import io.circuitdrift.androidialup.modem.ModemSnapshot;
import io.circuitdrift.androidialup.modem.ModemState;
import io.circuitdrift.androidialup.platform.dialer.CallLog;
import io.circuitdrift.androidialup.platform.dialer.CallLogRecord;
import io.circuitdrift.androidialup.platform.dialer.DestinationInput;
import io.circuitdrift.androidialup.platform.dialer.DialMethod;
import io.circuitdrift.androidialup.platform.dialer.DialerSession;
import io.circuitdrift.androidialup.platform.dialer.TestList;

import java.util.ArrayList;
import java.util.List;

/**
 * Developer dialer (S1_DIALER_GUI): one operator-chosen destination, dial method, per-call
 * timeout, live modem state rendered from the controller snapshot, Cancel / Hang Up, an optional
 * confirmed test list of individual entries (one explicit Dial tap per call, never
 * auto-advanced; there is deliberately no range input or sweep control), and the call-progress
 * log.
 */
public final class DialerActivity extends ServiceActivity implements ModemService.Observer {
    private static final int LOG_LINES = 20;

    private TextView status;
    private TextView console;
    private EditText target;
    private RadioGroup method;
    private EditText timeoutSeconds;
    private Button dialButton;
    private Button cancelButton;
    private Button hangUpButton;
    private EditText listInput;
    private CheckBox attestation;
    private LinearLayout listEntries;
    private TestList testList;
    private final List<Button> entryButtons = new ArrayList<>();
    private TextCallLogView logView;
    private DialerSession.Snapshot lastSnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("AndroidDialup dialer");
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void buildUi() {
        heading("Modem");
        status = mono("service not bound");

        heading("Destination");
        target = new EditText(this);
        target.setHint("target (1..256 bytes, e.g. 5551212 or loopback)");
        target.setSingleLine(true);
        root.addView(target);

        method = new RadioGroup(this);
        method.setOrientation(LinearLayout.HORIZONTAL);
        for (DialMethod m : DialMethod.values()) {
            RadioButton option = new RadioButton(this);
            option.setText(m.name() + " (" + m.prefix() + ")");
            option.setId(1000 + m.ordinal());
            method.addView(option);
        }
        method.check(1000 + DialMethod.TONE.ordinal());
        root.addView(method);

        timeoutSeconds = new EditText(this);
        timeoutSeconds.setHint("per-call timeout, seconds");
        timeoutSeconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeoutSeconds.setText("60");
        root.addView(timeoutSeconds);

        dialButton = button("Dial", v -> {
            DestinationInput input = input(target.getText().toString());
            if (input != null && service != null) service.dialer().dial(input);
        });
        cancelButton = button("Cancel (ATH while dialing)", v -> {
            if (service != null) service.dialer().cancel();
        });
        hangUpButton = button("Hang up (ATH)", v -> {
            if (service != null) service.dialer().hangUp();
        });

        heading("Console");
        console = mono("");

        heading("Test list (your own / authorized lines only)");
        text("One individual destination per line, at most " + TestList.MAX_ENTRIES
                + ". No ranges. Each entry is dialed only when you tap it.");
        listInput = new EditText(this);
        listInput.setHint("5551212\n5551313");
        listInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        listInput.setMinLines(3);
        root.addView(listInput);
        attestation = new CheckBox(this);
        attestation.setText(TestList.ATTESTATION_TEXT);
        root.addView(attestation);
        button("Load test list", v -> loadTestList());
        listEntries = new LinearLayout(this);
        listEntries.setOrientation(LinearLayout.VERTICAL);
        root.addView(listEntries);

        heading("Call-progress log");
        logView = new TextCallLogView(mono("(no calls yet)"), LOG_LINES);
        button("Export log (NDJSON)", v -> exportLog());
        button("Network policy and relay settings", v ->
                startActivity(new Intent(this, NetworkActivity.class)));
    }

    private DestinationInput input(String rawTarget) {
        try {
            return new DestinationInput(rawTarget.trim(), chosenMethod(), timeoutMs());
        } catch (IllegalArgumentException invalid) {
            toast("Not dialed: " + invalid.getMessage());
            return null;
        }
    }

    private DialMethod chosenMethod() {
        int index = method.getCheckedRadioButtonId() - 1000;
        return index >= 0 && index < DialMethod.values().length ? DialMethod.values()[index] : DialMethod.AUTO;
    }

    /** Per-call timeout in ms; throws IllegalArgumentException (incl. NumberFormatException). */
    private long timeoutMs() {
        long seconds = Long.parseLong(timeoutSeconds.getText().toString().trim());
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("timeout must be 1..3600 s");
        return seconds * 1000L;
    }

    private void loadTestList() {
        listEntries.removeAllViews();
        entryButtons.clear();
        testList = null;
        if (!attestation.isChecked()) {
            toast("Confirm the authorization statement first");
            return;
        }
        List<String> targets = new ArrayList<>();
        for (String line : listInput.getText().toString().split("\n")) {
            if (!line.trim().isEmpty()) targets.add(line.trim());
        }
        if (service == null) return;
        try {
            testList = TestList.of(targets, chosenMethod(), timeoutMs());
        } catch (IllegalArgumentException invalid) {
            toast("Test list blocked: " + invalid.getMessage());
            return;
        }
        testList.attest(service.callLog(), android.os.SystemClock.elapsedRealtime());
        for (int i = 0; i < testList.entries().size(); i++) {
            int index = i;
            Button entry = new Button(this);
            entry.setText("Dial entry " + (i + 1) + ": " + CallLogRecord.redact(testList.entries().get(i).target()));
            entry.setOnClickListener(v -> {
                if (service != null) service.dialer().dial(testList.entryForDial(index), index + 1);
            });
            listEntries.addView(entry);
            applyTooltipsRecursive(entry);
            entryButtons.add(entry);
        }
        render();
    }

    private void exportLog() {
        if (service == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/x-ndjson");
        send.putExtra(Intent.EXTRA_TEXT, service.callLog().exportNdjson());
        startActivity(Intent.createChooser(send, "Export call-progress log"));
    }

    @Override
    protected void onServiceBound() {
        service.addObserver(this);
        service.callLog().addListener(logView);
        logView.showAll(service.callLog());
    }

    @Override
    protected void onServiceUnbinding() {
        service.removeObserver(this);
        service.callLog().removeListener(logView);
    }

    @Override
    protected void refresh() {
        render();
    }

    @Override
    public void onDialerSnapshot(DialerSession.Snapshot snapshot) {
        lastSnapshot = snapshot;
        render();
    }

    @Override
    public void onDialerRejected(String reason) {
        toast("Not dialed: " + reason);
    }

    @Override
    public void onModemLine(String line) {
        CharSequence previous = console.getText();
        String next = previous + (previous.length() == 0 ? "" : "\n") + line;
        String[] lines = next.split("\n");
        int from = Math.max(0, lines.length - 8);
        console.setText(String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length)));
    }

    /** Renders only what the controller snapshot says; no state is inferred locally. */
    private void render() {
        if (status == null) return;
        if (service == null || lastSnapshot == null) {
            status.setText("service not bound");
            return;
        }
        ModemSnapshot modem = lastSnapshot.modem();
        ModemState state = modem.state();
        status.setText("state: " + state
                + "\nDCD: " + (modem.signals().dcd() ? "ON" : "off")
                + "  DSR: " + (modem.signals().dsr() ? "ON" : "off")
                + "  CTS: " + (modem.signals().cts() ? "ON" : "off")
                + "\nresult: " + (lastSnapshot.lastResult().isEmpty() ? "-" : lastSnapshot.lastResult())
                + "\nlast reason: " + modem.terminalReason().orElse("-")
                + "\ncall: " + (lastSnapshot.callInProgress() ? lastSnapshot.currentTargetRedacted() : "none")
                + "\nnetwork: " + service.diagnostics().selectedTransport()
                + " (" + service.policy() + ")"
                + "\nTCP DTE: " + service.dteStatus());
        boolean idle = state == ModemState.COMMAND && !lastSnapshot.callInProgress();
        dialButton.setEnabled(idle);
        for (Button entry : entryButtons) entry.setEnabled(idle);
        cancelButton.setEnabled(state == ModemState.DIALING);
        hangUpButton.setEnabled(state != ModemState.COMMAND);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Call-log view behind {@link CallLog.Listener}: newest records as text. */
    private final class TextCallLogView implements CallLog.Listener {
        private final TextView view;
        private final int maxRecords;

        TextCallLogView(TextView view, int maxRecords) {
            this.view = view;
            this.maxRecords = maxRecords;
        }

        void showAll(CallLog log) {
            List<CallLogRecord> records = log.records();
            StringBuilder out = new StringBuilder();
            for (int i = records.size() - 1; i >= 0 && i >= records.size() - maxRecords; i--) {
                out.append(format(records.get(i))).append('\n');
            }
            view.setText(out.length() == 0 ? "(no calls yet)" : out.toString());
        }

        @Override
        public void onRecordAppended(CallLogRecord record) {
            main.post(() -> {
                if (service != null) showAll(service.callLog());
            });
        }

        private String format(CallLogRecord r) {
            StringBuilder phases = new StringBuilder();
            for (CallLogRecord.Progress p : r.progress()) {
                if (phases.length() > 0) phases.append('>');
                phases.append(p.phase());
            }
            return r.targetRedacted() + " " + r.dialMethod() + " -> " + r.outcome() + " (" + r.internalReason()
                    + ") " + (r.endedAtMonotonicMs() - r.startedAtMonotonicMs()) + " ms"
                    + (phases.length() == 0 ? "" : " [" + phases + "]")
                    + (r.testListEntry() == null ? "" : " list#" + r.testListEntry());
        }
    }
}
