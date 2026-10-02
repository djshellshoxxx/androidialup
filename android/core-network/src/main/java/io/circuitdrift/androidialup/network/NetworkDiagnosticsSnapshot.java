package io.circuitdrift.androidialup.network;

import java.util.Map;
import java.util.Objects;

public record NetworkDiagnosticsSnapshot(
        NetworkPolicy policy,
        String selectedId,
        NetworkTransport selectedTransport,
        boolean validated,
        boolean metered,
        boolean roaming,
        Double relayProbeRttMs,
        Double relayProbeLoss,
        String selectionReason,
        boolean activeCall,
        boolean developerOverride,
        boolean betterNetworkAvailable,
        String betterNetworkId,
        Map<String, Double> scores
) {
    public NetworkDiagnosticsSnapshot {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(selectionReason, "selectionReason");
        scores = Map.copyOf(scores);
    }

    public static NetworkDiagnosticsSnapshot from(
            NetworkPolicy policy,
            NetworkCandidate selected,
            NetworkSelectionEngine.SelectionDecision decision,
            boolean activeCall,
            boolean developerOverride
    ) {
        Objects.requireNonNull(decision, "decision");
        return new NetworkDiagnosticsSnapshot(
                policy,
                selected == null ? null : selected.id(),
                selected == null ? null : selected.transport(),
                selected != null && selected.validated(),
                selected != null && selected.metered(),
                selected != null && selected.roaming(),
                selected == null ? null : selected.relayProbeRttMs(),
                selected == null ? null : selected.relayProbeLoss(),
                decision.reason(),
                activeCall,
                developerOverride,
                decision.betterNetworkAvailable(),
                decision.betterNetworkId(),
                decision.scores()
        );
    }
}
