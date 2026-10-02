package io.circuitdrift.androidialup.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.ModemState;
import io.circuitdrift.androidialup.network.NetworkCandidate;
import io.circuitdrift.androidialup.network.NetworkDiagnosticsSnapshot;
import io.circuitdrift.androidialup.network.NetworkPolicy;
import io.circuitdrift.androidialup.platform.AndroidNetworkManager;
import io.circuitdrift.androidialup.platform.dialer.CallLog;
import io.circuitdrift.androidialup.platform.dialer.DialerSession;
import io.circuitdrift.androidialup.platform.dte.TcpDteServer;
import io.circuitdrift.androidialup.platform.relay.DeviceCredentialAuth;
import io.circuitdrift.androidialup.platform.relay.NetworkBoundRelayConnector;
import io.circuitdrift.androidialup.platform.relay.RelayConnectException;
import io.circuitdrift.androidialup.platform.relay.RelayModemSessionPort;
import io.circuitdrift.androidialup.platform.relay.RelayTls;
import io.circuitdrift.androidialup.platform.relay.RelayTlsTransport;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLSocketFactory;

/**
 * Foreground service owning the modem runtime (S1_NETWORK_THREADING section 14): exactly one
 * {@link AndroidNetworkManager}, the loopback {@link TcpDteServer}, the developer
 * {@link DialerSession} and, per call, a {@link RelayTlsTransport} on the network selected at
 * dial time. UI components never touch ConnectivityManager or sockets; they bind to this
 * service and call its small API. No socket work happens on the main thread.
 */
public final class ModemService extends Service {
    private static final String TAG = "ModemService";
    private static final String CHANNEL_ID = "modem";
    private static final int NOTIFICATION_ID = 1;

    /** Same-process binder. */
    public final class LocalBinder extends Binder {
        public ModemService service() {
            return ModemService.this;
        }
    }

    /** UI observer; always called on the main thread. */
    public interface Observer {
        void onDialerSnapshot(DialerSession.Snapshot snapshot);

        default void onDialerRejected(String reason) {}

        default void onModemLine(String line) {}
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CallLog callLog = new CallLog();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();

    private AndroidNetworkManager network;
    private DialerSession dialer;
    private TcpDteServer dteServer;
    private volatile RelaySettings settings;
    private volatile String dteStatus = "starting";

    @Override
    public void onCreate() {
        super.onCreate();
        settings = RelaySettings.load(this);
        createChannel();

        network = new AndroidNetworkManager(getSystemService(ConnectivityManager.class), null);
        network.setPolicy(settings.policy);
        network.start();

        dialer = new DialerSession(this::openTransport, callLog, new DialerSession.Listener() {
            @Override public void onSnapshot(DialerSession.Snapshot snapshot) {
                network.setActiveCall(snapshot.modem().state() != ModemState.COMMAND);
                main.post(() -> {
                    for (Observer observer : observers) observer.onDialerSnapshot(snapshot);
                });
            }

            @Override public void onRejected(String reason) {
                main.post(() -> {
                    for (Observer observer : observers) observer.onDialerRejected(reason);
                });
            }

            @Override public void onModemLine(String line) {
                main.post(() -> {
                    for (Observer observer : observers) observer.onModemLine(line);
                });
            }
        }, SystemClock::elapsedRealtime, System::nanoTime);

        startDteServer(settings.dtePort);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // Process death loses calls in Beta; do not resurrect the service with stale state.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (dteServer != null) dteServer.close();
        if (dialer != null) dialer.close();
        if (network != null) network.close();
        super.onDestroy();
    }

    // ---- API for the activities ---------------------------------------------------------

    public void addObserver(Observer observer) {
        observers.add(observer);
        observer.onDialerSnapshot(dialer.snapshot());
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public DialerSession dialer() {
        return dialer;
    }

    public CallLog callLog() {
        return callLog;
    }

    public NetworkDiagnosticsSnapshot diagnostics() {
        return network.diagnostics();
    }

    public NetworkPolicy policy() {
        return network.policy();
    }

    public void setPolicy(NetworkPolicy policy) {
        network.setPolicy(policy);
        settings = settings.withPolicy(policy);
        settings.save(this);
    }

    public void setDeveloperOverride(boolean enabled) {
        network.setDeveloperOverride(enabled);
    }

    public RelaySettings settings() {
        return settings;
    }

    public void updateSettings(RelaySettings next) {
        int previousDtePort = settings.dtePort;
        settings = next;
        next.save(this);
        network.setPolicy(next.policy);
        if (next.dtePort != previousDtePort) startDteServer(next.dtePort);
    }

    public String dteStatus() {
        return dteStatus;
    }

    // ---- internals ----------------------------------------------------------------------

    private void startDteServer(int port) {
        TcpDteServer previous = dteServer;
        TcpDteServer next = new TcpDteServer(InetAddress.getLoopbackAddress(), port, client -> {
            RelayModemSessionPort session = new RelayModemSessionPort(this::openTransport, client.modemExecutor());
            ModemController controller = new ModemController(session, client.writer(), BuildInfo.ID);
            session.bind(controller);
            client.addCloseHook(session::close);
            return controller;
        }, null, TcpDteServer.DEFAULT_TIMER_TICK_MS, TcpDteServer.DEFAULT_MAX_QUEUED_DTE_BYTES, System::nanoTime);
        dteServer = next;
        // Socket bind off the main thread.
        new Thread(() -> {
            if (previous != null) previous.close();
            try {
                next.start();
                dteStatus = "listening on 127.0.0.1:" + next.boundPort();
            } catch (IOException failure) {
                dteStatus = "DTE listener failed: " + failure.getClass().getSimpleName();
                Log.w(TAG, "TCP DTE listener failed on port " + port);
            }
        }, "dte-start").start();
    }

    /**
     * Opens one relay transport on the currently selected Network. Called from a modem thread
     * on ATD; the actual connect happens on the transport's own I/O thread.
     */
    private RelayModemSessionPort.Connection openTransport(RelayTlsTransport.Listener listener)
            throws RelayConnectException {
        Network selected = network.selectedNetwork();
        NetworkCandidate candidate = network.selectedCandidate();
        if (selected == null || candidate == null) {
            throw new RelayConnectException(RelayConnectException.NO_ELIGIBLE_NETWORK, "no selected network");
        }
        RelaySettings config = settings;
        String incomplete = config.incompleteReason();
        if (incomplete != null) throw new RelayConnectException("LOCAL_CONFIG", incomplete);

        SSLSocketFactory tls;
        try {
            KeyStore trust = null;
            if (!config.caPem.isEmpty()) {
                trust = RelayTls.trustStoreOf(new ByteArrayInputStream(config.caPem.getBytes(StandardCharsets.US_ASCII)));
            }
            tls = RelayTls.socketFactory(trust);
        } catch (GeneralSecurityException | IOException failure) {
            throw new RelayConnectException(RelayConnectException.TLS_FAILURE, "trust store", failure);
        }

        byte[] endpointId = RelaySettings.decodeHex(config.endpointIdHex);
        DeviceCredentialAuth auth = new DeviceCredentialAuth(RelaySettings.decodeHex(config.deviceSecretHex), endpointId);
        RelaySessionMachine machine = new RelaySessionMachine(endpointId, auth, this::newCallId);
        RelayTlsTransport transport = new RelayTlsTransport(machine,
                new NetworkBoundRelayConnector(selected, config.host, config.port, tls),
                listener, SystemClock::elapsedRealtime, RelayTlsTransport.Config.DEFAULT, auth);
        return new RelayModemSessionPort.Connection(transport, bearer(candidate));
    }

    private byte[] newCallId() {
        byte[] id = new byte[16];
        do {
            random.nextBytes(id);
        } while (isZero(id));
        return id;
    }

    private static boolean isZero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }

    private static Messages.NetworkTransport bearer(NetworkCandidate candidate) {
        switch (candidate.transport()) {
            case WIFI: return Messages.NetworkTransport.WIFI;
            case CELLULAR: return Messages.NetworkTransport.CELLULAR;
            case ETHERNET: return Messages.NetworkTransport.ETHERNET;
            default: return Messages.NetworkTransport.OTHER;
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Modem service",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DialerActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("AndroidDialup modem")
                .setContentText("TCP DTE and relay service running")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    /** Build identifier reported by ATI3. */
    static final class BuildInfo {
        static final String ID = "android-0.1-dev";

        private BuildInfo() {}
    }
}
