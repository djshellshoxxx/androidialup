package io.circuitdrift.androidialup.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkSelectionEngineTest {
    private static NetworkCandidate c(String id, NetworkTransport t, double rtt) {
        return new NetworkCandidate(id, t, true, true, false, false, rtt, 0.0);
    }

    @Test
    void wifiOnlyAndCellularOnlyFilterTransports() {
        var engine = new NetworkSelectionEngine();
        var candidates = List.of(c("wifi", NetworkTransport.WIFI, 100), c("cell", NetworkTransport.CELLULAR, 10));
        assertEquals("wifi", engine.select(candidates, NetworkPolicy.WIFI_ONLY, null, false, false).selectedId());
        assertEquals("cell", engine.select(candidates, NetworkPolicy.CELLULAR_ONLY, null, false, false).selectedId());
    }

    @Test
    void unvalidatedIsIneligibleUnlessDeveloperOverrideEnabled() {
        var engine = new NetworkSelectionEngine();
        var candidate = new NetworkCandidate("wifi", NetworkTransport.WIFI, true, false, false, false, 10.0, 0.0);
        assertNull(engine.select(List.of(candidate), NetworkPolicy.AUTOMATIC, null, false, false).selectedId());
        assertEquals("wifi", engine.select(List.of(candidate), NetworkPolicy.AUTOMATIC, null, false, true).selectedId());
    }

    @Test
    void scoringMatchesFrozenS1Formula() {
        var engine = new NetworkSelectionEngine();
        var candidate = new NetworkCandidate("cell", NetworkTransport.CELLULAR, true, true, true, true, 100.0, 0.10);
        assertEquals(725.0, engine.score(candidate, NetworkPolicy.PREFER_CELLULAR, false), 0.0001);
    }

    @Test
    void unknownRttUses250PointPenalty() {
        var engine = new NetworkSelectionEngine();
        var candidate = new NetworkCandidate("wifi", NetworkTransport.WIFI, true, true, false, false, null, null);
        assertEquals(750.0, engine.score(candidate, NetworkPolicy.AUTOMATIC, false), 0.0001);
    }

    @Test
    void lossPenaltyClampsAtHalf() {
        var engine = new NetworkSelectionEngine();
        var candidate = new NetworkCandidate("wifi", NetworkTransport.WIFI, true, true, false, false, 0.0, 0.9);
        assertEquals(500.0, engine.score(candidate, NetworkPolicy.AUTOMATIC, false), 0.0001);
    }

    @Test
    void preferWifiBonusCanBeatLowerRttCellular() {
        var engine = new NetworkSelectionEngine();
        var candidates = List.of(c("wifi", NetworkTransport.WIFI, 100), c("cell", NetworkTransport.CELLULAR, 10));
        assertEquals("wifi", engine.select(candidates, NetworkPolicy.PREFER_WIFI, null, false, false).selectedId());
    }

    @Test
    void hysteresisRequiresExact100PointImprovement() {
        var engine = new NetworkSelectionEngine();
        var selected = c("wifi", NetworkTransport.WIFI, 200);
        var notEnough = c("cell", NetworkTransport.CELLULAR, 101);
        var exactBoundary = c("cell2", NetworkTransport.CELLULAR, 100);
        assertEquals("wifi", engine.select(List.of(selected, notEnough), NetworkPolicy.AUTOMATIC, "wifi", false, false).selectedId());
        assertEquals("cell2", engine.select(List.of(selected, exactBoundary), NetworkPolicy.AUTOMATIC, "wifi", false, false).selectedId());
    }

    @Test
    void selectedBecomingIneligibleBypassesHysteresis() {
        var engine = new NetworkSelectionEngine();
        var selected = new NetworkCandidate("wifi", NetworkTransport.WIFI, true, false, false, false, 1.0, 0.0);
        var cell = c("cell", NetworkTransport.CELLULAR, 700);
        assertEquals("cell", engine.select(List.of(selected, cell), NetworkPolicy.AUTOMATIC, "wifi", false, false).selectedId());
    }

    @Test
    void activeCallDoesNotMigrateForBetterCandidate() {
        var engine = new NetworkSelectionEngine();
        var selected = c("cell", NetworkTransport.CELLULAR, 400);
        var wifi = c("wifi", NetworkTransport.WIFI, 5);
        var decision = engine.select(List.of(selected, wifi), NetworkPolicy.AUTOMATIC, "cell", true, false);
        assertEquals("cell", decision.selectedId());
        assertTrue(decision.betterNetworkAvailable());
        assertFalse(decision.changed());
    }

    @Test
    void deterministicTieBreakPrefersWifiThenCellularThenLexicalId() {
        var engine = new NetworkSelectionEngine();
        var other = c("other-a", NetworkTransport.OTHER, 10);
        var cell = c("cell-z", NetworkTransport.CELLULAR, 10);
        var wifiB = c("wifi-b", NetworkTransport.WIFI, 10);
        var wifiA = c("wifi-a", NetworkTransport.WIFI, 10);
        assertEquals("wifi-a", engine.select(List.of(other, cell, wifiB, wifiA), NetworkPolicy.AUTOMATIC, null, false, false).selectedId());
    }

    @Test
    void malformedMeasurementsNormalizeDeterministically() {
        var bad = new NetworkCandidate("bad", NetworkTransport.WIFI, true, true, false, false, Double.NaN, -2.0);
        assertNull(bad.relayProbeRttMs());
        assertEquals(0.0, bad.relayProbeLoss());
    }
}
