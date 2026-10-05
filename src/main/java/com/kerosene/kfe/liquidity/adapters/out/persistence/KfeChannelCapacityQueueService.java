package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityIntent;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelCapacityJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Durable queue for dead-man capacity intents (OPEN / CLOSE).
 * Binary AND gates are re-evaluated at execution time by the worker.
 */
@Service
public class KfeChannelCapacityQueueService {

    /** Logger used when a new OPEN intent cannot be funded by available SYSTEM_PROFIT balance. */
    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityQueueService.class);

    /** Persists capacity jobs and performs atomic claim/status transitions. */
    private final KfeChannelCapacityJobRepository jobRepository;
    /** Resolves the SYSTEM_PROFIT wallet required for capacity operations. */
    private final KfeSystemWalletService systemWalletService;
    /** Checks available structural cost before enqueueing opens. */
    private final KfeBalanceService balanceService;
    /** Writes an audit event after a new capacity intent is persisted. */
    private final KfeAuditLogService auditLogService;

    /**
     * Fencing token identifying the worker lease for a capacity job.
     *
     * @param jobId claimed job identifier
     * @param claimToken unique token persisted for this claim
     */
    public record JobClaim(UUID jobId, UUID claimToken) { }

    /**
     * Creates the durable capacity queue service.
     *
     * @param jobRepository capacity intent/job repository
     * @param systemWalletService SYSTEM_PROFIT wallet resolver
     * @param balanceService balance read/lock port for open-cost admission
     * @param auditLogService audit writer for newly enqueued intents
     */
    public KfeChannelCapacityQueueService(
            KfeChannelCapacityJobRepository jobRepository,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Enqueues one OPEN intent per peer among PENDING/IN_PROGRESS jobs after checking the profit wallet
     * can cover the estimated structural cost. Values are clamped nonnegative and trigger text is capped;
     * a uniqueness race reloads the existing live intent.
     *
     * @param peerPubkey target peer public key
     * @param localAmountSats desired local channel capacity
     * @param estimatedCostSats expected structural cost to admit against available SYSTEM_PROFIT funds
     * @param expectedGainSats expected economic gain recorded for worker policy
     * @param triggerReason signal or administrative reason, truncated to the entity column limit
     * @param decisionId persisted lifecycle decision associated with this queue item
     * @return existing active intent or newly persisted job; empty when available profit is insufficient
     */
    @Transactional
    public Optional<KfeChannelCapacityJobEntity> enqueueOpenIfAbsent(
            String peerPubkey,
            long localAmountSats,
            long estimatedCostSats,
            long expectedGainSats,
            String triggerReason,
            UUID decisionId) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            throw new IllegalArgumentException("peerPubkey is required for OPEN capacity intent.");
        }
        String peer = peerPubkey.trim();
        Optional<KfeChannelCapacityJobEntity> existing =
                jobRepository.findFirstByIntentAndPeerPubkeyAndStatusIn(
                        KfeChannelCapacityIntent.OPEN,
                        peer,
                        EnumSet.of(
                                KfeChannelCapacityJobStatus.PENDING,
                                KfeChannelCapacityJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        if (estimatedCostSats > 0L) {
            var profit = balanceService.requireForUpdate(profitWalletId, KfeSystemWalletService.ASSET_BTC);
            if (profit.getAvailableSats() < estimatedCostSats) {
                log.warn(
                        "[KFE Capacity] profit insufficient for OPEN peer={} cost={} available={}",
                        peer,
                        estimatedCostSats,
                        profit.getAvailableSats());
                return Optional.empty();
            }
        }
        KfeChannelCapacityJobEntity job = new KfeChannelCapacityJobEntity();
        job.setIntent(KfeChannelCapacityIntent.OPEN);
        job.setPeerPubkey(peer);
        job.setLocalAmountSats(Math.max(0L, localAmountSats));
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setExpectedGainSats(Math.max(0L, expectedGainSats));
        job.setTriggerReason(truncate(triggerReason, 255));
        job.setDecisionId(decisionId);
        job.setStatus(KfeChannelCapacityJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByIntentAndPeerPubkeyAndStatusIn(
                    KfeChannelCapacityIntent.OPEN,
                    peer,
                    EnumSet.of(
                            KfeChannelCapacityJobStatus.PENDING,
                            KfeChannelCapacityJobStatus.IN_PROGRESS));
        }
        audit(job, profitWalletId);
        return Optional.of(job);
    }

    /**
     * Enqueues one CLOSE intent per channel point among PENDING/IN_PROGRESS jobs. Close jobs retain the
     * associated peer and decision but do not perform the OPEN-specific available-profit admission check.
     *
     * @param channelPoint exact channel point to close
     * @param peerPubkey remote peer public key, if known
     * @param estimatedCostSats estimated structural close cost, clamped nonnegative
     * @param triggerReason inactive-channel or administrative trigger, truncated to column length
     * @param decisionId persisted lifecycle close decision
     * @return existing active intent or newly persisted job
     */
    @Transactional
    public Optional<KfeChannelCapacityJobEntity> enqueueCloseIfAbsent(
            String channelPoint,
            String peerPubkey,
            long estimatedCostSats,
            String triggerReason,
            UUID decisionId) {
        if (channelPoint == null || channelPoint.isBlank()) {
            throw new IllegalArgumentException("channelPoint is required for CLOSE capacity intent.");
        }
        String point = channelPoint.trim();
        Optional<KfeChannelCapacityJobEntity> existing =
                jobRepository.findFirstByIntentAndChannelPointAndStatusIn(
                        KfeChannelCapacityIntent.CLOSE,
                        point,
                        EnumSet.of(
                                KfeChannelCapacityJobStatus.PENDING,
                                KfeChannelCapacityJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        KfeChannelCapacityJobEntity job = new KfeChannelCapacityJobEntity();
        job.setIntent(KfeChannelCapacityIntent.CLOSE);
        job.setChannelPoint(point);
        job.setPeerPubkey(peerPubkey);
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setTriggerReason(truncate(triggerReason, 255));
        job.setDecisionId(decisionId);
        job.setStatus(KfeChannelCapacityJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByIntentAndChannelPointAndStatusIn(
                    KfeChannelCapacityIntent.CLOSE,
                    point,
                    EnumSet.of(
                            KfeChannelCapacityJobStatus.PENDING,
                            KfeChannelCapacityJobStatus.IN_PROGRESS));
        }
        audit(job, profitWalletId);
        return Optional.of(job);
    }

    /**
     * Lists oldest PENDING jobs in a single page; nonpositive limits are normalized to one.
     *
     * @param limit maximum rows requested
     * @return pending jobs ordered by creation time
     */
    @Transactional(readOnly = true)
    public List<KfeChannelCapacityJobEntity> pending(int limit) {
        return jobRepository.findByStatusOrderByCreatedAtAsc(
                KfeChannelCapacityJobStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * Counts live OPEN intents, including both waiting and actively executing jobs.
     *
     * @return count of PENDING or IN_PROGRESS OPEN jobs
     */
    @Transactional(readOnly = true)
    public long pendingOpenCount() {
        return jobRepository.countByIntentAndStatusIn(
                KfeChannelCapacityIntent.OPEN,
                EnumSet.of(
                        KfeChannelCapacityJobStatus.PENDING,
                        KfeChannelCapacityJobStatus.IN_PROGRESS));
    }

    /**
     * Marks a pending/in-progress job as started for compatibility callers that do not use claims.
     *
     * @param jobId job identifier
     * @param ref provider or worker reference stored by the entity transition
     */
    @Transactional
    public void markInProgress(UUID jobId, String ref) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == KfeChannelCapacityJobStatus.PENDING
                    || job.getStatus() == KfeChannelCapacityJobStatus.IN_PROGRESS) {
                job.markInProgress(ref);
            }
        });
    }

    /**
     * Completes a job through the legacy identifier-only transition API.
     *
     * @param jobId job identifier
     * @param ref successful provider reference
     */
    @Transactional
    public void complete(UUID jobId, String ref) {
        jobRepository.findById(jobId).ifPresent(job -> job.markCompleted(ref));
    }

    /**
     * Fails a job through the legacy identifier-only transition API.
     *
     * @param jobId job identifier
     * @param error failure detail supplied by the compatibility caller
     */
    @Transactional
    public void fail(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> job.markFailed(error));
    }

    /**
     * Loads one capacity job without locking it.
     *
     * @param jobId job identifier
     * @return persisted job when it exists
     */
    @Transactional(readOnly = true)
    public Optional<KfeChannelCapacityJobEntity> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /**
     * Atomically claims a job with a fresh token and a lease of at least one second.
     *
     * @param jobId candidate job
     * @param workerId worker label normalized for storage
     * @param leaseSeconds requested lease duration, clamped to at least one second
     * @return claim token when repository state permitted the claim
     */
    @Transactional
    public Optional<JobClaim> claim(UUID jobId, String workerId, long leaseSeconds) {
        UUID token = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jobRepository.claim(jobId, now, normalize(workerId), token,
                now.plusSeconds(Math.max(1L, leaseSeconds)));
        return updated == 1 ? Optional.of(new JobClaim(jobId, token)) : Optional.empty();
    }

    /**
     * Extends a live claim lease; null claim or stale token returns false without changing ownership.
     *
     * @param claim current job/token pair
     * @param leaseSeconds requested extension, clamped to at least one second
     * @return true when the repository renewed the matching claim
     */
    @Transactional
    public boolean heartbeat(JobClaim claim, long leaseSeconds) {
        if (claim == null) return false;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return jobRepository.heartbeat(claim.jobId(), claim.claimToken(), now,
                now.plusSeconds(Math.max(1L, leaseSeconds))) == 1;
    }

    /**
     * Fenced completion transition that succeeds only for the current claim token.
     *
     * @param claim current claim identity
     * @param providerReference successful provider execution reference
     * @return true when this claim completed the job
     */
    @Transactional
    public boolean complete(JobClaim claim, String providerReference) {
        return claim != null && jobRepository.completeClaimed(claim.jobId(), claim.claimToken(),
                providerReference, LocalDateTime.now(ZoneOffset.UTC)) == 1;
    }

    /**
     * Fenced failure transition using a generic reconciliation message instead of caller-supplied data.
     *
     * @param claim current claim identity
     * @param ignoredError caller message intentionally ignored for safe persistence
     * @return true when this claim failed the job
     */
    @Transactional
    public boolean fail(JobClaim claim, String ignoredError) {
        return claim != null && jobRepository.failClaimed(claim.jobId(), claim.claimToken(),
                "Channel capacity execution failed; reconciliation required.",
                LocalDateTime.now(ZoneOffset.UTC)) == 1;
    }

    /**
     * Records the persisted intent, decision, target, trigger, and cost evidence for later investigation.
     *
     * @param job newly saved capacity job
     * @param profitWalletId SYSTEM_PROFIT wallet associated with the admission decision
     */
    private void audit(KfeChannelCapacityJobEntity job, UUID profitWalletId) {
        auditLogService.record(
                "KFE_CHANNEL_CAPACITY",
                null,
                profitWalletId,
                null,
                null,
                Map.of(
                        "capacityJobId", job.getId().toString(),
                        "intent", job.getIntent().name(),
                        "status", job.getStatus().name(),
                        "peerPubkey", job.getPeerPubkey() != null ? job.getPeerPubkey() : "",
                        "channelPoint", job.getChannelPoint() != null ? job.getChannelPoint() : "",
                        "triggerReason", job.getTriggerReason() != null ? job.getTriggerReason() : "",
                        "localAmountSats", job.getLocalAmountSats(),
                        "estimatedCostSats", job.getEstimatedCostSats()));
    }

    /**
     * Limits optional trigger text to the target persistence column length.
     *
     * @param value trigger text
     * @param max maximum retained characters
     * @return unchanged value within limit, truncated value, or null
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Normalizes worker IDs to lowercase, supplies a default, and caps the persisted label at 128 chars.
     *
     * @param workerId worker-supplied label
     * @return normalized nonblank worker label
     */
    private static String normalize(String workerId) {
        if (workerId == null || workerId.isBlank()) return "kfe-channel-capacity-worker";
        String value = workerId.trim().toLowerCase(java.util.Locale.ROOT);
        return value.substring(0, Math.min(128, value.length()));
    }
}
