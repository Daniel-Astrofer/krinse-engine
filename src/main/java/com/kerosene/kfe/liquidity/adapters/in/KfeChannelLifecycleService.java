package com.kerosene.kfe.liquidity.adapters.in;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelRebalanceQueueService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionResult;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelDecisionService;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelSnapshotResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeCloseChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfePpmAdjustRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeRebalanceChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;
import com.kerosene.kfe.adapters.out.rail.channels.ChannelsMeshInjectGateway;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelOperationDecisionRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Channel lifecycle: binary decision → optional LND execution → forensic row.
 * Structural costs are attributed under SYSTEM_PROFIT policy (profit wallet must exist).
 *
 * <p>When {@code kfe.channel.require-channels-mesh-inject=true}, OPEN couples mesh CHANNELS
 * soft-reserve → CHANNELS Taproot on-chain fund to LND address → LND {@code openChannel} →
 * Intent commit (release on open/fund failure). Durable phase + stable Intent id enable crash
 * resume and commit-retry. Open requires a mesh fund txid (no sole reliance on pre-existing
 * LND wallet UTXOs).
 */
@Service
public class KfeChannelLifecycleService {

    /** Durable phase indicating CHANNELS capacity has been reserved for an open. */
    public static final String PHASE_RESERVED = "RESERVED";
    /** Durable phase indicating the reserved CHANNELS amount was funded to the LND wallet. */
    public static final String PHASE_FUNDED = "FUNDED";
    /** Durable phase indicating LND opened the channel but the mesh intent commit is pending. */
    public static final String PHASE_OPENED_COMMIT_PENDING = "OPENED_COMMIT_PENDING";
    /** Durable terminal phase indicating the mesh intent was committed after channel open. */
    public static final String PHASE_COMMITTED = "COMMITTED";
    /** Durable phase indicating an open attempt's mesh reservation was compensated and released. */
    public static final String PHASE_RELEASED = "RELEASED";

    /** Applies business policy to proposed channel operations before provider execution. */
    private final KfeChannelDecisionService decisionService;
    /** Performs channel queries and mutations against the configured Lightning node. */
    private final LightningChannelGateway channelGateway;
    /** Reserves, funds, commits, and releases CHANNELS mesh intents for channel opens. */
    private final ChannelsMeshInjectGateway channelsMeshInject;
    /** Stores idempotent decisions and durable progress for channel operations. */
    private final KfeChannelOperationDecisionRepository decisionRepository;
    /** Persists asynchronous rebalance work accepted by a passing decision. */
    private final KfeChannelRebalanceQueueService rebalanceQueueService;
    /** Produces stable hashes for proposals and idempotency keys. */
    private final KfeHashService hashService;
    /** Resolves the system profit wallet required for structural channel costs. */
    private final KfeSystemWalletService systemWalletService;
    /** Records an auditable entry when a channel decision is persisted. */
    private final KfeAuditLogService auditLogService;
    /** Serializes decision flags for durable forensic storage. */
    private final ObjectMapper objectMapper;
    /** Whether channel policy requires anchor outputs when the request leaves the option unspecified. */
    private final boolean anchorsRequired;
    /** Default channel policy time-lock delta, clamped to the protocol minimum during construction. */
    private final int defaultTimeLockDelta;
    /** Whether channel opens must use the CHANNELS mesh reserve/fund/commit workflow. */
    private final boolean requireChannelsMeshInject;
    /** Lab-only switch permitting zero-confirmation mesh funding for channel opens. */
    private final boolean meshInjectFundZeroConfLab;

    /**
     * Creates the lifecycle coordinator with its policy, persistence, audit, wallet, and provider ports.
     *
     * @param decisionService channel decision policy evaluator
     * @param channelGateway Lightning node channel and on-chain operations
     * @param channelsMeshInject mesh intent operations used for funded channel opens
     * @param decisionRepository persistence for decisions and resumable workflow state
     * @param rebalanceQueueService durable rebalance job queue
     * @param hashService stable proposal and command hashing service
     * @param systemWalletService system profit wallet guard
     * @param auditLogService audit trail writer
     * @param objectMapper JSON serializer for decision evidence
     * @param anchorsRequired default anchor policy when a request does not specify it
     * @param defaultTimeLockDelta requested default, clamped to at least 18
     * @param requireChannelsMeshInject enable mesh-funded channel opens
     * @param meshInjectFundZeroConfLab permit the lab zero-confirmation funding path
     */
    public KfeChannelLifecycleService(
            KfeChannelDecisionService decisionService,
            LightningChannelGateway channelGateway,
            ChannelsMeshInjectGateway channelsMeshInject,
            KfeChannelOperationDecisionRepository decisionRepository,
            KfeChannelRebalanceQueueService rebalanceQueueService,
            KfeHashService hashService,
            KfeSystemWalletService systemWalletService,
            KfeAuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${kfe.channel.anchors-required:true}") boolean anchorsRequired,
            @Value("${kfe.channel.default-time-lock-delta:80}") int defaultTimeLockDelta,
            @Value("${kfe.channel.require-channels-mesh-inject:false}") boolean requireChannelsMeshInject,
            @Value("${kfe.channel.mesh-inject-fund-zero-conf-lab:false}")
                    boolean meshInjectFundZeroConfLab) {
        this.decisionService = decisionService;
        this.channelGateway = channelGateway;
        this.channelsMeshInject = channelsMeshInject;
        this.decisionRepository = decisionRepository;
        this.rebalanceQueueService = rebalanceQueueService;
        this.hashService = hashService;
        this.systemWalletService = systemWalletService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.anchorsRequired = anchorsRequired;
        this.defaultTimeLockDelta = Math.max(18, defaultTimeLockDelta);
        this.requireChannelsMeshInject = requireChannelsMeshInject;
        this.meshInjectFundZeroConfLab = meshInjectFundZeroConfLab;
    }

    /**
     * Lists current provider channels as API snapshots.
     *
     * @return immutable snapshot list from the live channel gateway
     */
    @Transactional(readOnly = true)
    public List<KfeChannelSnapshotResponse> listChannels() {
        return channelGateway.listChannels().stream().map(this::toSnapshot).toList();
    }

    /**
     * Evaluates an open proposal and stores or returns its idempotent decision without opening a channel.
     * The idempotency key includes amount, fee rate, anchor, privacy, and unconfirmed-spend options.
     *
     * @param request proposed peer, amount, and channel options
     * @return the existing decision for an identical request, or a newly persisted policy result
     */
    @Transactional
    public KfeChannelDecisionResponse evaluateOpen(KfeOpenChannelRequest request) {
        boolean anchors = resolveAnchorsEnabled(request.anchorsEnabled());
        long feeRate = request.estimatedFeeRateSatVb() != null ? request.estimatedFeeRateSatVb() : 0L;
        String idempotencyKey = operationKey(
                "OPEN", request.peerPubkey(), null, request.localAmountSats(),
                feeRate + ":" + anchors + ":" + request.privateChannel() + ":" + request.spendUnconfirmed());
        Optional<KfeChannelOperationDecisionEntity> existing = decisionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return toResponse(existing.orElseThrow());
        }
        String proposal = proposalHash("OPEN", request.peerPubkey(), request.localAmountSats());
        ChannelDecisionResult decision = decisionService.evaluateOpen(
                request.peerPubkey(),
                request.localAmountSats(),
                feeRate,
                anchors,
                proposal);
        return persistDecision(
                KfeChannelOperationType.OPEN,
                decision,
                request.peerPubkey(),
                null,
                request.localAmountSats(),
                false,
                null,
                idempotencyKey);
    }

    /**
     * Executes a passing open decision, resuming any durable mesh workflow before considering a new one.
     * A previously opened channel with a pending mesh commit is committed idempotently, never opened twice.
     *
     * @param request requested peer, local capacity, and provider options
     * @return persisted decision and execution/provider state
     */
    @Transactional
    public KfeChannelDecisionResponse openChannel(KfeOpenChannelRequest request) {
        KfeChannelDecisionResponse evaluated = resumeOrEvaluateOpen(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (evaluated.executed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }

        if (requireChannelsMeshInject) {
            KfeChannelOperationDecisionEntity existing =
                    decisionRepository.findById(evaluated.id()).orElse(null);
            if (existing != null
                    && PHASE_OPENED_COMMIT_PENDING.equals(existing.getMeshInjectPhase())) {
                // Channel already opened on LND — only retry Intent commit (do not
                // treat pending open as a double-open refuse).
                return retryCommit(evaluated.id());
            }
        }

        if (hasOpenOrPendingChannelToPeer(request.peerPubkey())) {
            return markReason(evaluated.id(), "CHANNEL_ALREADY_OPEN");
        }
        systemWalletService.requireProfitWalletId();

        if (!requireChannelsMeshInject) {
            LightningChannelGateway.OpenChannelResult result = channelGateway.openChannel(
                    new LightningChannelGateway.OpenChannelCommand(
                            request.peerPubkey(),
                            request.localAmountSats(),
                            Boolean.TRUE.equals(request.privateChannel()),
                            Boolean.TRUE.equals(request.spendUnconfirmed())));
            return markExecuted(evaluated.id(), result.channelPoint(), result.fundingTxid());
        }

        return openChannelWithMeshInject(evaluated.id(), request);
    }

    /**
     * Runs the resumable reserve, on-chain fund, LND open, and mesh commit sequence.
     * Each successful external step is recorded before the next step so a later call can resume safely.
     *
     * @param decisionId persisted passing decision anchoring this workflow
     * @param request peer and funding options for the channel
     * @return updated decision, including pending-commit or compensation failure state when applicable
     */
    private KfeChannelDecisionResponse openChannelWithMeshInject(
            UUID decisionId, KfeOpenChannelRequest request) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));

        String injectIntentId = entity.getMeshIntentId();
        if (injectIntentId == null || injectIntentId.isBlank()) {
            injectIntentId = "channels-inject-open-" + decisionId;
            entity.setMeshIntentId(injectIntentId);
            decisionRepository.save(entity);
        }

        String phase = entity.getMeshInjectPhase();
        if (phase == null
                || phase.isBlank()
                || PHASE_RELEASED.equals(phase)) {
            ChannelsMeshInjectGateway.DebitResult debit =
                    channelsMeshInject.reserveOpen(
                            injectIntentId, request.localAmountSats(), request.peerPubkey());
            if (!debit.authorized()) {
                return markReason(
                        decisionId,
                        debit.reasonCode() == null
                                ? "CHANNELS_INJECT_RESERVE_FAILED"
                                : debit.reasonCode());
            }
            injectIntentId = debit.intentId() != null ? debit.intentId() : injectIntentId;
            entity.setMeshIntentId(injectIntentId);
            entity.setMeshInjectPhase(PHASE_RESERVED);
            entity.setDecisionReason("MESH_RESERVED");
            decisionRepository.save(entity);
            phase = PHASE_RESERVED;
        }

        if (PHASE_RESERVED.equals(phase)) {
            String fundingAddress = entity.getLndFundingAddress();
            if (fundingAddress == null || fundingAddress.isBlank()) {
                try {
                    fundingAddress =
                            channelGateway.newOnchainAddress("channels-inject-" + decisionId);
                } catch (RuntimeException ex) {
                    return markReason(
                            decisionId,
                            "CHANNELS_INJECT_LND_ADDRESS_FAILED:"
                                    + ex.getClass().getSimpleName());
                }
                entity.setLndFundingAddress(fundingAddress);
                decisionRepository.save(entity);
            }

            ChannelsMeshInjectGateway.FundResult funded =
                    channelsMeshInject.fundOpen(
                            injectIntentId, request.localAmountSats(), fundingAddress);
            if (!funded.authorized()) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                return markReason(
                        decisionId,
                        funded.reasonCode() == null
                                ? "CHANNELS_INJECT_FUND_FAILED"
                                : funded.reasonCode());
            }
            // Fail-closed: mesh must broadcast CHANNELS→LND fund tx — do not open
            // solely on pre-existing LND wallet UTXOs.
            if (funded.fundingTxid() == null || funded.fundingTxid().isBlank()) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                return markReason(decisionId, "CHANNELS_INJECT_FUND_MISSING_TXID");
            }

            if (!meshInjectFundZeroConfLab) {
                long onchain = channelGateway.confirmedOnchainBalanceSats();
                if (onchain >= 0L && onchain < request.localAmountSats()) {
                    compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                    return markReason(decisionId, "CHANNELS_INJECT_LND_WALLET_UNDERFUNDED");
                }
            }

            entity.setMeshFundTxid(funded.fundingTxid());
            entity.setMeshInjectPhase(PHASE_FUNDED);
            entity.setDecisionReason("MESH_FUNDED");
            decisionRepository.save(entity);
            phase = PHASE_FUNDED;
        }

        if (PHASE_FUNDED.equals(phase)) {
            if (entity.getMeshFundTxid() == null || entity.getMeshFundTxid().isBlank()) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                return markReason(decisionId, "CHANNELS_INJECT_FUND_MISSING_TXID");
            }
            LightningChannelGateway.OpenChannelResult result;
            try {
                result = channelGateway.openChannel(
                        new LightningChannelGateway.OpenChannelCommand(
                                request.peerPubkey(),
                                request.localAmountSats(),
                                Boolean.TRUE.equals(request.privateChannel()),
                                Boolean.TRUE.equals(request.spendUnconfirmed())
                                        || meshInjectFundZeroConfLab));
            } catch (RuntimeException ex) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                String suffix = PHASE_RELEASED.equals(entity.getMeshInjectPhase())
                        ? ":COMPENSATED"
                        : ":COMPENSATE_FAILED";
                return markReason(decisionId, "OPEN_FAILED_AFTER_DEBIT" + suffix);
            }

            entity.setChannelPoint(result.channelPoint());
            entity.setProviderReference(
                    "OPENED_COMMIT_PENDING:"
                            + injectIntentId
                            + "|"
                            + (result.fundingTxid() == null ? "" : result.fundingTxid()));
            entity.setMeshInjectPhase(PHASE_OPENED_COMMIT_PENDING);
            entity.setDecisionReason("OPENED_COMMIT_PENDING");
            entity.setExecuted(false);
            decisionRepository.save(entity);
            phase = PHASE_OPENED_COMMIT_PENDING;
        }

        if (PHASE_OPENED_COMMIT_PENDING.equals(phase)) {
            return retryCommit(decisionId);
        }

        return toResponse(entity);
    }

    /**
     * Idempotent Intent commit for decisions stuck in {@link #PHASE_OPENED_COMMIT_PENDING}.
     */
    @Transactional
    public KfeChannelDecisionResponse retryCommit(UUID decisionId) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        if (entity.isExecuted() && PHASE_COMMITTED.equals(entity.getMeshInjectPhase())) {
            return toResponse(entity);
        }
        String intentId = entity.getMeshIntentId();
        if (intentId == null || intentId.isBlank()) {
            return markReason(decisionId, "OPENED_COMMIT_FAILED:MISSING_INTENT");
        }
        ChannelsMeshInjectGateway.InjectResult committed = channelsMeshInject.commitOpen(intentId);
        if (!committed.authorized()) {
            entity.setDecisionReason(
                    "OPENED_COMMIT_FAILED:"
                            + intentId
                            + ":"
                            + (committed.reasonCode() == null ? "UNKNOWN" : committed.reasonCode()));
            entity.setMeshInjectPhase(PHASE_OPENED_COMMIT_PENDING);
            entity.setExecuted(false);
            return toResponse(decisionRepository.save(entity));
        }
        entity.setMeshInjectPhase(PHASE_COMMITTED);
        entity.setExecuted(true);
        entity.setDecisionReason("EXECUTED");
        String ref = entity.getProviderReference();
        if (ref == null || ref.startsWith("OPENED_COMMIT_PENDING:")) {
            entity.setProviderReference(
                    "MESH:"
                            + intentId
                            + (entity.getChannelPoint() != null
                                    ? "|" + entity.getChannelPoint()
                                    : ""));
        }
        return toResponse(decisionRepository.save(entity));
    }

    /**
     * Attempts to release the mesh reservation after funding or LND opening fails.
     * Marks the decision released only when the mesh confirms authorization; failed compensation
     * remains visible through the durable phase for later investigation.
     *
     * @param entity persisted decision carrying the mesh intent identifier
     * @param amountSats reserved local channel capacity
     * @param peerPubkey remote peer associated with the reservation
     */
    private void compensateRelease(
            KfeChannelOperationDecisionEntity entity, long amountSats, String peerPubkey) {
        String intentId = entity.getMeshIntentId();
        if (intentId == null || intentId.isBlank()) {
            return;
        }
        ChannelsMeshInjectGateway.InjectResult released =
                channelsMeshInject.releaseOpen(intentId, amountSats, peerPubkey);
        if (released.authorized()) {
            entity.setMeshInjectPhase(PHASE_RELEASED);
            decisionRepository.save(entity);
        }
    }

    /**
     * Returns the latest matching resumable open or evaluates a fresh proposal if none exists.
     *
     * @param request proposed open request used to match recoverable work
     * @return resumable or newly evaluated decision
     */
    private KfeChannelDecisionResponse resumeOrEvaluateOpen(KfeOpenChannelRequest request) {
        Optional<KfeChannelOperationDecisionEntity> resumable =
                decisionRepository.findLatestResumableOpen(
                        request.peerPubkey(),
                        request.localAmountSats(),
                        List.of(PHASE_RESERVED, PHASE_FUNDED, PHASE_OPENED_COMMIT_PENDING));
        if (resumable.isPresent()) {
            return toResponse(resumable.get());
        }
        return evaluateOpen(request);
    }

    /**
     * Checks active and pending provider channels using a trimmed, case-insensitive peer key.
     *
     * @param peerPubkey remote node public key to check
     * @return true if an active or pending channel already targets that peer
     */
    private boolean hasOpenOrPendingChannelToPeer(String peerPubkey) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return false;
        }
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        boolean open = channelGateway.listChannels().stream()
                .anyMatch(
                        c -> c.remotePubkey() != null
                                && normalized.equals(c.remotePubkey().trim().toLowerCase(Locale.ROOT)));
        if (open) {
            return true;
        }
        return channelGateway.listPendingChannels().stream()
                .anyMatch(
                        c -> c.remotePubkey() != null
                                && normalized.equals(
                                        c.remotePubkey().trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Evaluates a rebalance and persists a deterministic decision keyed by channel and cost/gain inputs.
     *
     * @param request channel point and estimated rebalance economics
     * @return existing decision for a duplicate command or the newly evaluated result
     */
    @Transactional
    public KfeChannelDecisionResponse evaluateRebalance(KfeRebalanceChannelRequest request) {
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        String idempotencyKey = operationKey(
                "REBALANCE", channel != null ? channel.remotePubkey() : null, request.channelPoint(),
                request.estimatedCostSats() != null ? request.estimatedCostSats() : 0L,
                String.valueOf(request.expectedGainSats() != null ? request.expectedGainSats() : 0L));
        Optional<KfeChannelOperationDecisionEntity> existing = decisionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return toResponse(existing.orElseThrow());
        }
        ChannelDecisionResult decision = decisionService.evaluateRebalance(
                channel,
                request.estimatedCostSats() != null ? request.estimatedCostSats() : 0L,
                request.expectedGainSats() != null ? request.expectedGainSats() : 0L);
        return persistDecision(
                KfeChannelOperationType.REBALANCE,
                decision,
                channel != null ? channel.remotePubkey() : null,
                request.channelPoint(),
                request.estimatedCostSats(),
                false,
                null,
                idempotencyKey);
    }

    /**
     * Enqueues a passing rebalance once; this method records queue acceptance rather than claiming
     * the asynchronous rebalance itself has completed.
     *
     * @param request channel point and estimated rebalance economics
     * @return the decision marked queued, or a persisted rejection reason
     */
    @Transactional
    public KfeChannelDecisionResponse rebalance(KfeRebalanceChannelRequest request) {
        KfeChannelDecisionResponse evaluated = evaluateRebalance(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (evaluated.executed()) {
            return evaluated;
        }
        systemWalletService.requireProfitWalletId();
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        var job = rebalanceQueueService.enqueueIfAbsent(
                evaluated.id(),
                request.channelPoint(),
                channel != null ? channel.remotePubkey() : null,
                request.estimatedCostSats() != null ? request.estimatedCostSats() : 0L,
                request.expectedGainSats() != null ? request.expectedGainSats() : 0L);
        if (job.isEmpty()) {
            return markReason(evaluated.id(), "REBALANCE_ENQUEUE_REJECTED");
        }
        return markExecuted(
                evaluated.id(),
                request.channelPoint(),
                "REBALANCE_QUEUED:" + job.get().getId());
    }

    /**
     * Reads pending rebalance jobs, bounded by the caller-provided limit.
     *
     * @param limit maximum number of pending jobs requested
     * @return pending durable queue entries
     */
    @Transactional(readOnly = true)
    public java.util.List<com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity> pendingRebalances(int limit) {
        return rebalanceQueueService.pending(limit);
    }

    /**
     * Evaluates a close proposal using channel state, peer-offline evidence, force option, anchors,
     * and the requested fee-rate estimate. Identical inputs reuse their persisted decision.
     *
     * @param request close target and decision inputs
     * @return stored or newly evaluated close decision
     */
    @Transactional
    public KfeChannelDecisionResponse evaluateClose(KfeCloseChannelRequest request) {
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        long feeRate = request.estimatedFeeRateSatVb() != null ? request.estimatedFeeRateSatVb() : 1L;
        String idempotencyKey = operationKey(
                "CLOSE", channel != null ? channel.remotePubkey() : null, request.channelPoint(), 0L,
                feeRate + ":" + request.peerOfflineBeyondThreshold() + ":" + request.force());
        Optional<KfeChannelOperationDecisionEntity> existing = decisionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return toResponse(existing.orElseThrow());
        }
        ChannelDecisionResult decision = decisionService.evaluateClose(
                channel,
                Boolean.TRUE.equals(request.peerOfflineBeyondThreshold()),
                !Boolean.TRUE.equals(request.force()),
                anchorsRequired,
                feeRate);
        return persistDecision(
                KfeChannelOperationType.CLOSE,
                decision,
                channel != null ? channel.remotePubkey() : null,
                request.channelPoint(),
                null,
                false,
                null,
                idempotencyKey);
    }

    /**
     * Closes a channel only after policy passes and the provider gateway is live.
     *
     * @param request close target and force option
     * @return decision updated with closing transaction reference or execution failure reason
     */
    @Transactional
    public KfeChannelDecisionResponse closeChannel(KfeCloseChannelRequest request) {
        KfeChannelDecisionResponse evaluated = evaluateClose(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (evaluated.executed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }
        LightningChannelGateway.CloseChannelResult result = channelGateway.closeChannel(
                new LightningChannelGateway.CloseChannelCommand(
                        request.channelPoint(),
                        Boolean.TRUE.equals(request.force())));
        return markExecuted(evaluated.id(), request.channelPoint(), result.closingTxid());
    }

    /**
     * Evaluates a fee-policy adjustment and stores its recommended target in the decision evidence.
     *
     * @param request channel point and current policy inputs
     * @return persisted policy decision
     */
    @Transactional
    public KfeChannelDecisionResponse evaluatePpm(KfePpmAdjustRequest request) {
        long current = request.currentPpm() != null ? request.currentPpm() : 0L;
        boolean drain = Boolean.TRUE.equals(request.acceleratedDrain());
        ChannelDecisionResult decision = decisionService.evaluatePpm(current, drain);
        return persistDecision(
                KfeChannelOperationType.PPM_ADJUST,
                decision,
                null,
                request.channelPoint(),
                decisionService.recommendedPpm(current, drain),
                false,
                null,
                operationKey("PPM_ADJUST", null, request.channelPoint(), current,
                        request.baseFeeMsat() + ":" + drain));
    }

    /**
     * Applies the policy-recommended ppm after the decision passes and the gateway is live.
     *
     * @param request channel point and current fee-policy inputs
     * @return decision marked with the applied ppm or an explicit provider failure reason
     */
    @Transactional
    public KfeChannelDecisionResponse adjustPpm(KfePpmAdjustRequest request) {
        KfeChannelDecisionResponse evaluated = evaluatePpm(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (evaluated.executed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }
        long current = request.currentPpm() != null ? request.currentPpm() : 0L;
        boolean drain = Boolean.TRUE.equals(request.acceleratedDrain());
        long targetPpm = decisionService.recommendedPpm(current, drain);
        LightningChannelGateway.UpdatePolicyResult result = channelGateway.updateChannelPolicy(
                new LightningChannelGateway.UpdatePolicyCommand(
                        request.channelPoint(),
                        request.baseFeeMsat() != null ? request.baseFeeMsat() : 1000L,
                        targetPpm,
                        defaultTimeLockDelta));
        if (!result.ok()) {
            return markReason(evaluated.id(), "POLICY_UPDATE_FAILED");
        }
        return markExecuted(evaluated.id(), request.channelPoint(), "PPM:" + targetPpm);
    }

    /**
     * Resolves request-level anchor choice, falling back to the configured policy default.
     *
     * @param requestValue explicit request choice, or null when unspecified
     * @return effective anchor requirement
     */
    private boolean resolveAnchorsEnabled(Boolean requestValue) {
        if (requestValue != null) {
            return requestValue;
        }
        // Default true when anchors are required by policy (LND modern defaults).
        return anchorsRequired;
    }

    /**
     * Finds a channel among the provider's current active-channel snapshot.
     *
     * @param channelPoint exact channel point to find
     * @return matching snapshot, or null for blank or unknown points
     */
    private LightningChannelGateway.ChannelSnapshot findChannel(String channelPoint) {
        if (channelPoint == null || channelPoint.isBlank()) {
            return null;
        }
        return channelGateway.listChannels().stream()
                .filter(channel -> channelPoint.equals(channel.channelPoint()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Persists policy result, request identity, and serialized evidence, then writes its audit event.
     *
     * @param type operation represented by this decision
     * @param decision evaluated policy outcome and flags
     * @param peer remote peer, if the operation has one
     * @param channelPoint channel target, if already known
     * @param amountSats capacity associated with the operation, if applicable
     * @param executed whether provider work has already completed
     * @param providerReference provider or queue reference, if available
     * @param idempotencyKey stable command identity
     * @return response mapped from the saved decision entity
     */
    private KfeChannelDecisionResponse persistDecision(
            KfeChannelOperationType type,
            ChannelDecisionResult decision,
            String peer,
            String channelPoint,
            Long amountSats,
            boolean executed,
            String providerReference,
            String idempotencyKey) {
        KfeChannelOperationDecisionEntity entity = new KfeChannelOperationDecisionEntity();
        entity.setOperation(type);
        entity.setPassed(decision.passed());
        entity.setPeerPubkey(peer);
        entity.setChannelPoint(channelPoint);
        entity.setAmountSats(amountSats);
        entity.setFlagsJson(toJson(decision.toAuditMap()));
        entity.setDecisionReason(decision.passed() ? "AND_PASS" : "AND_FAIL");
        entity.setExecuted(executed);
        entity.setProviderReference(providerReference);
        entity.setIdempotencyKey(idempotencyKey);
        entity = decisionRepository.save(entity);

        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                null,
                null,
                null,
                null,
                Map.of(
                        "channelDecisionId", entity.getId().toString(),
                        "channelOperation", type.name(),
                        "passed", decision.passed() ? 1 : 0,
                        "flags", decision.toAuditMap().get("flags")));

        return toResponse(entity);
    }

    /**
     * Marks a persisted decision executed and records its resulting channel point/reference.
     *
     * @param id decision identifier
     * @param channelPoint resulting channel point, or null to retain the previous value
     * @param providerReference provider transaction, queue, or operation reference
     * @return response mapped from the updated row
     * @throws IllegalArgumentException if the decision identifier does not exist
     */
    private KfeChannelDecisionResponse markExecuted(UUID id, String channelPoint, String providerReference) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        entity.setExecuted(true);
        entity.setChannelPoint(channelPoint != null ? channelPoint : entity.getChannelPoint());
        entity.setProviderReference(providerReference);
        entity.setDecisionReason("EXECUTED");
        return toResponse(decisionRepository.save(entity));
    }

    /**
     * Stores a non-executed status reason for a decision that could not proceed or complete.
     *
     * @param id decision identifier
     * @param reason stable reason code or diagnostic suffix
     * @return response mapped from the updated row
     * @throws IllegalArgumentException if the decision identifier does not exist
     */
    private KfeChannelDecisionResponse markReason(UUID id, String reason) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        entity.setDecisionReason(reason);
        entity.setExecuted(false);
        return toResponse(decisionRepository.save(entity));
    }

    /**
     * Creates a stable hash for the policy proposal identity used by open-decision evaluation.
     *
     * @param op operation name
     * @param peer remote peer, or null when absent
     * @param amount amount in satoshis
     * @return SHA-256 digest over the normalized proposal components
     */
    private String proposalHash(String op, String peer, long amount) {
        return hashService.sha256(String.join(
                "|",
                "KFE_CHANNEL",
                op,
                peer != null ? peer : "",
                String.valueOf(amount)));
    }

    /**
     * Builds a stable idempotency digest, normalizing peer case and trimming channel-point whitespace.
     *
     * @param operation operation discriminator
     * @param peer remote peer, or null
     * @param channelPoint channel identifier, or null
     * @param amount operation amount in satoshis or other operation-defined units
     * @param options additional operation-specific identity inputs
     * @return SHA-256 digest for repository idempotency lookup
     */
    private String operationKey(String operation, String peer, String channelPoint, long amount, String options) {
        return hashService.sha256(String.join(
                "|", "KFE_CHANNEL_COMMAND", operation,
                peer == null ? "" : peer.trim().toLowerCase(Locale.ROOT),
                channelPoint == null ? "" : channelPoint.trim(),
                String.valueOf(amount), options == null ? "" : options));
    }

    /**
     * Serializes decision evidence for storage; serialization failure produces an empty JSON object.
     *
     * @param payload audit evidence map
     * @return serialized JSON, or {@code {}} if the mapper cannot encode the value
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * Maps the provider channel snapshot to the API response shape without altering balances or state.
     *
     * @param channel provider snapshot
     * @return API-facing channel snapshot
     */
    private KfeChannelSnapshotResponse toSnapshot(LightningChannelGateway.ChannelSnapshot channel) {
        return new KfeChannelSnapshotResponse(
                channel.channelPoint(),
                channel.remotePubkey(),
                channel.active(),
                channel.capacitySats(),
                channel.localBalanceSats(),
                channel.remoteBalanceSats(),
                channel.pendingHtlcs(),
                channel.initiator(),
                channel.commitFeeSats(),
                channel.localRatio());
    }

    /**
     * Maps a durable decision row, including evidence and provider reference, to the API response.
     *
     * @param entity persisted operation decision
     * @return API-facing decision response
     */
    private KfeChannelDecisionResponse toResponse(KfeChannelOperationDecisionEntity entity) {
        return new KfeChannelDecisionResponse(
                entity.getId(),
                entity.getOperation(),
                entity.isPassed(),
                entity.isExecuted(),
                entity.getPeerPubkey(),
                entity.getChannelPoint(),
                entity.getAmountSats(),
                entity.getDecisionReason(),
                entity.getProviderReference(),
                entity.getFlagsJson(),
                entity.getCreatedAt());
    }
}
