package io.circuitdrift.androidialup.platform;

import io.circuitdrift.androidialup.network.NetworkCandidate;
import io.circuitdrift.androidialup.network.NetworkTransport;

public final class CandidateNormalizer {
    private CandidateNormalizer() {}

    public static NetworkCandidate normalize(
            String id,
            NetworkTransport transport,
            boolean internet,
            boolean validated,
            boolean notMetered,
            boolean notRoaming,
            Double relayProbeRttMs,
            Double relayProbeLoss
    ) {
        return new NetworkCandidate(
                id,
                transport,
                internet,
                validated,
                !notMetered,
                !notRoaming,
                relayProbeRttMs,
                relayProbeLoss
        );
    }
}
