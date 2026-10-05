package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeNetworkObservationLogService;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.paymentexecution.domain.settlement.InboundSettlementBinding;
import com.kerosene.kfe.paymentexecution.domain.settlement.InboundSettlementBindingPolicy;
import com.kerosene.kfe.paymentexecution.domain.settlement.InboundSettlementDecision;
import com.kerosene.kfe.paymentexecution.domain.settlement.InboundSettlementEvidence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Verifies inbound settlement decisions and applies ledger, audit, statement, outbox, and notification effects atomically. */
@Service
public class KfeInboundSettlementService {

    /** Operational logger for best-effort reconciliation and post-credit notification failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeInboundSettlementService.class);
    /** Asset code used for inbound BTC ledger credits. */
    private static final String ASSET_BTC = "BTC";

    /** Locks and persists the transaction whose inbound evidence is being settled. */
    private final KfeTransactionRepository transactionRepository;
    /** Locks and updates the durable execution command associated with the settlement proof. */
    private final KfeExecutionOutboxRepository outboxRepository;
    /** Enforces one credit movement per transaction and checks earlier observer credits. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Updates the idempotency projection when the inbound transaction reaches settlement. */
    private final KfeIdempotencyRepository idempotencyRepository;
    /** Locks the owner-scoped destination wallet before crediting. */
    private final KfeWalletRepository walletRepository;
    /** Applies spendable BTC credits to the available wallet balance. */
    private final KfeBalanceService balanceService;
    /** Records the financial state transition with references stored only as hashes. */
    private final KfeAuditLogService auditLogService;
    /** Upserts the participant statement projection for the settled inbound payment. */
    private final KfeStatementService statementService;
    /** Builds the canonical transaction fields used in the statement snapshot. */
    private final KfeResponseMapper responseMapper;
    /** Publishes participant dashboard refresh only after this transaction commits. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Hashes provider and network evidence before including it in financial audit records. */
    private final KfeHashService hashService;
    /** Fallback notification boundary used when no durable notification outbox is available. */
    private final FinancialNotificationPort notificationPort;
    /** Credits the Kerosene fee after the inbound amount is settled. */
    private final KfeFeeSettlementService feeSettlementService;
    /** Optional synchronizer for chain-observed balances of on-chain wallets. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    /** Optional operational metrics for duplicate or competing credit paths. */
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    /** Supplies the configured confirmation threshold required for inbound credit. */
    private final KfeBitcoinFinalityPolicy finalityPolicy;
    /** Domain policy comparing persisted transaction identity with observed provider evidence. */
    private final InboundSettlementBindingPolicy bindingPolicy = new InboundSettlementBindingPolicy();

    /** Optional for plain unit tests; present in the running KFE application. */
    @Autowired(required = false)
    /** Durable notification outbox used when present to avoid losing post-commit deposit events. */
    private KfeFinancialNotificationOutboxService notificationOutbox;

    /** Optional audit sink for every observed inbound network decision. */
    @Autowired(required = false)
    private KfeNetworkObservationLogService observationLog;

    /** Supplies all persistence, ledger, audit, notification, and finality collaborators. */
    public KfeInboundSettlementService(
            KfeTransactionRepository transactionRepository,
            KfeExecutionOutboxRepository outboxRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeIdempotencyRepository idempotencyRepository,
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            FinancialNotificationPort notificationPort,
            KfeFeeSettlementService feeSettlementService,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            KfeBitcoinFinalityPolicy finalityPolicy) {
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.movementRepository = movementRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.notificationPort = notificationPort;
        this.feeSettlementService = feeSettlementService;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.balanceMetrics = balanceMetrics;
        this.finalityPolicy = finalityPolicy;
    }

    /**
     * Validates proof identity and domain binding under ordered reference/outbox/transaction locks,
     * then applies an idempotent credit and all financial projections in one transaction.
     *
     * @param proof provider/network evidence to evaluate
     * @return true when settlement was newly applied or already applied; false when evidence is invalid,
     *         incomplete, below finality, or requires reconciliation
     */
    @Transactional
    public boolean settle(InboundSettlementProof proof) {
        if (proof == null || proof.outboxId() == null || proof.transactionId() == null) {
            return false;
        }
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(proof.outboxId()).orElse(null);
        if (outbox == null) {
            return false;
        }
        if (!proof.transactionId().equals(outbox.getTransactionId())) {
            return false;
        }

        String providerReference = normalizeReference(proof.providerReference(), 255);
        String networkReference = normalizeReference(proof.networkReference(), 128);
        if (providerReference == null || providerReference.isBlank()
                || networkReference == null || networkReference.isBlank()) {
            return false;
        }
        // Acquire the reference lock before the transaction row. Every inbound
        // attempt with the same external identity follows this order, so the
        // subsequent cross-transaction FOR UPDATE lookup cannot deadlock.
        transactionRepository.acquireInboundProviderReferenceLock(providerReference);

        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(outbox.getTransactionId()).orElse(null);
        if (tx == null) {
            markOutboxFailed(outbox, "TRANSACTION_NOT_FOUND", "KFE inbound transaction does not exist.");
            return false;
        }

        KfeWalletEntity destinationWallet = tx.getDestinationWalletId() == null || tx.getUserId() == null
                ? null
                : walletRepository.findByIdAndUserIdForUpdate(tx.getDestinationWalletId(), tx.getUserId()).orElse(null);
        boolean conflictingProviderReference = hasSettledProviderReference(tx, providerReference);
        String persistedNetworkReference = tx.getRail() == KfeRail.ONCHAIN
                ? tx.getBlockchainTxid()
                : tx.getRail() == KfeRail.LIGHTNING ? tx.getPaymentHash() : null;
        InboundSettlementDecision decision = bindingPolicy.decide(
                new InboundSettlementBinding(
                        proof.transactionId(),
                        outbox.getTransactionId(),
                        outbox.getOperation(),
                        outbox.getStatus(),
                        tx.getRail() == null ? null : tx.getRail().name(),
                        tx.getDirection() == null ? null : tx.getDirection().name(),
                        tx.getStatus() == null ? null : tx.getStatus().name(),
                        tx.getUserId() == null ? 0L : tx.getUserId(),
                        tx.getDestinationWalletId(),
                        destinationWallet == null ? null : destinationWallet.getUserId(),
                        tx.getGrossAmountSats(),
                        tx.getReceiverAmountSats(),
                        outbox.getProviderReference(),
                        tx.getProviderReference(),
                        persistedNetworkReference,
                        conflictingProviderReference),
                new InboundSettlementEvidence(
                        providerReference,
                        networkReference,
                        proof.observedAmountSats(),
                        proof.confirmations(),
                        finalityPolicy.getCreditConfirmations()));

        if (observationLog != null) {
            observationLog.record(
                    tx.getId(),
                    networkReference,
                    decision.code(),
                    proof.confirmations());
        }

        if (decision.isIdempotent()) {
            if (!"DISPATCHED".equals(outbox.getStatus())) {
                markOutboxDispatched(outbox, providerReference);
            }
            return true;
        }
        if (!decision.allowsSettlement()) {
            if (decision.shouldRecordReconciliation()) {
                markStillReconciling(outbox, tx, decision.code());
            }
            return false;
        }

        long creditSats = tx.getReceiverAmountSats() > 0L
                ? tx.getReceiverAmountSats()
                : proof.observedAmountSats();
        if (creditSats <= 0L) {
            return false;
        }

        creditInbound(destinationWallet, tx.getId(), creditSats);

        KfeTransactionStatus previous = tx.getStatus();
        tx.setProvider(trim(proof.provider(), 64));
        tx.setProviderReference(providerReference);
        if (tx.getRail() == KfeRail.ONCHAIN) {
            tx.setBlockchainTxid(networkReference);
        } else if (tx.getRail() == KfeRail.LIGHTNING) {
            tx.setPaymentHash(networkReference);
        }
        tx.setConfirmations(Math.max(tx.getConfirmations(), proof.confirmations()));
        tx.setFailureCode(null);
        tx.setFailureMessage(null);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        transactionRepository.save(tx);
        feeSettlementService.creditKeroseneFee(tx);

        auditLogService.record(
                "KFE_INBOUND_SETTLED",
                tx.getId(),
                tx.getDestinationWalletId(),
                previous,
                KfeTransactionStatus.SETTLED,
                Map.of(
                        "provider", firstNonBlank(proof.provider(), "UNKNOWN"),
                        "providerReferenceHash", hashService.sha256(firstNonBlank(proof.providerReference(), "")),
                        "networkReferenceHash", hashService.sha256(firstNonBlank(proof.networkReference(), "")),
                        "observedAmountSats", proof.observedAmountSats(),
                        "creditedSats", creditSats,
                        "confirmations", proof.confirmations()));
        recordStatement(tx, proof.rawPayload());
        notifyInboundDepositCredited(tx, creditSats);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        return true;
    }

    /** Detects whether a different settled transaction already owns this external provider reference. */
    private boolean hasSettledProviderReference(KfeTransactionEntity tx, String providerReference) {
        if (providerReference == null || providerReference.isBlank()) {
            return false;
        }
        return transactionRepository.findByProviderReferenceAndStatusForUpdate(
                        providerReference,
                        KfeTransactionStatus.SETTLED)
                .stream()
                .anyMatch(existing -> !existing.getId().equals(tx.getId()));
    }

    /** Persists a reconciliation reason without changing financial balances or terminalizing the command. */
    private void markStillReconciling(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code) {
        if (tx.getStatus() == KfeTransactionStatus.EXECUTING
                || tx.getStatus() == KfeTransactionStatus.REQUIRES_RECONCILIATION) {
            tx.setFailureCode(code);
            tx.setFailureMessage("Inbound observation requires reconciliation.");
            transactionRepository.save(tx);
        }
        outbox.setLastError(code + ": inbound observation requires reconciliation.");
        outboxRepository.save(outbox);
    }

    /** Marks an unusable inbound command terminal and removes its lease and retry schedule. */
    private void markOutboxFailed(KfeExecutionOutboxEntity outbox, String code, String message) {
        outbox.setStatus("FAILED_FINAL");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /** Marks the evidence command dispatched and clears every claim/retry field. */
    private void markOutboxDispatched(KfeExecutionOutboxEntity outbox, String providerReference) {
        outbox.setStatus("DISPATCHED");
        outbox.setProviderReference(trim(providerReference, 255));
        outbox.setDispatchedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /** Credits spendable wallets once, records observation-only wallets, and reconciles custodial chain balance. */
    private void creditInbound(KfeWalletEntity wallet, UUID transactionId, long creditSats) {
        if (wallet == null || wallet.getId() == null) {
            // The binding policy should make this unreachable. Keep the effect path
            // fail-closed if a future caller bypasses or changes that policy.
            throw new IllegalStateException("Inbound destination wallet is unavailable.");
        }
        UUID walletId = wallet.getId();
        boolean watchOnly = wallet != null
                && (wallet.getKind() == KfeWalletKind.WATCH_ONLY || !wallet.isSpendable());
        if (watchOnly) {
            long chainSats = resyncChainObserved(walletId);
            long recorded = chainSats >= 0L ? chainSats : creditSats;
            movement(transactionId, walletId, "CHAIN_OBSERVED_SYNC", recorded, null, "OBSERVED");
            return;
        }
        // Dual path: payment-request monitor / custodial observer may have credited first.
        if (movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES)) {
            log.info(
                    "[KFE Inbound Settlement] skip dual credit transactionId={} walletId={} amount={}",
                    transactionId,
                    walletId,
                    creditSats);
            recordDualSkip("inbound-settlement");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        // Insert movement under unique index first; only credit when we own the row (race-safe).
        if (!tryMovement(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_INBOUND,
                creditSats,
                null,
                "AVAILABLE")) {
            log.info(
                    "[KFE Inbound Settlement] credit race lost transactionId={} walletId={}",
                    transactionId,
                    walletId);
            recordDualSkip("inbound-settlement-race");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
        if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
            resyncChainObserved(walletId);
        }
    }

    /** Refreshes a wallet's chain-observed balance, returning negative when the optional sync cannot run. */
    private long resyncChainObserved(UUID walletId) {
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return -1L;
        }
        try {
            return sync.syncWallet(walletId);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Inbound Settlement] chain balance sync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
            return -1L;
        }
    }

    /** Reports that another inbound observation path already performed or won the credit. */
    private void recordDualSkip(String path) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordDualCreditSkip(path);
        }
    }

    /** Attempts to persist a movement when failure to win a duplicate race is intentionally nonfatal. */
    private void movement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        tryMovement(transactionId, walletId, movementType, amountSats, fromBucket, toBucket);
    }

    /** Inserts an idempotent balance movement and returns false when an equivalent credit already exists. */
    private boolean tryMovement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        if (transactionId != null
                && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)
                && movementRepository.existsByTransactionIdAndMovementType(transactionId, movementType)) {
            return false;
        }
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        try {
            movementRepository.save(movement);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            if (transactionId != null && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)) {
                return false;
            }
            throw exception;
        }
    }

    /** Upserts a canonical statement snapshot and stores hashes instead of raw provider references/payload. */
    private void recordStatement(KfeTransactionEntity tx, String providerPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        payload.put("providerReferenceHash", hashService.sha256(firstNonBlank(tx.getProviderReference(), "")));
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        // Upsert same (user, tx) row — status/confs update in place; createdAt stays fixed.
        statementService.recordUserStatement(tx.getUserId(), tx.getDestinationWalletId(), tx, payload);
    }

    /** Enqueues a stable deposit event, falling back to best-effort direct notification when unavailable. */
    private void notifyInboundDepositCredited(KfeTransactionEntity tx, long creditSats) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_CONFIRMED", tx.getId(), "settled"),
                    tx.getUserId(),
                    tx.getId(),
                    "DEPOSIT_CONFIRMED",
                    Map.of(
                            "walletId", tx.getDestinationWalletId(),
                            "rail", tx.getRail().name(),
                            "amountSats", creditSats,
                            "confirmations", tx.getConfirmations(),
                            "txid", firstNonBlank(tx.getBlockchainTxid(), tx.getPaymentHash())));
            return;
        }
        try {
            notificationPort.notifyDepositConfirmed(
                    tx.getUserId(),
                    tx.getId(),
                    tx.getDestinationWalletId(),
                    tx.getRail().name(),
                    creditSats,
                    tx.getConfirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE inbound deposit was credited but notification failed. transactionId={} error={}",
                    tx.getId(),
                    exception.getMessage());
        }
    }

    /** Synchronizes the user's idempotency row with the transaction's newly persisted status. */
    private void updateIdempotency(KfeTransactionEntity tx) {
        idempotencyRepository.findById(new com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId(
                        tx.getUserId(),
                        tx.getIdempotencyKey()))
                .ifPresent(entity -> {
                    entity.setStatus(tx.getStatus().name());
                    idempotencyRepository.save(entity);
                });
    }

    /** Clears worker ownership and lease expiry after command completion or terminal rejection. */
    private void clearClaim(KfeExecutionOutboxEntity outbox) {
        outbox.setClaimedBy(null);
        outbox.setClaimedAt(null);
        outbox.setClaimToken(null);
        outbox.setLeaseExpiresAt(null);
    }

    /** Selects the first nonblank value without changing its contents. */
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

    /** Truncates an optional string to a storage limit without padding or altering null. */
    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** Trims external identity text and bounds its stored length before acquiring identity locks. */
    private String normalizeReference(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return trim(value.trim(), maxLength);
    }

    /** Provider evidence presented for inbound settlement; raw payload is retained only for internal hashing.
     * @param transactionId KFE transaction identity expected by this observation
     * @param outboxId durable inbound command that triggered or tracks reconciliation
     * @param provider provider identifier associated with the observation
     * @param providerReference provider-side stable invoice or receipt identity
     * @param networkReference blockchain transaction ID or Lightning payment hash
     * @param observedAmountSats amount reported as received, in satoshis
     * @param confirmations observed confirmation count used by finality policy
     * @param rawPayload provider evidence kept for internal processing and hashed statement metadata
     */
    public record InboundSettlementProof(
            UUID transactionId,
            UUID outboxId,
            String provider,
            String providerReference,
            String networkReference,
            long observedAmountSats,
            int confirmations,
            String rawPayload) {
    }
}
