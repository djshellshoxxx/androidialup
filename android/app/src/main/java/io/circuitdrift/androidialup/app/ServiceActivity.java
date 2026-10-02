package io.circuitdrift.androidialup.app;

import android.app.Activity;
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
        buildUi();
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
