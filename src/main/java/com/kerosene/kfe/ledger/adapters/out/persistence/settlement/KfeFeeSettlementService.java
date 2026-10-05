package com.kerosene.kfe.ledger.adapters.out.persistence.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Settles Kerosene fee to SYSTEM_PROFIT wallet (ITEM 10 — profit segregation).
 *
 * <p>PROFIT SEGREGATION MODEL (SUBLEDGER):
 * Fees are credited to SYSTEM_PROFIT as a ledger entry within the USERS bucket.
 * This is accounting-only — there is no physical UTXO segregation.
 * The SYSTEM_PROFIT balance is a LIABILITY within USERS until physically moved to a
 * dedicated vault PROFIT bucket.
 *
 * <p>INVARIANT: {@code userDebit = recipientCredit + revenueCredit + networkFee}
 * Every settled transaction must preserve this identity. No sats created or destroyed.
 *
 * <p>CONFIGURATION: {@code kfe.profit.segregation-mode=SUBLEDGER} (default).
 * Future modes: {@code DEDICATED_BUCKET} (separate vault bucket), {@code PERIODIC_TRANSFER}.
 *
 * <p>RECONCILIATION: When {@code kfe.profit.reconcile-with-vault=true}, the
 * SYSTEM_PROFIT balance is included in solvency calculations. Total assets must cover
 * user liabilities + SYSTEM_PROFIT balance + safety buffer.
 */
@Service
public class KfeFeeSettlementService {

    /** Logger for idempotent skips and successful fee settlement outcomes. */
    private static final Logger log = LoggerFactory.getLogger(KfeFeeSettlementService.class);
    /** Append-only posting type used to deduplicate fee credits. */
    private static final String MOVEMENT_TYPE = KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE;

    /** Resolves the platform profit wallet that receives settled fees. */
    private final KfeSystemWalletService systemWalletService;
    /** Persists profit credits and reorg reversals in the balance aggregate. */
    private final KfeBalanceService balanceService;
    /** Writes idempotency movements before applying fee balance mutations. */
    private final KfeBalanceMovementRecorder movementRecorder;
    /** Checks existing fee/reversal/restore movements by transaction and movement type. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Records auditable fee settlement and reorganization events. */
    private final KfeAuditLogService auditLogService;
    /** Optional counter for fee credits skipped as already settled. */
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    /** Normalized accounting model used in audit context and operational logs. */
    private final String profitSegregationMode;
    /** Whether the profit liability is included in Vault solvency reconciliation. */
    private final boolean profitReconcileWithVault;

    /**
     * Creates fee settlement with profit segregation and reconciliation policy.
     *
     * @param systemWalletService provider of the SYSTEM_PROFIT wallet identity
     * @param balanceService balance mutation service
     * @param movementRecorder append-only idempotency movement writer
     * @param movementRepository lookup for prior settlement lifecycle movements
     * @param auditLogService audit event writer
     * @param balanceMetrics optional operational metrics
     * @param profitSegregationMode configured subledger/segregation mode
     * @param profitReconcileWithVault whether profit is counted as a liability against Vault assets
     */
    public KfeFeeSettlementService(
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeBalanceMovementRepository movementRepository,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.profit.segregation-mode:SUBLEDGER}") String profitSegregationMode,
            @Value("${kfe.profit.reconcile-with-vault:true}") boolean profitReconcileWithVault) {
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.movementRepository = movementRepository;
        this.auditLogService = auditLogService;
        this.balanceMetrics = balanceMetrics;
        this.profitSegregationMode = normalizeSegregationMode(profitSegregationMode);
        this.profitReconcileWithVault = profitReconcileWithVault;
    }

    /**
     * Credits the Kerosene fee from a settled transaction to the SYSTEM_PROFIT wallet.
     *
     * <p>PROFIT INVARIANT: Every fee credit preserves the identity:
     * {@code userDebit = recipientCredit + keroseneFee + networkFee}
     *
     * <p>Idempotent: dual inbound paths / retries must not inflate SYSTEM_PROFIT.
     *
     * <p>SEGREGATION: In SUBLEDGER mode, profit is tracked in the ledger but remains
     * part of the USERS bucket's backing assets. It is a liability until physically
     * transferred to a dedicated vault PROFIT bucket.
     *
     * <p>CHANNEL COST ATTRIBUTION: Channel operations (open/close/rebalance) consume
     * on-chain fees. These MUST NOT accidentally consume USERS backing. Channel costs
     * are tracked via {@code KfeChannelLifecycleService} and attributed to CHANNELS or
     * INFRA buckets.
     */
    public void creditKeroseneFee(KfeTransactionEntity tx) {
        if (tx == null || tx.getId() == null || tx.getKeroseneFeeSats() <= 0L) {
            return;
        }
        // Idempotent: dual inbound paths / retries must not inflate SYSTEM_PROFIT.
        if (movementRepository.existsByTransactionIdAndMovementType(tx.getId(), MOVEMENT_TYPE)) {
            log.debug(
                    "KFE kerosene fee already settled transactionId={} — skip",
                    tx.getId());
            recordFeeSkip();
            return;
        }

        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        // Record movement first under unique index; only credit if row is new (race-safe).
        boolean wrote = movementRecorder.record(
                tx.getId(),
                profitWalletId,
                MOVEMENT_TYPE,
                tx.getKeroseneFeeSats(),
                null,
                "AVAILABLE");
        if (!wrote) {
            log.debug(
                    "KFE kerosene fee race lost transactionId={} — skip credit",
                    tx.getId());
            recordFeeSkip();
            return;
        }
        balanceService.creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, tx.getKeroseneFeeSats());
        auditLogService.record(
                "KFE_KEROSENE_FEE_SETTLED",
                tx.getId(),
                profitWalletId,
                null,
                tx.getStatus(),
                Map.of(
                        "transactionId", tx.getId().toString(),
                        "profitWalletId", profitWalletId.toString(),
                        "keroseneFeeSats", tx.getKeroseneFeeSats(),
                        "segregationMode", profitSegregationMode,
                        "reconcileWithVault", String.valueOf(profitReconcileWithVault)));
        log.info("KFE kerosene fee settled transactionId={} feeSats={} mode={}",
                tx.getId(), tx.getKeroseneFeeSats(), profitSegregationMode);
    }

    /**
     * Reverses a previously credited fee once when its inbound transaction is reorganized out.
     * The movement is recorded before balance mutation; any amount no longer available becomes reorg debt.
     *
     * @param tx transaction whose fee posting is being reversed; null or incomplete transactions are ignored
     */
    public void reverseKeroseneFeeForReorg(KfeTransactionEntity tx) {
        if (tx == null || tx.getId() == null || tx.getKeroseneFeeSats() <= 0L
                || !movementRepository.existsByTransactionIdAndMovementType(tx.getId(), MOVEMENT_TYPE)
                || movementRepository.existsByTransactionIdAndMovementType(
                        tx.getId(), KfeLedgerMovementTypes.REVERSAL_KEROSENE_FEE)) {
            return;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        boolean wrote = movementRecorder.record(
                tx.getId(),
                profitWalletId,
                KfeLedgerMovementTypes.REVERSAL_KEROSENE_FEE,
                tx.getKeroseneFeeSats(),
                "AVAILABLE_OR_DEBT",
                "CHAIN_REORG");
        if (!wrote) {
            return;
        }
        KfeBalanceService.ReorgDebitResult result = balanceService.reverseAvailableCreditForReorg(
                profitWalletId, KfeSystemWalletService.ASSET_BTC, tx.getKeroseneFeeSats());
        auditLogService.record(
                "KFE_KEROSENE_FEE_REORG_REVERSED",
                tx.getId(),
                profitWalletId,
                tx.getStatus(),
                tx.getStatus(),
                Map.of(
                        "feeSats", tx.getKeroseneFeeSats(),
                        "debitedSats", result.debitedSats(),
                        "debtAddedSats", result.debtAddedSats()));
    }

    /**
     * Restores a fee after a reorg only when a reversal exists and no restore has already been recorded.
     *
     * @param tx transaction whose previously reversed fee is being restored; invalid inputs are ignored
     */
    public void restoreKeroseneFeeAfterReorg(KfeTransactionEntity tx) {
        if (tx == null || tx.getId() == null || tx.getKeroseneFeeSats() <= 0L
                || !movementRepository.existsByTransactionIdAndMovementType(
                        tx.getId(), KfeLedgerMovementTypes.REVERSAL_KEROSENE_FEE)
                || movementRepository.existsByTransactionIdAndMovementType(
                        tx.getId(), KfeLedgerMovementTypes.RESTORE_KEROSENE_FEE)) {
            return;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        boolean wrote = movementRecorder.record(
                tx.getId(),
                profitWalletId,
                KfeLedgerMovementTypes.RESTORE_KEROSENE_FEE,
                tx.getKeroseneFeeSats(),
                "CHAIN_REORG",
                "AVAILABLE_OR_DEBT");
        if (wrote) {
            balanceService.creditAvailable(
                    profitWalletId, KfeSystemWalletService.ASSET_BTC, tx.getKeroseneFeeSats());
        }
    }

    /**
     * Returns the current profit segregation mode.
     *
     * @return SUBLEDGER, DEDICATED_BUCKET, or PERIODIC_TRANSFER
     */
    public String profitSegregationMode() {
        return profitSegregationMode;
    }

    /**
     * Returns whether SYSTEM_PROFIT is reconciled against vault-controlled assets.
     *
     * @return true when the profit balance is included in solvency liability calculations
     */
    public boolean profitReconcileWithVault() {
        return profitReconcileWithVault;
    }

    /** Increments the optional idempotent fee-credit skip counter when metrics are configured. */
    private void recordFeeSkip() {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordFeeIdempotentSkip();
        }
    }

    /**
     * Normalizes the configured segregation mode to uppercase and defaults blank values to SUBLEDGER.
     *
     * @param mode raw configured mode
     * @return trimmed uppercase mode, or SUBLEDGER when absent
     */
    private static String normalizeSegregationMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SUBLEDGER";
        }
        return mode.trim().toUpperCase();
    }
}
