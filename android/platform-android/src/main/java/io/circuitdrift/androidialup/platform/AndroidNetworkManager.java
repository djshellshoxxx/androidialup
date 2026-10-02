package io.circuitdrift.androidialup.platform;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import io.circuitdrift.androidialup.network.NetworkCandidate;
import io.circuitdrift.androidialup.network.NetworkPolicy;
import io.circuitdrift.androidialup.network.NetworkSelectionEngine;
import io.circuitdrift.androidialup.network.NetworkTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AndroidNetworkManager implements AutoCloseable {
    public interface Listener {
        default void onSelectionChanged(Network network, NetworkCandidate candidate,
                                        NetworkSelectionEngine.SelectionDecision decision) {}
        default void onBetterNetworkAvailable(Network network, NetworkCandidate candidate,
                                              NetworkSelectionEngine.SelectionDecision decision) {}
        default void onSelectedNetworkLost(Network network, NetworkCandidate previousCandidate) {}
    }

    private final ConnectivityManager connectivityManager;
    private final NetworkSelectionEngine selectionEngine;
    private final Listener listener;
    private final Map<Network, NetworkCandidate> candidates = new LinkedHashMap<>();
    private final ConnectivityManager.NetworkCallback callback;

    private NetworkPolicy policy = NetworkPolicy.AUTOMATIC;
    private boolean developerOverride;
    private boolean activeCall;
    private Network selectedNetwork;
    private boolean started;

    public AndroidNetworkManager(ConnectivityManager connectivityManager, Listener listener) {
        this(connectivityManager, new NetworkSelectionEngine(), listener);
    }

    AndroidNetworkManager(ConnectivityManager connectivityManager,
                          NetworkSelectionEngine selectionEngine,
                          Listener listener) {
        this.connectivityManager = Objects.requireNonNull(connectivityManager, "connectivityManager");
        this.selectionEngine = Objects.requireNonNull(selectionEngine, "selectionEngine");
        this.listener = listener == null ? new Listener() {} : listener;
        this.callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                refresh(network, connectivityManager.getNetworkCapabilities(network));
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                refresh(network, capabilities);
            }

            @Override public void onLost(Network network) {
                handleLost(network);
            }
        };
    }

    public synchronized void start() {
        if (started) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(request, callback);
        started = true;
    }

    @Override public synchronized void close() {
        if (!started) return;
        connectivityManager.unregisterNetworkCallback(callback);
        started = false;
        candidates.clear();
        selectedNetwork = null;
    }

    public synchronized void setPolicy(NetworkPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        reevaluate();
    }

    public synchronized NetworkPolicy policy() {
        return policy;
    }

    public synchronized void setDeveloperOverride(boolean enabled) {
        developerOverride = enabled;
        reevaluate();
    }

    public synchronized void setActiveCall(boolean active) {
        activeCall = active;
        reevaluate();
    }

    public synchronized Network selectedNetwork() {
        return selectedNetwork;
    }

    public synchronized NetworkCandidate selectedCandidate() {
        return selectedNetwork == null ? null : candidates.get(selectedNetwork);
    }

    public synchronized Map<Network, NetworkCandidate> snapshot() {
        return Map.copyOf(candidates);
    }

    public synchronized void updateRelayProbe(Network network, Double rttMs, Double lossFraction) {
        NetworkCandidate existing = candidates.get(network);
        if (existing == null) return;
        candidates.put(network, new NetworkCandidate(
                existing.id(), existing.transport(), existing.internet(), existing.validated(),
                existing.metered(), existing.roaming(), rttMs, lossFraction));
        reevaluate();
    }

    private synchronized void refresh(Network network, NetworkCapabilities caps) {
        if (caps == null) {
            candidates.remove(network);
            reevaluate();
            return;
        }
        NetworkCandidate previous = candidates.get(network);
        candidates.put(network, CandidateNormalizer.normalize(
                networkId(network),
                transport(caps),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
                previous == null ? null : previous.relayProbeRttMs(),
                previous == null ? null : previous.relayProbeLoss()));
        reevaluate();
    }

    private synchronized void handleLost(Network network) {
        NetworkCandidate removed = candidates.remove(network);
        boolean wasSelected = network.equals(selectedNetwork);
        if (wasSelected) {
            selectedNetwork = null;
            if (removed != null) listener.onSelectedNetworkLost(network, removed);
        }
        reevaluate();
    }

    private void reevaluate() {
        String selectedId = selectedNetwork == null ? null : networkId(selectedNetwork);
        NetworkSelectionEngine.SelectionDecision decision = selectionEngine.select(
                new ArrayList<>(candidates.values()), policy, selectedId, activeCall, developerOverride);

        Network next = networkForId(decision.selectedId());
        NetworkCandidate nextCandidate = next == null ? null : candidates.get(next);

        if (decision.betterNetworkAvailable()) {
            Network better = networkForId(decision.betterNetworkId());
            if (better != null) {
                listener.onBetterNetworkAvailable(better, candidates.get(better), decision);
            }
        }

        if (decision.changed()) {
            selectedNetwork = next;
            if (next != null) listener.onSelectionChanged(next, nextCandidate, decision);
        } else if (selectedNetwork == null && next != null) {
            selectedNetwork = next;
            listener.onSelectionChanged(next, nextCandidate, decision);
        }
    }

    private Network networkForId(String id) {
        if (id == null) return null;
        for (Map.Entry<Network, NetworkCandidate> entry : candidates.entrySet()) {
            if (entry.getValue().id().equals(id)) return entry.getKey();
        }
        return null;
    }

    private static String networkId(Network network) {
        return Long.toUnsignedString(network.getNetworkHandle());
    }

    private static NetworkTransport transport(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkTransport.WIFI;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return NetworkTransport.CELLULAR;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetworkTransport.ETHERNET;
        return NetworkTransport.OTHER;
    }
}
