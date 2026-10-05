package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;
import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.SettleInternalPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRoutingResult;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.util.Map;
import java.util.Objects;

/** Route only inside the owning submit; this service persists work but never dispatches a rail. */
public final class RouteLockedPaymentService {
    /** Locks and reads the execution that has passed quorum and funds reservation. */
    private final PaymentRoutingStatePort state;
    /** Settles internal transfers without creating an external-rail command. */
    private final SettleInternalPaymentUseCase internal;
    /** Persists external work to the transactional execution outbox. */
    private final ExecutionCommandStore commands;
    /** Records and validates the transition from LOCKED to the routing outcome. */
    private final PaymentExecutionLifecycleUseCase lifecycle;
    /** Writes the payment statement entry within the surrounding transaction. */
    private final PaymentStatementPort statements;
    /** Schedules initiated-payment notification effects. */
    private final PaymentInitiatedNotificationPort notifications;
    /** Notifies the vault subsystem about an outbound payment intent. */
    private final PaymentVaultIntentPort vault;

    /** Creates the routing service with transaction state and required settlement/outbox ports. */
    /** @param state locked execution state port @param internal internal transfer settlement use case @param commands transactional external execution command store @param lifecycle execution state transition use case @param statements payment statement writer @param notifications initiation notification port @param vault outbound vault intent port */
    public RouteLockedPaymentService(PaymentRoutingStatePort state, SettleInternalPaymentUseCase internal,
            ExecutionCommandStore commands, PaymentExecutionLifecycleUseCase lifecycle, PaymentStatementPort statements,
            PaymentInitiatedNotificationPort notifications, PaymentVaultIntentPort vault) {
        this.state = state; this.internal = internal; this.commands = commands; this.lifecycle = lifecycle;
        this.statements = statements; this.notifications = notifications; this.vault = vault;
    }

    /**
     * Settles INTERNAL payments directly; for other rails, validates authorized references,
     * enqueues external work, marks EXECUTING, flushes the execution row, and records associated
     * statement, notification, and vault effects. The method never calls the rail itself.
     * @param command routing inputs produced by the authorized submission
     * @return resulting execution, selected rail, and outbound outbox identifier when applicable
     */
    public PaymentRoutingResult route(RouteLockedPaymentCommand command) {
        var payment = state.lockAndLoad(command.userId(), command.executionId());
        payment.requireReadyFor(command.userId(), command.executionId());
        var id = payment.executionId();
        if (payment.rail() == PaymentRail.INTERNAL) {
            var settled = internal.settle(new SettleInternalPaymentCommand(command.userId(), id));
            requireConfirmed(settled, id, ExecutionStatus.SETTLED);
            return new PaymentRoutingResult(settled, payment.rail(), null);
        }
        if (!Objects.equals(clean(command.externalReference()), clean(payment.externalReference()))
                || !Objects.equals(clean(command.memo()), clean(payment.memo()))) {
            throw new IllegalArgumentException("Routing references do not match the authorized payment intent.");
        }
        var outboxId = commands.enqueue(new ScheduleExternalExecutionCommand(id, payment.idempotencyKey(), payment.userId(),
                payment.rail(), payment.direction(), payment.sourceWalletId(), payment.destinationWalletId(),
                payment.receiverAmountSats(), payment.networkFeeSats(), payment.totalDebitSats(),
                command.externalReference(), command.memo(), payment.proposalHash(),
                command.feeRateSatPerVbyte(), command.feeTargetBlocks()));
        if (outboxId == null) { throw new IllegalStateException("External execution command was not recorded."); }
        var executing = lifecycle.transition(id, ExecutionStatus.EXECUTING, "KFE_TRANSACTION_EXECUTING",
                Map.of("proposalHash", payment.proposalHash(), "rail", payment.rail().name()));
        requireConfirmed(executing, id, ExecutionStatus.EXECUTING);
        // Flush the execution row before the statement FK is written, preserving the existing order.
        state.flush(payment.userId(), id);
        statements.record(new RecordPaymentStatementCommand(payment.userId(), id, payment.statementWalletId(), command.memo(), false));
        notifications.initiated(payment.userId(), id, payment.statementWalletId(), payment.rail(), payment.grossAmountSats());
        vault.notifyOutbound(id, payment.rail(), payment.direction(), payment.externalReference(), payment.grossAmountSats());
        return new PaymentRoutingResult(executing, payment.rail(),
                payment.direction() == PaymentDirection.OUTBOUND ? outboxId : null);
    }

    /** Confirms that the lifecycle adapter recorded the expected transition from LOCKED. */
    /** @param event lifecycle transition result @param id expected execution identifier @param target expected resulting state @throws IllegalStateException when the event does not confirm the transition */
    private static void requireConfirmed(PaymentExecutionStatusChanged event, PaymentExecutionId id, ExecutionStatus target) {
        if (event == null || !id.equals(event.executionId()) || event.previousStatus() != ExecutionStatus.LOCKED
                || event.currentStatus() != target) {
            throw new IllegalStateException("Payment routing transition was not confirmed.");
        }
    }

    /** Trims an optional reference and treats null or blank text as absent. */
    /** @param value input reference @return trimmed nonblank value or null */
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
