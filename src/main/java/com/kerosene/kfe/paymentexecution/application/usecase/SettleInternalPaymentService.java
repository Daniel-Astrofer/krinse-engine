package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentSettlementStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import java.util.Map;

/** Pure orchestration; every effect participates in the authorized submit's transaction. */
public final class SettleInternalPaymentService {
    /** Loads and locks the authorized internal payment state. */
    private final InternalPaymentSettlementStatePort state;
    /** Applies the reserved debit and destination credit to the ledger. */
    private final PaymentLedgerPort ledger;
    /** Confirms the lifecycle transition to SETTLED. */
    private final PaymentExecutionLifecycleUseCase lifecycle;
    /** Settles fee accounting associated with the execution. */
    private final PaymentFeeSettlementPort fees;
    /** Records sender and optional recipient statement entries. */
    private final PaymentStatementPort statements;
    /** Schedules sender and optional recipient notifications. */
    private final InternalPaymentNotificationPort notifications;

    /** Creates the internal settlement coordinator with transactional ports. */
    /** @param state payment state loader and lock @param ledger financial ledger port @param lifecycle execution lifecycle use case @param fees fee settlement port @param statements statement writer @param notifications notification scheduler */
    public SettleInternalPaymentService(
            InternalPaymentSettlementStatePort state, PaymentLedgerPort ledger,
            PaymentExecutionLifecycleUseCase lifecycle, PaymentFeeSettlementPort fees,
            PaymentStatementPort statements, InternalPaymentNotificationPort notifications) {
        this.state = state;
        this.ledger = ledger;
        this.lifecycle = lifecycle;
        this.fees = fees;
        this.statements = statements;
        this.notifications = notifications;
    }

    /**
     * Settles the reserved sender debit and recipient credit, records SETTLED, settles fees,
     * and creates statement/notification effects for each participating account.
     * All writes must share the submit transaction.
     * @param command identifies the authorized internal payment
     * @return confirmed LOCKED-to-SETTLED lifecycle event
     */
    public PaymentExecutionStatusChanged settle(SettleInternalPaymentCommand command) {
        var payment = state.lockAndLoad(command.userId(), command.executionId());
        payment.requireReadyFor(command.userId(), command.executionId());
        var id = payment.executionId();
        ledger.settleReservedDebit(id, payment.sourceWalletId(), payment.totalDebitSats());
        ledger.creditAvailable(id, payment.destinationWalletId(), payment.receiverAmountSats());
        var settled = lifecycle.transition(id, ExecutionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("rail", payment.rail().name()));
        if (settled == null || !id.equals(settled.executionId())
                || settled.previousStatus() != ExecutionStatus.LOCKED
                || settled.currentStatus() != ExecutionStatus.SETTLED) {
            throw new IllegalStateException("Internal settlement transition was not confirmed.");
        }
        fees.settleFee(id);
        statements.record(new RecordPaymentStatementCommand(
                payment.userId(), id, payment.sourceWalletId(), null, false));
        notifications.sent(payment.userId(), id, payment.sourceWalletId(), payment.totalDebitSats());
        if (payment.hasAnotherRecipient()) {
            statements.record(new RecordPaymentStatementCommand(
                    payment.recipientUserId(), id, payment.destinationWalletId(), null, false));
            notifications.received(payment.recipientUserId(), id, payment.destinationWalletId(), payment.receiverAmountSats());
        }
        return settled;
    }
}
