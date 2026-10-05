package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetIdempotentPaymentServiceTest {
    private final IdempotencyReservationStore reservations = mock(IdempotencyReservationStore.class);
    private final PaymentIdempotencyQueryPort payments = mock(PaymentIdempotencyQueryPort.class);
    private final GetIdempotentPaymentService service = new GetIdempotentPaymentService(reservations, payments);
    private final IdempotencyKey key = new IdempotencyKey("  original-key  ");
    private final RequestFingerprint fingerprint = new RequestFingerprint("  original-fingerprint  ");
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());

    @Test
    void missingReservationIsTheOnlyNormalEmptyResultAndDoesNotQueryExecutions() {
        when(reservations.find(7L, key)).thenReturn(Optional.empty());

        assertThat(service.find(query())).isEmpty();

        verify(reservations).find(7L, key);
        verifyNoMoreInteractions(reservations);
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus.class)
    void returnsTheCurrentOwnerScopedProjectionForEveryExecutionStateWithoutRepeatingEffects(ExecutionStatus currentStatus) {
        var reservation = IdempotencyReservation.reconstitute(key, fingerprint, id);
        var result = payment(id.value(), currentStatus);
        when(reservations.find(7L, key)).thenReturn(Optional.of(reservation));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.of(result));

        assertThat(service.find(query())).containsSame(result);
        assertThat(result.status()).isEqualTo(currentStatus);

        var order = inOrder(reservations, payments);
        order.verify(reservations).find(7L, key);
        order.verify(payments).findOwnedByIdAndKey(7L, id, key);
        order.verifyNoMoreInteractions();
        assertThat(reservation.completedExecutionId()).isEqualTo(id);
        assertThat(reservation.fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void queriesAreScopedByAuthenticatedOwnerEvenWhenUsersReuseTheSameKeyAndFingerprint() {
        var otherId = new PaymentExecutionId(UUID.randomUUID());
        var first = payment(id.value(), ExecutionStatus.SETTLED);
        var second = payment(otherId.value(), ExecutionStatus.EXECUTING);
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        when(reservations.find(8L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, otherId)));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.of(first));
        when(payments.findOwnedByIdAndKey(8L, otherId, key)).thenReturn(Optional.of(second));

        assertThat(service.find(query())).containsSame(first);
        assertThat(service.find(new GetIdempotentPaymentQuery(8L, key, fingerprint))).containsSame(second);
        verify(payments, never()).findOwnedByIdAndKey(7L, otherId, key);
        verify(payments, never()).findOwnedByIdAndKey(8L, id, key);
    }

    @Test
    void pendingReservationKeepsTheEstablishedRetryableErrorWithoutLookingForAnExecution() {
        var pending = IdempotencyReservation.pending(key, fingerprint);
        when(reservations.find(7L, key)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(PaymentExecutionStillProcessing.class)
                .hasMessage("Transaction is currently being processed. Please retry.");
        assertThat(pending.isPending()).isTrue();
        verifyNoInteractions(payments);
        verify(reservations, never()).reserve(anyLong(), any());
        verify(reservations, never()).complete(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fingerprintConflictIsRejectedBeforePendingOrCompletedExecutionAccess(boolean pending) {
        var conflicting = IdempotencyReservation.reconstitute(key, new RequestFingerprint("different-fingerprint"), pending ? null : id);
        when(reservations.find(7L, key)).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(IdempotencyKeyConflict.class)
                .hasMessage("Idempotency key was reused with a different transaction payload.");
        verifyNoInteractions(payments);
        verify(reservations, never()).reserve(anyLong(), any());
        verify(reservations, never()).complete(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adapterCannotSubstituteADifferentReservationKeyEvenWhenTheFingerprintMatches(boolean pending) {
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(
                new IdempotencyKey("other-key"), fingerprint, pending ? null : id)));

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(payments);
        verify(reservations, never()).reserve(anyLong(), any());
    }

    @Test
    void aBoundExecutionMissingFromTheOwnerAndKeyScopedQueryIsAnErrorNotANewSubmission() {
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Idempotent transaction record is missing.");
        verify(reservations, never()).reserve(anyLong(), any());
        verify(reservations, never()).complete(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adapterCannotReturnAnUnboundOrForeignExecutionIdentity(boolean nullId) {
        var wrong = payment(nullId ? null : UUID.randomUUID(), ExecutionStatus.SETTLED);
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.of(wrong));

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(IllegalStateException.class);
        verify(reservations, never()).reserve(anyLong(), any());
        verify(reservations, never()).complete(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reservation", "execution"})
    void nullPortResultsFailClosedInsteadOfMasqueradingAsMissingReservations(String stage) {
        when(reservations.find(7L, key)).thenReturn(stage.equals("reservation") ? null
                : Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(null);

        assertThatThrownBy(() -> service.find(query())).isInstanceOf(NullPointerException.class);
        if (stage.equals("reservation")) { verifyNoInteractions(payments); }
        verify(reservations, never()).reserve(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reservation", "execution"})
    void propagatesReadFailuresWithoutRetryOrWriteFallback(String stage) {
        var failure = new IllegalStateException("lookup unavailable");
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        if (stage.equals("reservation")) { doThrow(failure).when(reservations).find(7L, key); }
        else { doThrow(failure).when(payments).findOwnedByIdAndKey(7L, id, key); }

        assertThatThrownBy(() -> service.find(query())).isSameAs(failure);
        verify(reservations).find(7L, key);
        if (stage.equals("reservation")) { verifyNoInteractions(payments); }
        else { verify(payments).findOwnedByIdAndKey(7L, id, key); }
        verify(reservations, never()).reserve(anyLong(), any());
        verify(reservations, never()).complete(anyLong(), any(), any());
    }

    @Test
    void nullQueryCannotReadAnything() {
        assertThatThrownBy(() -> service.find(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(reservations, payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-user", "negative-user", "key", "fingerprint"})
    void queryRequiresAuthenticatedIdentityAndBothOpaqueTokens(String invalid) {
        assertThatThrownBy(() -> new GetIdempotentPaymentQuery(invalid.equals("zero-user") ? 0L : invalid.equals("negative-user") ? -1L : 7L,
                invalid.equals("key") ? null : key, invalid.equals("fingerprint") ? null : fingerprint))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void queryDiagnosticsRedactTokensWithoutNormalizingTheirValues() {
        var query = query();
        assertThat(query.idempotencyKey().value()).isEqualTo("  original-key  ");
        assertThat(query.fingerprint().value()).isEqualTo("  original-fingerprint  ");
        assertThat(query.toString()).contains("REDACTED").doesNotContain(key.value(), fingerprint.value());
    }

    private GetIdempotentPaymentQuery query() { return new GetIdempotentPaymentQuery(7L, key, fingerprint); }
    private PaymentExecutionResult payment(UUID executionId, ExecutionStatus status) {
        var result = mock(PaymentExecutionResult.class);
        when(result.id()).thenReturn(executionId);
        when(result.status()).thenReturn(status);
        return result;
    }
}
