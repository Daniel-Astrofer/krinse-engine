package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import java.util.Map;

/** Pure reservation orchestration; ledger, capacity and LOCKED share the owning submit transaction. */
public final class ReservePaymentFundsService {
    /** Locks and reloads the prepared execution state. */
    private final PaymentFundsReservationStatePort state;
    /** Acquires and validates ownership of the source wallet when a reserve is required. */
    private final PaymentWalletLookupPort wallets;
    /** Records the ledger debit reservation for the execution. */
    private final PaymentLedgerPort ledger;
    /** Reserves outbound network capacity for rails that require it. */
    private final PaymentLiquidityPort liquidity;
    /** Confirms the transition from QUORUM_SYNC to LOCKED after all required reservations. */
    private final PaymentExecutionLifecycleUseCase lifecycle;

    /** Creates the reservation coordinator with wallet, ledger, liquidity, and lifecycle ports. */
    /** @param state locked reservation state port @param wallets owned wallet lookup port @param ledger ledger reservation port @param liquidity outbound capacity reservation port @param lifecycle execution transition use case */
    public ReservePaymentFundsService(
            PaymentFundsReservationStatePort state, PaymentWalletLookupPort wallets,
            PaymentLedgerPort ledger, PaymentLiquidityPort liquidity, PaymentExecutionLifecycleUseCase lifecycle) {
        this.state = state;
        this.wallets = wallets;
        this.ledger = ledger;
        this.liquidity = liquidity;
        this.lifecycle = lifecycle;
    }

    /**
     * Reserves source funds and required Lightning capacity, then transitions the execution
     * to LOCKED. All effects must participate in the caller's submission transaction.
     * @param command identifies the account and execution to reserve
     * @return confirmed transition event for QUORUM_SYNC to LOCKED
     */
    public PaymentExecutionStatusChanged reserve(ReservePaymentFundsCommand command) {
        var payment = state.lockAndLoad(command.userId(), command.executionId());
        payment.requireReadyFor(command.userId(), command.executionId());
        var id = payment.executionId();
        if (payment.requiresSourceReserve()) {
            var source = wallets.lockOwnedSource(payment.userId(), payment.sourceWalletId())
                    .orElseThrow(() -> new IllegalArgumentException("Source KFE wallet not found."));
            if (!source.id().equals(payment.sourceWalletId()) || source.userId() != payment.userId()) {
                throw new IllegalArgumentException("Source KFE wallet not found.");
            }
            source.requireSpendable("source");
            ledger.reserve(id, source.id(), payment.totalDebitSats());
        }
        if (payment.requiresLightningLiquidity()) {
            liquidity.reserve(id, payment.totalDebitSats());
        }
        var locked = lifecycle.transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED",
                Map.of("proposalHash", payment.proposalHash(), "quorumAckCount", payment.quorumAckCount()));
        if (locked == null || !id.equals(locked.executionId())
                || locked.previousStatus() != ExecutionStatus.QUORUM_SYNC
                || locked.currentStatus() != ExecutionStatus.LOCKED) {
            throw new IllegalStateException("Funds reservation transition was not confirmed.");
        }
        return locked;
    }
}
