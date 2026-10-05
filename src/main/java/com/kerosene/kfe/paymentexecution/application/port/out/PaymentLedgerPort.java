package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;

/**
 * Balance effects for payment execution, joined to the caller's transaction.
 * A reservation, debit or credit succeeds only together with its ledger movement.
 */
public interface PaymentLedgerPort {

    /** Moves available funds into the execution's reserved bucket within the caller transaction. */
    /** @param executionId idempotency identity for this financial movement @param walletId wallet being reserved @param amountSats positive debit reservation in satoshis */
    void reserve(PaymentExecutionId executionId, UUID walletId, long amountSats);

    /** Settles a previously reserved debit from the source wallet. */
    /** @param executionId execution owning the reservation @param walletId source wallet @param amountSats reserved amount to debit */
    void settleReservedDebit(PaymentExecutionId executionId, UUID walletId, long amountSats);

    /** Credits receiver funds to available balance as part of an internal settlement. */
    /** @param executionId execution causing the credit @param walletId recipient wallet @param amountSats receiver amount in satoshis */
    void creditAvailable(PaymentExecutionId executionId, UUID walletId, long amountSats);

    /** Releases a previously reserved source debit when a payment is cancelled. */
    /** @param executionId execution whose reservation is being released @param walletId source wallet @param amountSats amount to return to available balance */
    void releaseReserved(PaymentExecutionId executionId, UUID walletId, long amountSats);
}
