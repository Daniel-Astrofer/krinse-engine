package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelCapacityQueueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionResult;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelDecisionService;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeCloseChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dead-man capacity loop: usage stress → durable OPEN/CLOSE intents.
 * <p>
 * Does <strong>not</strong> open channels on the user request path. Runs async,
 * re-uses binary AND gates at enqueue and execution time, costs from SYSTEM_PROFIT.
 */
@Service
public class KfeChannelCapacityController {

    /** Logger for automated capacity decisions and scan-level failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityController.class);

    /** Reads live channel state and confirms that LND can accept a capacity scan. */
    private final LightningChannelGateway channelGateway;
    /** Re-evaluates business gates before automatic open decisions are persisted. */
    private final KfeChannelDecisionService decisionService;
    /** Persists lifecycle decisions without synchronously opening or closing channels. */
    private final KfeChannelLifecycleService lifecycleService;
    /** Deduplicates durable automatic open/close jobs and enforces pending-open bounds. */
    private final KfeChannelCapacityQueueService capacityQueue;
    /** Aggregates recent liquidity-rejection signals that can trigger capacity expansion. */
    private final KfeCapacitySignalStore signalStore;
    /** Optional metrics sink for skipped, rejected, and enqueued capacity actions. */
    private final org.springframework.beans.factory.ObjectProvider<KfeLightningOpsMetrics> opsMetrics;

    /** Master feature switch for scheduled channel capacity automation. */
    private final boolean enabled;
    /** Enables automatic opens in response to recent liquidity stress. */
    private final boolean autoOpen;
    /** Enables automatic close evaluation for persistently inactive channels. */
    private final boolean autoCloseDead;
    /** Lookback interval used to aggregate liquidity signals, clamped to at least one minute. */
    private final long signalWindowMs;
    /** Minimum liquidity rejection count required to consider an automatic open. */
    private final long liquidityRejectThreshold;
    /** Capacity requested for an automatic open, using minimum capital when unset/nonpositive. */
    private final long openAmountSats;
    /** Estimated structural cost used in profit gates and queued job metadata. */
    private final long estimatedOpenFeeSats;
    /** Expected benefit required to justify the structural open cost. */
    private final long expectedOpenGainSats;
    /** Fee rate used when evaluating automated open and close proposals. */
    private final long assumedFeeRateSatVb;
    /** Consecutive inactive scans required before a channel close is considered. */
    private final int inactiveScansBeforeClose;
    /** Maximum number of pending automatic open intents allowed concurrently. */
    private final long maxPendingOpens;
    /** Ordered peer candidates for automatic channel opening. */
    private final List<String> preferredPeers;

    /** channelPoint → consecutive inactive scan count */
    private final ConcurrentHashMap<String, Integer> inactiveStreak = new ConcurrentHashMap<>();

    /**
     * Creates the asynchronous capacity loop and normalizes configuration bounds before use.
     *
     * @param channelGateway Lightning provider channel operations
     * @param decisionService binary open policy evaluator
     * @param lifecycleService durable open/close decision service
     * @param capacityQueue automatic capacity intent queue
     * @param signalStore liquidity stress signal aggregator
     * @param opsMetrics optional capacity metrics provider
     * @param enabled master automation switch
     * @param autoOpen automatic open policy switch
     * @param autoCloseDead automatic close policy switch
     * @param signalWindowMs liquidity signal lookback window, clamped to 60 seconds or greater
     * @param liquidityRejectThreshold minimum rejection count to trigger open evaluation
     * @param openAmountSats automatic channel amount, falling back to minimum capital if nonpositive
     * @param estimatedOpenFeeSats estimated structural open cost, clamped to zero or greater
     * @param expectedOpenGainSats expected open gain, clamped to zero or greater
     * @param assumedFeeRateSatVb decision fee rate, clamped to at least one sat/vbyte
     * @param inactiveScansBeforeClose required consecutive inactive observations, clamped to at least one
     * @param maxPendingOpens concurrent pending open ceiling, clamped to at least one
     * @param preferredPeersCsv comma-separated ordered peer public keys
     * @param minOpenCapitalSats fallback open capacity when no positive explicit amount is configured
     */
    public KfeChannelCapacityController(
            LightningChannelGateway channelGateway,
            KfeChannelDecisionService decisionService,
            KfeChannelLifecycleService lifecycleService,
            KfeChannelCapacityQueueService capacityQueue,
            KfeCapacitySignalStore signalStore,
            org.springframework.beans.factory.ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.capacity.enabled:true}") boolean enabled,
            @Value("${kfe.channel.capacity.auto-open:true}") boolean autoOpen,
            @Value("${kfe.channel.capacity.auto-close-dead:true}") boolean autoCloseDead,
            @Value("${kfe.channel.capacity.signal-window-ms:900000}") long signalWindowMs,
            @Value("${kfe.channel.capacity.liquidity-reject-threshold:5}") long liquidityRejectThreshold,
            @Value("${kfe.channel.capacity.open-amount-sats:0}") long openAmountSats,
            @Value("${kfe.channel.capacity.estimated-open-fee-sats:5000}") long estimatedOpenFeeSats,
            @Value("${kfe.channel.capacity.expected-open-gain-sats:50000}") long expectedOpenGainSats,
            @Value("${kfe.channel.capacity.assumed-fee-rate-sat-vb:10}") long assumedFeeRateSatVb,
            @Value("${kfe.channel.capacity.inactive-scans-before-close:3}") int inactiveScansBeforeClose,
            @Value("${kfe.channel.capacity.max-pending-opens:2}") long maxPendingOpens,
            @Value("${kfe.channel.capacity.preferred-peers:}") String preferredPeersCsv,
            @Value("${kfe.channel.min-open-capital-sats:10000000}") long minOpenCapitalSats) {
        this.channelGateway = channelGateway;
        this.decisionService = decisionService;
        this.lifecycleService = lifecycleService;
        this.capacityQueue = capacityQueue;
        this.signalStore = signalStore;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.autoOpen = autoOpen;
        this.autoCloseDead = autoCloseDead;
        this.signalWindowMs = Math.max(60_000L, signalWindowMs);
        this.liquidityRejectThreshold = Math.max(1L, liquidityRejectThreshold);
        this.openAmountSats = openAmountSats > 0L ? openAmountSats : Math.max(1L, minOpenCapitalSats);
        this.estimatedOpenFeeSats = Math.max(0L, estimatedOpenFeeSats);
        this.expectedOpenGainSats = Math.max(0L, expectedOpenGainSats);
        this.assumedFeeRateSatVb = Math.max(1L, assumedFeeRateSatVb);
        this.inactiveScansBeforeClose = Math.max(1, inactiveScansBeforeClose);
        this.maxPendingOpens = Math.max(1L, maxPendingOpens);
        this.preferredPeers = parsePeers(preferredPeersCsv);
    }

    /**
     * Periodically evaluates close candidates and liquidity-driven open candidates when automation
     * is enabled and the Lightning provider is live. Exceptions are contained at scan scope so a
     * transient failure does not stop future scheduled scans.
     */
    @Scheduled(
            fixedDelayString = "${kfe.channel.capacity.fixed-delay-ms:180000}",
            initialDelayString = "${kfe.channel.capacity.initial-delay-ms:90000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        if (!channelGateway.isLive()) {
            log.debug("[KFE Capacity] LND not live — skip scan");
            return;
        }
        try {
            evaluateCloses();
            evaluateOpens();
        } catch (RuntimeException ex) {
            log.warn("[KFE Capacity] scan failed: {}", ex.getMessage());
        }
    }

    /**
     * Enqueues at most one profitable open intent when recent liquidity rejections exceed threshold.
     * Candidates already connected with outbound liquidity are skipped; both policy and lifecycle
     * decisions must pass, pending-open capacity must remain, and expected gain must exceed cost.
     */
    void evaluateOpens() {
        if (!autoOpen) {
            return;
        }
        KfeCapacitySignalStore.CapacitySignals signals = signalStore.snapshot(signalWindowMs);
        if (signals.liquidityRejects() < liquidityRejectThreshold) {
            return;
        }
        if (preferredPeers.isEmpty()) {
            log.info(
                    "[KFE Capacity] liquidity stress rejects={} but no preferred peers configured — cannot auto-open",
                    signals.liquidityRejects());
            recordCapacity("open_skipped", "no_peers");
            return;
        }
        if (capacityQueue.pendingOpenCount() >= maxPendingOpens) {
            log.debug("[KFE Capacity] max pending opens reached ({})", maxPendingOpens);
            return;
        }
        // Max profit policy: never spend structural cost without expected gain.
        if (expectedOpenGainSats <= estimatedOpenFeeSats) {
            log.info(
                    "[KFE Capacity] skip OPEN: expectedGain={} <= estimatedCost={} (profit policy)",
                    expectedOpenGainSats,
                    estimatedOpenFeeSats);
            recordCapacity("open_skipped", "no_profit");
            return;
        }

        for (String peer : preferredPeers) {
            if (alreadyConnectedWithOutbound(peer)) {
                continue;
            }
            ChannelDecisionResult decision = decisionService.evaluateOpen(
                    peer,
                    openAmountSats,
                    assumedFeeRateSatVb,
                    true,
                    "capacity-auto-open:" + peer);
            if (!decision.passed()) {
                log.info(
                        "[KFE Capacity] OPEN gate failed peer={} reason={}",
                        peer.substring(0, Math.min(16, peer.length())),
                        decision.toAuditMap().get("failedFlags"));
                recordCapacity("open_gate_fail", "and");
                continue;
            }
            // Persist full lifecycle decision for forensics, then enqueue.
            KfeChannelDecisionResponse evaluated = lifecycleService.evaluateOpen(
                    new KfeOpenChannelRequest(
                            peer,
                            openAmountSats,
                            assumedFeeRateSatVb,
                            true,
                            true,
                            false));
            if (!evaluated.passed()) {
                continue;
            }
            var job = capacityQueue.enqueueOpenIfAbsent(
                    peer,
                    openAmountSats,
                    estimatedOpenFeeSats,
                    expectedOpenGainSats,
                    "LIQUIDITY_REJECTS:" + signals.liquidityRejects(),
                    evaluated.id());
            if (job.isPresent()) {
                log.info(
                        "[KFE Capacity] OPEN enqueued job={} peer={} amount={} trigger={}",
                        job.get().getId(),
                        peer.substring(0, Math.min(16, peer.length())),
                        openAmountSats,
                        signals.liquidityRejects());
                recordCapacity("open_enqueued", "liquidity_stress");
                return; // one open intent per scan
            }
        }
    }

    /**
     * Enqueues close intents only after a channel is inactive with no pending HTLCs for the configured
     * number of consecutive scans and the lifecycle close policy passes. Active channels reset their
     * in-memory streak; successful enqueue also clears it to avoid duplicate threshold handling.
     */
    void evaluateCloses() {
        if (!autoCloseDead) {
            return;
        }
        for (LightningChannelGateway.ChannelSnapshot channel : channelGateway.listChannels()) {
            if (channel == null || channel.channelPoint() == null) {
                continue;
            }
            String point = channel.channelPoint();
            boolean inactive = !channel.active() && channel.pendingHtlcs() == 0;
            if (!inactive) {
                inactiveStreak.remove(point);
                continue;
            }
            int streak = inactiveStreak.merge(point, 1, Integer::sum);
            if (streak < inactiveScansBeforeClose) {
                continue;
            }
            KfeChannelDecisionResponse evaluated = lifecycleService.evaluateClose(
                    new KfeCloseChannelRequest(point, false, true, assumedFeeRateSatVb));
            if (!evaluated.passed()) {
                log.debug(
                        "[KFE Capacity] CLOSE gate failed channel={} reason={}",
                        point,
                        evaluated.decisionReason());
                continue;
            }
            var job = capacityQueue.enqueueCloseIfAbsent(
                    point,
                    channel.remotePubkey(),
                    estimatedOpenFeeSats,
                    "INACTIVE_STREAK:" + streak,
                    evaluated.id());
            if (job.isPresent()) {
                log.info(
                        "[KFE Capacity] CLOSE enqueued job={} channel={} streak={}",
                        job.get().getId(),
                        point,
                        streak);
                recordCapacity("close_enqueued", "inactive");
                inactiveStreak.remove(point);
            }
        }
    }

    /**
     * Records an optional operational capacity metric without making automation depend on metrics wiring.
     *
     * @param result outcome category such as enqueued or gate failure
     * @param reason trigger or skip reason
     */
    private void recordCapacity(String result, String reason) {
        KfeLightningOpsMetrics m = opsMetrics.getIfAvailable();
        if (m != null) {
            m.recordCapacity(result, reason);
        }
    }

    /**
     * Checks whether a peer already has an active channel with positive local balance.
     *
     * @param peerPubkey candidate remote peer public key
     * @return true when an active outbound-capable channel already exists
     */
    private boolean alreadyConnectedWithOutbound(String peerPubkey) {
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        return channelGateway.listChannels().stream()
                .anyMatch(ch -> ch.remotePubkey() != null
                        && ch.remotePubkey().equalsIgnoreCase(normalized)
                        && ch.active()
                        && ch.localBalanceSats() > 0L);
    }

    /**
     * Parses the configured comma-separated peer list, trimming entries and preserving order.
     *
     * @param csv configured peer public keys
     * @return immutable ordered list without blank entries
     */
    private static List<String> parsePeers(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

}
