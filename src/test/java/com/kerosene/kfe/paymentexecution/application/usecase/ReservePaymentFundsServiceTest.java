package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentFundsReservationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservePaymentFundsServiceTest {

    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID source = UUID.randomUUID();
    private final PaymentFundsReservationStatePort state = mock(PaymentFundsReservationStatePort.class);
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final PaymentLiquidityPort liquidity = mock(PaymentLiquidityPort.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final ReservePaymentFundsService service = new ReservePaymentFundsService(state, wallets, ledger, liquidity, lifecycle);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void preservesReservationAndTransitionOrderForEverySupportedRoute(PaymentRail rail, PaymentDirection direction) {
        var confirmed = ready(rail, direction, 10_000L);

        assertThat(service.reserve(command())).isSameAs(confirmed);

        var order = inOrder(state, wallets, ledger, liquidity, lifecycle);
        order.verify(state).lockAndLoad(7L, id);
        if (direction != PaymentDirection.INBOUND) {
            order.verify(wallets).lockOwnedSource(7L, source);
            order.verify(ledger).reserve(id, source, 10_000L);
        } else {
            verifyNoInteractions(wallets, ledger);
        }
        if (rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND) {
            order.verify(liquidity).reserve(id, 10_000L);
        } else {
            verifyNoInteractions(liquidity);
        }
        order.verify(lifecycle).transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED", auditPayload());
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRail.class, names = {"ONCHAIN", "LIGHTNING"})
    void permitsInboundZeroDebitAndNoSourceWalletWithoutCreatingReserves(PaymentRail rail) {
        ready(rail, PaymentDirection.INBOUND, 0L);
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                rail, PaymentDirection.INBOUND, null, 0L, "proposal", 2));

        assertThat(service.reserve(command()).currentStatus()).isEqualTo(ExecutionStatus.LOCKED);

        verifyNoInteractions(wallets, ledger, liquidity);
        verify(lifecycle).transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED", auditPayload());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "QUORUM_SYNC", mode = EnumSource.Mode.EXCLUDE)
    void rejectsAllOtherExecutionStatesBeforeWalletOrFinancialEffectsIncludingReplay(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, status,
                PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, source, 10_000L, "proposal", 2));

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id"})
    void rejectsSnapshotIdentityMismatchBeforeWalletOrFinancialEffects(String invalid) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(
                invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("owner") ? 8L : 7L, ExecutionStatus.QUORUM_SYNC,
                PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, source, 10_000L, "proposal", 2));

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INBOUND", "INTERNAL,OUTBOUND", "ONCHAIN,INTERNAL", "LIGHTNING,INTERNAL"})
    void rejectsIncoherentInternalRouteBeforeAnyFinancialEffect(PaymentRail rail, PaymentDirection direction) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                rail, direction, source, 10_000L, "proposal", 2));

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-source", "zero-debit", "negative-debit", "null-proposal", "blank-proposal", "negative-quorum"})
    void failsClosedForInvalidReadyStateBeforeAnyWalletOrFinancialEffect(String invalid) {
        assertThatThrownBy(() -> {
            var snapshot = snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                    PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                    invalid.equals("missing-source") ? null : source,
                    invalid.equals("zero-debit") ? 0L : invalid.equals("negative-debit") ? -1L : 10_000L,
                    invalid.equals("null-proposal") ? null : invalid.equals("blank-proposal") ? " \t\n" : "proposal",
                    invalid.equals("negative-quorum") ? -1 : 2);
            when(state.lockAndLoad(7L, id)).thenReturn(snapshot);
            service.reserve(command());
        }).isInstanceOf(invalid.equals("negative-debit") || invalid.equals("negative-quorum")
                ? IllegalArgumentException.class : IllegalStateException.class);

        verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
    }

    @Test
    void rejectsNegativeInboundDebitBeforeAnyFinancialEffect() {
        assertThatThrownBy(() -> {
            var snapshot = snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                    PaymentRail.ONCHAIN, PaymentDirection.INBOUND, null, -1L, "proposal", 2);
            when(state.lockAndLoad(7L, id)).thenReturn(snapshot);
            service.reserve(command());
        }).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "inactive", "watch-only", "not-spendable"})
    void rejectsMissingUnownedOrUnusableSourceWalletBeforeLedgerAndLiquidity(String invalid) {
        ready(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 10_000L);
        when(wallets.lockOwnedSource(7L, source)).thenReturn(invalid.equals("missing") ? Optional.empty()
                : Optional.of(new PaymentWalletSnapshot(invalid.equals("id") ? UUID.randomUUID() : source,
                        invalid.equals("owner") ? 8L : 7L, !invalid.equals("inactive"),
                        invalid.equals("watch-only"), !invalid.equals("not-spendable"))));

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(
                invalid.equals("missing") || invalid.equals("id") || invalid.equals("owner")
                        ? IllegalArgumentException.class : IllegalStateException.class);

        verifyNoInteractions(ledger, liquidity, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "wallet", "ledger", "liquidity", "lifecycle"})
    void propagatesTheSameStageFailureAndDoesNotRunLaterEffects(String stage) {
        ready(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 10_000L);
        var failure = new IllegalStateException("failure at " + stage);
        switch (stage) {
            case "state" -> when(state.lockAndLoad(7L, id)).thenThrow(failure);
            case "wallet" -> when(wallets.lockOwnedSource(7L, source)).thenThrow(failure);
            case "ledger" -> doThrow(failure).when(ledger).reserve(id, source, 10_000L);
            case "liquidity" -> doThrow(failure).when(liquidity).reserve(id, 10_000L);
            case "lifecycle" -> when(lifecycle.transition(any(), any(), any(), any())).thenThrow(failure);
        }

        assertThatThrownBy(() -> service.reserve(command())).isSameAs(failure);

        switch (stage) {
            case "state" -> verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
            case "wallet" -> verifyNoInteractions(ledger, liquidity, lifecycle);
            case "ledger" -> verifyNoInteractions(liquidity, lifecycle);
            case "liquidity" -> verifyNoInteractions(lifecycle);
            case "lifecycle" -> {
                verify(ledger).reserve(id, source, 10_000L);
                verify(liquidity).reserve(id, 10_000L);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "id", "null-id", "previous", "null-previous", "target", "null-target"})
    void rejectsMissingOrInconsistentLifecycleConfirmation(String invalid) {
        ready(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 10_000L);
        var event = invalid.equals("null") ? null : new PaymentExecutionStatusChanged(
                invalid.equals("null-id") ? null : invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("null-previous") ? null : invalid.equals("previous") ? ExecutionStatus.LOCKED : ExecutionStatus.QUORUM_SYNC,
                invalid.equals("null-target") ? null : invalid.equals("target") ? ExecutionStatus.FAILED : ExecutionStatus.LOCKED);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(event);

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(IllegalStateException.class);

        verify(ledger).reserve(id, source, 10_000L);
        verify(liquidity).reserve(id, 10_000L);
        verify(lifecycle).transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED", auditPayload());
    }

    @Test
    void preservesQuorumZeroAndProposalBytesWithoutRecalculatingGateDecisions() {
        ready(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 10_000L);
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, source, 10_000L, "  exact-proposal  ", 0));

        service.reserve(command());

        verify(lifecycle).transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED",
                Map.of("proposalHash", "  exact-proposal  ", "quorumAckCount", 0));
    }

    @Test
    void validatesCommandIdentityBeforeLoadingFinancialState() {
        assertThatThrownBy(() -> new ReservePaymentFundsCommand(0L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservePaymentFundsCommand(7L, null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(state, wallets, ledger, liquidity, lifecycle);
    }

    @Test
    void snapshotDiagnosticStringDoesNotExposeTheProposalHash() {
        var snapshot = snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC, PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND, source, 10_000L, "sensitive-proposal-hash", 2);

        assertThat(snapshot.toString()).doesNotContain("sensitive-proposal-hash");
    }

    private PaymentExecutionStatusChanged ready(PaymentRail rail, PaymentDirection direction, long debit) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.QUORUM_SYNC,
                rail, direction, source, debit, "proposal", 2));
        when(wallets.lockOwnedSource(7L, source)).thenReturn(Optional.of(new PaymentWalletSnapshot(source, 7L, true, false, true)));
        var event = new PaymentExecutionStatusChanged(id, ExecutionStatus.QUORUM_SYNC, ExecutionStatus.LOCKED);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(event);
        return event;
    }

    private static PaymentFundsReservationSnapshot snapshot(PaymentExecutionId id, long owner, ExecutionStatus status,
            PaymentRail rail, PaymentDirection direction, UUID source, long debit, String proposal, int quorum) {
        return new PaymentFundsReservationSnapshot(id, owner, status, rail, direction, source, debit, proposal, quorum);
    }

    private static Map<String, Object> auditPayload() {
        return Map.of("proposalHash", "proposal", "quorumAckCount", 2);
    }

    private ReservePaymentFundsCommand command() {
        return new ReservePaymentFundsCommand(7L, id);
    }
}
