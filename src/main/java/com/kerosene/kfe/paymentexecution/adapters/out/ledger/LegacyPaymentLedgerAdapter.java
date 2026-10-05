package com.kerosene.kfe.paymentexecution.adapters.out.ledger;

import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Keeps balance and movement writes inside the payment's existing transaction. */
@Transactional(propagation = Propagation.MANDATORY)
@Deprecated(forRemoval = false)
public class LegacyPaymentLedgerAdapter implements PaymentLedgerPort {

    private static final String ASSET_BTC = "BTC";

    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;

    public LegacyPaymentLedgerAdapter(
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder) {
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
    }

    @Override
    public void reserve(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        validateIdentity(executionId, walletId);
        balanceService.reserve(walletId, ASSET_BTC, amountSats);
        recordRequiredMovement(executionId, walletId, "RESERVE", amountSats, "AVAILABLE", "LOCKED");
    }

    @Override
    public void settleReservedDebit(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        validateIdentity(executionId, walletId);
        balanceService.settleReservedDebit(walletId, ASSET_BTC, amountSats);
        recordRequiredMovement(executionId, walletId, "SETTLE_DEBIT", amountSats, "LOCKED", null);
    }

    @Override
    public void creditAvailable(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        validateIdentity(executionId, walletId);
        balanceService.creditAvailable(walletId, ASSET_BTC, amountSats);
        recordRequiredMovement(
                executionId, walletId, KfeLedgerMovementTypes.CREDIT, amountSats, null, "AVAILABLE");
    }

    @Override
    public void releaseReserved(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        validateIdentity(executionId, walletId);
        balanceService.releaseReserved(walletId, ASSET_BTC, amountSats);
    }

    private void recordRequiredMovement(
            PaymentExecutionId executionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        if (!movementRecorder.record(
                executionId.value(), walletId, movementType, amountSats, fromBucket, toBucket)) {
            // A duplicate movement cannot authorize a second balance mutation. Roll back both.
            throw new IllegalStateException("Payment ledger movement was not recorded: " + movementType);
        }
    }

    private static void validateIdentity(PaymentExecutionId executionId, UUID walletId) {
        Objects.requireNonNull(executionId, "payment execution id is required");
        Objects.requireNonNull(walletId, "wallet id is required");
    }
}
