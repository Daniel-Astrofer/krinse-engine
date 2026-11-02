package com.kerosene.kfe.liquidity.domain.treasury;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic, side-effect-free proposals. No queue or financial gateway mutations. */
public final class TreasuryPlanner {
    public record Channel(String channelPoint, String remotePubkey, boolean active, long capacitySats,
                          long localBalanceSats, long remoteBalanceSats, int pendingHtlcs) { }

    public record Snapshot(Instant capturedAt, Instant capitalObservedAt, Long availableCapitalSats,
            Long observedCapitalSats, long encumberedSats, boolean lightningLive, boolean inFlight,
            List<Channel> channels, List<String> blockers) {
        public Snapshot { channels = List.copyOf(channels); blockers = List.copyOf(blockers); }
    }
    public enum Action { BLOCKED, NOOP, REBALANCE, OPEN_CHANNEL }
    public record Plan(String mode, String policyVersion, Action action, String channelPoint,
                       String peerPubkey, long amountSats, long maxFeeSats, List<String> reasons) {
        public Plan { reasons = List.copyOf(reasons); }
    }

    public Plan plan(TreasuryPolicy policy, Snapshot snapshot, Instant now) {
        List<String> blocks = new ArrayList<>(snapshot.blockers());
        if (!policy.configured()) blocks.add("POLICY_NOT_CONFIGURED");
        if (stale(snapshot.capturedAt(), now, policy.maxSnapshotAgeSeconds())) blocks.add("SNAPSHOT_STALE");
        if (stale(snapshot.capitalObservedAt(), now, policy.maxSnapshotAgeSeconds())) blocks.add("CAPITAL_OBSERVATION_STALE");
        if (!snapshot.lightningLive()) blocks.add("LIGHTNING_UNAVAILABLE");
        if (snapshot.inFlight()) blocks.add("OPERATIONS_REQUIRE_RECONCILIATION");
        if (snapshot.availableCapitalSats() == null || snapshot.observedCapitalSats() == null)
            blocks.add("CAPITAL_UNKNOWN");
        if (!blocks.isEmpty()) return result(policy, Action.BLOCKED, null, null, 0, blocks);
        try { return decide(policy, snapshot); }
        catch (ArithmeticException ex) {
            return result(policy, Action.BLOCKED, null, null, 0, List.of("AMOUNT_OVERFLOW"));
        }
    }

    private Plan decide(TreasuryPolicy p, Snapshot s) {
        if (s.availableCapitalSats() < 0 || s.observedCapitalSats() < 0 || s.encumberedSats() < 0
                || s.observedCapitalSats() != Math.addExact(s.availableCapitalSats(), s.encumberedSats()))
            return result(p, Action.BLOCKED, null, null, 0, List.of("CAPITAL_NOT_RECONCILED"));
        List<Channel> channels = s.channels().stream().sorted(Comparator.comparing(Channel::channelPoint)).toList();
        for (Channel c : channels) {
            if (c.capacitySats() <= 0 || c.localBalanceSats() < 0 || c.remoteBalanceSats() < 0
                    || Math.addExact(c.localBalanceSats(), c.remoteBalanceSats()) > c.capacitySats())
                return result(p, Action.BLOCKED, null, null, 0, List.of("CHANNEL_BALANCE_INVALID"));
        }
        for (Channel target : channels) {
            if (!target.active() || target.pendingHtlcs() > 0 || !p.allowedPeers().contains(target.remotePubkey())) continue;
            long floor = fraction(target.capacitySats(), p.targetLocalBps() - p.toleranceBps());
            if (target.localBalanceSats() >= floor) continue;
            long desired = fraction(target.capacitySats(), p.targetLocalBps()) - target.localBalanceSats();
            long amount = Math.min(p.maxRebalanceSats(), Math.min(desired, target.remoteBalanceSats()));
            boolean donor = amount > 0 && channels.stream().anyMatch(c -> c.active() && c.pendingHtlcs() == 0 && p.allowedPeers().contains(c.remotePubkey())
                    && !c.channelPoint().equals(target.channelPoint())
                    && c.localBalanceSats() >= Math.addExact(amount, p.maxFeeSats()));
            if (donor) return result(p, Action.REBALANCE, target.channelPoint(), target.remotePubkey(), amount,
                    List.of("OUTBOUND_BELOW_TARGET", "ALTERNATE_OUTGOING_CAPACITY"));
        }
        // Do not add local-funded channels to solve inbound shortages or transient peer outages.
        boolean needsOutbound = channels.isEmpty() || channels.stream().filter(Channel::active)
                .anyMatch(c -> c.localBalanceSats() < fraction(c.capacitySats(), p.targetLocalBps() - p.toleranceBps()));
        if (!needsOutbound) return result(p, Action.NOOP, null, null, 0, List.of("NO_OUTBOUND_DEFICIT"));
        if (channels.size() >= p.maxChannels())
            return result(p, Action.NOOP, null, null, 0, List.of("CHANNEL_LIMIT"));
        long free = Math.subtractExact(Math.subtractExact(s.availableCapitalSats(), p.reserveFloorSats()), p.maxFeeSats());
        long amount = Math.min(p.maxChannelSats(), free);
        if (amount < p.minChannelSats()) return result(p, Action.NOOP, null, null, 0, List.of("CAPITAL_FLOOR"));
        long capacity = 0;
        for (Channel c : channels) capacity = Math.addExact(capacity, c.capacitySats());
        for (String peer : p.allowedPeers()) {
            if (channels.stream().anyMatch(c -> peer.equalsIgnoreCase(c.remotePubkey()))) continue;
            if (amount <= fraction(Math.addExact(capacity, amount), p.maxPeerShareBps()))
                return result(p, Action.OPEN_CHANNEL, null, peer, amount,
                        List.of("OUTBOUND_CAPACITY_REQUIRED", "CAPITAL_ABOVE_FLOOR", "ALLOWLISTED_PEER"));
        }
        return result(p, Action.NOOP, null, null, 0, List.of("NO_PEER_WITHIN_CONCENTRATION_LIMIT"));
    }

    private static long fraction(long value, int bps) {
        return Math.addExact(Math.multiplyExact(value / 10000, bps), (value % 10000) * bps / 10000);
    }
    private static boolean stale(Instant at, Instant now, long ttl) {
        return at == null || at.isAfter(now) || ttl <= 0 || ttl > 3600 || at.isBefore(now.minusSeconds(ttl));
    }
    private Plan result(TreasuryPolicy p, Action action, String channel, String peer, long amount, List<String> reasons) {
        return new Plan("PROPOSE_ONLY", p.configured() ? p.version() : "UNCONFIGURED", action, channel, peer,
                amount, amount > 0 ? p.maxFeeSats() : 0, reasons);
    }
}
