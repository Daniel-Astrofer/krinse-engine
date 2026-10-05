package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.adapters.out.settlement.KfePlatformPeerInboundService;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeCustodialDepositObservationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExternalExecutionEvidence;
import com.kerosene.kfe.paymentexecution.domain.policy.ExecutionRecoveryPolicy;
import com.kerosene.kfe.paymentexecution.domain.policy.ExecutionIntentBindingPolicy;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.ExecutionEnvelopeReader;
import com.kerosene.kfe.paymentexecution.domain.policy.ExecutionFeePolicy;
import com.kerosene.kfe.paymentexecution.domain.policy.OutboundPreparationPolicy;
import com.kerosene.kfe.paymentexecution.domain.policy.OutboundConflictPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.audit.KfeAuditEventLogger;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates transaction-bound persistence for the payment execution outbox.
 *
 * <p>The helper validates worker claims and intent bindings, reconciles actual fees, records
 * balance movements and transaction projections, and routes uncertain external outcomes to
 * manual reconciliation. External notifications and potentially slow observations run after
 * commit so database locks are not held across network calls.</p>
 */
@Service
public class KfeExecutionTransactionHelper {

    /** Logger for execution, reconciliation and best-effort follow-up failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeExecutionTransactionHelper.class);
    /** Asset code used by BTC wallet balance operations. */
    private static final String ASSET_BTC = "BTC";

    /** Repository that owns durable execution work and worker claims. */
    private final KfeExecutionOutboxRepository outboxRepository;
    /** Repository for the authoritative payment transaction state. */
    private final KfeTransactionRepository transactionRepository;
    /** Repository used to identify custodial wallets for observed-balance refresh. */
    private final KfeWalletRepository walletRepository;
    /** Repository for the persisted client idempotency status. */
    private final KfeIdempotencyRepository idempotencyRepository;
    /** Repository used to query existing balance movements. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Service that reserves, releases and settles wallet balances. */
    private final KfeBalanceService balanceService;
    /** Service that persists transaction-state audit records. */
    private final KfeAuditLogService auditLogService;
    /** Best-effort user statement projection writer. */
    private final KfeStatementService statementService;
    /** Maps authoritative transaction fields into statement display data. */
    private final KfeResponseMapper responseMapper;
    /** Publishes dashboard refresh events after transaction updates. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Hashes identifiers and provider data before they enter audit payloads. */
    private final KfeHashService hashService;
    /** Finds an execution source wallet owned by the transaction user. */
    private final ExecutionSourceWalletPort executionSourceWallets;
    /** Parses and validates the encrypted execution envelope and its payload hash. */
    private final ExecutionEnvelopeReader envelopeReader = new ExecutionEnvelopeReader();
    /** Ensures the queued execution remains bound to the authorized payment intent. */
    private final ExecutionIntentBindingPolicy bindingPolicy = new ExecutionIntentBindingPolicy();
    /** Settles platform fees after the payment debit is finalized. */
    private final KfeFeeSettlementService feeSettlementService;
    /** Validates an optional legacy network fee quote during preparation. */
    private final KfeNetworkFeeEstimateService networkFeeEstimateService;
    /** Optional chain observer used to refresh custodial observed balances. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    /** Optional Lightning liquidity reservation manager. */
    private final ObjectProvider<KfeLightningLiquidityService> lightningLiquidityService;
    /** Optional observer kicked after a payment reaches a platform deposit address. */
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService;
    /** Optional router that resolves platform-controlled on-chain destinations. */
    private final ObjectProvider<com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter>
            platformOnchainDestinationRouter;
    /** Optional service that exposes the platform peer inbound after outbound broadcast. */
    private final ObjectProvider<KfePlatformPeerInboundService> platformPeerInboundService;
    @Autowired
    private KfeFinancialNotificationOutboxService notificationOutbox;

    private boolean enqueueFinancialEvent(String type, Long userId, UUID txId, UUID walletId,
                                          String rail, long amount, int confirmations,
                                          String reference, String failureCode) {
        // Standalone compatibility fixtures do not have Spring wiring. Real
        // services require the outbox bean and enqueue in the mutation transaction.
        if (notificationOutbox == null) return false;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Financial event must be enqueued in the mutation transaction.");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("walletId", walletId);
        payload.put("rail", rail);
        payload.put("amountSats", amount);
        payload.put("confirmations", Math.max(0, confirmations));
        if (reference != null && !reference.isBlank()) payload.put("txid", reference);
        if (failureCode != null && failureCode.matches("[A-Z0-9_]{1,64}")) payload.put("failureCode", failureCode);
        notificationOutbox.enqueue(UUID.randomUUID(), userId, txId, type, payload);
        return true;
    }

    /** Optional notification adapter for payment lifecycle events. */
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    /** Defines backoff and evidence requirements for execution recovery. */
    private final ExecutionRecoveryPolicy recoveryPolicy = new ExecutionRecoveryPolicy();
    /** Determines safe state transitions after conflicted or disappeared chain observations. */
    private final OutboundConflictPolicy conflictPolicy = new OutboundConflictPolicy();
    /** Derives the operation and fee details used during execution preparation. */
    private final OutboundPreparationPolicy preparationPolicy = new OutboundPreparationPolicy();
    /** Central fee validation and reconciliation rules shared by execution paths. */
    private static final ExecutionFeePolicy FEE_POLICY = new ExecutionFeePolicy();
    /** Records auditable wallet balance movements. */
    private final KfeBalanceMovementRecorder movementRecorder;
    /** Records execution and reconciliation metrics. */
    private final KfeFinancialMetrics financialMetrics;
    /** Emits structured audit events for settlement, state changes and chain incidents. */
    private final KfeAuditEventLogger auditEventLogger;
    /** Maximum number of retryable provider attempts before recovery policy changes the outcome. */
    private final int maxRetryAttempts;

    /**
     * Spring synchronization that dispatches work only after a successful transaction commit.
     */
    private static final class AfterCommitSynchronization implements TransactionSynchronization {
        /** Callback submitted to a virtual thread after commit. */
        private final Runnable action;

        /** Captures the already failure-isolated callback. */
        private AfterCommitSynchronization(Runnable action) {
            this.action = action;
        }

        /** Ignores rollback completion and starts the callback after a committed transaction. */
        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                Thread.startVirtualThread(action);
            }
        }
    }

    /**
     * Wires the transaction, outbox, balance, audit and projection collaborators.
     *
     * @param outboxRepository durable execution queue repository
     * @param transactionRepository authoritative transaction repository
     * @param walletRepository wallet repository used during observed-balance refresh
     * @param executionSourceWallets ownership-aware source-wallet lookup
     * @param idempotencyRepository idempotency projection repository
     * @param movementRepository wallet movement repository
     * @param balanceService balance reservation and settlement operations
     * @param auditLogService transaction audit persistence
     * @param statementService best-effort statement projection writer
     * @param responseMapper mapper used to build statement display payloads
     * @param dashboardPublisher dashboard update publisher
     * @param hashService hash utility for audit-safe references
     * @param objectMapper retained for constructor compatibility; envelope parsing is delegated to {@link ExecutionEnvelopeReader}
     * @param feeSettlementService platform fee credit operation
     * @param networkFeeEstimateService optional legacy quote validator
     * @param onchainBalanceSyncService optional custodial chain balance synchronizer
     * @param lightningLiquidityService optional Lightning liquidity reservation manager
     * @param custodialDepositObservationService optional deposit observer
     * @param platformOnchainDestinationRouter optional platform destination resolver
     * @param platformPeerInboundService optional peer inbound exposure service
     * @param notificationPort optional user payment notification port
     * @param bitcoinCoreRpcClient retained for constructor compatibility; execution recovery does not perform chain RPC while holding a database lock
     * @param movementRecorder records balanced movement entries
     * @param financialMetrics lifecycle and reconciliation metrics recorder
     * @param auditEventLogger structured financial audit event logger
     * @param maxRetryAttempts positive retry limit used by recovery policy
     * @throws IllegalArgumentException if {@code maxRetryAttempts} is not positive
     */
    public KfeExecutionTransactionHelper(
            KfeExecutionOutboxRepository outboxRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            ExecutionSourceWalletPort executionSourceWallets,
            KfeIdempotencyRepository idempotencyRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            ObjectMapper objectMapper,
            KfeFeeSettlementService feeSettlementService,
            KfeNetworkFeeEstimateService networkFeeEstimateService,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeLightningLiquidityService> lightningLiquidityService,
            ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService,
            ObjectProvider<com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter>
                    platformOnchainDestinationRouter,
            ObjectProvider<KfePlatformPeerInboundService> platformPeerInboundService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            ObjectProvider<com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            KfeBalanceMovementRecorder movementRecorder,
            KfeFinancialMetrics financialMetrics,
            KfeAuditEventLogger auditEventLogger,
            @Value("${kfe.execution.max-retry-attempts:8}") int maxRetryAttempts) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.movementRepository = movementRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.executionSourceWallets = executionSourceWallets;
        this.feeSettlementService = feeSettlementService;
        this.networkFeeEstimateService = networkFeeEstimateService;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.lightningLiquidityService = lightningLiquidityService;
        this.custodialDepositObservationService = custodialDepositObservationService;
        this.platformOnchainDestinationRouter = platformOnchainDestinationRouter;
        this.platformPeerInboundService = platformPeerInboundService;
        this.notificationPort = notificationPort;
        // BitcoinCoreRpcClient parameter retained for constructor compatibility; recovery never probes under a DB lock.
        this.movementRecorder = movementRecorder;
        this.financialMetrics = financialMetrics;
        this.auditEventLogger = auditEventLogger;
        if (maxRetryAttempts <= 0) {
            throw new IllegalArgumentException("maxRetryAttempts must be positive.");
        }
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /** Snapshot returned by preparation for execution outside the database transaction.
     *
     * @param proceed whether the worker may invoke the provider
     * @param operation normalized operation selected by the preparation policy
     * @param transactionId authoritative payment identifier
     * @param userId owner of the payment
     * @param sourceWalletLabel display label of the validated source wallet
     * @param sourceWalletId validated source wallet identifier
     * @param externalReference destination or provider-specific external reference
     * @param amountSats amount to execute, in satoshis
     * @param networkFeeSats network fee reserved for the payment, in satoshis
     * @param memo optional payment memo
     * @param idempotencyKey key used to deduplicate the originating request
     * @param quorumProposalHash authorization proposal hash, when quorum approval is required
     * @param feeRateSatsPerVbyte selected fee rate, when available
     * @param feeTargetBlocks requested confirmation target, when available
     * @param claimToken worker lease token that must accompany the provider result
     */
    public record PreparationResult(
            boolean proceed,
            String operation,
            UUID transactionId,
            Long userId,
            String sourceWalletLabel,
            UUID sourceWalletId,
            String externalReference,
            long amountSats,
            long networkFeeSats,
            String memo,
            String idempotencyKey,
            String quorumProposalHash,
            Long feeRateSatsPerVbyte,
            Integer feeTargetBlocks,
            UUID claimToken
    ) {
        /** Creates a non-executable result while retaining the worker token for correlation.
         * @param claimToken current outbox claim token
         * @return result with {@code proceed=false} and no payment details
         */
        private static PreparationResult skip(UUID claimToken) {
            return new PreparationResult(
                    false, null, null, null, null, null, null, 0, 0, null, null, null, null, null,
                    claimToken);
        }
    }

    /**
     * Locks the outbox and transaction, validates the claim and authorized envelope, and returns
     * the immutable data the worker needs to call the external payment provider.
     *
     * @param outboxId durable execution item to prepare
     * @param claimToken lease token proving current worker ownership
     * @return executable provider input, or a skip result when the item is missing, terminal,
     *         invalid, inbound-only or requires reconciliation
     * @throws KfeExecutionClaimLostException if the claim is absent, expired or owned by another worker
     */
    @Transactional
    public PreparationResult prepare(UUID outboxId, UUID claimToken) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId).orElse(null);
        if (outbox == null) {
            return PreparationResult.skip(claimToken);
        }
        requireClaimOwnership(outbox, claimToken);

        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(outbox.getTransactionId()).orElse(null);
        if (tx == null) {
            markOutboxFailed(outbox, "TRANSACTION_NOT_FOUND", "KFE transaction does not exist.", false);
            return PreparationResult.skip(claimToken);
        }
        var decision = preparationPolicy.decide(ExecutionStatus.valueOf(tx.getStatus().name()),
                outbox.getOperation(), tx.getBlockchainTxid() != null && !tx.getBlockchainTxid().isBlank());
        switch (decision.action()) {
            case TERMINAL_SKIP -> {
                markOutboxDispatched(outbox, firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid(), tx.getPaymentHash()));
                return PreparationResult.skip(claimToken);
            }
            case INVALID_STATUS -> {
                markOutboxFailed(outbox, "INVALID_TRANSACTION_STATUS",
                        "KFE transaction is not executable in status " + tx.getStatus() + ".", false);
                return PreparationResult.skip(claimToken);
            }
            case ALREADY_BROADCAST -> {
                // Retries after projection failures must never create a second on-chain spend.
                recordStatement(tx, tx.getSourceWalletId(), null);
                markOutboxDispatched(outbox,
                        firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid(), outbox.getProviderReference()));
                dashboardPublisher.publishAfterCommit(tx.getUserId());
                log.info("[KFE Outbox] skip re-broadcast txId={} already has blockchainTxid", tx.getId());
                return PreparationResult.skip(claimToken);
            }
            case RECONCILE_INBOUND -> {
                markRequiresReconciliation(outbox.getId(), tx.getId(), claimToken,
                        "INBOUND_REQUIRES_TRUSTED_MONITOR",
                        "Inbound settlement must be performed by a trusted KFE network monitor.");
                return PreparationResult.skip(claimToken);
            }
            case UNSUPPORTED -> {
                rejectExecutionEnvelope(outbox, tx);
                return PreparationResult.skip(claimToken);
            }
            case EXECUTE -> { /* Wallet, payload and quote access remain transactional below. */ }
        }
        String op = decision.operation();

        final ExecutionEnvelopeReader.Envelope envelope;
        try {
            envelope = envelopeReader.read(outbox.getPayloadJson(), outbox.getPayloadHash());
        } catch (ExecutionEnvelopeReader.InvalidExecutionEnvelope invalid) {
            rejectExecutionEnvelope(outbox, tx);
            return PreparationResult.skip(claimToken);
        }
        var authorized = new ExecutionIntentBinding(tx.getId(), tx.getUserId() == null ? 0L : tx.getUserId(),
                tx.getIdempotencyKey(), tx.getRail() == null ? null : PaymentRail.valueOf(tx.getRail().name()),
                tx.getDirection() == null ? null : PaymentDirection.valueOf(tx.getDirection().name()),
                tx.getSourceWalletId(), tx.getDestinationWalletId(), tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(), tx.getTotalDebitSats(), tx.getExternalReference(), tx.getMemo(),
                tx.getQuorumProposalHash());
        if (!bindingPolicy.matches(op, authorized, envelope.binding())) {
            rejectExecutionEnvelope(outbox, tx);
            return PreparationResult.skip(claimToken);
        }
        var sourceWallet = executionSourceWallets.findOwned(tx.getUserId(), tx.getSourceWalletId()).orElse(null);
        if (sourceWallet == null || !sourceWallet.usableFor(tx.getUserId(), tx.getSourceWalletId())) {
            markRequiresReconciliation(outbox, tx, "EXECUTION_SOURCE_WALLET_INVALID",
                    "Execution source wallet is unavailable or no longer eligible.");
            return PreparationResult.skip(claimToken);
        }

        Long feeRate = preparationPolicy.resolveFeeRate(
                envelope.feeRateSatsPerVbyte(), tx.getNetworkFeeSats(), envelope.estimatedVbytes());
        Integer feeTarget = envelope.feeTargetBlocks();
        // Optional legacy quote validation; no new durable signed-quote flow is introduced here.
        String quoteId = envelope.quoteId();
        if (quoteId != null && !quoteId.isBlank()) {
            try {
                networkFeeEstimateService.validateQuote(
                        quoteId,
                        tx.getReceiverAmountSats(),
                        tx.getExternalReference(),
                        tx.getRail().name());
            } catch (RuntimeException quoteError) {
                markFinalFailure(
                        outbox.getId(),
                        tx.getId(),
                        claimToken,
                        "INVALID_QUOTE",
                        "Fee quote validation failed.");
                return PreparationResult.skip(claimToken);
            }
        }

        return new PreparationResult(
                true,
                op,
                tx.getId(),
                tx.getUserId(),
                sourceWallet.label(),
                sourceWallet.id(),
                tx.getExternalReference(),
                tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(),
                tx.getMemo(),
                tx.getIdempotencyKey(),
                tx.getQuorumProposalHash(),
                feeRate,
                feeTarget,
                claimToken
        );
    }

    /**
     * Records a successful on-chain broadcast without unlocking the user reserve yet.
     * Funds stay LOCKED until {@link #settleOutboundWhenConfirmed} after N confirmations.
     */
    /**
     * Persists a successful on-chain broadcast while leaving the source reserve locked until
     * confirmation-based settlement. Follow-up peer exposure, deposit observation and notice
     * dispatch occur only after commit.
     *
     * @param outboxId execution item acknowledged by the worker
     * @param transactionId transaction identified by that item
     * @param claimToken current worker lease token
     * @param provider provider that broadcast the transaction
     * @param providerReference provider-side acknowledgement reference
     * @param blockchainTxid broadcast transaction ID; required and normalized before storage
     * @param feeSats actual network fee reported by the provider
     * @param sourceWalletId source wallet that funded the transaction
     * @param providerPayload optional provider response retained only as a hash in projections
     * @throws IllegalArgumentException if the transaction ID or source wallet does not match,
     *         or if the broadcast transaction ID is blank
     * @throws KfeExecutionClaimLostException if this worker no longer owns the outbox claim
     */
    @Transactional
    public void recordOutboundBroadcast(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String provider,
            String providerReference,
            String blockchainTxid,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        requireOutboxTransaction(outbox, transactionId);
        requireClaimOwnership(outbox, claimToken);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }
        requireSourceWallet(tx, sourceWalletId);
        if (blockchainTxid == null || blockchainTxid.isBlank()) {
            throw new IllegalArgumentException("blockchainTxid is required after broadcast.");
        }

        String normalizedTxid = blockchainTxid.trim();
        // Already recorded (retry after a partial success) — close outbox, do not re-touch fee.
        if (tx.getBlockchainTxid() != null
                && !tx.getBlockchainTxid().isBlank()
                && tx.getBlockchainTxid().equalsIgnoreCase(normalizedTxid)) {
            recordStatement(tx, sourceWalletId, providerPayload);
            markOutboxDispatched(outbox, firstNonBlank(providerReference, normalizedTxid));
            dashboardPublisher.publishAfterCommit(tx.getUserId());
            return;
        }

        tx.setProvider(trim(provider, 64));
        tx.setProviderReference(firstNonBlank(providerReference, normalizedTxid));
        tx.setBlockchainTxid(normalizedTxid);
        tx.setConfirmations(0);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }
        // Keep EXECUTING — reserve remains locked until chain confirmation monitor settles.
        // Persist txid BEFORE statement so a statement glitch cannot leave EXECUTING with no txid.
        transactionRepository.saveAndFlush(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, firstNonBlank(providerReference, normalizedTxid));
        audit(tx, "KFE_PSBT_WORKFLOW_BROADCAST", tx.getStatus(), tx.getStatus(),
                Map.of(
                        "txidHash", hashService.sha256(normalizedTxid),
                        "providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, normalizedTxid))));
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        // Peer expose + deposit observe MUST run after commit. Audit takes
        // pg_advisory_xact_lock(GLOBAL_AUDIT_APPENDER) for the whole outer TX; doing RPC/observe
        // here held that lock for minutes, exhausted Hikari, and made /health/ready fail so the
        // API (including payment-requests) stopped answering.
        UUID outboundId = tx.getId();
        String destinationAddress = tx.getExternalReference();
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final UUID notifyWalletId = sourceWalletId;
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final String notifyTxid = normalizedTxid;
        // Never block the submit HTTP thread: peer expose + deposit observe can take seconds
        // (and used to hold the client on "Autorizando…" for 10–60s after broadcast).
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_BROADCAST", notifyUserId, notifyTxId, notifyWalletId, notifyRail, notifyAmount, 0, notifyTxid, null);
        runAfterCommitAsync(() -> {
            transactionRepository.findById(outboundId).ifPresent(this::exposePlatformPeerInbound);
            kickPlatformDepositObservation(destinationAddress);
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentBroadcast(notifyUserId, notifyTxId, notifyWalletId,
                                notifyRail, notifyAmount, notifyTxid);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] broadcast notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /** Compatibility wrapper that delegates callback handling to the asynchronous variant.
     * @param action work to execute after successful completion
     */
    private void runAfterCommit(Runnable action) {
        runAfterCommitAsync(action);
    }

    /** Runs best-effort work on a virtual thread only after the surrounding transaction commits.
     * @param action callback to dispatch; {@code null} is ignored
     */
    private void runAfterCommitAsync(Runnable action) {
        if (action == null) {
            return;
        }
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException exception) {
                log.warn("[KFE Execution] after-completion hook failed: {}", exception.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            Thread.startVirtualThread(safe);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new AfterCommitSynchronization(safe));
    }

    /** Exposes a matching platform peer inbound after an outbound broadcast, if configured.
     * @param outbound committed outbound transaction
     */
    private void exposePlatformPeerInbound(KfeTransactionEntity outbound) {
        KfePlatformPeerInboundService peer = platformPeerInboundService.getIfAvailable();
        if (peer == null) {
            return;
        }
        try {
            peer.exposeAfterOutboundBroadcast(outbound);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Execution] platform peer inbound expose failed txId={}: {}",
                    outbound.getId(),
                    exception.getMessage());
        }
    }

    /** Triggers deposit observation when the broadcast destination resolves to a platform sink.
     * @param destinationAddress external destination address from the transaction
     */
    private void kickPlatformDepositObservation(String destinationAddress) {
        if (destinationAddress == null || destinationAddress.isBlank()) {
            return;
        }
        var router = platformOnchainDestinationRouter.getIfAvailable();
        var observer = custodialDepositObservationService.getIfAvailable();
        if (router == null || observer == null) {
            return;
        }
        try {
            Optional<UUID> sink = router.findPlatformSinkWalletIdForAddress(destinationAddress.trim());
            if (sink.isEmpty()) {
                sink = router.resolveRecipientOnchainSinkWalletId(destinationAddress.trim());
            }
            sink.ifPresent(walletId -> {
                try {
                    observer.observeWallet(walletId);
                } catch (RuntimeException exception) {
                    log.debug(
                            "[KFE Execution] post-broadcast deposit observe failed walletId={}: {}",
                            walletId,
                            exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Execution] platform deposit kick failed: {}",
                    exception.getMessage());
        }
    }

    /**
     * Persist chain confirmation progress for UI rings (0/6…6/6).
     *
     * <p>ITEM 10: Allow confirmation decreases while not FINALIZED. Only after SETTLED does a
     * decrease trigger reconciliation. Previously only accepted increases, which hid reorgs.
     *
     * <p>{@link Propagation#REQUIRES_NEW}: committed independently of settle/audit so a hung
     * settle cannot leave the app frozen at 0 confirmations while Core already has 1+.
     */
    /**
     * Persists the latest chain confirmation count independently of a potentially failing settle.
     * A decrease before settlement is visible as a reorg; after settlement it moves the payment
     * to reconciliation. The supplied block metadata is used for audit context only.
     *
     * @param transactionId payment whose chain observation changed
     * @param confirmations latest observed confirmation count
     * @param blockHash block hash associated with the observation, if available
     * @param blockHeight block height associated with the observation, if available
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchOutboundConfirmations(UUID transactionId, int confirmations,
                                           String blockHash, Integer blockHeight) {
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null) {
            return;
        }
        // After FINALIZED (SETTLED), any confirmation decrease is a reorg incident
        if (tx.getStatus() == KfeTransactionStatus.SETTLED && confirmations < tx.getConfirmations()) {
            log.error(
                    "[KFE Execution] CONFIRMATIONS_DECREASED_AFTER_SETTLE txId={} was={} now={}",
                    transactionId, tx.getConfirmations(), confirmations);
            tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
            tx.setFailureCode("CONFIRMATIONS_DECREASED");
            tx.setFailureMessage("Confirmation count decreased after settlement: "
                    + tx.getConfirmations() + " -> " + confirmations);
            audit(tx, "KFE_TRANSACTION_CONFIRMATIONS_DECREASED",
                    KfeTransactionStatus.SETTLED, KfeTransactionStatus.REQUIRES_RECONCILIATION,
                    Map.of("was", String.valueOf(tx.getConfirmations()),
                            "now", String.valueOf(confirmations),
                            "blockHash", blockHash != null ? blockHash : ""));
            // Metrics + audit: reorg detected after settlement
            String rail = tx.getRail() != null ? tx.getRail().name() : "UNKNOWN";
            financialMetrics.recordReorg(rail);
            auditEventLogger.logReorg(tx.getId(), tx.getSourceWalletId(),
                    tx.getConfirmations(), confirmations, rail);
        }
        if (confirmations == tx.getConfirmations()) {
            return;
        }
        tx.setConfirmations(confirmations);
        transactionRepository.saveAndFlush(tx);
        // Refresh 24h statement so the app shows confirmation progress (not infinite PENDING).
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Backward-compatible confirmation update without block metadata.
     * @param transactionId payment whose chain observation changed
     * @param confirmations latest observed confirmation count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchOutboundConfirmations(UUID transactionId, int confirmations) {
        touchOutboundConfirmations(transactionId, confirmations, null, null);
    }

    /**
     * Unlocks/settles reserved debit after the outbound tx is monitored with enough confirmations.
     *
     * <p>ITEM 14: Before settling, verify:
     * <ol>
     *   <li>txid matches prepared transaction</li>
     *   <li>Transaction not conflicted (confirms >= 0)</li>
     *   <li>Confirmation count from current source (passed by caller)</li>
     *   <li>Wallet/source matches original registration</li>
     * </ol>
     * Don't settle just because txid has N confirmations.
     *
     * <p>Callers must already have persisted conf progress via {@link #touchOutboundConfirmations}
     * so the UI keeps advancing if this method fails mid-way (audit lock, fee race, etc.).
     */
    /**
     * Settles an on-chain outbound payment after its monitor has verified sufficient confirmations.
     * It persists confirmation progress first, validates the state and source, then consumes the
     * reserved debit and records audit, statement and idempotency projections. Negative
     * confirmations are treated as unresolved evidence and never authorize a refund.
     *
     * @param transactionId payment to settle
     * @param confirmations current confirmation count from the trusted monitor
     * @return {@code true} when already settled or newly settled; {@code false} when the payment
     *         is missing, lacks required broadcast data, has an unsupported state or remains unresolved
     */
    @Transactional
    public boolean settleOutboundWhenConfirmed(UUID transactionId, int confirmations) {
        // Match worker preparation and cancellation: every outbox lock precedes the
        // transaction lock. Reversing these locks can deadlock a concurrent cancellation.
        List<KfeExecutionOutboxEntity> outboxes =
                outboxRepository.findByTransactionIdInForUpdate(List.of(transactionId));
        KfeExecutionOutboxEntity outbox = outboxes.stream().findFirst().orElse(null);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null) {
            return false;
        }
        if (confirmations < 0) {
            applyUnresolvedObservation(tx, tx.getBlockchainTxid(), confirmations, false);
            return false;
        }
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            // Still advance confs on already-settled rows (rings past minConfirmations).
            if (confirmations > tx.getConfirmations()) {
                tx.setConfirmations(confirmations);
                transactionRepository.saveAndFlush(tx);
                recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
                dashboardPublisher.publishAfterCommit(tx.getUserId());
            }
            return true;
        }
        if (tx.getStatus() != KfeTransactionStatus.EXECUTING
                && tx.getStatus() != KfeTransactionStatus.VALIDATING
                && tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION) {
            return false;
        }
        if (tx.getBlockchainTxid() == null || tx.getBlockchainTxid().isBlank()) {
            return false;
        }
        if (tx.getSourceWalletId() == null) {
            return false;
        }
        // Flush confs before heavy settle work so a later failure still leaves progress visible.
        tx.setConfirmations(Math.max(tx.getConfirmations(), confirmations));
        transactionRepository.saveAndFlush(tx);

        String providerReference = firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid());
        String provider = firstNonBlank(tx.getProvider(), "BITCOIN_CORE_QUORUM");

        // ITEM 14+11: Movement-first — record SETTLE_DEBIT movement before updating balance
        if (!movementExists(tx.getId(), tx.getSourceWalletId(), "SETTLE_DEBIT")) {
            movementRecorder.record(tx.getId(), tx.getSourceWalletId(), "SETTLE_DEBIT",
                    tx.getTotalDebitSats(), "LOCKED", null);
        }
        balanceService.settleReservedDebit(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of(
                        "providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, "")),
                        "confirmations", String.valueOf(confirmations),
                        "provider", provider));
        // Audit: settlement with amounts and fees
        String settleRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        auditEventLogger.logSettlement("KFE_TRANSACTION_SETTLED",
                tx.getId(), tx.getSourceWalletId(),
                tx.getGrossAmountSats(), tx.getNetworkFeeSats(),
                null, settleRail,
                hashService.sha256(firstNonBlank(providerReference, "")));
        try {
            feeSettlementService.creditKeroseneFee(tx);
        } catch (RuntimeException feeFailure) {
            log.warn(
                    "[KFE Execution] kerosene fee settle deferred txId={}: {}",
                    tx.getId(),
                    feeFailure.getMessage());
        }
        recordStatement(tx, tx.getSourceWalletId(), null);
        updateIdempotency(tx);
        if (outbox != null) {
            outbox.setProviderReference(providerReference);
            markOutboxDispatched(outbox, providerReference);
        }
        UUID sourceWalletId = tx.getSourceWalletId();
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final int notifyConfs = confirmations;
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_CONFIRMED", notifyUserId, notifyTxId, sourceWalletId, notifyRail, notifyAmount, notifyConfs, null, null);
        runAfterCommit(() -> Thread.startVirtualThread(
                () -> {
                    resyncCustodialObserved(sourceWalletId);
                    if (!notificationQueued) {
                        FinancialNotificationPort port = notificationPort.getIfAvailable();
                        if (port != null) {
                            try {
                                port.notifyPaymentConfirmed(notifyUserId, notifyTxId, sourceWalletId,
                                        notifyRail, notifyAmount, notifyConfs);
                            } catch (RuntimeException exception) {
                                log.warn("[KFE Execution] confirmed notification failed txId={}: {}",
                                        notifyTxId, exception.getMessage());
                            }
                        }
                    }
                }));
        return true;
    }

    /** Checks whether a transaction movement type was already recorded.
     * @param transactionId owning transaction
     * @param walletId wallet associated with the movement
     * @param movementType ledger movement classification
     * @return whether a matching transaction/type movement exists
     */
    private boolean movementExists(UUID transactionId, UUID walletId, String movementType) {
        return movementRepository.existsByTransactionIdAndMovementType(transactionId, movementType);
    }

    /**
     * Performs immediate outbound settlement for rails whose provider result is final (for example,
     * Lightning). On-chain production uses broadcast recording and confirmation-gated settlement.
     *
     * @param outboxId execution item acknowledged by the worker
     * @param transactionId payment identified by the item
     * @param claimToken current worker lease token
     * @param provider execution provider name
     * @param providerReference provider-side result reference
     * @param blockchainTxid chain transaction ID, if produced by this rail
     * @param feeSats actual execution fee in satoshis
     * @param sourceWalletId source wallet whose reserve is settled
     * @param providerPayload optional provider response stored only as a hash
     * @throws IllegalArgumentException if the claim references another transaction or source wallet
     * @throws KfeExecutionClaimLostException if the claim is no longer owned by this worker
     */
    @Transactional
    public void settleOutbound(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String provider,
            String providerReference,
            String blockchainTxid,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        // Immediate settle path (e.g. lightning or tests). On-chain production uses
        // recordOutboundBroadcast + settleOutboundWhenConfirmed.
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        requireOutboxTransaction(outbox, transactionId);
        requireClaimOwnership(outbox, claimToken);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }
        requireSourceWallet(tx, sourceWalletId);

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        feeSettlementService.creditKeroseneFee(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        resyncCustodialObserved(sourceWalletId);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final int notifyConfs = tx.getConfirmations();
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_CONFIRMED", notifyUserId, notifyTxId, sourceWalletId, notifyRail, notifyAmount, notifyConfs, null, null);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentConfirmed(notifyUserId, notifyTxId, sourceWalletId,
                                notifyRail, notifyAmount, notifyConfs);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] confirmed notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Settles a Lightning payment, including its payment hash and reserved channel liquidity.
     *
     * @param outboxId execution item acknowledged by the worker
     * @param transactionId payment identified by the item
     * @param claimToken current worker lease token
     * @param provider Lightning provider name
     * @param providerReference provider-side payment reference
     * @param blockchainTxid optional on-chain identifier supplied by the provider
     * @param paymentHash Lightning payment hash returned for this payment
     * @param feeSats actual Lightning routing fee in satoshis
     * @param sourceWalletId source wallet whose reserve is settled
     * @param providerPayload optional provider response stored only as a hash
     * @throws IllegalArgumentException if the claim references another transaction or source wallet
     * @throws KfeExecutionClaimLostException if the claim is no longer owned by this worker
     */
    @Transactional
    public void settleOutboundLightning(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String provider,
            String providerReference,
            String blockchainTxid,
            String paymentHash,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        requireOutboxTransaction(outbox, transactionId);
        requireClaimOwnership(outbox, claimToken);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, firstNonBlank(providerReference, paymentHash, blockchainTxid))) {
            return;
        }
        requireSourceWallet(tx, sourceWalletId);

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        tx.setPaymentHash(paymentHash);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        consumeLightningLiquidity(tx.getId());
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        feeSettlementService.creditKeroseneFee(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "LIGHTNING";
        final long notifyAmount = tx.getGrossAmountSats();
        final int notifyConfs = tx.getConfirmations();
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_CONFIRMED", notifyUserId, notifyTxId, sourceWalletId, notifyRail, notifyAmount, notifyConfs, null, null);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentConfirmed(notifyUserId, notifyTxId, sourceWalletId,
                                notifyRail, notifyAmount, notifyConfs);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] lightning confirmed notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /** Consumes a pending Lightning liquidity reservation after successful settlement.
     * @param transactionId settled transaction whose reservation is consumed
     */
    private void consumeLightningLiquidity(UUID transactionId) {
        KfeLightningLiquidityService liquidity = lightningLiquidityService.getIfAvailable();
        if (liquidity != null) {
            liquidity.consumeForTransaction(transactionId);
        }
    }

    /** Releases a Lightning liquidity reservation after a final safe failure.
     * @param transactionId failed transaction whose reservation is released
     */
    private void releaseLightningLiquidity(UUID transactionId) {
        KfeLightningLiquidityService liquidity = lightningLiquidityService.getIfAvailable();
        if (liquidity != null) {
            liquidity.releaseForTransaction(transactionId);
        }
    }

    /**
     * Reconciles the provider's actual fee against the authorized amount and releases any unused
     * fee reserve. Unsafe or excessive results move the transaction to reconciliation.
     *
     * @param outbox execution item to update if reconciliation is required
     * @param tx payment whose reserved amounts are reconciled
     * @param sourceWalletId wallet holding the reserve
     * @param actualFeeSats actual fee reported after execution
     * @return {@code true} if fee accounting is accepted; {@code false} if reconciliation was recorded
     */
    private boolean reconcileOutboundFee(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            UUID sourceWalletId,
            long actualFeeSats) {
        var decision = FEE_POLICY.reconcile(tx.getNetworkFeeSats(), tx.getReceiverAmountSats(),
                tx.getTotalDebitSats(), actualFeeSats);
        if (!decision.accepted()) {
            markRequiresReconciliation(outbox, tx, decision.failureCode(), decision.message());
            return false;
        }
        long releaseSats = decision.releaseSats();
        tx.setNetworkFeeSats(decision.actualFeeSats());
        tx.setTotalDebitSats(decision.totalDebitSats());
        if (releaseSats > 0L) {
            balanceService.releaseReserved(sourceWalletId, ASSET_BTC, releaseSats);
            movement(tx.getId(), sourceWalletId, "RELEASE_FEE_RESERVE", releaseSats, "LOCKED", "AVAILABLE");
        }
        return true;
    }

    /**
     * Compatibility validation utility; this method itself does not gate a broadcast.
     * The caller must supply the estimated absolute fee and enforce the result.
     *
     * @param estimatedFeeSats  the fee from the funded PSBT
     * @param reservedFeeSats   the fee the user authorized
     * @param amountSats        the amount being sent (for ratio check)
     * @param maxFeeRate        retained for compatibility; unused without a virtual-size input
     * @param maxAbsoluteFee    maximum absolute fee in sats (0 = skip check)
     * @param maxFeeRatioPct    maximum fee as % of amount (0 = skip check)
     */
    public static FeeValidationResult validateFeeBeforeBroadcast(
            long estimatedFeeSats,
            long reservedFeeSats,
            long amountSats,
            long maxFeeRate,
            long maxAbsoluteFee,
            long maxFeeRatioPct) {
        var validation = FEE_POLICY.validateBeforeBroadcast(
                estimatedFeeSats, reservedFeeSats, amountSats, maxAbsoluteFee, maxFeeRatioPct);
        return validation.valid() ? FeeValidationResult.VALID : FeeValidationResult.invalid(validation.reason());
    }

    /** Result of the compatibility fee validation utility.
     * @param valid whether the estimated fee satisfies the configured checks
     * @param reason explanation for rejection; {@code null} for a valid result
     */
    public record FeeValidationResult(boolean valid, @Nullable String reason) {
        /** Shared valid result with no rejection reason. */
        public static final FeeValidationResult VALID = new FeeValidationResult(true, null);

        /** Creates an invalid fee validation result.
         * @param reason human-readable rejection reason
         * @return invalid result containing the supplied reason
         */
        public static FeeValidationResult invalid(String reason) {
            return new FeeValidationResult(false, reason);
        }
    }

    /**
     * Records an inconclusive provider result, schedules recovery with policy backoff, and retains
     * the reserve because the external payment may already have executed.
     * @param outboxId execution item with the active claim
     * @param transactionId payment associated with the item
     * @param claimToken current worker lease token
     * @param providerReference provider reference, if one was returned
     * @param providerPayload provider result retained only as a hash in the statement projection
     * @param message diagnostic explanation of the uncertain result
     */
    @Transactional
    /** Records an inconclusive provider outcome and schedules recovery while retaining the reserve.
     * @param outboxId claimed execution item
     * @param transactionId payment associated with the item
     * @param claimToken current worker lease token
     * @param providerReference provider-side reference, if available
     * @param providerPayload provider response stored only as a hash in the statement
     * @param message diagnostic explanation of the uncertain outcome
     */
    public void markUnknown(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String providerReference,
            String providerPayload,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        requireOutboxTransaction(outbox, transactionId);
        requireClaimOwnership(outbox, claimToken);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }

        tx.setProviderReference(firstNonBlank(providerReference, tx.getProviderReference()));
        tx.setFailureCode("PROVIDER_RESULT_UNKNOWN");
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        recordStatement(tx, tx.getSourceWalletId(), providerPayload);
        updateIdempotency(tx);

        outbox.setAttempts(recoveryPolicy.nextAttempt(outbox.getAttempts()));
        outbox.setStatus("UNKNOWN");
        outbox.setProviderReference(providerReference);
        outbox.setLastError(trim(message, 1000));
        outbox.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plus(recoveryPolicy.unknownDelay(outbox.getAttempts())));
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final UUID notifyWalletId = firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId());
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final String notifyReason = trim(message, 255);
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_RECONCILIATION_REQUIRED", notifyUserId, notifyTxId, notifyWalletId, notifyRail, notifyAmount, 0, null, null);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentReconciliationRequired(notifyUserId, notifyTxId, notifyWalletId,
                                notifyRail, notifyAmount, notifyReason);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] reconciliation notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Schedules recovery for a retryable provider error, or finalizes it after the attempt limit.
     * Reserve release still depends on evidence that external execution is absent.
     * @param outboxId execution item with the active claim
     * @param transactionId payment associated with the item
     * @param claimToken current worker lease token
     * @param code stable failure code
     * @param message diagnostic failure detail
     */
    @Transactional
    /** Schedules retry after a provider failure, or finalizes it after the configured attempt limit.
     * @param outboxId claimed execution item
     * @param transactionId payment associated with the item
     * @param claimToken current worker lease token
     * @param code stable failure code
     * @param message diagnostic failure detail
     */
    public void markRetryableFailure(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        requireOutboxTransaction(outbox, transactionId);
        requireClaimOwnership(outbox, claimToken);
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        if (recoveryPolicy.retryExhausted(outbox.getAttempts(), maxRetryAttempts)) {
            finalizeFailure(
                    outbox,
                    tx,
                    "PROVIDER_RETRY_EXHAUSTED",
                    "Provider execution failed after " + maxRetryAttempts + " attempts: " + message);
            return;
        }

        outbox.setAttempts(recoveryPolicy.nextAttempt(outbox.getAttempts()));
        outbox.setStatus("FAILED_RETRYABLE");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plus(recoveryPolicy.retryDelay(outbox.getAttempts())));
        clearClaim(outbox);
        outboxRepository.save(outbox);
        audit(tx, "KFE_EXECUTION_RETRYABLE_FAILURE", tx.getStatus(), tx.getStatus(),
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
    }

    /** Marks an execution permanently failed when no worker token is supplied.
     * @param outboxId associated execution item, or {@code null} to resolve it by transaction
     * @param transactionId payment to fail
     * @param code stable failure code
     * @param message diagnostic failure detail
     */
    @Transactional
    /** Marks a payment failed when no worker claim token is supplied.
     * @param outboxId execution item, or {@code null} to resolve by transaction
     * @param transactionId payment to fail
     * @param code stable failure code
     * @param message diagnostic detail
     */
    public void markFinalFailure(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        markFinalFailure(outboxId, transactionId, null, code, message);
    }

    /**
     * Marks an execution permanently failed after validating the worker claim when supplied. Funds
     * are released only if stored evidence proves the external payment was not prepared or submitted.
     * @param outboxId associated execution item, or {@code null} to resolve it by transaction
     * @param transactionId payment to fail
     * @param claimToken current worker token when an outbox ID is supplied
     * @param code stable failure code
     * @param message diagnostic failure detail
     */
    @Transactional
    /** Marks a payment failed after validating the worker claim when an outbox is specified.
     * Reserve release still requires evidence that external execution did not occur.
     * @param outboxId execution item, or {@code null} to resolve by transaction
     * @param transactionId payment to fail
     * @param claimToken current worker token when an outbox ID is supplied
     * @param code stable failure code
     * @param message diagnostic detail
     */
    public void markFinalFailure(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = resolveOutbox(outboxId, transactionId);
        if (outboxId != null) {
            requireClaimOwnership(outbox, claimToken);
        }
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        finalizeFailure(outbox, tx, code, message);
    }

    /** Finalizes a failure only when the available evidence makes reserve release safe.
     * @param outbox execution item associated with the payment, if available
     * @param tx payment whose final failure is being recorded
     * @param code stable failure code
     * @param message diagnostic failure detail
     */
    private void finalizeFailure(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code,
            String message) {
        // A persisted operation can have reached the provider in an earlier attempt. Exhaustion,
        // invalid quotes or local parsing errors do not prove that returning the reserve is safe.
        if (outbox != null && recoveryPolicy.requiresReconciliation(new ExternalExecutionEvidence(
                outbox.getPreparedPayloadCiphertext() != null, outbox.getPreparedPayloadHash() != null,
                outbox.getExecutionReference() != null, firstNonBlank(outbox.getProviderReference()) != null,
                firstNonBlank(tx.getProviderReference()) != null, firstNonBlank(tx.getBlockchainTxid()) != null,
                firstNonBlank(tx.getPaymentHash()) != null))) {
            markRequiresReconciliation(outbox, tx, "EXTERNAL_EXECUTION_NOT_PROVEN_ABSENT",
                    "Prepared or observed external execution requires reconciliation before releasing reserves.");
            outbox.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plus(recoveryPolicy.reconciliationDelay()));
            outboxRepository.save(outbox);
            return;
        }
        if (tx.getSourceWalletId() != null && tx.getTotalDebitSats() > 0L) {
            balanceService.releaseReserved(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
            movement(tx.getId(), tx.getSourceWalletId(), "RELEASE_RESERVE", tx.getTotalDebitSats(), "LOCKED", "AVAILABLE");
        }
        releaseLightningLiquidity(tx.getId());
        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.FAILED, "KFE_TRANSACTION_FAILED",
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        updateIdempotency(tx);
        markOutboxFailed(outbox, code, message, false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        // Best-effort notification after commit — funds returned to available.
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final UUID notifyWalletId = firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId());
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final String notifyCode = trim(code, 64);
        final String notifyMessage = trim(message, 255);
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_FAILED", notifyUserId, notifyTxId, notifyWalletId, notifyRail, notifyAmount, 0, null, notifyCode);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentFailed(notifyUserId, notifyTxId, notifyWalletId,
                                notifyRail, notifyAmount, notifyCode, notifyMessage);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] failure notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Records a negative-confirmation observation without treating it as proof that a reserve can
     * be returned. The compatibility overload does not identify a specific observed transaction.
     * @param transactionId outbound payment with an unresolved chain observation
     * @param confirmations negative observation count; non-negative values are ignored
     */
    @Transactional
    /** Records a negative-confirmation observation without assuming the payment is refundable.
     * @param transactionId outbound payment being observed
     * @param confirmations negative observation count; non-negative values are ignored
     */
    public void markOutboundConflicted(UUID transactionId, int confirmations) {
        markOutboundConflicted(transactionId, null, confirmations);
    }

    /** Records that an on-chain transaction disappeared without proof that it cannot confirm.
     * @param transactionId payment associated with the observed transaction
     * @param observedTxid transaction ID reported as missing; required and non-blank
     * @throws IllegalArgumentException if {@code observedTxid} is blank
     */
    @Transactional
    /** Records a conflict observation tied to the transaction ID reported by the monitor.
     * @param transactionId outbound payment being observed
     * @param observedTxid observed chain transaction ID
     * @param confirmations negative count indicating an unresolved conflict
     */
    public void markOutboundConflicted(UUID transactionId, String observedTxid, int confirmations) {
        if (confirmations >= 0) {
            return;
        }
        // Same order as worker ACK/cancellation. Observation does not acquire or clear a worker claim.
        outboxRepository.findByTransactionIdInForUpdate(List.of(transactionId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        applyUnresolvedObservation(tx, observedTxid, confirmations, false);
    }

    /** Records an on-chain disappearance without treating it as proof the payment cannot confirm.
     * @param transactionId payment associated with the observed transaction
     * @param observedTxid transaction ID reported as missing; must be non-blank
     * @throws IllegalArgumentException if {@code observedTxid} is blank
     */
    @Transactional
    /** Records disappearance without treating it as proof the payment cannot confirm.
     * @param transactionId payment associated with the observed transaction
     * @param observedTxid transaction ID reported as missing; must be non-blank
     * @throws IllegalArgumentException if {@code observedTxid} is blank
     */
    public void markOutboundDisappeared(UUID transactionId, String observedTxid) {
        if (observedTxid == null || observedTxid.isBlank()) {
            throw new IllegalArgumentException("Observed transaction reference is required.");
        }
        outboxRepository.findByTransactionIdInForUpdate(List.of(transactionId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        applyUnresolvedObservation(tx, observedTxid, null, true);
    }

    /** Applies a conflict or disappearance observation while preserving funds for reconciliation.
     * @param tx observed transaction, or {@code null} if it no longer exists
     * @param observedTxid transaction identifier from the monitor, if available
     * @param confirmations observed confirmation count, if supplied
     * @param disappeared whether the monitor reports disappearance rather than conflict
     */
    private void applyUnresolvedObservation(KfeTransactionEntity tx, String observedTxid,
            Integer confirmations, boolean disappeared) {
        if (tx == null || tx.getRail() != com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail.ONCHAIN
                || tx.getDirection() != com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.OUTBOUND) {
            return;
        }
        if (observedTxid != null && (observedTxid.isBlank() || tx.getBlockchainTxid() == null
                || !observedTxid.trim().equalsIgnoreCase(tx.getBlockchainTxid().trim()))) {
            return; // Ignore stale observations; a different transaction requires its own observation.
        }
        var target = conflictPolicy.target(ExecutionStatus.valueOf(tx.getStatus().name()));
        if (target.isEmpty()) {
            return;
        }
        KfeTransactionStatus next = KfeTransactionStatus.valueOf(target.orElseThrow().name());
        String reason = disappeared ? "TX_DISAPPEARED_INCONCLUSIVE" : "CONFLICT_OBSERVATION_UNRESOLVED";
        boolean duplicate = tx.getStatus() == next && reason.equals(tx.getFailureCode());
        boolean confirmationChanged = confirmations != null && confirmations != tx.getConfirmations();
        if (confirmations != null) {
            tx.setConfirmations(confirmations);
        }
        if (!duplicate) {
            if (tx.getConflictedAt() == null && !disappeared) {
                tx.setConflictedAt(LocalDateTime.now(ZoneOffset.UTC));
            }
            tx.setFailureCode(reason);
            tx.setFailureMessage("External observation requires reconciliation; no automatic refund is authorized.");
            transition(tx, next, "KFE_TRANSACTION_OBSERVATION_REQUIRES_RECONCILIATION", Map.of("reason", reason));
            financialMetrics.recordReconciliationRequired(tx.getRail().name());
            if (!disappeared) {
                financialMetrics.recordConflicted(tx.getRail().name());
            }
            auditEventLogger.logReconciliation("KFE_TRANSACTION_OBSERVATION_REQUIRES_RECONCILIATION",
                    tx.getId(), tx.getSourceWalletId(), reason, tx.getRail().name());
            notifyConflictedResolved(tx);
        } else if (confirmationChanged) {
            transactionRepository.save(tx);
        } else {
            return;
        }
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        updateIdempotency(tx);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Schedules a best-effort notification that an outbound conflict needs human resolution.
     * @param tx committed transaction requiring reconciliation
     */
    private void notifyConflictedResolved(KfeTransactionEntity tx) {
        final Long userId = tx.getUserId();
        final UUID txId = tx.getId();
        final UUID walletId = firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId());
        final String rail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long amount = tx.getGrossAmountSats();
        final String txid = tx.getBlockchainTxid();
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_CONFLICTED", userId, txId, walletId, rail, amount, 0, txid != null ? txid : "", null);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyOutboundConflicted(userId, txId, walletId, rail, amount,
                                txid != null ? txid : "");
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] conflicted notification failed txId={}: {}",
                                txId, exception.getMessage());
                    }
                }
            }
        });
    }

    /** Moves a payment to reconciliation without validating a worker claim.
     * @param outboxId associated execution item, or {@code null} to resolve it
     * @param transactionId payment requiring reconciliation
     * @param code stable reconciliation reason code
     * @param message explanation for operators and the user notification
     */
    @Transactional
    public void markRequiresReconciliation(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        markRequiresReconciliation(outboxId, transactionId, null, code, message);
    }

    /** Moves a payment to reconciliation after validating the active worker claim when supplied.
     * @param outboxId associated execution item, or {@code null} to resolve it
     * @param transactionId payment requiring reconciliation
     * @param claimToken current claim token when an outbox ID is supplied
     * @param code stable reconciliation reason code
     * @param message explanation for operators and the user notification
     */
    @Transactional
    public void markRequiresReconciliation(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = resolveOutbox(outboxId, transactionId);
        if (outboxId != null) {
            requireClaimOwnership(outbox, claimToken);
        }
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));

        markRequiresReconciliation(outbox, tx, code, message);
    }

    /** Persists transaction, outbox, audit and projection state for manual reconciliation.
     * @param outbox execution item to park as unknown
     * @param tx payment whose outcome cannot safely be finalized
     * @param code stable reconciliation reason code
     * @param message explanation of the unresolved state
     */
    private void markRequiresReconciliation(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code,
            String message) {
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("reason", code));

        // Metrics + audit: reconciliation required
        String rail = tx.getRail() != null ? tx.getRail().name() : "UNKNOWN";
        financialMetrics.recordReconciliationRequired(rail);
        auditEventLogger.logReconciliation("KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                tx.getId(), tx.getSourceWalletId(), code, rail);

        recordStatement(tx, firstNonNull(tx.getDestinationWalletId(), tx.getSourceWalletId()), null);
        updateIdempotency(tx);

        outbox.setAttempts(recoveryPolicy.nextAttempt(outbox.getAttempts()));
        outbox.setStatus("UNKNOWN");
        outbox.setProviderReference(firstNonBlank(
                outbox.getProviderReference(),
                tx.getProviderReference(),
                tx.getBlockchainTxid(),
                tx.getPaymentHash()));
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        // Best-effort notification — manual reconciliation needed.
        final Long notifyUserId = tx.getUserId();
        final UUID notifyTxId = tx.getId();
        final UUID notifyWalletId = firstNonNull(tx.getDestinationWalletId(), tx.getSourceWalletId());
        final String notifyRail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
        final long notifyAmount = tx.getGrossAmountSats();
        final String notifyReason = trim(code, 64);
        final boolean notificationQueued = enqueueFinancialEvent("PAYMENT_RECONCILIATION_REQUIRED", notifyUserId, notifyTxId, notifyWalletId, notifyRail, notifyAmount, 0, null, null);
        runAfterCommitAsync(() -> {
            if (!notificationQueued) {
                FinancialNotificationPort port = notificationPort.getIfAvailable();
                if (port != null) {
                    try {
                        port.notifyPaymentReconciliationRequired(notifyUserId, notifyTxId, notifyWalletId,
                                notifyRail, notifyAmount, notifyReason);
                    } catch (RuntimeException exception) {
                        log.warn("[KFE Execution] reconciliation notification failed txId={}: {}",
                                notifyTxId, exception.getMessage());
                    }
                }
            }
        });
    }

    /** Marks a completed execution item dispatched and releases its worker lease.
     * @param outbox execution item to close
     * @param providerReference provider or transaction reference for future reconciliation
     */
    private void markOutboxDispatched(KfeExecutionOutboxEntity outbox, String providerReference) {
        outbox.setStatus("DISPATCHED");
        outbox.setProviderReference(providerReference);
        outbox.setDispatchedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /**
     * After custodial outbound settles, re-probe chain observed so dual-ledger drift is short-lived.
     * Failures are non-fatal — scheduled on-chain sync remains the backstop.
     */
    /** Refreshes an observed custodial balance after settlement; scheduled synchronization remains the fallback.
     * @param walletId wallet whose observed on-chain balance should be refreshed
     */
    private void resyncCustodialObserved(UUID walletId) {
        if (walletId == null) {
            return;
        }
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null || wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return;
        }
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            long probed = sync.syncWallet(walletId);
            log.info(
                    "[KFE Execution] custodial observed resync walletId={} resultSats={}",
                    walletId,
                    probed);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Execution] custodial observed resync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
        }
    }

    /** Closes an outbox item consistently with an already terminal transaction.
     * @param outbox execution item being acknowledged
     * @param tx authoritative payment state
     * @param providerReference provider result reference available to the acknowledgement
     * @return {@code true} when the transaction was terminal and the outbox was handled
     * @throws IllegalStateException when a late provider result targets a closed or reorg-reconciling payment
     */
    private boolean completeTerminalOutboxIfTransactionTerminal(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String providerReference) {
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            markOutboxDispatched(outbox, firstNonBlank(
                    providerReference,
                    tx.getProviderReference(),
                    tx.getBlockchainTxid(),
                    tx.getPaymentHash()));
            return true;
        }
        if (tx.getStatus() == KfeTransactionStatus.FAILED) {
            markOutboxFinalFailed(outbox, tx);
            return true;
        }
        if (tx.getStatus() == KfeTransactionStatus.CANCELLED
                || tx.getStatus() == KfeTransactionStatus.CONFLICTED_REFUNDED
                || tx.getStatus() == KfeTransactionStatus.DROPPED
                || tx.getStatus() == KfeTransactionStatus.ABANDONED
                || tx.getStatus() == KfeTransactionStatus.REORG_RECONCILIATION) {
            throw new IllegalStateException("Execution outcome cannot reopen a closed or reorg-reconciling payment.");
        }
        return false;
    }

    /** Records an outbox failure and computes retry timing when the failure is retryable.
     * @param outbox execution item to update
     * @param code stable failure code
     * @param message failure detail
     * @param retryable whether recovery policy should schedule another attempt
     */
    private void markOutboxFailed(
            KfeExecutionOutboxEntity outbox,
            String code,
            String message,
            boolean retryable) {
        outbox.setAttempts(recoveryPolicy.nextAttempt(outbox.getAttempts()));
        outbox.setStatus(retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL");
        String finalMsg = message != null && !message.isBlank() ? message : "KFE provider execution failed.";
        outbox.setLastError(trim(code + ": " + finalMsg, 1000));
        outbox.setNextAttemptAt(retryable
                ? LocalDateTime.now(java.time.ZoneOffset.UTC).plus(recoveryPolicy.retryDelay(outbox.getAttempts()))
                : null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /** Mirrors a terminal failed transaction onto its execution item.
     * @param outbox execution item to close
     * @param tx already failed transaction providing the error code and detail
     */
    private void markOutboxFinalFailed(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx) {
        outbox.setStatus("FAILED_FINAL");
        String code = firstNonBlank(tx.getFailureCode(), "TRANSACTION_FAILED");
        String message = firstNonBlank(tx.getFailureMessage(), "KFE transaction is already failed.");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /** Persists a transaction status transition and emits its audit and metric events.
     * @param tx transaction whose state changes
     * @param target new lifecycle state
     * @param eventType stable audit event type
     * @param auditPayload additional redacted audit attributes
     */
    private void transition(
            KfeTransactionEntity tx,
            KfeTransactionStatus target,
            String eventType,
            Map<String, ?> auditPayload) {
        KfeTransactionStatus previous = tx.getStatus();
        tx.setStatus(target);
        transactionRepository.save(tx);
        audit(tx, eventType, previous, target, auditPayload);

        // Metrics: record transaction lifecycle counter
        String rail = tx.getRail() != null ? tx.getRail().name() : "UNKNOWN";
        String direction = tx.getDirection() != null ? tx.getDirection().name() : "UNKNOWN";
        financialMetrics.recordTransaction(rail, direction, target.name());

        // Structured audit log for state transitions
        auditEventLogger.logStateTransition(
                eventType,
                tx.getId(),
                tx.getSourceWalletId(),
                previous != null ? previous.name() : null,
                target.name(),
                tx.getGrossAmountSats(),
                rail);
    }

    /** Writes an audit record after hashing the idempotency key and adding transaction identity.
     * @param tx transaction associated with the event
     * @param eventType stable event name
     * @param from previous transaction state
     * @param to resulting transaction state
     * @param payload event-specific data, when available
     */
    private void audit(
            KfeTransactionEntity tx,
            String eventType,
            KfeTransactionStatus from,
            KfeTransactionStatus to,
            Map<String, ?> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("transactionId", tx.getId().toString());
        redacted.put("idempotencyHash", hashService.sha256(tx.getIdempotencyKey()));
        if (payload != null) {
            redacted.putAll(payload);
        }
        auditLogService.record(eventType, tx.getId(), tx.getSourceWalletId(), from, to, redacted);
    }

    /** Persists one wallet balance movement with its source and destination buckets.
     * @param transactionId related payment
     * @param walletId wallet whose balance changes
     * @param movementType stable movement classification
     * @param amountSats moved amount in satoshis
     * @param fromBucket source balance bucket
     * @param toBucket destination balance bucket, or {@code null} where not applicable
     */
    private void movement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        movementRepository.save(movement);
    }

    /** Builds and writes a best-effort user statement projection, storing provider data only as a hash.
     * @param tx transaction to project; ignored when absent or lacking an owner
     * @param walletId wallet associated with the statement row
     * @param providerPayload provider response to hash for diagnostics, if present
     */
    private void recordStatement(KfeTransactionEntity tx, UUID walletId, String providerPayload) {
        if (tx == null || tx.getUserId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        // Best-effort: outbox/monitor must not die if statement cache glitches.
        statementService.recordUserStatementBestEffort(tx.getUserId(), walletId, tx, payload);
    }

    /** Copies the authoritative payment status into its idempotency record when one exists.
     * @param tx transaction whose status is authoritative
     */
    private void updateIdempotency(KfeTransactionEntity tx) {
        idempotencyRepository.findById(new com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId(tx.getUserId(), tx.getIdempotencyKey())).ifPresent(entity -> {
            entity.setStatus(tx.getStatus().name());
            idempotencyRepository.save(entity);
        });
    }

    /** Routes a missing, malformed or mismatched execution envelope to reconciliation.
     * @param outbox invalid queued execution
     * @param tx authorized payment intent against which it was checked
     */
    private void rejectExecutionEnvelope(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx) {
        markRequiresReconciliation(outbox, tx, "EXECUTION_ENVELOPE_INVALID",
                "Execution message does not match the authorized payment.");
    }

    /** Resolves and locks the outbox row, rejecting implicit access to an actively claimed item.
     * @param outboxId explicit outbox identifier, or {@code null} to look up by transaction
     * @param transactionId payment whose outbox is required
     * @return locked outbox belonging to the requested transaction
     * @throws IllegalStateException when no matching item exists or an implicit lookup finds a processing claim
     * @throws IllegalArgumentException when the outbox belongs to another transaction
     */
    private KfeExecutionOutboxEntity resolveOutbox(UUID outboxId, UUID transactionId) {
        UUID resolvedId = outboxId;
        if (resolvedId == null) {
            resolvedId = outboxRepository.findByTransactionId(transactionId).stream()
                    .map(KfeExecutionOutboxEntity::getId).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Outbox not found for transaction."));
        }
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(resolvedId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found."));
        requireOutboxTransaction(outbox, transactionId);
        if (outboxId == null && "PROCESSING".equals(outbox.getStatus())) {
            throw new KfeExecutionClaimLostException(outbox.getId());
        }
        return outbox;
    }

    /** Verifies that a worker acknowledgement addresses the transaction owned by its outbox item.
     * @param outbox locked execution item
     * @param transactionId requested payment ID
     * @throws IllegalArgumentException if the IDs do not match or the payment ID is absent
     */
    private void requireOutboxTransaction(KfeExecutionOutboxEntity outbox, UUID transactionId) {
        if (transactionId == null || !transactionId.equals(outbox.getTransactionId())) {
            throw new IllegalArgumentException("Execution claim does not belong to the requested transaction.");
        }
    }

    /** Verifies that an execution result uses the source wallet registered on the transaction.
     * @param tx authoritative payment
     * @param sourceWalletId wallet reported by the execution worker
     * @throws IllegalArgumentException if the supplied wallet is absent or does not match
     */
    private void requireSourceWallet(KfeTransactionEntity tx, UUID sourceWalletId) {
        if (sourceWalletId == null || !sourceWalletId.equals(tx.getSourceWalletId())) {
            throw new IllegalArgumentException("Execution acknowledgement does not match the source wallet.");
        }
    }

    /** Verifies the outbox is processing under the supplied worker lease token.
     * @param outbox locked execution item
     * @param claimToken expected claim token
     * @throws KfeExecutionClaimLostException if no live matching claim is held
     */
    private void requireClaimOwnership(KfeExecutionOutboxEntity outbox, UUID claimToken) {
        if (outbox == null
                || claimToken == null
                || !"PROCESSING".equals(outbox.getStatus())
                || outbox.getClaimToken() == null
                || !outbox.getClaimToken().equals(claimToken)) {
            throw new KfeExecutionClaimLostException(outbox != null ? outbox.getId() : null);
        }
    }

    /** Clears all lease ownership fields when work is dispatched, retried or parked.
     * @param outbox execution item whose claim is released
     */
    private void clearClaim(KfeExecutionOutboxEntity outbox) {
        outbox.setClaimedBy(null);
        outbox.setClaimedAt(null);
        outbox.setClaimToken(null);
        outbox.setLeaseExpiresAt(null);
    }
    /** Returns the first non-null, non-blank string in preference order.
     * @param values candidate values
     * @return first usable value, or {@code null} when none is present
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Returns the first available wallet identifier.
     * @param first preferred wallet ID
     * @param second fallback wallet ID
     * @return first non-null ID, or {@code null} when both are absent
     */
    private UUID firstNonNull(UUID first, UUID second) {
        return first != null ? first : second;
    }

    /** Truncates a persisted string to its column limit without changing shorter values.
     * @param value original string, or {@code null}
     * @param maxLength maximum allowed character count
     * @return truncated value, unchanged value, or {@code null}
     */
    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
