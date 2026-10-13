package io.circuitdrift.androidialup.app;

import static org.junit.Assert.assertEquals;

import io.circuitdrift.androidialup.network.NetworkPolicy;
import org.junit.Test;

/** Pure-JVM regression coverage for resilient persisted network policy parsing. */
public final class RelaySettingsTest {
    @Test
    public void missingOrInvalidPersistedNetworkPolicyDefaultsToAutomatic() {
        assertEquals(NetworkPolicy.AUTOMATIC, RelaySettings.parsePolicy(null));
        assertEquals(NetworkPolicy.AUTOMATIC, RelaySettings.parsePolicy(""));
        assertEquals(NetworkPolicy.AUTOMATIC, RelaySettings.parsePolicy("UNRECOGNIZED"));
    }

    @Test
    public void validPersistedNetworkPoliciesPreserveUserChoice() {
        for (NetworkPolicy policy : NetworkPolicy.values()) {
            assertEquals(policy, RelaySettings.parsePolicy(policy.name()));
        }
    }
}
