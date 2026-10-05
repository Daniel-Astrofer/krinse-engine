package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteLockedPaymentServiceTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final UUID outbox = UUID.randomUUID();
    private final PaymentRoutingStatePort state = mock(PaymentRoutingStatePort.class);
    private final SettleInternalPaymentUseCase internal = mock(SettleInternalPaymentUseCase.class);
    private final ExecutionCommandStore commands = mock(ExecutionCommandStore.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final PaymentStatementPort statements = mock(PaymentStatementPort.class);
    private final PaymentInitiatedNotificationPort notifications = mock(PaymentInitiatedNotificationPort.class);
    private final PaymentVaultIntentPort vault = mock(PaymentVaultIntentPort.class);
    private final RouteLockedPaymentService service = new RouteLockedPaymentService(state, internal, commands, lifecycle, statements, notifications, vault);

    @Test
    void internalDelegatesOnlySettlementAndReturnsNoDispatchCommand() {
        ready(PaymentRail.INTERNAL, PaymentDirection.INTERNAL);
        var event = new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.SETTLED);
        when(internal.settle(new SettleInternalPaymentCommand(7L, id))).thenReturn(event);
        // Internal request reference can be canonical publicId while raw externalReference is null.
        var result = service.route(new RouteLockedPaymentCommand(7L, id, null, "different raw memo", null, null));
        assertThat(result.transition()).isSameAs(event);
        assertThat(result.rail()).isEqualTo(PaymentRail.INTERNAL);
        assertThat(result.immediateDispatchOutboxId()).isNull();
        var order = inOrder(state, internal);
        order.verify(state).lockAndLoad(7L, id);
        order.verify(internal).settle(new SettleInternalPaymentCommand(7L, id));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(commands, lifecycle, statements, notifications, vault);
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void preservesExternalOrderRawPayloadAndInboundNoImmediateDispatch(PaymentRail rail, PaymentDirection direction) {
        ready(rail, direction);
        var result = service.route(command());
        assertThat(result.transition().currentStatus()).isEqualTo(ExecutionStatus.EXECUTING);
        assertThat(result.rail()).isEqualTo(rail);
        assertThat(result.immediateDispatchOutboxId()).isEqualTo(direction == PaymentDirection.OUTBOUND ? outbox : null);
        UUID statementWallet = direction == PaymentDirection.INBOUND ? destination : source;
        var order = inOrder(state, commands, lifecycle, statements, notifications, vault);
        order.verify(state).lockAndLoad(7L, id);
        order.verify(commands).enqueue(new ScheduleExternalExecutionCommand(id, new IdempotencyKey("  key  "), 7L,
                rail, direction, source, destination, 9_910L, 100L, direction == PaymentDirection.INBOUND ? 0L : 10_100L,
                "  reference  ", "  memo  ", "proposal", 12L, 3));
        order.verify(lifecycle).transition(id, ExecutionStatus.EXECUTING, "KFE_TRANSACTION_EXECUTING",
                Map.of("proposalHash", "proposal", "rail", rail.name()));
        order.verify(state).flush(7L, id);
        order.verify(statements).record(new RecordPaymentStatementCommand(7L, id, statementWallet, "  memo  ", false));
        order.verify(notifications).initiated(7L, id, statementWallet, rail, 10_000L);
        order.verify(vault).notifyOutbound(id, rail, direction, "reference", 10_000L);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(internal);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "LOCKED", mode = EnumSource.Mode.EXCLUDE)
    void refusesAllOtherStatesBeforeEnqueueIncludingExecutingReplay(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, status, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, destination, "proposal"));
        assertThatThrownBy(() -> service.route(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(internal, commands, lifecycle, statements, notifications, vault);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "id", "source", "destination", "rail", "proposal"})
    void deniesInvalidIdentityRouteOrWalletBeforeSideEffects(String invalid) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("user") ? 8L : 7L, ExecutionStatus.LOCKED,
                invalid.equals("rail") ? PaymentRail.ONCHAIN : PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                invalid.equals("source") ? null : source, invalid.equals("destination") ? null : destination,
                invalid.equals("proposal") ? " " : "proposal"));
        assertThatThrownBy(() -> service.route(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(internal, commands, lifecycle, statements, notifications, vault);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "memo"})
    void cannotReplaceAuthorizedExternalReferenceOrMemo(String mismatch) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        assertThatThrownBy(() -> service.route(new RouteLockedPaymentCommand(7L, id,
                mismatch.equals("reference") ? "other-address" : "reference", mismatch.equals("memo") ? "other memo" : "memo", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(commands, lifecycle, statements, notifications, vault);
    }

    @Test
    void nullableOrBlankMetadataPreservesOriginalWireValues() {
        var snapshot = new PaymentRoutingSnapshot(id, 7L, ExecutionStatus.LOCKED, PaymentRail.ONCHAIN, PaymentDirection.INBOUND,
                new IdempotencyKey("key"), null, destination, 10_000L, 9_910L, 0L, 0L, null, null, "proposal");
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot);
        when(commands.enqueue(any())).thenReturn(outbox);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.EXECUTING));
        service.route(new RouteLockedPaymentCommand(7L, id, "  ", null, -1L, 0));
        verify(commands).enqueue(new ScheduleExternalExecutionCommand(id, new IdempotencyKey("key"), 7L, PaymentRail.ONCHAIN,
                PaymentDirection.INBOUND, null, destination, 9_910L, 0L, 0L, "  ", null, "proposal", -1L, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "enqueue", "lifecycle", "flush", "statement", "notification", "vault"})
    void propagatesFailureAndDoesNotRunFollowingSteps(String stage) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        var failure = new IllegalStateException("step unavailable");
        switch (stage) {
            case "state" -> when(state.lockAndLoad(7L, id)).thenThrow(failure);
            case "enqueue" -> when(commands.enqueue(any())).thenThrow(failure);
            case "lifecycle" -> when(lifecycle.transition(any(), any(), any(), any())).thenThrow(failure);
            case "flush" -> doThrow(failure).when(state).flush(7L, id);
            case "statement" -> doThrow(failure).when(statements).record(any());
            case "notification" -> doThrow(failure).when(notifications).initiated(anyLong(), any(), any(), any(), anyLong());
            case "vault" -> doThrow(failure).when(vault).notifyOutbound(any(), any(), any(), any(), anyLong());
        }
        assertThatThrownBy(() -> service.route(command())).isSameAs(failure);
        if (stage.equals("state")) { verifyNoInteractions(commands); }
        if (stage.equals("state") || stage.equals("enqueue")) { verifyNoInteractions(lifecycle); }
        if (!stage.equals("vault")) { verifyNoInteractions(vault); }
        if (!stage.equals("vault") && !stage.equals("notification")) { verifyNoInteractions(notifications); }
        if (java.util.List.of("state", "enqueue", "lifecycle", "flush").contains(stage)) { verifyNoInteractions(statements); }
        verifyNoInteractions(internal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal-null", "internal-id", "internal-previous", "internal-target", "external-null", "external-id", "external-previous", "external-target"})
    void validatesTransitionAcknowledgementBeforeContinuing(String invalid) {
        boolean isInternal = invalid.startsWith("internal");
        ready(isInternal ? PaymentRail.INTERNAL : PaymentRail.ONCHAIN, isInternal ? PaymentDirection.INTERNAL : PaymentDirection.OUTBOUND);
        var target = isInternal ? ExecutionStatus.SETTLED : ExecutionStatus.EXECUTING;
        var event = invalid.endsWith("null") ? null : new PaymentExecutionStatusChanged(
                invalid.endsWith("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.endsWith("previous") ? ExecutionStatus.EXECUTING : ExecutionStatus.LOCKED,
                invalid.endsWith("target") ? ExecutionStatus.FAILED : target);
        if (isInternal) { when(internal.settle(any())).thenReturn(event); }
        else { when(lifecycle.transition(any(), any(), any(), any())).thenReturn(event); }
        assertThatThrownBy(() -> service.route(command())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment routing transition was not confirmed.");
        verify(state, never()).flush(anyLong(), any());
        verifyNoInteractions(statements, notifications, vault);
    }

    @Test
    void nullOutboxIdCannotConfirmExecuting() {
        ready(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND);
        when(commands.enqueue(any())).thenReturn(null);
        assertThatThrownBy(() -> service.route(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(lifecycle, statements, notifications, vault);
    }

    @Test
    void internalFailureIsNotConvertedIntoExternalScheduling() {
        ready(PaymentRail.INTERNAL, PaymentDirection.INTERNAL);
        var failure = new IllegalStateException("internal unavailable");
        when(internal.settle(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.route(command())).isSameAs(failure);
        verifyNoInteractions(commands, lifecycle, statements, notifications, vault);
    }

    @Test
    void diagnosticsRedactPaymentMetadata() {
        assertThat(command().toString()).doesNotContain("  reference  ", "  memo  ");
        assertThat(snapshot(id, 7L, ExecutionStatus.LOCKED, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, destination, "secret-proposal").toString())
                .contains("REDACTED").doesNotContain("secret-proposal", "  key  ", "=reference", "memo");
        var message = new ScheduleExternalExecutionCommand(id, new IdempotencyKey("secret-key"), 7L, PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, source, null, 1L, 0L, 1L, "secret-ref", "secret-memo", "secret-proposal", null, null);
        assertThat(message.toString()).doesNotContain("secret-key", "secret-ref", "secret-memo", "secret-proposal");
    }

    private void ready(PaymentRail rail, PaymentDirection direction) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.LOCKED, rail, direction, source, destination, "proposal"));
        when(commands.enqueue(any())).thenReturn(outbox);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.EXECUTING));
    }
    private PaymentRoutingSnapshot snapshot(PaymentExecutionId executionId, long owner, ExecutionStatus status,
            PaymentRail rail, PaymentDirection direction, UUID src, UUID dest, String proposal) {
        return new PaymentRoutingSnapshot(executionId, owner, status, rail, direction, new IdempotencyKey("  key  "),
                src, dest, 10_000L, 9_910L, 100L, direction == PaymentDirection.INBOUND ? 0L : 10_100L, "reference", "memo", proposal);
    }
    private RouteLockedPaymentCommand command() { return new RouteLockedPaymentCommand(7L, id, "  reference  ", "  memo  ", 12L, 3); }
}
