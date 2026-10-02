package io.circuitdrift.androidialup.modem;

/** V.250 result codes with their Beta numeric and text forms (S1 AT/DTE section 5). */
public enum ResultCode {
    OK(0, "OK"),
    CONNECT(1, "CONNECT"),
    RING(2, "RING"),
    NO_CARRIER(3, "NO CARRIER"),
    ERROR(4, "ERROR"),
    NO_DIALTONE(6, "NO DIALTONE"),
    BUSY(7, "BUSY"),
    NO_ANSWER(8, "NO ANSWER");

    private final int number;
    private final String text;

    ResultCode(int number, String text) {
        this.number = number;
        this.text = text;
    }

    /** Numeric form emitted under {@code ATV0}. */
    public int number() {
        return number;
    }

    /** Text form emitted under {@code ATV1}. */
    public String text() {
        return text;
    }

    /** Whether this code is one of the four permitted dial-failure results (S1 section 7). */
    public boolean isDialFailure() {
        return this == BUSY || this == NO_DIALTONE || this == NO_ANSWER || this == NO_CARRIER;
    }
}
