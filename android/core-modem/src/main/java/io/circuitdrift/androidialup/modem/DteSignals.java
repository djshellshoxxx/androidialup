package io.circuitdrift.androidialup.modem;

/** Logical DTE control signals (S1 AT/DTE section 15). */
public record DteSignals(boolean dsr, boolean dcd, boolean cts, boolean ri) {

    /** DTE connected before any call: DSR=1, DCD=0, CTS=1, RI=0. */
    public static final DteSignals INITIAL = new DteSignals(true, false, true, false);

    public DteSignals withDcd(boolean value) {
        return new DteSignals(dsr, value, cts, ri);
    }
}
