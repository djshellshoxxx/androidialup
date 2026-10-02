package io.circuitdrift.androidialup.modem;

/** Top-level modem states owned by {@link ModemController} in Beta 0.1. */
public enum ModemState {
    COMMAND,
    DIALING,
    ONLINE_DATA,
    ONLINE_COMMAND;

    /** True while a dial is in progress or a call is up. */
    public boolean hasSession() {
        return this != COMMAND;
    }

    /** True while a call is up (ONLINE_DATA or ONLINE_COMMAND). */
    public boolean isActiveCall() {
        return this == ONLINE_DATA || this == ONLINE_COMMAND;
    }
}
