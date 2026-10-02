package io.circuitdrift.androidialup.network;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NetworkSelectionEngine {
    public static final double HANDOVER_MARGIN = 100.0;

    public record SelectionDecision(
            String selectedId,
            boolean changed,
            boolean betterNetworkAvailable,
            String betterNetworkId,
            String reason,
            Map<String, Double> scores
    ) {}

    public double score(NetworkCandidate candidate, NetworkPolicy policy, boolean developerOverride) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(policy, "policy");
        if (!eligible(candidate, policy, developerOverride)) {
            return Double.NEGATIVE_INFINITY;
        }

        double score = 1000.0;
        if (candidate.relayProbeRttMs() != null) {
            score -= Math.min(candidate.relayProbeRttMs(), 800.0);
        } else {
            score -= 250.0;
        }

        if (candidate.relayProbeLoss() != null) {
            score -= 1000.0 * Math.max(0.0, Math.min(candidate.relayProbeLoss(), 0.5));
        }
        if (candidate.metered()) score -= 25.0;
        if (candidate.roaming()) score -= 50.0;
        if (policy == NetworkPolicy.PREFER_WIFI && candidate.transport() == NetworkTransport.WIFI) score += 150.0;
        if (policy == NetworkPolicy.PREFER_CELLULAR && candidate.transport() == NetworkTransport.CELLULAR) score += 150.0;
        return score;
    }

    public boolean eligible(NetworkCandidate candidate, NetworkPolicy policy, boolean developerOverride) {
        if (!candidate.internet()) return false;
        if (!candidate.validated() && !developerOverride) return false;
        return switch (policy) {
            case WIFI_ONLY -> candidate.transport() == NetworkTransport.WIFI;
            case CELLULAR_ONLY -> candidate.transport() == NetworkTransport.CELLULAR;
            default -> true;
        };
    }

    public SelectionDecision select(
            Collection<NetworkCandidate> candidates,
            NetworkPolicy policy,
            String selectedId,
            boolean activeCall,
            boolean developerOverride
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(policy, "policy");

        Map<String, NetworkCandidate> byId = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (NetworkCandidate candidate : candidates) {
            if (byId.put(candidate.id(), candidate) != null) {
                throw new IllegalArgumentException("duplicate network candidate id: " + candidate.id());
            }
            scores.put(candidate.id(), score(candidate, policy, developerOverride));
        }

        NetworkCandidate current = selectedId == null ? null : byId.get(selectedId);
        boolean currentEligible = current != null && eligible(current, policy, developerOverride);
        NetworkCandidate best = candidates.stream()
                .filter(candidate -> eligible(candidate, policy, developerOverride))
                .min(candidateComparator(policy, developerOverride))
                .orElse(null);

        if (best == null) {
            return new SelectionDecision(null, selectedId != null, false, null,
                    "NO_ELIGIBLE_NETWORK", Map.copyOf(scores));
        }

        if (!currentEligible) {
            boolean changed = !best.id().equals(selectedId);
            return new SelectionDecision(best.id(), changed, false, null,
                    selectedId == null ? "INITIAL_SELECTION" : "SELECTED_INELIGIBLE", Map.copyOf(scores));
        }

        if (best.id().equals(current.id())) {
            return new SelectionDecision(current.id(), false, false, null,
                    "CURRENT_BEST", Map.copyOf(scores));
        }

        double bestScore = scores.get(best.id());
        double currentScore = scores.get(current.id());
        boolean genuinelyBetter = candidateComparator(policy, developerOverride).compare(best, current) < 0;

        if (activeCall) {
            return new SelectionDecision(current.id(), false, genuinelyBetter,
                    genuinelyBetter ? best.id() : null,
                    "ACTIVE_CALL_NO_MIGRATION", Map.copyOf(scores));
        }

        if (bestScore >= currentScore + HANDOVER_MARGIN) {
            return new SelectionDecision(best.id(), true, false, null,
                    "HANDOVER_MARGIN_MET", Map.copyOf(scores));
        }
        return new SelectionDecision(current.id(), false, genuinelyBetter,
                genuinelyBetter ? best.id() : null,
                "HYSTERESIS_HOLD", Map.copyOf(scores));
    }

    private Comparator<NetworkCandidate> candidateComparator(NetworkPolicy policy, boolean developerOverride) {
        return (a, b) -> {
            int scoreCompare = Double.compare(score(b, policy, developerOverride), score(a, policy, developerOverride));
            if (scoreCompare != 0) return scoreCompare;

            double aRtt = a.relayProbeRttMs() == null ? Double.POSITIVE_INFINITY : a.relayProbeRttMs();
            double bRtt = b.relayProbeRttMs() == null ? Double.POSITIVE_INFINITY : b.relayProbeRttMs();
            int rttCompare = Double.compare(aRtt, bRtt);
            if (rttCompare != 0) return rttCompare;

            int transportCompare = Integer.compare(transportRank(a.transport()), transportRank(b.transport()));
            if (transportCompare != 0) return transportCompare;
            return a.id().compareTo(b.id());
        };
    }

    private int transportRank(NetworkTransport transport) {
        return switch (transport) {
            case WIFI -> 0;
            case CELLULAR -> 1;
            case ETHERNET -> 2;
            case OTHER -> 3;
        };
    }
}
