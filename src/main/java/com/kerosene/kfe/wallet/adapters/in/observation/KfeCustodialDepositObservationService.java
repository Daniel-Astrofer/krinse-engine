package com.kerosene.kfe.wallet.adapters.in.observation;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.OptionalInt;

/**
 * Detects external on-chain deposits into {@link KfeWalletKind#CUSTODIAL_ONCHAIN} addresses
 * (e.g. Electrum → custodial receive address) and credits the internal ledger.
 *
 * <p>Policy: as soon as the tx is seen on the network (mempool / 0-conf), create an inbound
 * row with status {@code VALIDATING} (UI badge PENDING), notify the user, and publish
 * dashboard/WS so the recipient app updates immediately. {@code available_sats} is credited
 * only when confirmations reach {@code minConfirmations}.
 *
 * <p>Without this, only payment-request inbounds credit {@code available_sats}. Random chain
 * deposits only moved {@code observed_sats}, so the app balance looked stuck.
 */
@Service
@ConditionalOnProperty(
        name = "kfe.custodial-deposit-observation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KfeCustodialDepositObservationService {

    /** Logger for per-wallet probes, deposit lifecycle decisions, and isolated notification failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeCustodialDepositObservationService.class);
    /** Provider taxonomy assigned to transactions found by custodial deposit scans. */
    public static final String PROVIDER_CUSTODIAL_OBSERVER = "BITCOIN_CORE_CUSTODIAL_OBSERVER";
    /** Ledger asset code used for available-balance credits and reorg reversals. */
    private static final String ASSET_BTC = "BTC";

    /** Finds active custodial/internal wallets and resolves wallet ownership for observation. */
    private final KfeWalletRepository walletRepository;
    /** Loads wallet addresses and their active status for chain scanning. */
    private final KfeWalletAddressRepository addressRepository;
    /** Finds existing deposits, locks monitored rows, and persists observation/finality transitions. */
    private final KfeTransactionRepository transactionRepository;
    /** Checks for an existing available credit before applying a second inbound path. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Optional Bitcoin source for UTXO scans and transaction confirmation probes. */
    private final ObjectProvider<BlockchainClient> blockchainClient;
    /** Applies available-balance credits, reorg debits, debt, and restorations. */
    private final KfeBalanceService balanceService;
    /** Records idempotent movements before mutating the corresponding balance. */
    private final KfeBalanceMovementRecorder movementRecorder;
    /** Computes receiver amount and Kerosene fees for each observed deposit. */
    private final KfePricingService pricingService;
    /** Applies, reverses, and restores Kerosene fees as deposit finality changes. */
    private final KfeFeeSettlementService feeSettlementService;
    /** Upserts the participant statement projection as deposit state changes. */
    private final KfeStatementService statementService;
    /** Builds canonical display payloads for deposit statements. */
    private final KfeResponseMapper responseMapper;
    /** Schedules dashboard refresh after each committed deposit state change. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Records settlement, drop, reorg compensation, and reorg recovery events. */
    private final KfeAuditLogService auditLogService;
    @Autowired
    private KfeFinancialNotificationOutboxService notificationOutbox;

    private boolean queueDepositEvent(String type, Long userId, UUID txId, UUID walletId,
                                      long amount, int confirmations) {
        if (notificationOutbox == null) return false;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Deposit event must join its financial transaction.");
        notificationOutbox.enqueue(UUID.randomUUID(), userId, txId, type, Map.of(
                "walletId", walletId, "rail", "ONCHAIN", "amountSats", amount,
                "confirmations", Math.max(0, confirmations)));
        return true;
    }

    /** Optional notifier invoked only after the database transaction commits. */
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    /** Records balance divergence and duplicate-credit operational metrics. */
    private final KfeFinancialMetrics financialMetrics;
    /** Optional absolute refresh of chain-observed wallet balances. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    /** Optional normalized address-to-wallet index used to match ZMQ outputs efficiently. */
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex;
    /** Runs each deposit upsert/reconciliation in an independent short transaction. */
    private final TransactionTemplate transactionTemplate;
    /** Maximum number of wallets or monitored transactions processed per scheduled pass. */
    private final int batchSize;
    /** Configured threshold at which an observed deposit may credit available balance. */
    private final int minConfirmations;
    /** Supplies the longer reorg-monitor threshold beyond initial credit finality. */
    private final KfeBitcoinFinalityPolicy finalityPolicy;
    /** Minimum consecutive missing probes required before treating chain evidence as disappeared. */
    private final int missingObservationsBeforeReorg;
    /** Minimum elapsed absence window required before reorg compensation. */
    private final long missingSecondsBeforeReorg;

    /** Optional link lookup used to mirror deposit reorg/recovery onto the associated payment request. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KfePaymentRequestRepository paymentRequestRepository;

    /** Supplies persistence, ledger, pricing, notification, chain, and finality collaborators. */
    public KfeCustodialDepositObservationService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeTransactionRepository transactionRepository,
            KfeBalanceMovementRepository movementRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfePricingService pricingService,
            KfeFeeSettlementService feeSettlementService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            KfeFinancialMetrics financialMetrics,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex,
            TransactionTemplate transactionTemplate,
            @Value("${kfe.custodial-deposit-observation.batch-size:30}") int batchSize,
            KfeBitcoinFinalityPolicy finalityPolicy,
            @Value("${kfe.custodial-deposit-observation.reorg-missing-observations:3}")
                    int missingObservationsBeforeReorg,
            @Value("${kfe.custodial-deposit-observation.reorg-missing-min-seconds:60}")
                    long missingSecondsBeforeReorg) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.transactionRepository = transactionRepository;
        this.movementRepository = movementRepository;
        this.blockchainClient = blockchainClient;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.pricingService = pricingService;
        this.feeSettlementService = feeSettlementService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.notificationPort = notificationPort;
        this.financialMetrics = financialMetrics;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.addressIndex = addressIndex;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.finalityPolicy = finalityPolicy;
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
        this.missingObservationsBeforeReorg = Math.max(2, missingObservationsBeforeReorg);
        this.missingSecondsBeforeReorg = Math.max(0L, missingSecondsBeforeReorg);
    }

    /** Scans a bounded set of active custodial/internal wallets, isolates per-wallet failures, then checks finality for known deposits. */
    @Scheduled(
            fixedDelayString = "${kfe.custodial-deposit-observation.fixed-delay-ms:20000}",
            initialDelayString = "${kfe.custodial-deposit-observation.initial-delay-ms:12000}")
    public void reconcileCustodialDeposits() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        // CUSTODIAL is the primary sink; also scan INTERNAL wallets that still hold
        // historical receive addresses so deposits are not invisible if routing missed.
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.CUSTODIAL_ONCHAIN, KfeWalletKind.INTERNAL),
                KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                observeWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }

        monitorKnownDepositFinality(client);
    }

    /** Probes known inbound transactions and reconciles each confirmation result in its own short transaction. */
    private void monitorKnownDepositFinality(BlockchainClient client) {
        List<KfeTransactionEntity> candidates = transactionRepository.findInboundUnderReorgMonitoring(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                List.of(
                        KfeTransactionStatus.VALIDATING,
                        KfeTransactionStatus.SETTLED,
                        KfeTransactionStatus.REORG_RECONCILIATION),
                PageRequest.of(0, batchSize));
        for (KfeTransactionEntity candidate : candidates) {
            OptionalInt confirmations = client.findTransactionConfirmations(candidate.getBlockchainTxid());
            try {
                transactionTemplate.executeWithoutResult(
                        ignored -> reconcileKnownDeposit(candidate.getId(), confirmations));
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] finality reconciliation failed txId={}: {}",
                        candidate.getId(),
                        exception.getMessage());
            }
        }
    }

    /** Locks a monitored deposit, updates chain evidence, settles at finality, compensates reorged credits, or restores deposits that recover. */
    private void reconcileKnownDeposit(UUID transactionId, OptionalInt observedConfirmations) {
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null || !tx.isConfirmationMonitoringActive()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tx.setLastChainProbeAt(now);
        if (observedConfirmations.isEmpty()) {
            recordMissingDepositProbe(tx, now);
            transactionRepository.save(tx);
            return;
        }

        int confirmations = observedConfirmations.getAsInt();
        tx.setLastChainProbeStatus(confirmations < 0 ? "CONFLICTED" : "FOUND");
        tx.setNetworkLastSeenAt(now);
        tx.setNetworkNotFoundSince(null);
        tx.setNetworkNotFoundCount(0);
        tx.setConfirmations(confirmations);

        if (confirmations < minConfirmations) {
            if (alreadyCreditedAvailable(tx.getId())) {
                compensateCreditedDeposit(tx, confirmations < 0 ? "CHAIN_CONFLICT" : "CONFIRMATIONS_REORGED");
            } else if (confirmations < 0) {
                markUncreditedDepositDropped(tx, "CHAIN_CONFLICT");
            } else {
                transactionRepository.save(tx);
            }
            return;
        }

        if (tx.getStatus() == KfeTransactionStatus.REORG_RECONCILIATION) {
            restoreReorgedDeposit(tx);
        } else if (tx.getStatus() != KfeTransactionStatus.SETTLED) {
            settleKnownDeposit(tx);
        }
        if (confirmations >= finalityPolicy.getReorgMonitorConfirmations()) {
            tx.setConfirmationMonitoringActive(false);
        }
        transactionRepository.save(tx);
    }

    /** Counts consecutive missing chain observations and compensates/drops only after both count and duration thresholds pass. */
    private void recordMissingDepositProbe(KfeTransactionEntity tx, LocalDateTime now) {
        tx.setLastChainProbeStatus("NOT_FOUND");
        if (tx.getNetworkNotFoundSince() == null) {
            tx.setNetworkNotFoundSince(now);
            tx.setNetworkNotFoundCount(1);
            return;
        }
        tx.setNetworkNotFoundCount(tx.getNetworkNotFoundCount() + 1);
        boolean observationsMet = tx.getNetworkNotFoundCount() >= missingObservationsBeforeReorg;
        boolean durationMet = !tx.getNetworkNotFoundSince().plusSeconds(missingSecondsBeforeReorg).isAfter(now);
        if (!observationsMet || !durationMet) {
            return;
        }
        if (alreadyCreditedAvailable(tx.getId())) {
            compensateCreditedDeposit(tx, "TRANSACTION_DISAPPEARED");
        } else {
            markUncreditedDepositDropped(tx, "TRANSACTION_DISAPPEARED");
        }
    }

    /** Reverses an existing available credit, records any resulting debt, reverses fees, and marks the deposit for reconciliation. */
    private void compensateCreditedDeposit(KfeTransactionEntity tx, String reason) {
        if (tx.getStatus() == KfeTransactionStatus.REORG_RECONCILIATION) {
            return;
        }
        UUID walletId = tx.getDestinationWalletId();
        long creditSats = Math.max(0L, tx.getReceiverAmountSats());
        if (creditSats <= 0L || walletId == null) {
            throw new IllegalStateException("Credited deposit lacks wallet or amount for reorg compensation.");
        }
        boolean wrote = movementRecorder.record(
                tx.getId(),
                walletId,
                KfeLedgerMovementTypes.REVERSAL_DEBIT,
                creditSats,
                "AVAILABLE_OR_DEBT",
                "CHAIN_REORG");
        if (!wrote) {
            throw new IllegalStateException(
                    "Reorg reversal movement already exists while transaction is not in reconciliation.");
        }
        KfeBalanceService.ReorgDebitResult result =
                balanceService.reverseAvailableCreditForReorg(walletId, ASSET_BTC, creditSats);
        if (result.debtAddedSats() > 0L) {
            movementRecorder.record(
                    tx.getId(),
                    walletId,
                    KfeLedgerMovementTypes.REORG_DEBT,
                    result.debtAddedSats(),
                    null,
                    "REORG_DEBT");
        }
        feeSettlementService.reverseKeroseneFeeForReorg(tx);
        KfeTransactionStatus previous = tx.getStatus();
        tx.setStatus(KfeTransactionStatus.REORG_RECONCILIATION);
        tx.setFailureCode("DEPOSIT_REORG");
        tx.setFailureMessage("Deposit backing was lost or fell below the credit confirmation threshold.");
        transactionRepository.save(tx);
        markLinkedPaymentRequestsReorged(tx, reason);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("reason", reason);
        audit.put("confirmations", tx.getConfirmations());
        audit.put("creditSats", creditSats);
        audit.put("debitedSats", result.debitedSats());
        audit.put("debtAddedSats", result.debtAddedSats());
        auditLogService.record(
                "KFE_INBOUND_REORG_COMPENSATED",
                tx.getId(),
                walletId,
                previous,
                KfeTransactionStatus.REORG_RECONCILIATION,
                audit);
        queueDepositEvent("DEPOSIT_REVERSED", tx.getUserId(), tx.getId(), walletId, creditSats, tx.getConfirmations());
        recordDepositStatement(tx, walletId, true);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Restores a compensated deposit and fee after chain confirmations recover, then reopens linked request state. */
    private void restoreReorgedDeposit(KfeTransactionEntity tx) {
        UUID walletId = tx.getDestinationWalletId();
        long creditSats = Math.max(0L, tx.getReceiverAmountSats());
        if (creditSats <= 0L || walletId == null) {
            throw new IllegalStateException("Reorged deposit lacks wallet or amount for restoration.");
        }
        boolean wrote = movementRecorder.record(
                tx.getId(),
                walletId,
                KfeLedgerMovementTypes.REORG_RESTORE_CREDIT,
                creditSats,
                "CHAIN_REORG",
                "AVAILABLE_OR_DEBT");
        if (!wrote) {
            throw new IllegalStateException(
                    "Reorg restoration movement already exists while transaction remains in reconciliation.");
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
        feeSettlementService.restoreKeroseneFeeAfterReorg(tx);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setFailureCode(null);
        tx.setFailureMessage(null);
        restoreLinkedPaymentRequests(tx);
        auditLogService.record(
                "KFE_INBOUND_REORG_RECOVERED",
                tx.getId(),
                walletId,
                KfeTransactionStatus.REORG_RECONCILIATION,
                KfeTransactionStatus.SETTLED,
                Map.of("confirmations", tx.getConfirmations(), "creditSats", creditSats));
        queueDepositEvent("DEPOSIT_RECONCILED", tx.getUserId(), tx.getId(), walletId, creditSats, tx.getConfirmations());
        recordDepositStatement(tx, walletId, false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Marks linked PAID requests failed when their backing chain deposit is compensated. */
    private void markLinkedPaymentRequestsReorged(KfeTransactionEntity tx, String reason) {
        if (paymentRequestRepository == null || tx.getId() == null || tx.getUserId() == null) {
            return;
        }
        for (KfePaymentRequestEntity request : paymentRequestRepository
                .findByPaidTransactionIdAndUserIdForUpdate(tx.getId(), tx.getUserId())) {
            if (request.getStatus() != KfePaymentRequestStatus.PAID) {
                continue;
            }
            request.markFailed("CHAIN_REORG");
            paymentRequestRepository.save(request);
            auditLogService.record(
                    "KFE_PAYMENT_REQUEST_FAILED",
                    request.getId(),
                    request.getWalletId(),
                    null,
                    null,
                    Map.of("transactionId", tx.getId().toString(), "reason", reason,
                            "fromRequestStatus", KfePaymentRequestStatus.PAID.name(),
                            "toRequestStatus", KfePaymentRequestStatus.FAILED.name()));
        }
    }

    /** Returns linked FAILED requests to PAID when the deposit is restored after a reorg. */
    private void restoreLinkedPaymentRequests(KfeTransactionEntity tx) {
        if (paymentRequestRepository == null || tx.getId() == null || tx.getUserId() == null) {
            return;
        }
        for (KfePaymentRequestEntity request : paymentRequestRepository
                .findByPaidTransactionIdAndUserIdForUpdate(tx.getId(), tx.getUserId())) {
            if (request.getStatus() != KfePaymentRequestStatus.FAILED) {
                continue;
            }
            request.markPaid(tx.getId());
            paymentRequestRepository.save(request);
            auditLogService.record(
                    "KFE_PAYMENT_REQUEST_PAID",
                    request.getId(),
                    request.getWalletId(),
                    null,
                    null,
                    Map.of("transactionId", tx.getId().toString(),
                            "fromRequestStatus", KfePaymentRequestStatus.FAILED.name(),
                            "toRequestStatus", KfePaymentRequestStatus.PAID.name(),
                            "reason", "CHAIN_REORG_RECOVERED"));
        }
    }

    /** Applies a not-yet-recorded credit and fee once, then updates the transaction statement and dashboard projection. */
    private void settleKnownDeposit(KfeTransactionEntity tx) {
        UUID walletId = tx.getDestinationWalletId();
        long creditSats = Math.max(0L, tx.getReceiverAmountSats());
        if (!alreadyCreditedAvailable(tx.getId())
                && creditAvailableOnce(tx.getId(), walletId, creditSats)) {
            feeSettlementService.creditKeroseneFee(tx);
        }
        tx.setStatus(KfeTransactionStatus.SETTLED);
        queueDepositEvent("DEPOSIT_CONFIRMED", tx.getUserId(), tx.getId(), walletId, creditSats, tx.getConfirmations());
        recordDepositStatement(tx, walletId, false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Drops an uncredited deposit whose chain evidence disappeared and disables further monitoring. */
    private void markUncreditedDepositDropped(KfeTransactionEntity tx, String reason) {
        tx.setStatus(KfeTransactionStatus.DROPPED);
        tx.setFailureCode("DEPOSIT_DROPPED");
        tx.setFailureMessage("Deposit transaction is no longer confirmed by Bitcoin Core.");
        tx.setConfirmationMonitoringActive(false);
        auditLogService.record(
                "KFE_INBOUND_DROPPED",
                tx.getId(),
                tx.getDestinationWalletId(),
                KfeTransactionStatus.VALIDATING,
                KfeTransactionStatus.DROPPED,
                Map.of("reason", reason));
        queueDepositEvent("DEPOSIT_DROPPED", tx.getUserId(), tx.getId(), tx.getDestinationWalletId(),
                Math.max(tx.getReceiverAmountSats(), tx.getGrossAmountSats()), tx.getConfirmations());
        recordDepositStatement(tx, tx.getDestinationWalletId(), false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /** Upserts the canonical transaction statement with an explicit reorg marker. */
    private void recordDepositStatement(KfeTransactionEntity tx, UUID walletId, boolean reorg) {
        Map<String, Object> statement = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        statement.put("reorg", reorg);
        statementService.recordUserStatement(tx.getUserId(), walletId, tx, statement);
    }

    /** Public entry for ZMQ / reactive path. */
    public void observeWallet(UUID walletId) {
        // Probe UTXOs outside a long TX, then commit each deposit independently so one
        // failure cannot roll back earlier credits.
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null || wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            return;
        }
        // Ledger-credit sinks for on-chain deposits (not cold WATCH_ONLY — that path is cold observation).
        if (wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN
                && wallet.getKind() != KfeWalletKind.INTERNAL) {
            return;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        Map<String, DepositAggregate> byTxid = collectDeposits(client, walletId);
        if (byTxid.isEmpty()) {
            return;
        }

        boolean changed = false;
        Long userId = wallet.getUserId();
        for (Map.Entry<String, DepositAggregate> entry : byTxid.entrySet()) {
            try {
                Boolean applied = transactionTemplate.execute(status -> {
                    KfeWalletEntity locked = walletRepository.findById(walletId).orElse(null);
                    if (locked == null) {
                        return false;
                    }
                    return upsertDeposit(locked, entry.getKey(), entry.getValue());
                });
                if (Boolean.TRUE.equals(applied)) {
                    changed = true;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] deposit commit failed walletId={} txid={}: {}",
                        walletId,
                        entry.getKey(),
                        exception.getMessage(),
                        exception);
            }
        }
        if (changed) {
            resyncObserved(walletId);
            if (userId != null) {
                dashboardPublisher.publishAfterCommit(userId);
            }
        }
    }

    /**
     * Instant path: materialize custodial/INTERNAL inbound from a ZMQ {@code rawtx} at
     * 0 confirmations without waiting for {@code listunspent}/{@code scantxoutset}.
     * Full {@link #observeWallet} still runs afterwards for confs + available credit.
     */
    public void ingestZmqRawTx(KfeBitcoinZmqTxMatcher.ParsedRawTx parsed, Set<UUID> walletIds) {
        if (parsed == null || walletIds == null || walletIds.isEmpty()) {
            return;
        }
        String txid = parsed.txid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        String normalizedTxid = txid.trim().toLowerCase(Locale.ROOT);
        KfeMonitoredChainAddressIndex index = addressIndex.getIfAvailable();
        Set<Long> usersToPublish = new HashSet<>();

        for (UUID walletId : walletIds) {
            if (walletId == null) {
                continue;
            }
            KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
            if (wallet == null || wallet.getStatus() != KfeWalletStatus.ACTIVE) {
                continue;
            }
            if (wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN
                    && wallet.getKind() != KfeWalletKind.INTERNAL) {
                continue;
            }

            long amountSats = 0L;
            String sampleAddress = null;
            for (KfeBitcoinZmqTxMatcher.ParsedOutput out : parsed.outputs()) {
                if (out == null || out.valueSats() <= 0L) {
                    continue;
                }
                String address = out.address();
                if (address == null || address.isBlank()) {
                    continue;
                }
                if (!addressBelongsToWallet(walletId, address, index)) {
                    continue;
                }
                amountSats = Math.addExact(amountSats, out.valueSats());
                if (sampleAddress == null) {
                    sampleAddress = address.trim();
                }
            }
            if (amountSats <= 0L) {
                continue;
            }

            DepositAggregate deposit = new DepositAggregate(sampleAddress, amountSats, 0);
            try {
                Boolean applied = transactionTemplate.execute(status -> {
                    KfeWalletEntity locked = walletRepository.findById(walletId).orElse(null);
                    if (locked == null) {
                        return false;
                    }
                    return upsertDeposit(locked, normalizedTxid, deposit);
                });
                if (Boolean.TRUE.equals(applied)) {
                    if (wallet.getUserId() != null) {
                        usersToPublish.add(wallet.getUserId());
                    }
                    log.info(
                            "[KFE Custodial Deposit] zmq-ingest walletId={} txid={} sats={} confs=0",
                            walletId,
                            normalizedTxid,
                            amountSats);
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] zmq-ingest failed walletId={} txid={}: {}",
                        walletId,
                        normalizedTxid,
                        exception.getMessage());
            }
        }

        for (Long userId : usersToPublish) {
            dashboardPublisher.publishAfterCommit(userId);
        }
    }

    /** Uses the index when authoritative and falls back to active database addresses on a cache miss. */
    private boolean addressBelongsToWallet(
            UUID walletId, String address, KfeMonitoredChainAddressIndex index) {
        if (index != null) {
            UUID owner = index.walletIdForAddress(address);
            if (walletId.equals(owner)) {
                return true;
            }
            // Index miss (stale cache) — fall through to DB.
            if (owner != null) {
                return false;
            }
        }
        String key = address == null ? null : address.trim().toLowerCase(Locale.ROOT);
        if (key == null || key.isEmpty()) {
            return false;
        }
        return addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId).stream()
                .anyMatch(row ->
                        row.getAddress() != null
                                && key.equals(row.getAddress().trim().toLowerCase(Locale.ROOT))
                                && (row.getStatus() == null
                                        || row.getStatus() == KfeWalletAddressStatus.ACTIVE));
    }

    /** Scans active receive addresses, aggregates positive UTXOs by normalized txid, and retains the strongest observed confirmation count. */
    private Map<String, DepositAggregate> collectDeposits(BlockchainClient client, UUID walletId) {
        List<KfeWalletAddressEntity> addresses =
                addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId);
        Map<String, DepositAggregate> byTxid = new LinkedHashMap<>();
        for (KfeWalletAddressEntity row : addresses) {
            if (row.getStatus() != null && row.getStatus() != KfeWalletAddressStatus.ACTIVE) {
                continue;
            }
            String address = row.getAddress();
            if (address == null || address.isBlank()) {
                continue;
            }
            List<BlockchainClient.AddressUtxo> utxos;
            try {
                utxos = client.getUnspentOutputsMerged(address.trim());
            } catch (RuntimeException exception) {
                log.debug(
                        "[KFE Custodial Deposit] utxo probe failed address={}: {}",
                        address,
                        exception.getMessage());
                continue;
            }
            for (BlockchainClient.AddressUtxo utxo : utxos) {
                if (utxo == null || utxo.txid() == null || utxo.txid().isBlank() || utxo.valueSats() <= 0L) {
                    continue;
                }
                String txid = utxo.txid().trim().toLowerCase(Locale.ROOT);
                int confs = Math.max(0, utxo.confirmations());
                DepositAggregate agg = byTxid.computeIfAbsent(
                        txid, key -> new DepositAggregate(address.trim(), 0L, confs));
                agg.amountSats += utxo.valueSats();
                agg.confirmations = Math.max(agg.confirmations, confs);
                if (agg.sampleAddress == null || agg.sampleAddress.isBlank()) {
                    agg.sampleAddress = address.trim();
                }
            }
        }
        return byTxid;
    }

    /** Creates or updates one idempotent inbound transaction, publishes observation progress, and credits only at configured finality. */
    private boolean upsertDeposit(KfeWalletEntity wallet, String txid, DepositAggregate deposit) {
        if (deposit.amountSats <= 0L) {
            return false;
        }
        // Always surface 0-conf (mempool) deposits as VALIDATING so the recipient
        // app sees the inbound immediately. available_sats is credited only when
        // confirmations >= minConfirmations (default 3 in prod / 1 local).
        // Previously confs < min returned false and the Linux app stayed blank
        // until 3 blocks — sender already saw the outbound.

        // Already known for this chain tx (payment-request or prior custodial observe).
        List<KfeTransactionEntity> existingForTx =
                transactionRepository.findByBlockchainTxidAndUserId(txid, wallet.getUserId());
        for (KfeTransactionEntity existing : existingForTx) {
            if (existing.getDirection() != KfeDirection.INBOUND) {
                continue;
            }
            if (!wallet.getId().equals(existing.getDestinationWalletId())) {
                continue;
            }
            // Update confs / settle when needed; credit only if no available credit yet.
            boolean patched = false;
            boolean confBumped = false;
            if (deposit.confirmations > existing.getConfirmations()) {
                existing.setConfirmations(deposit.confirmations);
                patched = true;
                confBumped = true;
            }
            // Amount can grow if more outputs to same address appear in the same tx.
            if (deposit.amountSats > existing.getGrossAmountSats()) {
                existing.setGrossAmountSats(deposit.amountSats);
                existing.setReceiverAmountSats(Math.max(existing.getReceiverAmountSats(), deposit.amountSats));
                patched = true;
            }
            int settleAt = Math.max(0, minConfirmations);
            boolean canSettle = deposit.confirmations >= settleAt;
            boolean settledNow = false;
            if (existing.getStatus() != KfeTransactionStatus.SETTLED && canSettle) {
                existing.setStatus(KfeTransactionStatus.SETTLED);
                patched = true;
                settledNow = true;
                if (!alreadyCreditedAvailable(existing.getId())) {
                    long creditSats = Math.max(0L, existing.getReceiverAmountSats());
                    if (creditSats <= 0L) {
                        creditSats = Math.max(0L, existing.getGrossAmountSats());
                    }
                    if (creditSats > 0L
                            && creditAvailableOnce(
                                    existing.getId(), wallet.getId(), creditSats)) {
                        feeSettlementService.creditKeroseneFee(existing);
                        scheduleDepositNotificationAfterCommit(
                                wallet, existing, creditSats, deposit.confirmations, true);
                    }
                } else {
                    // Already credited earlier — still announce confirmed once.
                    scheduleDepositNotificationAfterCommit(
                            wallet,
                            existing,
                            Math.max(0L, existing.getReceiverAmountSats()),
                            deposit.confirmations,
                            true);
                }
            } else if (confBumped && existing.getStatus() != KfeTransactionStatus.SETTLED) {
                // Realtime ring updates: 0→1→2… before settle.
                scheduleDepositNotificationAfterCommit(
                        wallet,
                        existing,
                        Math.max(0L, existing.getReceiverAmountSats()),
                        deposit.confirmations,
                        false);
            }
            if (patched) {
                transactionRepository.save(existing);
                // Keep history row identity; only status/confs/updatedAt change.
                // Publishes /queue/transactions so FE rings advance on each block.
                statementService.recordUserStatement(
                        wallet.getUserId(),
                        wallet.getId(),
                        existing,
                        new LinkedHashMap<>(responseMapper.buildDisplayPayload(existing, wallet.getUserId())));
                if (settledNow) {
                    log.info(
                            "[KFE Custodial Deposit] settled walletId={} txid={} confs={}",
                            wallet.getId(),
                            txid,
                            deposit.confirmations);
                }
                return true;
            }
            return false;
        }

        String idempotencyKey = "custodial-dep:" + wallet.getId() + ":" + txid;
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return false;
        }

        KfePricingService.Quote quote;
        try {
            quote = pricingService.quote(
                    KfeRail.ONCHAIN, KfeDirection.INBOUND, deposit.amountSats, 0L);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Custodial Deposit] quote failed walletId={} amount={}: {}",
                    wallet.getId(),
                    deposit.amountSats,
                    exception.getMessage());
            return false;
        }

        int confs = deposit.confirmations;
        // minConfirmations=0 (local) → credit in mempool; production typically waits for N confs.
        boolean settleNow = confs >= minConfirmations;
        KfeTransactionStatus status =
                settleNow ? KfeTransactionStatus.SETTLED : KfeTransactionStatus.VALIDATING;

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(wallet.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(wallet.getId());
        tx.setExternalReference(deposit.sampleAddress);
        tx.setMemo(wallet.getKind() == KfeWalletKind.INTERNAL
                ? "Depósito on-chain (conta Kerosene)"
                : "Depósito on-chain (carteira custodial)");
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(0L);
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(0L);
        tx.setProvider(PROVIDER_CUSTODIAL_OBSERVER);
        tx.setProviderReference(txid);
        tx.setBlockchainTxid(txid);
        tx.setConfirmations(confs);
        LocalDateTime observedAt = LocalDateTime.now(ZoneOffset.UTC);
        tx.setNetworkFirstSeenAt(observedAt);
        tx.setNetworkLastSeenAt(observedAt);
        tx.setLastChainProbeAt(observedAt);
        tx.setLastChainProbeStatus("FOUND");
        tx.setConfirmationMonitoringActive(
                confs < finalityPolicy.getReorgMonitorConfirmations());
        tx.setStatus(status);
        tx = transactionRepository.save(tx);

        if (settleNow) {
            if (!alreadyCreditedAvailable(tx.getId())) {
                creditAvailableOnce(tx.getId(), wallet.getId(), quote.receiverAmountSats());
            }
            feeSettlementService.creditKeroseneFee(tx);
            scheduleDepositNotificationAfterCommit(
                    wallet, tx, quote.receiverAmountSats(), confs, true);
        } else {
            scheduleDepositNotificationAfterCommit(
                    wallet, tx, quote.receiverAmountSats(), confs, false);
        }

        Map<String, Object> statement =
                new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, wallet.getUserId()));
        // Upsert: first sight creates row; conf/status later refresh same transactionId.
        statementService.recordUserStatement(wallet.getUserId(), wallet.getId(), tx, statement);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("txid", txid);
        audit.put("observedSats", deposit.amountSats);
        audit.put("creditedSats", quote.receiverAmountSats());
        audit.put("confirmations", confs);
        audit.put("address", deposit.sampleAddress == null ? "" : deposit.sampleAddress);
        auditLogService.record(
                settleNow ? "KFE_INBOUND_SETTLED" : "KFE_INBOUND_CREDITED",
                tx.getId(),
                wallet.getId(),
                null,
                status,
                audit);

        log.info(
                "[KFE Custodial Deposit] exposed walletId={} txid={} observedSats={} creditedSats={} confs={} status={} settleNow={}",
                wallet.getId(),
                txid,
                deposit.amountSats,
                quote.receiverAmountSats(),
                confs,
                status,
                settleNow);
        return true;
    }

    /** Checks whether any recognized available-credit movement already exists for the transaction. */
    private boolean alreadyCreditedAvailable(UUID transactionId) {
        if (transactionId == null) {
            return false;
        }
        return movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES);
    }

    /** Movement-first credit; returns true when this caller owns the credit. */
    private boolean creditAvailableOnce(UUID transactionId, UUID walletId, long creditSats) {
        if (creditSats <= 0L) {
            return false;
        }
        boolean wrote = movementRecorder.record(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_CUSTODIAL_DEPOSIT,
                creditSats,
                null,
                "AVAILABLE");
        if (!wrote) {
            return false;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
        return true;
    }

    /**
     * Schedules a deposit notification to fire after the current transaction commits.
     * This prevents premature "deposit confirmed" push when the DB tx later rolls back.
     */
    private void scheduleDepositNotificationAfterCommit(
            KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs, boolean isSettled) {
        // Capture values before transaction commits.
        final Long userId = wallet.getUserId();
        final UUID txId = tx.getId();
        final UUID walletId = wallet.getId();
        String type = isSettled ? "DEPOSIT_CONFIRMED" : confs > 0 ? "DEPOSIT_CONFIRMATION_PROGRESS" : "DEPOSIT_DETECTED";
        if (queueDepositEvent(type, userId, txId, walletId, amount, confs)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active transaction — fire immediately (e.g. unit tests).
            fireDepositNotification(userId, txId, walletId, amount, confs, isSettled);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new DepositNotificationSynchronization(userId, txId, walletId, amount, confs, isSettled));
    }

    /** Sends confirmed or detected notification through the optional provider and logs delivery failures without undoing credit. */
    private void fireDepositNotification(
            Long userId, UUID txId, UUID walletId, long amount, int confs, boolean isSettled) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            if (isSettled) {
                port.notifyDepositConfirmed(userId, txId, walletId, "ONCHAIN", amount, confs);
            } else {
                port.notifyDepositDetected(userId, txId, walletId, "ONCHAIN", amount, confs);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Custodial Deposit] notification after-commit failed txId={}: {}",
                    txId,
                    exception.getMessage());
        }
    }

    /** Refreshes chain-observed balance after an applied deposit and updates the divergence metric when available. */
    private void resyncObserved(UUID walletId) {
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            long probed = sync.syncWallet(walletId);
            // Update divergence gauge with the sync result
            if (probed >= 0L) {
                financialMetrics.setBalanceDivergenceSats(probed);
            }
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Custodial Deposit] observed resync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
        }
    }

    /** Mutable per-txid accumulator combining outputs and the strongest confirmation observation. */
    private static final class DepositAggregate {
        /** One owned address retained as the transaction display reference. */
        private String sampleAddress;
        /** Sum of all positive outputs for this transaction and observed wallet. */
        private long amountSats;
        /** Highest confirmation count seen across the transaction outputs. */
        private int confirmations;

        /** Initializes an aggregate from the first matching output. */
        private DepositAggregate(String sampleAddress, long amountSats, int confirmations) {
            this.sampleAddress = sampleAddress;
            this.amountSats = amountSats;
            this.confirmations = confirmations;
        }
    }

    /** Captures deposit notification data and delivers it only after its database transaction commits. */
    private final class DepositNotificationSynchronization implements TransactionSynchronization {
        /** Participant receiving the notification, captured before transaction completion. */
        private final Long userId;
        /** Persisted inbound transaction identifier captured before commit. */
        private final UUID transactionId;
        /** Destination wallet identifier used by the client notification. */
        private final UUID walletId;
        /** Credited or observed amount in satoshis. */
        private final long amountSats;
        /** Confirmation count associated with the event. */
        private final int confirmations;
        /** Whether this event represents final settlement instead of first detection. */
        private final boolean settled;

        /** Stores immutable callback values so later transaction state changes cannot alter the event. */
        private DepositNotificationSynchronization(
                Long userId, UUID transactionId, UUID walletId, long amountSats, int confirmations, boolean settled) {
            this.userId = userId;
            this.transactionId = transactionId;
            this.walletId = walletId;
            this.amountSats = amountSats;
            this.confirmations = confirmations;
            this.settled = settled;
        }

        /** Fires the captured notification after commit, suppressing delivery on rollback. */
        @Override
        public void afterCommit() {
            fireDepositNotification(userId, transactionId, walletId, amountSats, confirmations, settled);
        }
    }
}
