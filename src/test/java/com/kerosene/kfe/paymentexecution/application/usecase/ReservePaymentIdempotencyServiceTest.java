package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservePaymentIdempotencyServiceTest {
    private final IdempotencyReservationStore reservations = mock(IdempotencyReservationStore.class);
    private final PaymentIdempotencyQueryPort payments = mock(PaymentIdempotencyQueryPort.class);
    private final GetIdempotentPaymentService replay = new GetIdempotentPaymentService(reservations, payments);
    private final ReservePaymentIdempotencyService service = new ReservePaymentIdempotencyService(reservations, replay);
    private final IdempotencyKey key = new IdempotencyKey("  original-key  ");
    private final RequestFingerprint fingerprint = new RequestFingerprint("  original-fingerprint  ");
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());

    @Test
    void insertWinnerReceivesTheOnlyResultThatMayStartFinancialEffects() {
        when(reservations.reserve(eq(7L), any())).thenReturn(true);

        var result = service.reserve(command());

        assertThat(result.reserved()).isTrue();
        assertThat(result.existingPayment()).isNull();
        verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        verifyNoMoreInteractions(reservations);
        verifyNoInteractions(payments);
    }

    @Test
    void insertLoserPerformsAFreshReadAndReplaysTheCurrentExecutionWithoutAnotherInsert() {
        var response = currentPayment();
        when(reservations.reserve(eq(7L), any())).thenReturn(false);
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.of(response));

        var result = service.reserve(command());

        assertThat(result.reserved()).isFalse();
        assertThat(result.existingPayment()).isSameAs(response);
        var order = inOrder(reservations, payments);
        order.verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        order.verify(reservations).find(7L, key);
        order.verify(payments).findOwnedByIdAndKey(7L, id, key);
        order.verifyNoMoreInteractions();
    }

    @Test
    void insertConflictWithoutAVisibleReservationFailsInsteadOfTryingToReserveAgain() {
        when(reservations.reserve(eq(7L), any())).thenReturn(false);
        when(reservations.find(7L, key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(command())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Idempotency conflict detected, but no record found.");
        verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        verify(reservations).find(7L, key);
        verifyNoMoreInteractions(reservations);
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "wrong-key", "wrong-fingerprint", "missing-payment", "wrong-id"})
    void losingInsertCannotConvertInvalidReplayEvidenceIntoANewReservation(String invalid) {
        when(reservations.reserve(eq(7L), any())).thenReturn(false);
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(
                invalid.equals("wrong-key") ? new IdempotencyKey("other-key") : key,
                invalid.equals("wrong-fingerprint") ? new RequestFingerprint("other-fingerprint") : fingerprint,
                invalid.equals("pending") ? null : id)));
        var result = currentPayment();
        if (invalid.equals("wrong-id")) { when(result.id()).thenReturn(UUID.randomUUID()); }
        when(payments.findOwnedByIdAndKey(7L, id, key)).thenReturn(invalid.equals("missing-payment") ? Optional.empty() : Optional.of(result));

        var failure = catchThrowable(() -> service.reserve(command()));
        if (invalid.equals("pending")) { assertThat(failure).isInstanceOf(PaymentExecutionStillProcessing.class); }
        else if (invalid.equals("wrong-fingerprint")) { assertThat(failure).isInstanceOf(IdempotencyKeyConflict.class); }
        else { assertThat(failure).isInstanceOf(IllegalStateException.class); }
        verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        verify(reservations).find(7L, key);
        verify(reservations, never()).complete(anyLong(), any(), any());
        if (java.util.List.of("pending", "wrong-key", "wrong-fingerprint").contains(invalid)) { verifyNoInteractions(payments); }
        else { verify(payments).findOwnedByIdAndKey(7L, id, key); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert", "reservation-read", "execution-read"})
    void propagatesEveryFailureWithoutAnErrorRecoveryInsertOrCompletion(String stage) {
        var failure = new IllegalStateException("idempotency unavailable");
        when(reservations.reserve(eq(7L), any())).thenReturn(false);
        when(reservations.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.reconstitute(key, fingerprint, id)));
        switch (stage) {
            case "insert" -> doThrow(failure).when(reservations).reserve(eq(7L), any());
            case "reservation-read" -> doThrow(failure).when(reservations).find(7L, key);
            case "execution-read" -> doThrow(failure).when(payments).findOwnedByIdAndKey(7L, id, key);
        }

        assertThatThrownBy(() -> service.reserve(command())).isSameAs(failure);
        verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        verify(reservations, never()).complete(anyLong(), any(), any());
        if (stage.equals("insert")) { verify(reservations, never()).find(anyLong(), any()); }
        if (!stage.equals("execution-read")) { verifyNoInteractions(payments); }
    }

    @Test
    void anInvalidNullReplayResultCannotPermitTheLosingInsertToContinue() {
        var brokenReplay = mock(GetIdempotentPaymentService.class);
        when(brokenReplay.find(any())).thenReturn(null);
        when(reservations.reserve(eq(7L), any())).thenReturn(false);

        assertThatThrownBy(() -> new ReservePaymentIdempotencyService(reservations, brokenReplay).reserve(command()))
                .isInstanceOf(NullPointerException.class);
        verify(brokenReplay).find(new GetIdempotentPaymentQuery(7L, key, fingerprint));
        verify(reservations).reserve(eq(7L), argThat(this::isExactPendingReservation));
        verifyNoMoreInteractions(reservations);
    }

    @Test
    void nullCommandCannotAttemptPersistence() {
        assertThatThrownBy(() -> service.reserve(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(reservations, payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-user", "negative-user", "key", "fingerprint"})
    void commandRequiresAuthenticatedOwnerAndBothOpaqueTokens(String invalid) {
        assertThatThrownBy(() -> new ReservePaymentIdempotencyCommand(invalid.equals("zero-user") ? 0L : invalid.equals("negative-user") ? -1L : 7L,
                invalid.equals("key") ? null : key, invalid.equals("fingerprint") ? null : fingerprint))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void resultMustDescribeExactlyOneOfInsertSuccessOrExistingPayment() {
        var payment = currentPayment();
        assertThat(PaymentIdempotencyReservationResult.reservedNew()).isEqualTo(new PaymentIdempotencyReservationResult(true, null));
        assertThat(PaymentIdempotencyReservationResult.replay(payment)).isEqualTo(new PaymentIdempotencyReservationResult(false, payment));
        assertThatThrownBy(() -> new PaymentIdempotencyReservationResult(false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentIdempotencyReservationResult(true, payment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentIdempotencyReservationResult.replay(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandAndResultDiagnosticsDoNotPrintIdempotencyOrFinancialMetadata() {
        var payment = currentPayment();
        when(payment.toString()).thenReturn("sensitive-reference-and-memo");
        assertThat(command().toString()).contains("REDACTED").doesNotContain(key.value(), fingerprint.value());
        assertThat(PaymentIdempotencyReservationResult.replay(payment).toString()).contains("REDACTED")
                .doesNotContain("sensitive-reference-and-memo", key.value(), fingerprint.value());
    }

    private boolean isExactPendingReservation(IdempotencyReservation reservation) {
        return reservation != null && reservation.isPending() && key.equals(reservation.key()) && fingerprint.equals(reservation.fingerprint());
    }
    private ReservePaymentIdempotencyCommand command() { return new ReservePaymentIdempotencyCommand(7L, key, fingerprint); }
    private PaymentExecutionResult currentPayment() {
        var response = mock(PaymentExecutionResult.class);
        when(response.id()).thenReturn(id.value());
        when(response.status()).thenReturn(ExecutionStatus.SETTLED);
        return response;
    }
}
