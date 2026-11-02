package com.kerosene.kfe.liquidity.adapters.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.liquidity.domain.treasury.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.security.MessageDigest;

/** Collects explicit evidence and stores proposals; has no financial mutation/queue dependency. */
@Service
@EnableConfigurationProperties(KfeTreasuryProperties.class)
public class KfeTreasuryPlanningService {
    private final TreasuryPolicy policy;
    private final KfeBalanceRepository balances;
    private final KfeWalletRepository wallets;
    private final LightningChannelGateway lightning;
    private final KfeChannelRebalanceJobRepository rebalances;
    private final KfeChannelCapacityJobRepository capacity;
    private final KfeChannelOperationDecisionRepository decisions;
    private final KfeTreasuryProposalRepository proposals;
    private final ObjectMapper mapper;
    private final boolean scheduled;

    public KfeTreasuryPlanningService(KfeTreasuryProperties properties, KfeBalanceRepository balances,
            KfeWalletRepository wallets, LightningChannelGateway lightning,
            KfeChannelRebalanceJobRepository rebalances, KfeChannelCapacityJobRepository capacity,
            KfeChannelOperationDecisionRepository decisions, KfeTreasuryProposalRepository proposals,
            ObjectMapper mapper, @Value("${kfe.treasury.planner.enabled:false}") boolean scheduled) {
        this.policy=properties.policy(); this.balances=balances; this.wallets=wallets; this.lightning=lightning;
        this.rebalances=rebalances; this.capacity=capacity; this.decisions=decisions;
        this.proposals=proposals; this.mapper=mapper; this.scheduled=scheduled;
    }

    @Scheduled(fixedDelayString="${kfe.treasury.planner.fixed-delay-ms:300000}",
               initialDelayString="${kfe.treasury.planner.initial-delay-ms:120000}")
    public void scheduledPlan() { if (scheduled) propose(); }

    public KfeTreasuryProposalEntity propose() {
        Instant captured = Instant.now();
        List<String> blockers = new ArrayList<>();
        Long available = null, observed = null;
        long encumbered = 0;
        Instant observedAt = null;
        if (policy.capitalWalletId() != null) {
            var wallet = wallets.findById(policy.capitalWalletId()).orElse(null);
            var balance = balances.findById(new KfeBalanceId(policy.capitalWalletId(), "BTC")).orElse(null);
            if (wallet == null || wallet.getKind() != KfeWalletKind.SYSTEM_FUNDS
                    || wallet.getStatus() != KfeWalletStatus.ACTIVE) blockers.add("CAPITAL_WALLET_NOT_ELIGIBLE");
            else if (balance == null) blockers.add("CAPITAL_BALANCE_MISSING");
            else {
                available=balance.getAvailableSats(); observed=balance.getObservedSats();
                encumbered=Math.addExact(balance.getLockedSats(), Math.addExact(balance.getAutoHoldSats(), balance.getPendingSats()));
                observedAt=balance.getObservedProbeAt() == null ? null : balance.getObservedProbeAt().toInstant(ZoneOffset.UTC);
                if (!"LIVE_MEMPOOL_AWARE".equals(balance.getObservedProbeQuality()))
                    blockers.add("CAPITAL_MEMPOOL_AWARE_OBSERVATION_REQUIRED");
                if (balance.getObservedProbeSource() == null || balance.getObservedProbeSource().isBlank())
                    blockers.add("CAPITAL_OBSERVATION_SOURCE_MISSING");
            }
        }
        boolean live=lightning.isLive();
        List<TreasuryPlanner.Channel> channels=List.of();
        boolean inFlight=rebalances.existsByStatusIn(EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS))
                || capacity.existsByStatusIn(EnumSet.of(KfeChannelCapacityJobStatus.PENDING, KfeChannelCapacityJobStatus.IN_PROGRESS))
                || decisions.hasUnresolvedFunding();
        if (live) {
            try {
                var inventory=lightning.observeChannels();
                channels=inventory.channels().stream()
                        .map(c -> new TreasuryPlanner.Channel(c.channelPoint(), c.remotePubkey(), c.active(),
                                c.capacitySats(), c.localBalanceSats(), c.remoteBalanceSats(), c.pendingHtlcs()))
                        .sorted(Comparator.comparing(TreasuryPlanner.Channel::channelPoint)).toList();
                inFlight=inFlight || !inventory.pending().isEmpty()
                        || channels.stream().anyMatch(c -> c.pendingHtlcs() > 0);
            } catch (RuntimeException ex) { blockers.add("LIGHTNING_OBSERVATION_FAILED"); live=false; }
        }
        var snapshot=new TreasuryPlanner.Snapshot(captured, observedAt, available, observed, encumbered, live, inFlight, channels, blockers);
        Instant evaluated=Instant.now();
        var plan=new TreasuryPlanner().plan(policy, snapshot, evaluated);
        // Evidence contains the entire policy, not just its human version label.
        try {
            String evidence=mapper.writeValueAsString(Map.of("policy",policy,"snapshot",snapshot));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Instant until=plan.action() == TreasuryPlanner.Action.BLOCKED ? evaluated
                    : captured.plusSeconds(policy.maxSnapshotAgeSeconds());
            if (observedAt != null && policy.configured()) {
                Instant capitalExpiry=observedAt.plusSeconds(policy.maxSnapshotAgeSeconds());
                if (capitalExpiry.isBefore(until)) until=capitalExpiry;
            }
            return proposals.save(new KfeTreasuryProposalEntity(evaluated,until,plan.policyVersion(),hash,evidence,mapper.writeValueAsString(plan)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Could not persist treasury evidence",ex);
        }
    }

    public List<KfeTreasuryProposalEntity> history(int limit) {
        return proposals.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0,Math.max(1,Math.min(100,limit))));
    }
}
