package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompletePaymentSubmissionServiceTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final IdempotencyKey key = new IdempotencyKey(" opaque-key ");
    private final RequestFingerprint fingerprint = new RequestFingerprint(" opaque-fingerprint ");
    private final UUID destination = UUID.randomUUID();
    private final PaymentSubmissionCompletionPort state = mock(PaymentSubmissionCompletionPort.class);
    private final IdempotencyReservationStore reservations = mock(IdempotencyReservationStore.class);
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final PaymentSubmissionDashboardPort dashboards = mock(PaymentSubmissionDashboardPort.class);
    private final CompletePaymentSubmissionService service = new CompletePaymentSubmissionService(state, reservations, wallets, dashboards);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL,SETTLED", "ONCHAIN,OUTBOUND,EXECUTING", "LIGHTNING,OUTBOUND,EXECUTING",
            "ONCHAIN,INBOUND,EXECUTING", "LIGHTNING,INBOUND,EXECUTING"})
    void completesTheExactReservationBeforeDashboardSchedulingAndProjection(PaymentRail rail, PaymentDirection direction, ExecutionStatus status) {
        var snapshot = ready(rail, direction, status);
        var result = service.complete(command());
        assertThat(result.id()).isEqualTo(id.value());
        assertThat(result.status()).isEqualTo(status);
        var order = inOrder(state, wallets, reservations, dashboards);
        order.verify(state).lockAndLoad(7L, id);
        if (direction != PaymentDirection.OUTBOUND) { order.verify(wallets).findById(destination); }
        order.verify(reservations).complete(eq(7L), argThat(this::isExpectedReservation), eq(status));
        order.verify(dashboards).publishAfterCommit(7L);
        if (direction == PaymentDirection.INTERNAL) { order.verify(dashboards).publishAfterCommit(8L); }
        order.verify(state).saveAndProject(snapshot);
        order.verifyNoMoreInteractions();
        verify(reservations, never()).find(anyLong(), any());
        verify(reservations, never()).reserve(anyLong(), any());
        if (direction == PaymentDirection.OUTBOUND) { verifyNoInteractions(wallets); }
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL,SETTLED", "ONCHAIN,OUTBOUND,EXECUTING", "LIGHTNING,OUTBOUND,EXECUTING",
            "ONCHAIN,INBOUND,EXECUTING", "LIGHTNING,INBOUND,EXECUTING"})
    void anIdenticalCompletedBindingIsProjectedWithoutSchedulingDuplicateDashboards(PaymentRail rail, PaymentDirection direction, ExecutionStatus status) {
        var snapshot = ready(rail, direction, status);
        when(reservations.complete(anyLong(), any(), any())).thenReturn(false);
        assertThat(service.complete(command()).id()).isEqualTo(id.value());
        verify(reservations).complete(eq(7L), argThat(this::isExpectedReservation), eq(status));
        verify(state).saveAndProject(snapshot);
        verifyNoInteractions(dashboards);
    }

    @Test
    void internalDestinationOwnedByTheSubmittingUserGetsOnlyOneDashboardSchedule() {
        ready(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, ExecutionStatus.SETTLED);
        when(wallets.findById(destination)).thenReturn(Optional.of(wallet(destination, 7L)));
        service.complete(command());
        verify(dashboards).publishAfterCommit(7L);
        verifyNoMoreInteractions(dashboards);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "SETTLED", mode = EnumSource.Mode.EXCLUDE)
    void internalCompletionRejectsEveryUnsettledStatus(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, key, status, PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination));
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservations, wallets, dashboards);
        verify(state, never()).saveAndProject(any());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "EXECUTING", mode = EnumSource.Mode.EXCLUDE)
    void externalCompletionRejectsEveryOtherStatusIncludingLaterSuccessfulStates(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, key, status, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null));
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservations, wallets, dashboards);
        verify(state, never()).saveAndProject(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id", "key", "internal-rail", "internal-direction"})
    void foreignIdentityKeyAndIncoherentRoutesCannotCompleteTheReservation(String invalid) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("owner") ? 8L : 7L, invalid.equals("key") ? new IdempotencyKey("other-key") : key,
                ExecutionStatus.EXECUTING, invalid.equals("internal-rail") ? PaymentRail.INTERNAL : PaymentRail.ONCHAIN,
                invalid.equals("internal-direction") ? PaymentDirection.INTERNAL : PaymentDirection.OUTBOUND, destination));
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(reservations, wallets, dashboards);
        verify(state, never()).saveAndProject(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal-null", "internal-missing", "internal-id", "inbound-null", "inbound-missing", "inbound-id", "inbound-owner"})
    void missingForeignOrMismatchedDestinationCannotCauseCompletionOrNotifications(String invalid) {
        boolean internal = invalid.startsWith("internal");
        var rail = internal ? PaymentRail.INTERNAL : PaymentRail.ONCHAIN;
        var direction = internal ? PaymentDirection.INTERNAL : PaymentDirection.INBOUND;
        var status = internal ? ExecutionStatus.SETTLED : ExecutionStatus.EXECUTING;
        ready(rail, direction, status);
        if (invalid.endsWith("null")) {
            when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, key, status, rail, direction, null));
        } else {
            Optional<PaymentWalletSnapshot> result = invalid.endsWith("missing") ? Optional.empty()
                    : Optional.of(wallet(invalid.endsWith("id") ? UUID.randomUUID() : destination,
                            invalid.endsWith("owner") || internal ? 8L : 7L));
            when(wallets.findById(destination)).thenReturn(result);
        }
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(reservations, dashboards);
        verify(state, never()).saveAndProject(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "id", "status", "rail", "direction"})
    void neverReturnsAnUnconfirmedOrForeignProjection(String invalid) {
        var snapshot = ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, ExecutionStatus.EXECUTING);
        var result = invalid.equals("null") ? null : result(invalid.equals("id") ? UUID.randomUUID() : id.value(),
                invalid.equals("status") ? ExecutionStatus.SETTLED : ExecutionStatus.EXECUTING,
                invalid.equals("rail") ? PaymentRail.LIGHTNING : PaymentRail.ONCHAIN,
                invalid.equals("direction") ? PaymentDirection.INBOUND : PaymentDirection.OUTBOUND);
        when(state.saveAndProject(snapshot)).thenReturn(result);
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(IllegalStateException.class);
        verify(reservations).complete(eq(7L), argThat(this::isExpectedReservation), eq(ExecutionStatus.EXECUTING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "wallet", "reservation", "payer-dashboard", "recipient-dashboard", "projection"})
    void propagatesEveryFailureWithoutExecutingFollowingSteps(String stage) {
        ready(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, ExecutionStatus.SETTLED);
        var failure = new IllegalStateException("completion unavailable");
        switch (stage) {
            case "load" -> doThrow(failure).when(state).lockAndLoad(7L, id);
            case "wallet" -> doThrow(failure).when(wallets).findById(destination);
            case "reservation" -> doThrow(failure).when(reservations).complete(anyLong(), any(), any());
            case "payer-dashboard" -> doThrow(failure).when(dashboards).publishAfterCommit(7L);
            case "recipient-dashboard" -> doThrow(failure).when(dashboards).publishAfterCommit(8L);
            case "projection" -> doThrow(failure).when(state).saveAndProject(any());
        }
        assertThatThrownBy(() -> service.complete(command())).isSameAs(failure);
        if (stage.equals("load")) { verifyNoInteractions(wallets); }
        if (stage.equals("load") || stage.equals("wallet")) { verifyNoInteractions(reservations); }
        if (java.util.List.of("load", "wallet", "reservation").contains(stage)) { verifyNoInteractions(dashboards); }
        if (!stage.equals("projection")) { verify(state, never()).saveAndProject(any()); }
        if (!stage.equals("projection") && !stage.equals("recipient-dashboard")) {
            verify(dashboards, never()).publishAfterCommit(8L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "id", "key", "fingerprint"})
    void commandRequiresAuthenticatedIdentityKeyAndFingerprint(String invalid) {
        assertThatThrownBy(() -> new CompletePaymentSubmissionCommand(invalid.equals("user") ? 0L : 7L,
                invalid.equals("id") ? null : id, invalid.equals("key") ? null : key,
                invalid.equals("fingerprint") ? null : fingerprint)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void diagnosticStringsDoNotExposeIdempotencySecrets() {
        assertThat(command().toString()).contains("REDACTED").doesNotContain(key.value(), fingerprint.value());
        assertThat(snapshot(id, 7L, key, ExecutionStatus.SETTLED, PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination).toString())
                .contains("REDACTED").doesNotContain(key.value());
    }

    @Test
    void aNullCommandCannotLoadOrModifyAnything() {
        assertThatThrownBy(() -> service.complete(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(state, reservations, wallets, dashboards);
    }

    @Test
    void aMissingSnapshotCannotCompleteIdempotencyOrPublish() {
        when(state.lockAndLoad(7L, id)).thenReturn(null);
        assertThatThrownBy(() -> service.complete(command())).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(reservations, wallets, dashboards);
        verify(state, never()).saveAndProject(any());
    }

    private PaymentSubmissionCompletionSnapshot ready(PaymentRail rail, PaymentDirection direction, ExecutionStatus status) {
        var snapshot = snapshot(id, 7L, key, status, rail, direction, destination);
        var response = result(id.value(), status, rail, direction);
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot);
        when(state.saveAndProject(snapshot)).thenReturn(response);
        when(reservations.complete(anyLong(), any(), any())).thenReturn(true);
        when(wallets.findById(destination)).thenReturn(Optional.of(wallet(destination, direction == PaymentDirection.INTERNAL ? 8L : 7L)));
        return snapshot;
    }

    private boolean isExpectedReservation(IdempotencyReservation reservation) {
        return reservation != null && key.equals(reservation.key()) && fingerprint.equals(reservation.fingerprint())
                && id.equals(reservation.completedExecutionId());
    }

    private PaymentExecutionResult result(UUID executionId, ExecutionStatus status, PaymentRail rail, PaymentDirection direction) {
        var result = mock(PaymentExecutionResult.class);
        when(result.id()).thenReturn(executionId);
        when(result.status()).thenReturn(status);
        when(result.rail()).thenReturn(rail);
        when(result.direction()).thenReturn(direction);
        return result;
    }

    private PaymentSubmissionCompletionSnapshot snapshot(PaymentExecutionId executionId, long userId, IdempotencyKey idempotencyKey,
            ExecutionStatus status, PaymentRail rail, PaymentDirection direction, UUID destinationWalletId) {
        return new PaymentSubmissionCompletionSnapshot(executionId, userId, idempotencyKey, status, rail, direction, destinationWalletId);
    }

    private PaymentWalletSnapshot wallet(UUID walletId, long userId) { return new PaymentWalletSnapshot(walletId, userId, true, false, true); }
    private CompletePaymentSubmissionCommand command() { return new CompletePaymentSubmissionCommand(7L, id, key, fingerprint); }
}
