package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Financial cancellation effects; the caller must already hold the batch fence in its transaction. */
public final class CancelPaymentEffectsService {
    /** Reloads the fenced execution before applying any effect. */
    private final PaymentCancellationStatePort state;
    /** Releases a previously reserved ledger debit when required by the snapshot. */
    private final PaymentLedgerPort ledger;
    /** Releases reserved rail capacity when required by the snapshot. */
    private final PaymentLiquidityPort liquidity;
    /** Records the cancellation statement row after state transition. */
    private final PaymentStatementPort statement;
    /** Records durable cancellation audit data. */
    private final PaymentCancellationAuditPort audit;

    /** Creates the cancellation effect coordinator with state and financial side-effect ports. */
    /** @param state authoritative cancellation state loader @param ledger reserved debit release port @param liquidity capacity release port @param statement statement writer @param audit cancellation audit port */
    public CancelPaymentEffectsService(
            PaymentCancellationStatePort state, PaymentLedgerPort ledger, PaymentLiquidityPort liquidity,
            PaymentStatementPort statement, PaymentCancellationAuditPort audit) {
        this.state = state;
        this.ledger = ledger;
        this.liquidity = liquidity;
        this.statement = statement;
        this.audit = audit;
    }

    /**
     * Reloads execution state after the caller's fence, releases outstanding reservations,
     * marks the execution cancelled, then records statement and audit effects.
     * @param executionId execution protected by the caller's request/outbox/execution fences
     * @param message cancellation reason recorded with state
     * @throws PaymentCancellationRejected when the current state cannot be cancelled
     */
    public void cancel(PaymentExecutionId executionId, String message) {
        // Never decide from a snapshot obtained before the caller acquired its fence.
        var previous = state.load(executionId);
        if (!executionId.equals(previous.executionId())) {
            throw new IllegalStateException("Cancellation state does not match requested execution.");
        }
        if (!previous.incomplete()) {
            return;
        }
        if (!previous.cancellable()) {
            throw new PaymentCancellationRejected();
        }
        if (previous.requiresReserveRelease()) {
            ledger.releaseReserved(executionId, previous.sourceWalletId(), previous.totalDebitSats());
        }
        if (previous.requiresLiquidityRelease()) {
            liquidity.release(executionId);
        }
        state.markCancelled(previous, message);
        statement.record(new RecordPaymentStatementCommand(
                previous.userId(), executionId, previous.statementWalletId(), null, true));
        audit.recordCancelled(previous);
    }
}
