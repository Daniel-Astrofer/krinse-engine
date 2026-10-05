package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettleInternalPaymentServiceTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final InternalPaymentSettlementStatePort state = mock(InternalPaymentSettlementStatePort.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final PaymentFeeSettlementPort fees = mock(PaymentFeeSettlementPort.class);
    private final PaymentStatementPort statements = mock(PaymentStatementPort.class);
    private final InternalPaymentNotificationPort notifications = mock(InternalPaymentNotificationPort.class);
    private final SettleInternalPaymentService service = new SettleInternalPaymentService(
            state, ledger, lifecycle, fees, statements, notifications);

    @ParameterizedTest
    @ValueSource(longs = {7L, 8L})
    void preservesAccountingAndParticipantOrder(long recipient) {
        ready(recipient);
        var event = service.settle(command());
        assertThat(event.currentStatus()).isEqualTo(ExecutionStatus.SETTLED);
        var order = inOrder(state, ledger, lifecycle, fees, statements, notifications);
        order.verify(state).lockAndLoad(7L, id);
        order.verify(ledger).settleReservedDebit(id, source, 10_000L);
        order.verify(ledger).creditAvailable(id, destination, 9_910L);
        order.verify(lifecycle).transition(id, ExecutionStatus.SETTLED, "KFE_TRANSACTION_SETTLED", Map.of("rail", "INTERNAL"));
        order.verify(fees).settleFee(id);
        order.verify(statements).record(new RecordPaymentStatementCommand(7L, id, source, null, false));
        order.verify(notifications).sent(7L, id, source, 10_000L);
        if (recipient != 7L) {
            order.verify(statements).record(new RecordPaymentStatementCommand(recipient, id, destination, null, false));
            order.verify(notifications).received(recipient, id, destination, 9_910L);
        }
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "LOCKED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsEveryNonLockedStateBeforeAnyDebitIncludingReplay(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, status, PaymentRail.INTERNAL, PaymentDirection.INTERNAL));
        assertThatThrownBy(() -> service.settle(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ledger, lifecycle, fees, statements, notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id", "rail", "direction", "same-wallet"})
    void rejectsInvalidIdentityOrRouteBeforeAccounting(String invalid) {
        when(state.lockAndLoad(7L, id)).thenReturn(new InternalPaymentSettlementSnapshot(
                invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("owner") ? 8L : 7L, ExecutionStatus.LOCKED,
                invalid.equals("rail") ? PaymentRail.LIGHTNING : PaymentRail.INTERNAL,
                invalid.equals("direction") ? PaymentDirection.OUTBOUND : PaymentDirection.INTERNAL,
                source, invalid.equals("same-wallet") ? source : destination, 8L, 10_000L, 9_910L));
        assertThatThrownBy(() -> service.settle(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ledger, lifecycle, fees, statements, notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "debit", "credit", "transition", "fee", "sender-statement", "sender-notification", "recipient-statement", "recipient-notification"})
    void propagatesFailureWithoutRunningLaterEffects(String stage) {
        ready(8L);
        var failure = new IllegalStateException("failure at " + stage);
        switch (stage) {
            case "state" -> when(state.lockAndLoad(7L, id)).thenThrow(failure);
            case "debit" -> doThrow(failure).when(ledger).settleReservedDebit(id, source, 10_000L);
            case "credit" -> doThrow(failure).when(ledger).creditAvailable(id, destination, 9_910L);
            case "transition" -> when(lifecycle.transition(any(), any(), any(), any())).thenThrow(failure);
            case "fee" -> doThrow(failure).when(fees).settleFee(id);
            case "sender-statement" -> doThrow(failure).when(statements).record(new RecordPaymentStatementCommand(7L, id, source, null, false));
            case "sender-notification" -> doThrow(failure).when(notifications).sent(7L, id, source, 10_000L);
            case "recipient-statement" -> doThrow(failure).when(statements).record(new RecordPaymentStatementCommand(8L, id, destination, null, false));
            case "recipient-notification" -> doThrow(failure).when(notifications).received(8L, id, destination, 9_910L);
        }
        assertThatThrownBy(() -> service.settle(command())).isSameAs(failure);
        switch (stage) {
            case "state" -> verifyNoInteractions(ledger, lifecycle, fees, statements, notifications);
            case "debit" -> { verify(ledger, never()).creditAvailable(any(), any(), anyLong()); verifyNoInteractions(lifecycle, fees, statements, notifications); }
            case "credit" -> verifyNoInteractions(lifecycle, fees, statements, notifications);
            case "transition" -> verifyNoInteractions(fees, statements, notifications);
            case "fee" -> verifyNoInteractions(statements, notifications);
            case "sender-statement" -> verifyNoInteractions(notifications);
            case "sender-notification" -> verify(statements, never()).record(new RecordPaymentStatementCommand(8L, id, destination, null, false));
            case "recipient-statement" -> verify(notifications, never()).received(anyLong(), any(), any(), anyLong());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "id", "previous", "target"})
    void rejectsUnconfirmedLifecycleBeforeFeeOrStatements(String invalid) {
        ready(8L);
        var event = invalid.equals("null") ? null : new PaymentExecutionStatusChanged(
                invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("previous") ? ExecutionStatus.SETTLED : ExecutionStatus.LOCKED,
                invalid.equals("target") ? ExecutionStatus.FAILED : ExecutionStatus.SETTLED);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(event);
        assertThatThrownBy(() -> service.settle(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(fees, statements, notifications);
    }

    @Test
    void validatesCommandAndFinancialSnapshot() {
        assertThatThrownBy(() -> new SettleInternalPaymentCommand(0L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettleInternalPaymentCommand(7L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalPaymentSettlementSnapshot(id, 7L, ExecutionStatus.LOCKED,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination, 8L, 5L, 6L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalPaymentSettlementSnapshot(id, 7L, ExecutionStatus.LOCKED,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination, 8L, 5L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void ready(long recipient) {
        when(state.lockAndLoad(7L, id)).thenReturn(new InternalPaymentSettlementSnapshot(
                id, 7L, ExecutionStatus.LOCKED, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                source, destination, recipient, 10_000L, 9_910L));
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(
                new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.SETTLED));
    }

    private InternalPaymentSettlementSnapshot snapshot(PaymentExecutionId id, long user, ExecutionStatus status,
                                                       PaymentRail rail, PaymentDirection direction) {
        return new InternalPaymentSettlementSnapshot(id, user, status, rail, direction, source, destination, 8L, 10_000L, 9_910L);
    }

    private SettleInternalPaymentCommand command() { return new SettleInternalPaymentCommand(7L, id); }
}
