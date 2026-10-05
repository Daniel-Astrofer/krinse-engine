package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelRebalanceQueueService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobStatus;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningLoopClient;

import java.util.List;
import java.util.UUID;

/**
 * Executes recoverable circular payments with durable identity and transactional fee reservations.
 * Ambiguous outcomes remain active until provider reconciliation; Loop execution is blocked.
 */
@Service
public class KfeChannelRebalanceWorker {

    /** Logger for queue and provider outcomes without exposing provider payloads. */
    private static final Logger log = LoggerFactory.getLogger(KfeChannelRebalanceWorker.class);
    /** Unique process-local lease owner used to fence concurrent workers. */
    private final String workerId = "kfe-channel-rebalance-worker-" + UUID.randomUUID();

    /** Claims jobs, exposes pending work, and persists terminal/retry state. */
    private final KfeChannelRebalanceQueueService queueService;
    /** Executes preferred LND circular self-payments and reconciles the original payment identity. */
    private final LightningChannelGateway channelGateway;
    /** Optional operational metric sink for rebalance outcomes. */
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics;
    /** Whether scheduled processing is allowed to claim and execute jobs. */
    private final boolean enabled;
    /** Number of pending jobs the scheduled invocation inspects per batch. */
    private final int batchSize;
    /** Hard cap on the fee reservation requested for any job, in satoshis. */
    private final long maxFeeSats;
    /** Hard cap on rebalance amount submitted to a provider, in satoshis. */
    private final long maxAmountSats;

    /**
     * Creates the scheduled rebalance consumer, clamping batch and amount limits to positive values
     * and allowing a zero fee cap to disable reservation spending.
     *
     * @param queueService durable rebalance job repository and claim service
     * @param channelGateway LND channel and circular-payment gateway
     * @param loopClientProvider retained for constructor compatibility; no swaps are submitted
     * @param systemWalletService SYSTEM_PROFIT wallet resolver
     * @param balanceService fee reservation and settlement service
     * @param auditLogService audit event writer
     * @param opsMetrics optional rebalance metrics sink
     * @param enabled scheduled worker feature switch
     * @param loopFallbackEnabled compatibility setting; Loop submission remains blocked
     * @param batchSize requested work batch size, clamped to at least one
     * @param maxFeeSats fee ceiling, clamped to zero or greater
     * @param maxAmountSats amount ceiling, clamped to at least one satoshi
     */
    public KfeChannelRebalanceWorker(
            KfeChannelRebalanceQueueService queueService,
            LightningChannelGateway channelGateway,
            ObjectProvider<LightningLoopClient> loopClientProvider,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.rebalance-worker.enabled:true}") boolean enabled,
            @Value("${kfe.channel.rebalance-worker.loop-fallback-enabled:true}") boolean loopFallbackEnabled,
            @Value("${kfe.channel.rebalance-worker.batch-size:5}") int batchSize,
            @Value("${kfe.channel.rebalance-worker.max-fee-sats:5000}") long maxFeeSats,
            @Value("${kfe.channel.rebalance-worker.max-amount-sats:500000}") long maxAmountSats) {
        this.queueService = queueService;
        this.channelGateway = channelGateway;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxFeeSats = Math.max(0L, maxFeeSats);
        this.maxAmountSats = Math.max(1L, maxAmountSats);
    }

    /**
     * Periodically processes one configured batch; the schedule is inert when the worker is disabled.
     */
    @Scheduled(
            fixedDelayString = "${kfe.channel.rebalance-worker.fixed-delay-ms:90000}",
            initialDelayString = "${kfe.channel.rebalance-worker.initial-delay-ms:60000}")
    public void processPending() {
        if (!enabled) {
            return;
        }
        processBatch(batchSize);
    }

    /** Claims both new work and expired leases. Recovery never sends a second payment. */
    public int processBatch(int limit) {
        if (!channelGateway.isLive()) return 0;
        int processed = 0;
        for (KfeChannelRebalanceJobEntity job : queueService.pending(limit)) {
            var acquired = queueService.claim(job.getId(), workerId, 600L);
            if (acquired.isEmpty()) continue;
            executeClaim(acquired.orElseThrow());
            processed++;
        }
        return processed;
    }

    /** Manual invocation obeys the same claim fence as scheduled execution. */
    public void executeJob(UUID jobId) {
        if (!channelGateway.isLive()) return;
        queueService.claim(jobId, workerId, 600L).ifPresent(this::executeClaim);
    }

    private void executeClaim(KfeChannelRebalanceQueueService.JobClaim claim) {
        try {
            KfeChannelRebalanceJobEntity job = queueService.findById(claim.jobId()).orElse(null);
            if (job == null || !queueService.heartbeat(claim, 600L)) return;
            if ("RECONCILING".equals(job.getExecutionPhase())) {
                // Legacy or interrupted pre-send executions may have no provider identity.
                // They require evidence/manual reconciliation, never an inferred retry.
                if (job.getPaymentHash() != null) {
                    reconcile(claim, channelGateway.lookupCircularRebalance(job.getPaymentHash()), job.getPaymentHash());
                }
                return;
            }
            if (!"READY".equals(job.getExecutionPhase())) return;
            if (maxFeeSats <= 0) {
                queueService.failClaim(claim, "Fee spending disabled");
                return;
            }
            long feeBudget = Math.min(maxFeeSats, Math.max(job.getEstimatedCostSats(), 1L));
            var prepared = channelGateway.prepareCircularRebalance(
                    new LightningChannelGateway.CircularRebalanceCommand(job.getChannelPoint(),
                            resolveAmount(job), feeBudget, "kfe-rebalance-job-" + job.getId()));
            if (prepared.maxFeeSats() != feeBudget)
                throw new IllegalStateException("Prepared fee limit differs from reservation");
            // Transaction completes in the queue service before the external submission starts.
            if (!queueService.beginDispatch(claim, prepared.paymentHash(), feeBudget)) return;
            if (!queueService.heartbeat(claim, 600L)) return;
            reconcile(claim, channelGateway.submitCircularRebalance(prepared), prepared.paymentHash());
        } catch (RuntimeException ex) {
            // failClaim can terminate READY only. After dispatch it preserves the active job/reserve.
            queueService.failClaim(claim, "Worker failure");
            metric("reconciliation_required", "circular");
            log.warn("[KFE Rebal Worker] job={} interrupted; provider evidence required before retry ({})",
                    claim.jobId(), ex.getClass().getSimpleName());
        }
    }

    private void reconcile(KfeChannelRebalanceQueueService.JobClaim claim,
                           LightningChannelGateway.CircularRebalanceResult result, String expectedHash) {
        if (result == null) return;
        boolean terminalSuccess = result.succeeded() && "SUCCEEDED".equals(result.status())
                && result.paymentHash() != null && expectedHash.equalsIgnoreCase(result.paymentHash());
        boolean terminalFailure = !result.succeeded() && "FAILED".equals(result.status());
        if (!terminalSuccess && !terminalFailure) {
            metric("reconciliation_required", "circular");
            return;
        }
        long fee = terminalSuccess ? result.feeSats() : 0L;
        if (queueService.finishDispatch(claim, terminalSuccess, fee)) {
            metric(terminalSuccess ? "success" : "failed", "circular");
        } else {
            metric("reconciliation_required", "circular");
        }
        // Loop requires its own principal reservation and durable swap reconciliation.
        // A failed circular payment does not authorize a submarine swap.
    }

    private long resolveAmount(KfeChannelRebalanceJobEntity job) {
        long gain = job.getExpectedGainSats();
        if (gain <= 0L) {
            long cost = Math.max(0L, job.getEstimatedCostSats());
            gain = Math.max(1_000L, cost > Long.MAX_VALUE / 5 ? Long.MAX_VALUE : cost * 5);
        }
        return Math.min(maxAmountSats, gain);
    }

    /**
     * Records an operational outcome when a metrics adapter is present.
     *
     * @param result outcome category such as success, failed, or error
     * @param provider path label such as circular, Loop, or worker
     */
    private void metric(String result, String provider) {
        KfeLightningOpsMetrics metrics = opsMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordRebalance(result, provider);
        }
    }

}
