package io.circuitdrift.androidialup.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
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
import io.circuitdrift.androidialup.platform.dte.UsbAccessoryCoordinator;
import io.circuitdrift.androidialup.platform.dte.UsbAccessoryDteTransport;
import io.circuitdrift.androidialup.platform.dte.UsbDteLifecycle;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocketFactory;

/**
 * Foreground service owning the modem runtime (S1_NETWORK_THREADING section 14): exactly one
 * {@link AndroidNetworkManager}, the developer loopback {@link TcpDteServer}, the I2 Android Open
 * Accessory USB DTE, the developer {@link DialerSession} and, per call, a
 * {@link RelayTlsTransport} on the network selected at dial time. UI components never touch
 * ConnectivityManager or relay sockets; they bind to this service and call its small API.
 */
public final class ModemService extends Service {
    private static final String TAG = "ModemService";
    private static final String CHANNEL_ID = "modem";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_USB_PERMISSION =
            "io.circuitdrift.androidialup.action.USB_ACCESSORY_PERMISSION";

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

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbAccessory accessory = accessoryFrom(intent);
            UsbAccessoryCoordinator<UsbAccessory> coordinator = usbCoordinator;
            if (accessory == null || coordinator == null || usbManager == null) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    && usbManager.hasPermission(accessory);
            coordinator.permissionResult(accessory, granted);
        }
    };

    private final BroadcastReceiver usbAttachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbAccessoryCoordinator<UsbAccessory> coordinator = usbCoordinator;
            if (coordinator == null) return;
            UsbAccessory accessory = accessoryFrom(intent);
            if (accessory == null) return;
            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
                coordinator.discovered(accessory);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) {
                coordinator.detached(accessory);
            }
        }
    };

    private AndroidNetworkManager network;
    private DialerSession dialer;
    private TcpDteServer dteServer;
    private UsbManager usbManager;
    private UsbAccessoryCoordinator<UsbAccessory> usbCoordinator;
    private boolean usbReceiversRegistered;
    private volatile RelaySettings settings;
    private volatile String dteStatus = "starting";
    private volatile String usbDteStatus = "USB DTE not initialized";

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

        setupUsbDte();
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
        unregisterUsbReceivers();
        UsbAccessoryCoordinator<UsbAccessory> coordinator = usbCoordinator;
        usbCoordinator = null;
        if (coordinator != null) coordinator.close();
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

    public String usbDteStatus() {
        return usbDteStatus;
    }

    // ---- TCP DTE -----------------------------------------------------------------------

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

    // ---- USB accessory DTE -------------------------------------------------------------

    private void setupUsbDte() {
        usbManager = getSystemService(UsbManager.class);
        if (usbManager == null) {
            usbDteStatus = "USB accessory API unavailable";
            return;
        }

        UsbDteLifecycle<UsbAccessory> lifecycle = new UsbDteLifecycle<>(
                this::createUsbTransport, ignored -> {});
        usbCoordinator = new UsbAccessoryCoordinator<>(
                lifecycle,
                usbManager::hasPermission,
                this::requestUsbPermission,
                value -> usbDteStatus = value);
        registerUsbReceivers();

        UsbAccessory[] attached = usbManager.getAccessoryList();
        if (attached == null || attached.length == 0) {
            usbDteStatus = "no USB accessory";
            return;
        }
        usbCoordinator.discovered(attached[0]);
    }

    private UsbAccessoryDteTransport createUsbTransport(UsbAccessory accessory) {
        AtomicReference<RelayModemSessionPort> relayPort = new AtomicReference<>();
        return new UsbAccessoryDteTransport(
                usbManager,
                accessory,
                (writer, modemExecutor) -> {
                    RelayModemSessionPort port = new RelayModemSessionPort(this::openTransport, modemExecutor);
                    ModemController controller = new ModemController(port, writer, BuildInfo.ID);
                    port.bind(controller);
                    relayPort.set(port);
                    return controller;
                },
                new UsbAccessoryDteTransport.Listener() {
                    @Override public void onClosed(UsbAccessory closedAccessory, String reason) {
                        RelayModemSessionPort port = relayPort.getAndSet(null);
                        if (port != null) port.close();
                        UsbAccessoryCoordinator<UsbAccessory> coordinator = usbCoordinator;
                        if (coordinator != null) coordinator.transportClosed(closedAccessory, reason);
                    }
                });
    }

    private void requestUsbPermission(UsbAccessory accessory) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        Intent result = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, 1, result, flags);
        usbManager.requestPermission(accessory, pending);
    }

    private void registerUsbReceivers() {
        if (usbReceiversRegistered) return;
        IntentFilter permission = new IntentFilter(ACTION_USB_PERMISSION);
        IntentFilter attach = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        attach.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permission, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbAttachReceiver, attach, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, permission);
            registerReceiver(usbAttachReceiver, attach);
        }
        usbReceiversRegistered = true;
    }

    private void unregisterUsbReceivers() {
        if (!usbReceiversRegistered) return;
        usbReceiversRegistered = false;
        try { unregisterReceiver(usbPermissionReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(usbAttachReceiver); } catch (IllegalArgumentException ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory accessoryFrom(Intent intent) {
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
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
                .setContentText("TCP/USB DTE and relay service running")
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
