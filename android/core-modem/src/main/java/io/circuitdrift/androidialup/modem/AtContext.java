package io.circuitdrift.androidialup.modem;

import java.util.Objects;

/**
 * Read-only view of modem/session state that command execution needs: whether a call is active
 * (to reject {@code +MODE=} and to let {@code ATZ} hang up), whether the DTE is in
 * ONLINE_COMMAND (for {@code ATO}) and the values reported by {@code +NET?} and {@code +DIAG?}.
 *
 * @param activeCall true in ONLINE_DATA or ONLINE_COMMAND
 * @param onlineCommand true in ONLINE_COMMAND
 * @param selectedNetwork the selected network name reported by {@code +NET?}
 * @param validated whether the selected network is validated
 * @param metered whether the selected network is metered
 * @param modemState modem state name reported by {@code +DIAG?}
 * @param sessionState session state name reported by {@code +DIAG?}
 */
public record AtContext(
        boolean activeCall,
        boolean onlineCommand,
        String selectedNetwork,
        boolean validated,
        boolean metered,
        String modemState,
        String sessionState) {

    /** Idle COMMAND state with no network selected. */
    public static final AtContext DEFAULT =
            new AtContext(false, false, "NONE", false, false, "COMMAND", "IDLE");

    public AtContext {
        Objects.requireNonNull(selectedNetwork, "selectedNetwork");
        Objects.requireNonNull(modemState, "modemState");
        Objects.requireNonNull(sessionState, "sessionState");
    }

    public AtContext withActiveCall(boolean value) {
        return new AtContext(value, onlineCommand, selectedNetwork, validated, metered, modemState, sessionState);
    }

    public AtContext withOnlineCommand(boolean value) {
        return new AtContext(activeCall, value, selectedNetwork, validated, metered, modemState, sessionState);
    }

    public AtContext withNetwork(String selected, boolean isValidated, boolean isMetered) {
        return new AtContext(activeCall, onlineCommand, selected, isValidated, isMetered, modemState, sessionState);
    }

    public AtContext withStates(String modem, String session) {
        return new AtContext(activeCall, onlineCommand, selectedNetwork, validated, metered, modem, session);
    }
}
