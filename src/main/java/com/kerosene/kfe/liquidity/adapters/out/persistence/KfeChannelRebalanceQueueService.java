package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelRebalanceJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Durable queue for rebalance work after binary decision pass.
 * Circular submission uses a durable identity and atomic fee settlement under a lease fence.
 */
@Service
public class KfeChannelRebalanceQueueService {

    /** Logger for soft rejection when SYSTEM_PROFIT cannot cover estimated rebalance cost. */
    private static final Logger log = LoggerFactory.getLogger(KfeChannelRebalanceQueueService.class);

    /** Persists rebalance jobs and executes claim-fenced status transitions. */
    private final KfeChannelRebalanceJobRepository jobRepository;
    /** Resolves the SYSTEM_PROFIT wallet required for structural rebalance costs. */
    private final KfeSystemWalletService systemWalletService;
    /** Reads available profit balance before enqueue and reserves/settles funds in the worker. */
    private final KfeBalanceService balanceService;
    /** Records newly admitted rebalance jobs for audit. */
    private final KfeAuditLogService auditLogService;

    /**
     * Lease-fencing identity required for worker heartbeat and completion transitions.
     *
     * @param jobId claimed rebalance job
     * @param claimToken token assigned to the current lease
     */
    public record JobClaim(UUID jobId, UUID claimToken) { }

    /**
     * Creates the durable rebalance queue service.
     *
     * @param jobRepository job persistence and claim operations
     * @param systemWalletService SYSTEM_PROFIT wallet resolver
     * @param balanceService available balance read/lock service
     * @param auditLogService audit event writer
     */
    public KfeChannelRebalanceQueueService(
            KfeChannelRebalanceJobRepository jobRepository,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Enqueues at most one PENDING/IN_PROGRESS job per channel point. Admission requires the profit
     * wallet and performs a soft available-balance check for positive estimated cost without reserving
     * funds; the worker reserves the fee budget when provider execution begins. Unique-key races reload
     * the active job instead of producing a duplicate intent.
     *
     * @param decisionId passing policy decision that authorized this work
     * @param channelPoint channel being rebalanced
     * @param peerPubkey remote peer, if known
     * @param estimatedCostSats estimated structural cost in satoshis
     * @param expectedGainSats expected future gain in satoshis
     * @return existing active job, new PENDING job, or empty when profit funds are insufficient
     * @throws IllegalArgumentException when channel point is blank
     */
    @Transactional
    public Optional<KfeChannelRebalanceJobEntity> enqueueIfAbsent(
            UUID decisionId,
            String channelPoint,
            String peerPubkey,
            long estimatedCostSats,
            long expectedGainSats) {
        if (channelPoint == null || channelPoint.isBlank()) {
            throw new IllegalArgumentException("channelPoint is required.");
        }
        Optional<KfeChannelRebalanceJobEntity> existing = jobRepository.findFirstByChannelPointAndStatusIn(
                channelPoint.trim(),
                EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        // Costs must be attributable to SYSTEM_PROFIT policy (wallet must exist).
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        if (estimatedCostSats > 0L) {
            // Soft capacity check on profit available (does not reserve until provider starts).
            var profit = balanceService.requireForUpdate(profitWalletId, KfeSystemWalletService.ASSET_BTC);
            if (profit.getAvailableSats() < estimatedCostSats) {
                log.warn(
                        "[KFE Channel Rebal] profit wallet insufficient for estimated cost channel={} cost={} available={}",
                        channelPoint,
                        estimatedCostSats,
                        profit.getAvailableSats());
                return Optional.empty();
            }
        }

        KfeChannelRebalanceJobEntity job = new KfeChannelRebalanceJobEntity();
        job.setDecisionId(decisionId);
        job.setChannelPoint(channelPoint.trim());
        job.setPeerPubkey(peerPubkey);
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setExpectedGainSats(Math.max(0L, expectedGainSats));
        job.setStatus(KfeChannelRebalanceJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByChannelPointAndStatusIn(
                    channelPoint.trim(),
                    EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS));
        }
        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                null,
                profitWalletId,
                null,
                null,
                java.util.Map.of(
                        "rebalanceJobId", job.getId().toString(),
                        "channelPoint", job.getChannelPoint(),
                        "status", job.getStatus().name(),
                        "estimatedCostSats", job.getEstimatedCostSats(),
                        "expectedGainSats", job.getExpectedGainSats()));
        return Optional.of(job);
    }

    /**
     * Lists oldest PENDING jobs in one page; a nonpositive limit is clamped to one.
     *
     * @param limit maximum number of pending jobs
     * @return pending jobs ordered by creation time
     */
    @Transactional(readOnly = true)
    public List<KfeChannelRebalanceJobEntity> pending(int limit) {
        return jobRepository.findClaimCandidates(
                LocalDateTime.now(ZoneOffset.UTC),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * Loads one job without acquiring a lock.
     *
     * @param jobId job identifier
     * @return job if present
     */
    @Transactional(readOnly = true)
    public Optional<KfeChannelRebalanceJobEntity> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /**
     * Attempts to claim a job with a fresh fencing token and a UTC lease of at least one second.
     *
     * @param jobId candidate job
     * @param workerId worker label normalized and capped for persistence
     * @param leaseSeconds requested claim duration, clamped to at least one second
     * @return claim when the atomic repository transition succeeds
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
     * Renews the lease only when the supplied token still owns the claim.
     *
     * @param claim current job/token pair
     * @param leaseSeconds requested lease extension, clamped to at least one second
     * @return true when the current claim was renewed
     */
    @Transactional
    public boolean heartbeat(JobClaim claim, long leaseSeconds) {
        if (claim == null) return false;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return jobRepository.heartbeat(claim.jobId(), claim.claimToken(), now,
                now.plusSeconds(Math.max(1L, leaseSeconds))) == 1;
    }

    /**
     * Completes a job only when the token matches its current lease.
     *
     * @param claim current job/token pair
     * @param providerReference payment hash or Loop swap reference
     * @return true when this claim marked the job completed
     */
    @Transactional
    public boolean completeClaim(JobClaim claim, String providerReference) {
        KfeChannelRebalanceJobEntity job = owned(claim);
        if (job == null || !"READY".equals(job.getExecutionPhase())) return false;
        job.markCompleted(providerReference);
        jobRepository.saveAndFlush(job);
        return true;
    }

    /**
     * Fails a job through the claim fence with a fixed reconciliation message; caller error detail is ignored.
     *
     * @param claim current job/token pair
     * @param ignoredError caller detail intentionally ignored for safe persistence
     * @return true when this claim marked the job failed
     */
    @Transactional
    public boolean failClaim(JobClaim claim, String ignoredError) {
        KfeChannelRebalanceJobEntity job = owned(claim);
        if (job == null || !"READY".equals(job.getExecutionPhase())) return false;
        job.markFailed("Execution rejected before payment dispatch");
        jobRepository.saveAndFlush(job);
        return true;
    }

    /**
     * Compatibility transition for workers that do not use claim tokens; accepts only pending or
     * already in-progress states and records a provider reference.
     *
     * @param jobId job identifier
     * @param providerReference worker/provider start reference
     */
    @Transactional
    public void markInProgress(UUID jobId, String providerReference) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            if (job.getClaimToken() != null || !"READY".equals(job.getExecutionPhase()))
                throw new IllegalStateException("Claim-fenced rebalance transition required");
            if (job.getStatus() != KfeChannelRebalanceJobStatus.PENDING
                    && job.getStatus() != KfeChannelRebalanceJobStatus.IN_PROGRESS) {
                return;
            }
            job.markInProgress(providerReference);
            jobRepository.save(job);
        });
    }

    /**
     * Compatibility completion transition without claim fencing.
     *
     * @param jobId job identifier
     * @param providerReference successful provider reference
     */
    @Transactional
    public void complete(UUID jobId, String providerReference) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            if (job.getClaimToken() != null || !"READY".equals(job.getExecutionPhase()))
                throw new IllegalStateException("Claim-fenced rebalance transition required");
            job.markCompleted(providerReference);
            jobRepository.save(job);
        });
    }

    /**
     * Compatibility failure transition without claim fencing.
     *
     * @param jobId job identifier
     * @param error failure detail supplied by compatibility caller
     */
    @Transactional
    public void fail(UUID jobId, String error) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            if (job.getClaimToken() != null || !"READY".equals(job.getExecutionPhase()))
                throw new IllegalStateException("Claim-fenced rebalance transition required");
            job.markFailed(error);
            jobRepository.save(job);
        });
    }

    /** Commits the fee reservation and recovery identity together, before any payment send. */
    @Transactional
    public boolean beginDispatch(JobClaim claim, String paymentHash, long feeBudget) {
        if (paymentHash == null || !paymentHash.matches("[0-9a-fA-F]{64}") || feeBudget < 0)
            throw new IllegalArgumentException("Invalid payment identity or fee budget");
        KfeChannelRebalanceJobEntity job = owned(claim);
        if (job == null || !"READY".equals(job.getExecutionPhase())) return false;
        UUID walletId = systemWalletService.requireProfitWalletId();
        if (feeBudget > 0) balanceService.reserve(walletId, KfeSystemWalletService.ASSET_BTC, feeBudget);
        job.setFeeWalletId(walletId);
        job.setReservedFeeSats(feeBudget);
        job.setPaymentHash(paymentHash.toLowerCase(java.util.Locale.ROOT));
        job.markInProgress(paymentHash);
        job.setExecutionPhase("RECONCILING");
        jobRepository.saveAndFlush(job);
        return true;
    }

    /** Settles fees and finalizes the job in the same database transaction under the lease fence. */
    @Transactional
    public boolean finishDispatch(JobClaim claim, boolean succeeded, long actualFeeSats) {
        KfeChannelRebalanceJobEntity job = owned(claim);
        if (job == null || !"RECONCILING".equals(job.getExecutionPhase())
                || job.getFeeWalletId() == null || job.getPaymentHash() == null) return false;
        long budget = job.getReservedFeeSats();
        if (actualFeeSats < 0 || actualFeeSats > budget || (!succeeded && actualFeeSats != 0)) return false;
        if (actualFeeSats > 0) balanceService.settleReservedDebit(
                job.getFeeWalletId(), KfeSystemWalletService.ASSET_BTC, actualFeeSats);
        if (budget > actualFeeSats) balanceService.releaseReserved(
                job.getFeeWalletId(), KfeSystemWalletService.ASSET_BTC, budget - actualFeeSats);
        if (succeeded) job.markCompleted(job.getPaymentHash());
        else job.markFailed("Provider confirmed terminal payment failure");
        job.setExecutionPhase("SETTLED");
        job.setActualFeeSats(actualFeeSats);
        job.setReservedFeeSats(0L);
        job.setClaimedBy(null);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        jobRepository.saveAndFlush(job);
        auditLogService.record("KFE_CHANNEL_DECISION", null, job.getFeeWalletId(), null, null,
                java.util.Map.of("rebalanceJobId", job.getId().toString(), "paymentHash", job.getPaymentHash(),
                        "success", succeeded ? 1 : 0, "feeSats", actualFeeSats, "status", job.getStatus().name()));
        return true;
    }

    private KfeChannelRebalanceJobEntity owned(JobClaim claim) {
        if (claim == null) return null;
        return jobRepository.findForUpdate(claim.jobId()).filter(job ->
                job.getStatus() == KfeChannelRebalanceJobStatus.IN_PROGRESS
                && claim.claimToken().equals(job.getClaimToken())
                && job.getLeaseExpiresAt() != null
                && job.getLeaseExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))).orElse(null);
    }

    /**
     * Normalizes worker labels to lowercase, supplies a stable fallback, and caps them at 128 chars.
     *
     * @param workerId worker-provided owner label
     * @return normalized nonblank label suitable for persistence
     */
    private static String normalize(String workerId) {
        if (workerId == null || workerId.isBlank()) return "kfe-channel-rebalance-worker";
        String value = workerId.trim().toLowerCase(java.util.Locale.ROOT);
        return value.substring(0, Math.min(128, value.length()));
    }
}
