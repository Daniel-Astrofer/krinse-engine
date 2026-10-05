package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelCapacityQueueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeCloseChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityIntent;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;

import java.util.List;
import java.util.UUID;

/**
 * Executes durable capacity intents after re-checking binary AND gates.
 * Never runs on the user request thread.
 */
@Service
public class KfeChannelCapacityWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityWorker.class);
    private final String workerId = "kfe-channel-capacity-worker-" + UUID.randomUUID();

    private final KfeChannelCapacityQueueService queueService;
    private final KfeChannelLifecycleService lifecycleService;
    private final LightningChannelGateway channelGateway;
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics;
    private final boolean enabled;
    private final int batchSize;
    private final long assumedFeeRateSatVb;

    public KfeChannelCapacityWorker(
            KfeChannelCapacityQueueService queueService,
            KfeChannelLifecycleService lifecycleService,
            LightningChannelGateway channelGateway,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.capacity.worker.enabled:true}") boolean enabled,
            @Value("${kfe.channel.capacity.worker.batch-size:3}") int batchSize,
            @Value("${kfe.channel.capacity.assumed-fee-rate-sat-vb:10}") long assumedFeeRateSatVb) {
        this.queueService = queueService;
        this.lifecycleService = lifecycleService;
        this.channelGateway = channelGateway;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.assumedFeeRateSatVb = Math.max(1L, assumedFeeRateSatVb);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.capacity.worker.fixed-delay-ms:120000}",
            initialDelayString = "${kfe.channel.capacity.worker.initial-delay-ms:120000}")
    public void processPending() {
        if (!enabled) {
            return;
        }
        processBatch(batchSize);
    }

    public int processBatch(int limit) {
        if (!channelGateway.isLive()) {
            log.debug("[KFE Capacity Worker] gateway not live — skip");
            return 0;
        }
        List<KfeChannelCapacityJobEntity> pending = queueService.pending(limit);
        int processed = 0;
        for (KfeChannelCapacityJobEntity job : pending) {
            KfeChannelCapacityQueueService.JobClaim claim = null;
            try {
                var acquired = queueService.claim(job.getId(), workerId, 600L);
                if (acquired.isEmpty()) {
                    continue;
                }
                claim = acquired.orElseThrow();
                executeJob(job.getId());
                processed++;
            } catch (RuntimeException ex) {
                log.warn("[KFE Capacity Worker] job {} failed; claim will be recovered or reconciled.", job.getId());
                if (claim == null) queueService.fail(job.getId(), "worker failure");
                else queueService.fail(claim, "worker failure");
                metric("error", job.getIntent() != null ? job.getIntent().name() : "unknown");
            }
        }
        return processed;
    }

    public void executeJob(UUID jobId) {
        KfeChannelCapacityJobEntity job = queueService.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Capacity job not found: " + jobId));
        KfeChannelCapacityQueueService.JobClaim claim = job.getClaimToken() == null
                ? null
                : new KfeChannelCapacityQueueService.JobClaim(jobId, job.getClaimToken());
        if (claim == null) {
            queueService.markInProgress(jobId, "worker-start");
        } else if (!queueService.heartbeat(claim, 600L)) {
            return;
        }
        if (job.getIntent() == KfeChannelCapacityIntent.OPEN) {
            executeOpen(job, claim);
        } else if (job.getIntent() == KfeChannelCapacityIntent.CLOSE) {
            executeClose(job, claim);
        } else {
            fail(jobId, claim, "UNKNOWN_INTENT");
        }
    }

    private void executeOpen(KfeChannelCapacityJobEntity job, KfeChannelCapacityQueueService.JobClaim claim) {
        KfeChannelDecisionResponse result = lifecycleService.openChannel(
                new KfeOpenChannelRequest(
                        job.getPeerPubkey(),
                        job.getLocalAmountSats(),
                        assumedFeeRateSatVb,
                        true,
                        true,
                        false));
        if (!result.passed()) {
            fail(job.getId(), claim, "GATE_FAILED");
            metric("gate_fail", "OPEN");
            return;
        }
        if (!result.executed()) {
            fail(job.getId(), claim, "NOT_EXECUTED");
            metric("not_executed", "OPEN");
            return;
        }
        complete(job.getId(), claim,
                result.providerReference() != null ? result.providerReference() : result.channelPoint());
        metric("completed", "OPEN");
        log.info(
                "[KFE Capacity Worker] OPEN completed job={} channel={}",
                job.getId(),
                result.channelPoint());
    }

    private void executeClose(KfeChannelCapacityJobEntity job, KfeChannelCapacityQueueService.JobClaim claim) {
        KfeChannelDecisionResponse result = lifecycleService.closeChannel(
                new KfeCloseChannelRequest(
                        job.getChannelPoint(),
                        false,
                        true,
                        assumedFeeRateSatVb));
        if (!result.passed()) {
            fail(job.getId(), claim, "GATE_FAILED");
            metric("gate_fail", "CLOSE");
            return;
        }
        if (!result.executed()) {
            fail(job.getId(), claim, "NOT_EXECUTED");
            metric("not_executed", "CLOSE");
            return;
        }
        complete(job.getId(), claim,
                result.providerReference() != null ? result.providerReference() : "CLOSED");
        metric("completed", "CLOSE");
        log.info(
                "[KFE Capacity Worker] CLOSE completed job={} channel={}",
                job.getId(),
                job.getChannelPoint());
    }

    private void metric(String result, String intent) {
        KfeLightningOpsMetrics m = opsMetrics.getIfAvailable();
        if (m != null) {
            m.recordCapacity(result, intent);
        }
    }

    private void complete(UUID jobId, KfeChannelCapacityQueueService.JobClaim claim, String reference) {
        if (claim == null) queueService.complete(jobId, reference);
        else queueService.complete(claim, reference);
    }

    private void fail(UUID jobId, KfeChannelCapacityQueueService.JobClaim claim, String error) {
        if (claim == null) queueService.fail(jobId, error);
        else queueService.fail(claim, error);
    }
}
