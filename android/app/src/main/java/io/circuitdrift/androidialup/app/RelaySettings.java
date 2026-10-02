package io.circuitdrift.androidialup.app;

import android.content.Context;
import android.content.SharedPreferences;

import io.circuitdrift.androidialup.network.NetworkPolicy;

/**
 * Developer relay configuration in app-private SharedPreferences.
 *
 * <p>Developer build only: the device secret is stored as hex in private preferences, not yet
 * in Android Keystore-backed storage as S1_SPEC_FREEZE section 11 requires for release (see
 * docs/implementation/I1_ANDROID_PLATFORM_STATUS.md, remaining work). It is never logged or
 * shown back in the UI.
 */
final class RelaySettings {
    private static final String PREFS = "relay_settings";

    final String host;
    final int port;
    final String endpointIdHex;
    final String deviceSecretHex;
    final String caPem;
    final int dtePort;
    final NetworkPolicy policy;

    RelaySettings(String host, int port, String endpointIdHex, String deviceSecretHex, String caPem,
                  int dtePort, NetworkPolicy policy) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.endpointIdHex = endpointIdHex == null ? "" : endpointIdHex.trim();
        this.deviceSecretHex = deviceSecretHex == null ? "" : deviceSecretHex.trim();
        this.caPem = caPem == null ? "" : caPem.trim();
        this.dtePort = dtePort;
        this.policy = policy;
    }

    static RelaySettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NetworkPolicy policy;
        try {
            policy = NetworkPolicy.valueOf(prefs.getString("policy", NetworkPolicy.AUTOMATIC.name()));
        } catch (IllegalArgumentException unknown) {
            policy = NetworkPolicy.AUTOMATIC;
        }
        return new RelaySettings(
                prefs.getString("host", ""),
                prefs.getInt("port", 4443),
                prefs.getString("endpoint_id", ""),
                prefs.getString("device_secret", ""),
                prefs.getString("ca_pem", ""),
                prefs.getInt("dte_port", 2323),
                policy);
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("endpoint_id", endpointIdHex)
                .putString("device_secret", deviceSecretHex)
                .putString("ca_pem", caPem)
                .putInt("dte_port", dtePort)
                .putString("policy", policy.name())
                .apply();
    }

    RelaySettings withPolicy(NetworkPolicy next) {
        return new RelaySettings(host, port, endpointIdHex, deviceSecretHex, caPem, dtePort, next);
    }

    /** Null when complete, else the reason dialing cannot start (S1 LOCAL_CONFIG). */
    String incompleteReason() {
        if (host.isEmpty()) return "relay host not set";
        if (port < 1 || port > 65535) return "relay port out of range";
        if (decodeHex(endpointIdHex) == null || decodeHex(endpointIdHex).length != 32) {
            return "endpoint id must be 64 hex characters";
        }
        byte[] secret = decodeHex(deviceSecretHex);
        if (secret == null || secret.length == 0) return "device secret must be non-empty hex";
        return null;
    }

    /** Hex decoder (java.util.HexFormat is API 34+ on Android). Null on malformed input. */
    static byte[] decodeHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
