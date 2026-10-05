package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelPaymentEffectsServiceTest {
    private static final PaymentExecutionId ID = new PaymentExecutionId(
            UUID.fromString("059f51d9-f08a-4a31-8b36-9244459bcfa1"));
    private static final UUID SOURCE = UUID.fromString("844b70ce-21e8-4a06-9d10-d8e91799d54e");
    private static final UUID DESTINATION = UUID.fromString("818e6e3f-a206-4161-85fc-d1c6d8a293bb");
    private static final long USER_ID = 41L;
    private static final long DEBIT = 5_100L;
    private static final String MESSAGE = "Cancelada pelo participante";

    private final PaymentCancellationStatePort state = mock(PaymentCancellationStatePort.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final PaymentLiquidityPort liquidity = mock(PaymentLiquidityPort.class);
    private final PaymentStatementPort statement = mock(PaymentStatementPort.class);
    private final PaymentCancellationAuditPort audit = mock(PaymentCancellationAuditPort.class);
    private final CancelPaymentEffectsService service =
            new CancelPaymentEffectsService(state, ledger, liquidity, statement, audit);

    @Test
    void rereadsLockedStateThenReleasesBalancesBeforePersistingStatementAndAudit() {
        var previous = outbound(ExecutionStatus.EXECUTING);
        when(state.load(ID)).thenReturn(previous);

        service.cancel(ID, MESSAGE);

        InOrder order = inOrder(state, ledger, liquidity, statement, audit);
        order.verify(state).load(ID);
        order.verify(ledger).releaseReserved(ID, SOURCE, DEBIT);
        order.verify(liquidity).release(ID);
        order.verify(state).markCancelled(previous, MESSAGE);
        order.verify(statement).record(cancelledStatement(SOURCE));
        order.verify(audit).recordCancelled(previous);
        order.verifyNoMoreInteractions();
    }

    @Test
    void rejectsStateBelongingToAnotherExecutionBeforeFinancialEffects() {
        when(state.load(ID)).thenReturn(new PaymentCancellationSnapshot(
                new PaymentExecutionId(UUID.randomUUID()), USER_ID, ExecutionStatus.EXECUTING,
                PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, SOURCE, DESTINATION, DEBIT, null));

        assertThatThrownBy(() -> service.cancel(ID, MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cancellation state does not match requested execution.");

        verify(state).load(ID);
        verifyNoMoreInteractions(state);
        verifyNoInteractions(ledger, liquidity, statement, audit);
    }

    @Test
    void doesNotReuseTheFirstSnapshotWhenCalledAgainAfterTheFence() {
        var pending = outbound(ExecutionStatus.EXECUTING);
        var closed = outbound(ExecutionStatus.CANCELLED);
        when(state.load(ID)).thenReturn(pending, closed);

        service.cancel(ID, MESSAGE);
        service.cancel(ID, MESSAGE);

        verify(state, times(2)).load(ID);
        verify(state).markCancelled(pending, MESSAGE);
        verify(ledger).releaseReserved(ID, SOURCE, DEBIT);
        verify(liquidity).release(ID);
        verify(statement).record(cancelledStatement(SOURCE));
        verify(audit).recordCancelled(pending);
        verifyNoMoreInteractions(state, ledger, liquidity, statement, audit);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "SETTLED", "FAILED", "CANCELLED", "CONFLICTED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED"})
    void closedStateLoadedAfterTheFenceHasNoFurtherEffects(ExecutionStatus status) {
        when(state.load(ID)).thenReturn(outbound(status));

        service.cancel(ID, MESSAGE);

        verify(state).load(ID);
        verifyNoMoreInteractions(state);
        verifyNoInteractions(ledger, liquidity, statement, audit);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "BROADCAST", "CONFIRMING", "REQUIRES_RECONCILIATION", "CONFLICTED_RECONCILING", "REORG_RECONCILIATION"})
    void unresolvedNetworkStateIsRejectedBeforeAnyCancellationEffect(ExecutionStatus status) {
        when(state.load(ID)).thenReturn(outbound(status));

        assertRejectedWithoutEffects();
    }

    @Test
    void executingPaymentWithBroadcastEvidenceIsRejectedBeforeReleasingFunds() {
        when(state.load(ID)).thenReturn(snapshot(
                ExecutionStatus.EXECUTING, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, DEBIT, "broadcast-txid"));

        assertRejectedWithoutEffects();
    }

    @Test
    void inboundPaymentUsesItsDestinationWalletForStatementAndPreservesItForAudit() {
        var inbound = snapshot(ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING, PaymentDirection.INBOUND,
                null, DESTINATION, DEBIT, null);
        when(state.load(ID)).thenReturn(inbound);

        service.cancel(ID, MESSAGE);

        InOrder order = inOrder(state, statement, audit);
        order.verify(state).load(ID);
        order.verify(state).markCancelled(inbound, MESSAGE);
        order.verify(statement).record(cancelledStatement(DESTINATION));
        order.verify(audit).recordCancelled(inbound);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(ledger, liquidity);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"INTENT", "VALIDATING", "QUORUM_SYNC"})
    void preReservationStatesDoNotReleaseReservedBalance(ExecutionStatus status) {
        var previous = outbound(status);
        when(state.load(ID)).thenReturn(previous);

        service.cancel(ID, MESSAGE);

        verifyNoInteractions(ledger);
        verify(liquidity).release(ID);
        verify(state).markCancelled(previous, MESSAGE);
        verify(statement).record(cancelledStatement(SOURCE));
        verify(audit).recordCancelled(previous);
    }

    @ParameterizedTest
    @MethodSource("paymentsWithoutReleasableBalance")
    void zeroDebitOrMissingSourceDoesNotReleaseBalance(PaymentCancellationSnapshot previous) {
        when(state.load(ID)).thenReturn(previous);

        service.cancel(ID, MESSAGE);

        verifyNoInteractions(ledger);
        verify(liquidity).release(ID);
        verify(state).markCancelled(previous, MESSAGE);
        verify(statement).record(cancelledStatement(previous.statementWalletId()));
        verify(audit).recordCancelled(previous);
    }

    static Stream<PaymentCancellationSnapshot> paymentsWithoutReleasableBalance() {
        return Stream.of(
                snapshot(ExecutionStatus.LOCKED, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                        SOURCE, DESTINATION, 0L, null),
                snapshot(ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                        null, DESTINATION, DEBIT, null));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRail.class, names = {"INTERNAL", "ONCHAIN"})
    void otherRailsReleaseReservedBalanceButNeverLightningLiquidity(PaymentRail rail) {
        var previous = snapshot(ExecutionStatus.LOCKED, rail, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, DEBIT, null);
        when(state.load(ID)).thenReturn(previous);

        service.cancel(ID, MESSAGE);

        verify(ledger).releaseReserved(ID, SOURCE, DEBIT);
        verifyNoInteractions(liquidity);
        verify(state).markCancelled(previous, MESSAGE);
        verify(statement).record(cancelledStatement(SOURCE));
        verify(audit).recordCancelled(previous);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentDirection.class, names = {"INBOUND", "INTERNAL"})
    void nonOutboundLightningDoesNotReleaseOutboundLiquidity(PaymentDirection direction) {
        var previous = snapshot(ExecutionStatus.INTENT, PaymentRail.LIGHTNING, direction,
                null, DESTINATION, 0L, null);
        when(state.load(ID)).thenReturn(previous);

        service.cancel(ID, MESSAGE);

        verifyNoInteractions(ledger, liquidity);
        verify(state).markCancelled(previous, MESSAGE);
        verify(statement).record(cancelledStatement(DESTINATION));
        verify(audit).recordCancelled(previous);
    }

    @ParameterizedTest
    @EnumSource(FailingStep.class)
    void anyFailedStepPropagatesAndPreventsAllSubsequentEffects(FailingStep step) {
        var previous = outbound(ExecutionStatus.EXECUTING);
        var failure = new IllegalStateException("failure in " + step);
        when(state.load(ID)).thenReturn(previous);
        switch (step) {
            case LOAD -> when(state.load(ID)).thenThrow(failure);
            case RESERVE -> doThrow(failure).when(ledger).releaseReserved(ID, SOURCE, DEBIT);
            case LIQUIDITY -> doThrow(failure).when(liquidity).release(ID);
            case MARK -> doThrow(failure).when(state).markCancelled(previous, MESSAGE);
            case STATEMENT -> doThrow(failure).when(statement).record(cancelledStatement(SOURCE));
            case AUDIT -> doThrow(failure).when(audit).recordCancelled(previous);
        }

        assertThatThrownBy(() -> service.cancel(ID, MESSAGE)).isSameAs(failure);

        InOrder order = inOrder(state, ledger, liquidity, statement, audit);
        order.verify(state).load(ID);
        if (step.ordinal() >= FailingStep.RESERVE.ordinal()) {
            order.verify(ledger).releaseReserved(ID, SOURCE, DEBIT);
        }
        if (step.ordinal() >= FailingStep.LIQUIDITY.ordinal()) {
            order.verify(liquidity).release(ID);
        }
        if (step.ordinal() >= FailingStep.MARK.ordinal()) {
            order.verify(state).markCancelled(previous, MESSAGE);
        }
        if (step.ordinal() >= FailingStep.STATEMENT.ordinal()) {
            order.verify(statement).record(cancelledStatement(SOURCE));
        }
        if (step.ordinal() >= FailingStep.AUDIT.ordinal()) {
            order.verify(audit).recordCancelled(previous);
        }
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(state, ledger, liquidity, statement, audit);
    }

    private void assertRejectedWithoutEffects() {
        assertThatThrownBy(() -> service.cancel(ID, MESSAGE)).isInstanceOf(PaymentCancellationRejected.class);
        verify(state).load(ID);
        verifyNoMoreInteractions(state);
        verifyNoInteractions(ledger, liquidity, statement, audit);
    }

    private static RecordPaymentStatementCommand cancelledStatement(UUID walletId) {
        return new RecordPaymentStatementCommand(USER_ID, ID, walletId, null, true);
    }

    private static PaymentCancellationSnapshot outbound(ExecutionStatus status) {
        return snapshot(status, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, DEBIT, null);
    }

    private static PaymentCancellationSnapshot snapshot(
            ExecutionStatus status, PaymentRail rail, PaymentDirection direction,
            UUID source, UUID destination, long debit, String blockchainTransactionId) {
        return new PaymentCancellationSnapshot(
                ID, USER_ID, status, rail, direction, source, destination, debit, blockchainTransactionId);
    }

    enum FailingStep { LOAD, RESERVE, LIQUIDITY, MARK, STATEMENT, AUDIT }
}
