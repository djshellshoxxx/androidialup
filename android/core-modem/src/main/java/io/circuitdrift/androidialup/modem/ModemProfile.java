package io.circuitdrift.androidialup.modem;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The single effective runtime profile of the Beta modem (S1 AT/DTE sections 10 and 16).
 *
 * <p>Holds the E/Q/V switches, the supported S-registers, the preferred {@link ModemMode}, the
 * {@link NetworkPolicy} and the extended-diagnostics flag. {@link #restoreFactory()} implements
 * the setting side of both {@code AT&F} and {@code ATZ}; the hangup side of {@code ATZ} is an
 * {@link AtEngine} effect. Unsupported S-registers are rejected rather than fabricated.
 */
public final class ModemProfile {

    /** S2: escape character. */
    public static final int S2_ESCAPE_CHAR = 2;
    /** S3: command line terminator. */
    public static final int S3_TERMINATOR = 3;
    /** S4: response formatting line feed. */
    public static final int S4_LINE_FEED = 4;
    /** S5: backspace. */
    public static final int S5_BACKSPACE = 5;
    /** S7: wait-for-carrier timeout in seconds. */
    public static final int S7_CARRIER_WAIT = 7;
    /** S12: escape guard time in 1/50 s units. */
    public static final int S12_GUARD_TIME = 12;

    /** Factory S-register values from S1 AT/DTE section 10. */
    public static final Map<Integer, Integer> FACTORY_S_REGISTERS =
            Collections.unmodifiableMap(
                    new TreeMap<>(Map.of(0, 0, 2, 43, 3, 13, 4, 10, 5, 8, 7, 60, 10, 14, 12, 50)));

    private boolean echo;
    private boolean quiet;
    private boolean verbose;
    private ModemMode mode;
    private NetworkPolicy networkPolicy;
    private boolean extendedDiagnostics;
    private final TreeMap<Integer, Integer> sRegisters = new TreeMap<>();

    private ModemProfile() {
        restoreFactory();
    }

    /** Returns a new profile holding compiled factory defaults. */
    public static ModemProfile factory() {
        return new ModemProfile();
    }

    /** Resets every setting and S-register to factory defaults. */
    public void restoreFactory() {
        echo = true;
        quiet = false;
        verbose = true;
        mode = ModemMode.AUTO;
        networkPolicy = NetworkPolicy.AUTO;
        extendedDiagnostics = false;
        sRegisters.clear();
        sRegisters.putAll(FACTORY_S_REGISTERS);
    }

    public boolean echo() {
        return echo;
    }

    public void setEcho(boolean echo) {
        this.echo = echo;
    }

    public boolean quiet() {
        return quiet;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public boolean verbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public ModemMode mode() {
        return mode;
    }

    public void setMode(ModemMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public NetworkPolicy networkPolicy() {
        return networkPolicy;
    }

    public void setNetworkPolicy(NetworkPolicy networkPolicy) {
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
    }

    public boolean extendedDiagnostics() {
        return extendedDiagnostics;
    }

    public void setExtendedDiagnostics(boolean extendedDiagnostics) {
        this.extendedDiagnostics = extendedDiagnostics;
    }

    /** Whether {@code register} is one of the Beta-supported S-registers. */
    public boolean hasSRegister(int register) {
        return sRegisters.containsKey(register);
    }

    /**
     * Reads an S-register.
     *
     * @throws IllegalArgumentException if the register is unsupported
     */
    public int sRegister(int register) {
        Integer value = sRegisters.get(register);
        if (value == null) {
            throw new IllegalArgumentException("unsupported S-register S" + register);
        }
        return value;
    }

    /**
     * Writes an S-register.
     *
     * @throws IllegalArgumentException if the register is unsupported or the value is outside 0..255
     */
    public void setSRegister(int register, int value) {
        if (!sRegisters.containsKey(register)) {
            throw new IllegalArgumentException("unsupported S-register S" + register);
        }
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("S" + register + " value out of range: " + value);
        }
        sRegisters.put(register, value);
    }

    /** Read-only view of all supported S-registers, ordered by number. */
    public Map<Integer, Integer> sRegisters() {
        return Collections.unmodifiableMap(sRegisters);
    }
}
