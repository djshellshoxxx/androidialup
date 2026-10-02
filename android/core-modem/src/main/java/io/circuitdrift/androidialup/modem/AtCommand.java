package io.circuitdrift.androidialup.modem;

import java.util.Objects;

/**
 * One parsed Beta 0.1 AT command (S1 AT/DTE section 4). Instances are immutable value
 * objects produced by {@link AtLineParser} and consumed by {@link AtEngine}.
 */
public sealed interface AtCommand {

    /** Bare {@code AT}. */
    record Attention() implements AtCommand {}

    /** {@code ATE0}/{@code ATE1}. */
    record Echo(boolean enabled) implements AtCommand {}

    /** {@code ATQ0}/{@code ATQ1}. */
    record Quiet(boolean enabled) implements AtCommand {}

    /** {@code ATV0}/{@code ATV1}. */
    record Verbose(boolean enabled) implements AtCommand {}

    /** {@code ATZ}. */
    record Reset() implements AtCommand {}

    /** {@code AT&F}. */
    record Factory() implements AtCommand {}

    /** {@code ATH}/{@code ATH0}. */
    record Hangup() implements AtCommand {}

    /** {@code ATA}. */
    record Answer() implements AtCommand {}

    /** {@code ATD<target>}, {@code ATDT<target>} or {@code ATDP<target>}; the target keeps its case. */
    record Dial(String target) implements AtCommand {
        public Dial {
            Objects.requireNonNull(target, "target");
        }
    }

    /** {@code ATO}. */
    record Online() implements AtCommand {}

    /** {@code ATI}/{@code ATI0..ATI4}. */
    record Identify(int page) implements AtCommand {}

    /** {@code ATS<n>?}. */
    record SQuery(int register) implements AtCommand {}

    /** {@code ATS<n>=<value>}. */
    record SSet(int register, int value) implements AtCommand {}

    /**
     * {@code AT+<NAME>?} or {@code AT+<NAME>=<value>} for {@code MODE}, {@code NET} and
     * {@code DIAG}. The name is upper-cased; the value is passed through verbatim and is
     * {@code null} for a query.
     */
    record Plus(String name, Operation operation, String value) implements AtCommand {
        /** Whether the extended command reads or writes. */
        public enum Operation {
            QUERY,
            SET
        }

        public Plus {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
