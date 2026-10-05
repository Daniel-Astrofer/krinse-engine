package com.kerosene.kfe.ledger.adapters.out.paymentexecution;

import com.kerosene.kfe.ledger.application.service.LedgerService;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Implements Payment Execution's ledger port using the extracted Ledger application service.
 * Every operation must join its caller's transaction so execution state and monetary postings
 * commit or roll back together.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerPaymentPortAdapter implements PaymentLedgerPort {
    /** Asset code used by the Bitcoin payment execution flow. */
    private static final String BTC = "BTC";
    /** Application service that enforces ledger invariants and idempotency. */
    private final LedgerService ledger;

    /**
     * Creates the adapter with the ledger application service.
     *
     * @param ledger domain-facing service that applies payment balance transitions
     */
    public LedgerPaymentPortAdapter(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Reserves the payment amount before an outbound execution proceeds.
     *
     * @param executionId stable identity of the payment execution
     * @param walletId source wallet being debited
     * @param amountSats amount to move from available to locked balance
     */
    @Override
    public void reserve(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        ledger.reserve(command(executionId, walletId, amountSats, "reserve"));
    }

    /**
     * Finalizes a debit previously reserved for a completed payment.
     *
     * @param executionId stable identity matching the original reservation
     * @param walletId wallet holding the reservation
     * @param amountSats reserved amount to settle in satoshis
     */
    @Override
    public void settleReservedDebit(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        ledger.settleDebit(command(executionId, walletId, amountSats, "settlement debit"));
    }

    /**
     * Credits available balance using the execution identifier as the idempotency reference.
     *
     * @param executionId stable identity of the crediting execution
     * @param walletId wallet receiving the credit
     * @param amountSats amount to credit in satoshis
     */
    @Override
    public void creditAvailable(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        ledger.credit(command(executionId, walletId, amountSats, "credit"));
    }

    /**
     * Releases a reservation after execution does not consume the held funds.
     *
     * @param executionId stable identity matching the original reservation
     * @param walletId wallet holding the reservation
     * @param amountSats reserved amount to restore to available balance
     */
    @Override
    public void releaseReserved(PaymentExecutionId executionId, UUID walletId, long amountSats) {
        ledger.release(command(executionId, walletId, amountSats, "release reserve"));
    }

    /**
     * Builds the common BTC ledger command from an execution and wallet identity.
     *
     * @param id payment execution identifier used as command and idempotency reference
     * @param walletId wallet whose BTC balance is mutated
     * @param amount positive satoshi amount validated by the Ledger service
     * @param reason human-readable movement description
     * @return ledger command scoped to this execution
     * @throws NullPointerException if execution or wallet identity is absent
     */
    private LedgerService.Command command(PaymentExecutionId id, UUID walletId, long amount, String reason) {
        Objects.requireNonNull(id, "payment execution id is required");
        Objects.requireNonNull(walletId, "wallet id is required");
        return new LedgerService.Command(id.value(), walletId, BTC, amount, reason, id.value(), null);
    }

}
