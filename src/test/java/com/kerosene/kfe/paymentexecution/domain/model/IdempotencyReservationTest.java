package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyReservationTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-42-attempt-1");
    private static final RequestFingerprint FINGERPRINT = new RequestFingerprint("request-hash-a");

    @Test
    void acceptsReplayOfTheSameSemanticRequest() {
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);

        reservation.assertSameRequest(new RequestFingerprint("request-hash-a"));
    }

    @Test
    void rejectsReuseOfTheKeyWithAnotherPayload() {
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);

        assertThatThrownBy(() -> reservation.assertSameRequest(new RequestFingerprint("request-hash-b")))
                .isInstanceOf(IdempotencyKeyConflict.class)
                .hasMessage("Idempotency key was reused with a different transaction payload.");
    }

    @Test
    void pendingReservationDoesNotPretendToHaveACompletedPayment() {
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);

        assertThatThrownBy(reservation::completedExecutionId)
                .isInstanceOf(PaymentExecutionStillProcessing.class);
    }

    @Test
    void completionIsIdempotentOnlyForTheSamePaymentExecution() {
        PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);

        reservation.complete(executionId);
        reservation.complete(executionId);

        assertThat(reservation.completedExecutionId()).isEqualTo(executionId);
        assertThatThrownBy(() -> reservation.complete(new PaymentExecutionId(UUID.randomUUID())))
                .isInstanceOf(IdempotencyKeyConflict.class);
    }

    @Test
    void keyEnforcesTheDatabaseBoundaryBeforePersistence() {
        assertThatThrownBy(() -> new IdempotencyKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey("x".repeat(IdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingInspectionDoesNotChangeTheReservationOrHideItsRetryableError() {
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);

        assertThat(reservation.isPending()).isTrue();
        assertThat(reservation.isPending()).isTrue();
        assertThat(reservation.key()).isEqualTo(KEY);
        assertThat(reservation.fingerprint()).isEqualTo(FINGERPRINT);
        assertThatThrownBy(reservation::completedExecutionId).isInstanceOf(PaymentExecutionStillProcessing.class);
    }

    @Test
    void completionChangesPendingInspectionOnlyForTheBoundExecution() {
        PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
        IdempotencyReservation reservation = IdempotencyReservation.pending(KEY, FINGERPRINT);
        reservation.complete(executionId);

        assertThat(reservation.isPending()).isFalse();
        reservation.complete(executionId);
        assertThat(reservation.isPending()).isFalse();
        assertThatThrownBy(() -> reservation.complete(new PaymentExecutionId(UUID.randomUUID())))
                .isInstanceOf(IdempotencyKeyConflict.class);
        assertThat(reservation.isPending()).isFalse();
        assertThat(reservation.completedExecutionId()).isEqualTo(executionId);
    }

    @Test
    void reconstitutedPendingAndCompletedBindingsHaveTheSamePendingSemantics() {
        PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
        assertThat(IdempotencyReservation.reconstitute(KEY, FINGERPRINT, null).isPending()).isTrue();
        IdempotencyReservation completed = IdempotencyReservation.reconstitute(KEY, FINGERPRINT, executionId);
        assertThat(completed.isPending()).isFalse();
        assertThat(completed.completedExecutionId()).isEqualTo(executionId);
    }
}
