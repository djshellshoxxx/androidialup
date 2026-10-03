package io.circuitdrift.androidialup.modem;

/** Beta 0.1 operating modes accepted by {@code AT+MODE=} (S1 AT/DTE section 11). */
public enum ModemMode {
    AUTO,
    BYTE_RELAY,
    PCM_VBD_EXPERIMENTAL
}
