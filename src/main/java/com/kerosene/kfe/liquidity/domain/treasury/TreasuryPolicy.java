package com.kerosene.kfe.liquidity.domain.treasury;

import java.util.List;
import java.util.UUID;

/** Explicit limits only. Missing configuration never authorizes a proposal or execution. */
public record TreasuryPolicy(String version, UUID capitalWalletId, long reserveFloorSats,
        long minChannelSats, long maxChannelSats, long maxRebalanceSats, long maxFeeSats,
        int maxChannels, int maxPeerShareBps, int targetLocalBps, int toleranceBps,
        long maxSnapshotAgeSeconds, List<String> allowedPeers) {
    public TreasuryPolicy {
        allowedPeers = allowedPeers == null ? List.of() : allowedPeers.stream()
                .map(peer -> peer.toLowerCase(java.util.Locale.ROOT)).distinct().sorted().toList();
    }

    public static TreasuryPolicy unconfigured() {
        return new TreasuryPolicy(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    public boolean configured() {
        return version != null && version.matches("[A-Za-z0-9._-]{1,128}") && capitalWalletId != null
                && reserveFloorSats >= 0 && minChannelSats > 0 && maxChannelSats >= minChannelSats
                && maxRebalanceSats > 0 && maxFeeSats > 0 && maxChannels > 0
                && maxPeerShareBps > 0 && maxPeerShareBps <= 10000
                && targetLocalBps > 0 && targetLocalBps < 10000
                && toleranceBps > 0 && toleranceBps < targetLocalBps
                && targetLocalBps + toleranceBps < 10000
                && maxSnapshotAgeSeconds > 0 && maxSnapshotAgeSeconds <= 3600
                && !allowedPeers.isEmpty()
                && allowedPeers.stream().allMatch(peer -> peer.matches("0[23][0-9a-f]{64}"));
    }
}
