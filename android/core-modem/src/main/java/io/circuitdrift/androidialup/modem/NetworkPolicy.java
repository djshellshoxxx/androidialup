package io.circuitdrift.androidialup.modem;

/** Network policies accepted by {@code AT+NET=} (S1 AT/DTE section 12). */
public enum NetworkPolicy {
    AUTO,
    WIFI_ONLY,
    CELLULAR_ONLY,
    PREFER_WIFI,
    PREFER_CELLULAR
}
