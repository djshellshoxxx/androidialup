package io.circuitdrift.androidialup.network;

import java.util.Objects;

public record NetworkCandidate(
        String id,
        NetworkTransport transport,
        boolean internet,
        boolean validated,
        boolean metered,
        boolean roaming,
        Double relayProbeRttMs,
        Double relayProbeLoss
) {
    public NetworkCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transport, "transport");
        relayProbeRttMs = normalizeRtt(relayProbeRttMs);
        relayProbeLoss = normalizeLoss(relayProbeLoss);
    }

    private static Double normalizeRtt(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            return null;
        }
        return value;
    }

    private static Double normalizeLoss(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.max(0.0, Math.min(value, 1.0));
    }
}
