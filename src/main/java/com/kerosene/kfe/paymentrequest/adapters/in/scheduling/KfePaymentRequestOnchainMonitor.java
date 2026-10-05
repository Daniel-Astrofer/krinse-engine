package com.kerosene.kfe.paymentrequest.adapters.in.scheduling;

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
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.paymentrequest.domain.PaymentRequestLifecyclePolicy;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reconciles on-chain deposits against open payment requests and atomically credits qualifying payments. */
@Component
@ConditionalOnProperty(name = "kfe.payment-request-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class KfePaymentRequestOnchainMonitor {

    /** Logger for isolated request reconciliation and notification failures. */
    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestOnchainMonitor.class);
    /** Pattern accepting exactly 32-byte hexadecimal Bitcoin transaction identifiers. */
    private static final Pattern TXID = Pattern.compile("^[0-9a-fA-F]{64}$");
    /** Exact conversion factor for parsing BTC-valued Bitcoin Core fields. */
    private static final BigDecimal SATOSHIS_PER_BTC = new BigDecimal("100000000");
    /** Ledger asset identifier used for inbound satoshi credits. */
    private static final String ASSET_BTC = "BTC";

    /** Loads and locks payment requests while preserving their lifecycle state. */
    private final KfePaymentRequestRepository paymentRequestRepository;
    /** Finds prior inbound observations and persists transaction projections. */
    private final KfeTransactionRepository transactionRepository;
    /** Resolves the destination wallet and custody model for an observed payment. */
    private final KfeWalletRepository walletRepository;
    /** Checks whether another path already credited this transaction. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Optional Bitcoin RPC source used to inspect address transactions. */
    private final ObjectProvider<BlockchainClient> blockchainClient;
    /** Calculates inbound gross, receiver, fee, and debit amounts from observed value. */
    private final KfePricingService pricingService;
    /** Credits spendable wallet balances after an idempotent movement is recorded. */
    private final KfeBalanceService balanceService;
    /** Atomically records ledger movements and reports when a competing credit won. */
    private final KfeBalanceMovementRecorder movementRecorder;
    /** Credits the Kerosene fee as part of payment-request settlement. */
    private final KfeFeeSettlementService feeSettlementService;
    /** Records payment-request observation and paid events for financial audit. */
    private final KfeAuditLogService auditLogService;
    /** Upserts participant-visible payment statements as observations advance. */
    private final KfeStatementService statementService;
    /** Builds the canonical transaction display payload included in statements. */
    private final KfeResponseMapper responseMapper;
    /** Schedules dashboard refresh after transaction commit. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Fallback direct notification boundary when the durable outbox is unavailable. */
    private final FinancialNotificationPort notificationPort;
    /** Owns short transactions around provider observations found by the scheduled scan. */
    private final TransactionTemplate transactionTemplate;
    /** Optional absolute synchronization of chain-observed wallet balances. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    /** Optional metrics for duplicate-credit paths and idempotency races. */
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    /** Maximum number of payment requests read per scan page. */
    private final int batchSize;
    /** Credit finality threshold derived from Bitcoin finality configuration. */
    private final int minConfirmations;

    /** Optional durable notification publisher; absence falls back to best-effort direct delivery. */
    @Autowired(required = false)
    private KfeFinancialNotificationOutboxService notificationOutbox;

    /** Optional sink for recording each confirmed or progressing chain observation. */
    @Autowired(required = false)
    private KfeNetworkObservationLogService observationLog;

    /** Supplies repository, ledger, notification, pricing, and chain-finality collaborators. */
    public KfePaymentRequestOnchainMonitor(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeBalanceMovementRepository movementRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfePricingService pricingService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeFeeSettlementService feeSettlementService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            FinancialNotificationPort notificationPort,
            TransactionTemplate transactionTemplate,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.payment-request-monitor.batch-size:50}") int batchSize,
            KfeBitcoinFinalityPolicy finalityPolicy) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.movementRepository = movementRepository;
        this.blockchainClient = blockchainClient;
        this.pricingService = pricingService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.feeSettlementService = feeSettlementService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.notificationPort = notificationPort;
        this.transactionTemplate = transactionTemplate;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.balanceMetrics = balanceMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
    }

    /** Pages OPEN and EXPIRED on-chain requests, detects address receipts, and reconciles each observation in its own short transaction. */
    @Scheduled(
            fixedDelayString = "${kfe.payment-request-monitor.fixed-delay-ms:1000}",
            initialDelayString = "${kfe.payment-request-monitor.initial-delay-ms:2000}")
    public void reconcileOpenOnchainPaymentRequests() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        int page = 0;
        List<KfePaymentRequestEntity> requests;
        do {
            requests = paymentRequestRepository.findByStatusInAndRailOrderByCreatedAtAsc(
                    List.of(KfePaymentRequestStatus.OPEN, KfePaymentRequestStatus.EXPIRED),
                    KfeRail.ONCHAIN,
                    PageRequest.of(page, batchSize));
            for (KfePaymentRequestEntity request : requests) {
                try {
                    findObservedPayment(client, request)
                            .ifPresent(payment -> transactionTemplate.executeWithoutResult(
                                    status -> reconcileObservedPayment(request.getId(), payment)));
                } catch (RuntimeException exception) {
                    log.warn(
                            "[KFE PaymentRequest Monitor] reconciliation failed paymentRequestId={}: {}",
                            request.getId(),
                            exception.getMessage());
                }
            }
            page++;
        } while (requests.size() == batchSize);
    }

    /** Routes below-finality evidence to observation-only state and sufficiently confirmed evidence to financial settlement. */
    private void reconcileObservedPayment(UUID paymentRequestId, ObservedPayment payment) {
        if (payment.confirmations() >= minConfirmations) {
            settlePaymentRequest(paymentRequestId, payment);
        } else {
            observePaymentRequest(paymentRequestId, payment);
        }
    }

    /** Inbound-adapter entry point for authenticated, deduplicated observations. */
    public void acceptObservation(UUID paymentRequestId, ObservedPayment payment) {
        if (paymentRequestId == null || payment == null) {
            throw new IllegalArgumentException("payment request observation is required");
        }
        transactionTemplate.executeWithoutResult(status -> reconcileObservedPayment(paymentRequestId, payment));
    }

    /** Scans Bitcoin Core address history, validates transaction IDs and requested amounts, deduplicates txids, then chooses the strongest deterministic observation. */
    private Optional<ObservedPayment> findObservedPayment(BlockchainClient client, KfePaymentRequestEntity request) {
        JsonNode received = client.getAddressTransactions(request.getAddress());
        if (received == null || !received.isArray()) {
            return Optional.empty();
        }

        Map<String, ObservedPayment> candidates = new LinkedHashMap<>();
        for (JsonNode entry : received) {
            String txid = txidFromReceivedEntry(entry);
            int confirmations = confirmations(entry);
            long observedSats = amountSats(entry);
            if (txid != null
                    && observedSats > 0L
                    && satisfiesRequestedAmount(request, observedSats)) {
                ObservedPayment candidate = new ObservedPayment(
                        txid, observedSats, confirmations, entry.toString());
                candidates.merge(txid, candidate, KfePaymentRequestOnchainMonitor::preferObservation);
            }
        }
        return candidates.values().stream()
                .sorted((left, right) -> {
                    int confirmationsOrder = Integer.compare(right.confirmations(), left.confirmations());
                    if (confirmationsOrder != 0) return confirmationsOrder;
                    int amountOrder = Long.compare(right.observedSats(), left.observedSats());
                    if (amountOrder != 0) return amountOrder;
                    return left.txid().compareTo(right.txid());
                })
                .findFirst();
    }

    /** Keeps the candidate with more confirmations, then greater received value when confirmation counts tie. */
    private static ObservedPayment preferObservation(ObservedPayment left, ObservedPayment right) {
        if (right.confirmations() > left.confirmations()) return right;
        if (right.confirmations() < left.confirmations()) return left;
        return right.observedSats() > left.observedSats() ? right : left;
    }

    /** Locks an eligible request and records a VALIDATING inbound transaction without crediting funds until finality is reached. */
    @Transactional
    public void observePaymentRequest(java.util.UUID paymentRequestId, ObservedPayment payment) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (!canObserve(request) || !PaymentRequestLifecyclePolicy.canSettle(request.getStatus().name())) {
            return;
        }
        if (!satisfiesRequestedAmount(request, payment.observedSats())) {
            return;
        }

        KfeTransactionEntity existing = findObservedOrSettledTransaction(payment.txid()).orElse(null);
        if (existing != null) {
            boolean patched = false;
            if (existing.getConfirmations() < payment.confirmations()) {
                existing.setConfirmations(payment.confirmations());
                patched = true;
                notifyDepositConfirmationProgress(request, existing, payment);
            }
            if (patched) {
                transactionRepository.save(existing);
                statementService.recordUserStatement(
                        request.getUserId(),
                        request.getWalletId(),
                        existing,
                        new LinkedHashMap<>(
                                responseMapper.buildDisplayPayload(existing, request.getUserId())));
                dashboardPublisher.publishAfterCommit(request.getUserId());
            } else {
                recordObservedStatementIfAbsent(
                        request, existing, payment, existing.getReceiverAmountSats());
            }
            recordObservation(existing, payment);
            return;
        }

        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                payment.observedSats(),
                0L);

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(request.getUserId());
        tx.setIdempotencyKey("payment-request:" + request.getId() + ":" + payment.txid());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(request.getWalletId());
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setProvider("BITCOIN_CORE_PAYMENT_REQUEST_MONITOR");
        tx.setProviderReference(payment.txid());
        tx.setBlockchainTxid(payment.txid());
        tx.setConfirmations(payment.confirmations());
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        transactionRepository.save(tx);
        recordObservation(tx, payment);
        notifyDepositDetected(request, tx, payment);
        recordObservedStatementIfAbsent(request, tx, payment, quote.receiverAmountSats());

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_OBSERVED",
                tx.getId(),
                request.getWalletId(),
                null,
                KfeTransactionStatus.VALIDATING,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "txid", payment.txid(),
                        "observedSats", payment.observedSats(),
                        "confirmations", payment.confirmations()));
        dashboardPublisher.publishAfterCommit(request.getUserId());
    }

    /** Locks an eligible request, applies pricing and idempotent balance/fee credits, marks it PAID, and updates audit, statement, notification, and dashboard state. */
    @Transactional
    public void settlePaymentRequest(java.util.UUID paymentRequestId, ObservedPayment payment) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (!canObserve(request)) {
            return;
        }
        if (!satisfiesRequestedAmount(request, payment.observedSats())) {
            return;
        }
        KfeTransactionEntity existing = findObservedOrSettledTransaction(payment.txid()).orElse(null);
        if (existing != null && existing.getStatus() == KfeTransactionStatus.SETTLED) {
            // Deposit may already be credited by custodial observer — still close the PR.
            if (request.getStatus() != KfePaymentRequestStatus.PAID) {
                request.markPaid(existing.getId());
                paymentRequestRepository.save(request);
                recordPaymentRequestStatement(
                        request,
                        existing,
                        payment,
                        Math.max(0L, existing.getReceiverAmountSats()));
                notifyPaymentRequestPaid(
                        request, existing, Math.max(0L, existing.getReceiverAmountSats()));
                dashboardPublisher.publishAfterCommit(request.getUserId());
                log.info(
                        "[KFE PR Monitor] markPaid on already-SETTLED inbound walletId={} publicId={} txid={}",
                        request.getWalletId(),
                        request.getPublicId(),
                        payment.txid());
            }
            return;
        }

        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                payment.observedSats(),
                0L);

        KfeTransactionEntity tx = existing != null ? existing : new KfeTransactionEntity();
        if (existing == null) {
            tx.setUserId(request.getUserId());
            tx.setIdempotencyKey("payment-request:" + request.getId() + ":" + payment.txid());
            tx.setRail(KfeRail.ONCHAIN);
            tx.setDirection(KfeDirection.INBOUND);
            tx.setDestinationWalletId(request.getWalletId());
            tx.setProvider("BITCOIN_CORE_PAYMENT_REQUEST_MONITOR");
            tx.setProviderReference(payment.txid());
            tx.setBlockchainTxid(payment.txid());
        }
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setConfirmations(payment.confirmations());
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx = transactionRepository.save(tx);

        creditInboundToWallet(request.getWalletId(), tx.getId(), quote.receiverAmountSats());
        feeSettlementService.creditKeroseneFee(tx);
        request.markPaid(tx.getId());
        paymentRequestRepository.save(request);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_PAID",
                tx.getId(),
                request.getWalletId(),
                null,
                KfeTransactionStatus.SETTLED,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "txid", payment.txid(),
                        "observedSats", payment.observedSats(),
                        "creditedSats", quote.receiverAmountSats(),
                        "confirmations", payment.confirmations()));
        recordPaymentRequestStatement(request, tx, payment, quote.receiverAmountSats());
        notifyPaymentRequestPaid(request, tx, quote.receiverAmountSats());
        dashboardPublisher.publishAfterCommit(request.getUserId());
    }

    /** Finds an existing VALIDATING or SETTLED inbound transaction for the provider txid under a row lock. */
    private Optional<KfeTransactionEntity> findObservedOrSettledTransaction(String txid) {
        return transactionRepository.findByProviderReferenceForUpdate(txid).stream()
                .filter(tx -> tx.getStatus() == KfeTransactionStatus.VALIDATING
                        || tx.getStatus() == KfeTransactionStatus.SETTLED)
                .findFirst();
    }

    /** Delegates request observation eligibility to the payment-request lifecycle policy. */
    private boolean canObserve(KfePaymentRequestEntity request) {
        return PaymentRequestLifecyclePolicy.canObserve(request.getStatus().name());
    }

    /** Upserts the canonical participant statement so repeated observations update the same transaction row. */
    private void recordObservedStatementIfAbsent(
            KfePaymentRequestEntity request,
            KfeTransactionEntity tx,
            ObservedPayment payment,
            long creditedSats) {
        // Upsert canonical payload so conf/status advance in place (same transactionId).
        recordPaymentRequestStatement(request, tx, payment, creditedSats);
    }

    /** Appends status and confirmation evidence when the optional observation log is configured. */
    private void recordObservation(KfeTransactionEntity tx, ObservedPayment payment) {
        if (observationLog != null) {
            observationLog.record(tx.getId(), payment.txid(),
                    tx.getStatus().name(), payment.confirmations());
        }
    }

    /** Builds the canonical display snapshot and adds request, amount, and provider-payload fingerprint metadata. */
    private void recordPaymentRequestStatement(
            KfePaymentRequestEntity request,
            KfeTransactionEntity tx,
            ObservedPayment payment,
            long creditedSats) {
        java.util.Map<String, Object> payload =
                new java.util.LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, request.getUserId()));
        payload.put("paymentRequestId", request.getId().toString());
        payload.put("publicId", request.getPublicId());
        payload.put("txid", payment.txid());
        payload.put("observedSats", payment.observedSats());
        payload.put("creditedSats", creditedSats);
        payload.put(
                "rawPaymentHash",
                Integer.toHexString(payment.rawPayload() != null ? payment.rawPayload().hashCode() : 0));
        statementService.recordUserStatement(request.getUserId(), request.getWalletId(), tx, payload);
    }

    /** Enqueues a stable paid event or uses best-effort direct delivery when durable notification is absent. */
    private void notifyPaymentRequestPaid(KfePaymentRequestEntity request, KfeTransactionEntity tx, long creditedSats) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId(
                            "PAYMENT_REQUEST_DEPOSIT_CONFIRMED", tx.getId(), request.getId().toString()),
                    request.getUserId(),
                    tx.getId(),
                    "PAYMENT_REQUEST_DEPOSIT_CONFIRMED",
                    Map.of(
                            "paymentRequestId", request.getId(),
                            "publicId", request.getPublicId(),
                            "walletId", request.getWalletId(),
                            "rail", request.getRail().name(),
                            "amountSats", creditedSats,
                            "confirmations", tx.getConfirmations()));
            return;
        }
        try {
            notificationPort.notifyPaymentRequestDepositConfirmed(
                    request.getUserId(),
                    tx.getId(),
                    request.getId(),
                    request.getPublicId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    creditedSats);
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE payment request was credited but notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Balance model by custody:
     * <ul>
     *   <li>WATCH_ONLY — only blockchain observed balance (absolute resync; never internal available).</li>
     *   <li>CUSTODIAL_ONCHAIN — internal available (authorization) + chain observed resync.</li>
     *   <li>INTERNAL — internal available only.</li>
     * </ul>
     */
    private void creditInboundToWallet(UUID walletId, UUID transactionId, long amountSats) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        boolean watchOnly = wallet != null
                && (wallet.getKind() == KfeWalletKind.WATCH_ONLY || !wallet.isSpendable());
        if (watchOnly) {
            long chainSats = resyncChainObserved(walletId);
            long recorded = chainSats >= 0L ? chainSats : amountSats;
            movementRecorder.record(
                    transactionId, walletId, "CHAIN_OBSERVED_SYNC", recorded, null, "OBSERVED");
            return;
        }
        // Dual path: custodial observer / inbound settlement may have credited the same tx.
        if (movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES)) {
            log.info(
                    "[KFE PR Monitor] skip dual credit transactionId={} walletId={} amount={}",
                    transactionId,
                    walletId,
                    amountSats);
            recordDualSkip("payment-request");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        boolean wrote = movementRecorder.record(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_PAYMENT_REQUEST,
                amountSats,
                null,
                "AVAILABLE");
        if (!wrote) {
            log.info(
                    "[KFE PR Monitor] credit race lost transactionId={} walletId={}",
                    transactionId,
                    walletId);
            recordDualSkip("payment-request-race");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, amountSats);
        if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
            resyncChainObserved(walletId);
        }
    }

    /** Refreshes absolute chain balance when available and returns a negative sentinel on absence or failure. */
    private long resyncChainObserved(UUID walletId) {
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return -1L;
        }
        try {
            return sync.syncWallet(walletId);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE PaymentRequest Monitor] chain balance sync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
            return -1L;
        }
    }

    /** Reports that another observer already credited the transaction or won its idempotency race. */
    private void recordDualSkip(String path) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordDualCreditSkip(path);
        }
    }

    /** Applies the domain policy for whether observed value satisfies the requested amount. */
    private boolean satisfiesRequestedAmount(KfePaymentRequestEntity request, long observedSats) {
        return PaymentRequestLifecyclePolicy.acceptsAmount(request.getAmountSats(), observedSats);
    }

    /** Extracts and validates a direct txid or the first txid in Bitcoin Core's array form. */
    private String txidFromReceivedEntry(JsonNode entry) {
        String direct = text(entry, "txid");
        if (looksLikeTxid(direct)) {
            return direct;
        }
        JsonNode txids = entry.path("txids");
        if (txids.isArray() && txids.size() > 0) {
            String txid = txids.get(0).asText();
            return looksLikeTxid(txid) ? txid : null;
        }
        return null;
    }

    /** Returns a nonnegative integral confirmation count, defaulting malformed provider data to zero. */
    private int confirmations(JsonNode node) {
        JsonNode confirmations = node.path("confirmations");
        return confirmations.isIntegralNumber() ? Math.max(0, confirmations.asInt()) : 0;
    }

    /** Reads satoshi-valued fields first and falls back to converting Bitcoin-denominated amount/value fields. */
    private long amountSats(JsonNode node) {
        long sats = satsField(node, "sats", "satoshis", "amountSats", "amount_sats", "valueSats", "value_sats");
        if (sats > 0L) {
            return sats;
        }
        long amount = amountFromBtcField(node, "amount");
        if (amount > 0L) {
            return amount;
        }
        return amountFromBtcField(node, "value");
    }

    /** Reads the first usable integer or numeric-string satoshi property while rejecting negative values. */
    private long satsField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Math.max(0L, value.asLong());
            }
            if (value.isTextual()) {
                try {
                    return Math.max(0L, Long.parseLong(value.asText()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    /** Converts a positive numeric BTC field to integer satoshis, rounding down fractional sats. */
    private long amountFromBtcField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return 0L;
        }
        BigDecimal btc = value.decimalValue();
        if (btc.signum() <= 0) {
            return 0L;
        }
        return btc.multiply(SATOSHIS_PER_BTC)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    /** Checks that an optional string is exactly a 64-character hexadecimal transaction id. */
    private boolean looksLikeTxid(String value) {
        return value != null && TXID.matcher(value.trim()).matches();
    }

    /** Returns a trimmed nonblank textual JSON property, or null for absent/nontextual values. */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    /** Publishes a stable first-seen deposit event with amount and confirmation metadata. */
    private void notifyDepositDetected(KfePaymentRequestEntity request, KfeTransactionEntity tx, ObservedPayment payment) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_DETECTED", tx.getId(), payment.txid()),
                    request.getUserId(), tx.getId(), "DEPOSIT_DETECTED",
                    Map.of("walletId", request.getWalletId(), "rail", request.getRail().name(),
                            "amountSats", tx.getReceiverAmountSats(),
                            "confirmations", payment.confirmations(), "txid", payment.txid()));
            return;
        }
        try {
            notificationPort.notifyDepositDetected(
                    request.getUserId(),
                    tx.getId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    tx.getReceiverAmountSats(),
                    payment.confirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE deposit detected notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    /** Publishes confirmation-ring progress under a stable per-count event identity. */
    private void notifyDepositConfirmationProgress(KfePaymentRequestEntity request, KfeTransactionEntity tx, ObservedPayment payment) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId(
                            "DEPOSIT_CONFIRMATION_PROGRESS", tx.getId(), String.valueOf(payment.confirmations())),
                    request.getUserId(), tx.getId(), "DEPOSIT_CONFIRMATION_PROGRESS",
                    Map.of("walletId", request.getWalletId(), "rail", request.getRail().name(),
                            "amountSats", tx.getReceiverAmountSats(),
                            "confirmations", payment.confirmations(), "txid", payment.txid()));
            return;
        }
        try {
            notificationPort.notifyDepositConfirmationProgress(
                    request.getUserId(),
                    tx.getId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    tx.getReceiverAmountSats(),
                    payment.confirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE deposit confirmation progress notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    /** Deduplicated Bitcoin address observation carried from provider scan to request settlement.
     * @param txid canonical Bitcoin transaction identifier
     * @param observedSats value observed for this request address, in satoshis
     * @param confirmations chain confirmation count used to choose observation versus settlement
     * @param rawPayload provider row retained for internal observation evidence
     */
    public record ObservedPayment(String txid, long observedSats, int confirmations, String rawPayload) {
    }
}
