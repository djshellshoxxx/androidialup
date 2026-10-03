package io.circuitdrift.androidialup.platform.dialer;

/** S1_DIALER_GUI section 2 dial method, mapped to the V.250 dial command prefix. */
public enum DialMethod {
    AUTO("ATD"),
    TONE("ATDT"),
    PULSE("ATDP");

    private final String prefix;

    DialMethod(String prefix) {
        this.prefix = prefix;
    }

    /** The AT command prefix ({@code ATD}, {@code ATDT} or {@code ATDP}). */
    public String prefix() {
        return prefix;
    }
}
