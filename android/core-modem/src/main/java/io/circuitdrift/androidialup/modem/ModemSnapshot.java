package io.circuitdrift.androidialup.modem;

import java.util.Optional;

/**
 * Immutable view of the controller for diagnostics.
 *
 * @param state current modem state
 * @param signals current logical DTE signals
 * @param terminalReason reason of the most recent call termination, if any
 */
public record ModemSnapshot(ModemState state, DteSignals signals, Optional<String> terminalReason) {}
