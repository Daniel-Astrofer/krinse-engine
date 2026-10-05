package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeColdWalletObservationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Production confirmation monitor for on-chain outbounds and inbounds.
 *
 * <p>After broadcast ({@link KfeExecutionTransactionHelper#recordOutboundBroadcast}), funds remain
 * LOCKED on the source wallet until this monitor observes {@code minConfirmations} on the
 * blockchain txid, then settles the reserved debit.
 *
 * <p><strong>UI contract:</strong> confirmation rings (0/6…6/6) must advance on every block even
 * when settle fails or hangs. Conf updates run in a short {@code REQUIRES_NEW} TX before settle.
 */
@Component
@ConditionalOnProperty(name = "kfe.network-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOutboundConfirmationMonitor {

    /** Logger for observation progress and isolated reconciliation/settlement failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeOutboundConfirmationMonitor.class);

    /** Queries candidate transactions and persists chain-observation metadata. */
    private final KfeTransactionRepository transactionRepository;
    /** Applies confirmation progress and final settlement against the current locked row. */
    private final KfeExecutionTransactionHelper transactionHelper;
    /** Optional Bitcoin Core source of transaction chain status. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    /** Optional observer that updates non-custodial cold-wallet confirmations. */
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    /** Records network absence signals for operational monitoring. */
    private final KfeFinancialMetrics financialMetrics;
    /** Maximum number of candidate rows loaded for each status group. */
    private final int batchSize;
    /** Credit finality threshold for settling a reserved outbound debit. */
    private final int minConfirmations;
    /** Confirmation count at which client confirmation rings stop advancing. */
    private final int uiConfirmationTarget;
    /** Consecutive missing probes required before classifying a transaction as disappeared. */
    private final int maxNotFoundCount;
    /** Minimum duration of repeated absence before reconciliation escalation. */
    private final int notFoundGracePeriodSeconds;

    /** Wires chain observers, transaction helpers, finality thresholds, and bounded disappearance policy. */
    public KfeOutboundConfirmationMonitor(
            KfeTransactionRepository transactionRepository,
            KfeExecutionTransactionHelper transactionHelper,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            KfeFinancialMetrics financialMetrics,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            KfeBitcoinFinalityPolicy finalityPolicy,
            @Value("${kfe.network-monitor.onchain.max-not-found-count:5}") int maxNotFoundCount,
            @Value("${kfe.network-monitor.onchain.not-found-grace-period-seconds:300}")
            int notFoundGracePeriodSeconds) {
        this.transactionRepository = transactionRepository;
        this.transactionHelper = transactionHelper;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.coldObservationService = coldObservationService;
        this.financialMetrics = financialMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
        this.uiConfirmationTarget = finalityPolicy.getFinalityConfirmations();
        this.maxNotFoundCount = Math.max(1, maxNotFoundCount);
        this.notFoundGracePeriodSeconds = Math.max(30, notFoundGracePeriodSeconds);
    }

    /** Scans open and settled chain transactions, advancing UI confirmations before settlement attempts. */
    @Scheduled(
            fixedDelayString = "${kfe.network-monitor.fixed-delay-ms:30000}",
            initialDelayString = "${kfe.network-monitor.initial-delay-ms:20000}")
    public void reconcileOutboundConfirmations() {
        BitcoinCoreRpcClient core = bitcoinCoreRpcClient.getIfAvailable();
        if (core == null) {
            return;
        }

        // Prefer unlocks (non-SETTLED) first, then SETTLED ring climbs — two queries so a flood of
        // SETTLED conf bumps cannot delay settle of EXECUTING rows.
        List<KfeTransactionEntity> openOutbounds = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(
                        KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.VALIDATING,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                uiConfirmationTarget,
                PageRequest.of(0, batchSize));
        List<KfeTransactionEntity> settledOutbounds = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(KfeTransactionStatus.SETTLED),
                uiConfirmationTarget,
                PageRequest.of(0, batchSize));

        for (KfeTransactionEntity tx : openOutbounds) {
            try {
                inspect(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] confirmation check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }
        for (KfeTransactionEntity tx : settledOutbounds) {
            try {
                inspect(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] confirmation check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }

        // Inbounds: VALIDATING + SETTLED until rings hit 6.
        List<KfeTransactionEntity> openInbounds =
                transactionRepository.findOutboundAwaitingConfirmation(
                        KfeRail.ONCHAIN,
                        KfeDirection.INBOUND,
                        List.of(
                                KfeTransactionStatus.VALIDATING,
                                KfeTransactionStatus.EXECUTING,
                                KfeTransactionStatus.SETTLED),
                        uiConfirmationTarget,
                        PageRequest.of(0, batchSize));
        for (KfeTransactionEntity tx : openInbounds) {
            try {
                inspectInbound(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] inbound conf check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }
    }

    /** Updates inbound chain progress while leaving credit finality to the inbound settlement flow. */
    private void inspectInbound(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        String txid = tx.getBlockchainTxid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        // ITEM 8: Use full chain status for inbounds too, to detect disappeared deposits
        BitcoinCoreRpcClient.TransactionChainStatus status =
                core.fetchTransactionChainStatus(txid.trim());

        if (status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND
                || status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN) {
            return;
        }
        int confirmations = status.confirmations();
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            }
            return;
        }
        persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "inbound");
    }

    /** Persists chain observations, handles reorg or absence evidence, then settles confirmed outbounds. */
    private void inspect(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        String txid = tx.getBlockchainTxid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BitcoinCoreRpcClient.TransactionChainStatus status = core.fetchTransactionChainStatus(txid.trim());

        // Update network tracking fields
        if (tx.getNetworkFirstSeenAt() == null
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            tx.setNetworkFirstSeenAt(now);
        }
        if (status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            tx.setNetworkLastSeenAt(now);
        }
        tx.setLastChainProbeAt(now);
        tx.setLastChainProbeStatus(status.state().name());

        // ITEM 8: Handle disappeared transactions
        if (status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            if (tx.getNetworkNotFoundSince() == null) {
                tx.setNetworkNotFoundSince(now);
                tx.setNetworkNotFoundCount(1);
                // Metrics: first not-found occurrence
                String rail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
                financialMetrics.recordNetworkNotFound(rail);
            } else {
                tx.setNetworkNotFoundCount(tx.getNetworkNotFoundCount() + 1);
            }
            long notFoundDuration = java.time.Duration.between(tx.getNetworkNotFoundSince(), now).getSeconds();

            if (tx.getNetworkNotFoundCount() >= maxNotFoundCount
                    && notFoundDuration >= notFoundGracePeriodSeconds) {
                // Missing network evidence cannot prove that a signed payment is safe to refund.
                handleDisappearedTransaction(tx, txid.trim());
            } else {
                // First absences: mark UNKNOWN, don't change ledger
                transactionRepository.save(tx);
                log.info(
                        "[KFE Outbound Monitor] tx disappeared txId={} txid={} notFound={}",
                        tx.getId(), txid, tx.getNetworkNotFoundCount());
            }
            return;
        }

        // Reset not-found tracking when tx is seen again
        if (tx.getNetworkNotFoundSince() != null) {
            tx.setNetworkNotFoundSince(null);
            tx.setNetworkNotFoundCount(0);
        }

        int confirmations = status.confirmations();
        // Track mempool presence
        if (confirmations == 0) {
            tx.setMempoolLastSeenAt(now);
        }

        // Negative confirmations = conflicted / double-spend / reorg reversal.
        if (confirmations < 0) {
            log.error(
                    "[KFE Outbound Monitor] CONFLICTED txId={} txid={} confs={} — entering reconciliation",
                    tx.getId(),
                    txid,
                    confirmations);
            try {
                transactionHelper.markOutboundConflicted(tx.getId(), txid.trim(), confirmations);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] conflicted resolution failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
            // The helper owns the current locked row. Merging this older snapshot would undo its transition.
            return;
        }
        // Cold PSBT / observer rows never hold a custodial LOCKED reserve.
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            } else {
                persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "cold-outbound");
            }
            transactionRepository.save(tx);
            return;
        }

        // Always commit conf rings first (REQUIRES_NEW). Settle can hang on audit locks without
        // freezing the UI at 0/6.
        persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "outbound");

        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            return;
        }
        if (confirmations < minConfirmations) {
            return;
        }
        try {
            boolean settled = transactionHelper.settleOutboundWhenConfirmed(tx.getId(), confirmations);
            if (settled) {
                log.info(
                        "[KFE Outbound Monitor] settled outbound txId={} confs={}",
                        tx.getId(),
                        confirmations);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Outbound Monitor] settle deferred txId={} confs={}: {}",
                    tx.getId(),
                    confirmations,
                    exception.getMessage());
        }
    }

    /** ITEM 8: Handle a transaction that has completely disappeared from the network. */
    private void handleDisappearedTransaction(KfeTransactionEntity tx, String observedTxid) {
        log.error("[KFE Outbound Monitor] DISAPPEARED txId={} txid={} notFoundCount={}",
                tx.getId(), observedTxid, tx.getNetworkNotFoundCount());
        // Apply against the current locked row and the exact transaction observed. No detached save,
        // input/replacement heuristic or refund follows, even when the helper rejects/fails the update.
        transactionHelper.markOutboundDisappeared(tx.getId(), observedTxid);
    }

    /** Persists changed confirmation rings through the helper's short independent transaction. */
    private void persistConfProgress(KfeTransactionEntity tx, int confirmations,
                                      String blockHash, Integer blockHeight, String kind) {
        if (tx == null) {
            return;
        }
        // ITEM 10: Allow confirmation decreases while not FINALIZED
        if (confirmations == tx.getConfirmations()) {
            return;
        }
        transactionHelper.touchOutboundConfirmations(tx.getId(), confirmations, blockHash, blockHeight);
        log.info(
                "[KFE Outbound Monitor] {} confs txId={} confs={} was={} status={}",
                kind,
                tx.getId(),
                confirmations,
                tx.getConfirmations(),
                tx.getStatus());
    }
}
