package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.result.CancellationEligibilitySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRequestCancellationReference;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentCancellationHintsServiceTest {
    private static final long USER_ID = 42L;
    private static final PaymentExecutionId ID = new PaymentExecutionId(
            UUID.fromString("9b346c27-d839-4738-bdb8-3e9154a2f53a"));
    private static final UUID REQUEST_ID = UUID.fromString("2b535597-d8ba-4539-aa43-6e47c6970fc1");
    private static final String REQUEST_PUBLIC_ID = "request-public-id";

    private final PaymentCancellationQueryPort query = mock(PaymentCancellationQueryPort.class);
    private final PaymentCancellationHintsService service = new PaymentCancellationHintsService(query);

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void invalidUserDoesNotReachPersistence(long userId) {
        assertThat(service.hintsFor(userId, ID)).isEqualTo(PaymentCancellationHints.none());
        verifyNoInteractions(query);
    }

    @Test
    void missingExecutionIdDoesNotReachPersistence() {
        assertThat(service.hintsFor(USER_ID, null)).isEqualTo(PaymentCancellationHints.none());
        verifyNoInteractions(query);
    }

    @Test
    void invisibleOrMissingExecutionReturnsNoHints() {
        when(query.findParticipantVisible(USER_ID, ID)).thenReturn(Optional.empty());

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
        verify(query).findParticipantVisible(USER_ID, ID);
        verifyNoMoreInteractions(query);
    }

    @Test
    void aDifferentExecutionSnapshotCannotExposeRequestMetadata() {
        var differentId = new PaymentExecutionId(UUID.fromString("5a67b815-bd23-429c-850d-84390539945e"));
        given(new CancellationEligibilitySnapshot(differentId, USER_ID, ExecutionStatus.INTENT,
                null, request(REQUEST_ID, USER_ID, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @Test
    void aSnapshotWithoutExecutionIdentityCannotExposeRequestMetadata() {
        given(new CancellationEligibilitySnapshot(null, USER_ID, ExecutionStatus.INTENT,
                null, request(REQUEST_ID, USER_ID, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @Test
    void participantVisibilityDoesNotAuthorizeCancellationOrRequestMetadataForAnotherOwner() {
        given(new CancellationEligibilitySnapshot(ID, USER_ID + 1L, ExecutionStatus.INTENT,
                null, request(REQUEST_ID, USER_ID, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @Test
    void requestOwnedByAnotherUserDoesNotExposeMetadataOrFallBackToTransactionHints() {
        given(snapshot(ExecutionStatus.INTENT, null,
                request(REQUEST_ID, USER_ID + 1L, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @Test
    void requestWithoutIdentityDoesNotExposeMetadataOrFallBackToTransactionHints() {
        given(snapshot(ExecutionStatus.INTENT, null,
                request(null, USER_ID, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestCancellationStatus.class, names = {"OPEN", "EXPIRED"})
    void cancellableRequestIncludesItsOwnedMetadataAndRequestTarget(PaymentRequestCancellationStatus status) {
        given(snapshot(ExecutionStatus.INTENT, null, request(REQUEST_ID, USER_ID, status)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                true, PaymentCancellationHints.PAYMENT_REQUEST, REQUEST_ID, REQUEST_PUBLIC_ID, status.name()));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestCancellationStatus.class, names = {"PAID", "HIDDEN", "CANCELLED", "FAILED"})
    void closedRequestPreservesOwnedMetadataWithoutTransactionFallback(PaymentRequestCancellationStatus status) {
        given(snapshot(ExecutionStatus.INTENT, null, request(REQUEST_ID, USER_ID, status)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                false, null, REQUEST_ID, REQUEST_PUBLIC_ID, status.name()));
    }

    @Test
    void requestWithoutStatusPreservesOwnedMetadataButCannotBeCancelled() {
        given(snapshot(ExecutionStatus.INTENT, null, request(REQUEST_ID, USER_ID, null)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                false, null, REQUEST_ID, REQUEST_PUBLIC_ID, null));
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus.class)
    @NullSource
    void openRequestPrecedesExecutionStatusOnlyForPreliminaryHints(ExecutionStatus status) {
        // This legacy UI hint is not action authorization: the locked cancellation fence rechecks execution.
        given(snapshot(status, "observed-network-transaction", request(
                REQUEST_ID, USER_ID, PaymentRequestCancellationStatus.OPEN)));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                true, PaymentCancellationHints.PAYMENT_REQUEST, REQUEST_ID, REQUEST_PUBLIC_ID, "OPEN"));
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"INTENT", "VALIDATING", "QUORUM_SYNC", "LOCKED"})
    void localExecutionStatesUseAggregateEligibilityWithoutARequest(ExecutionStatus status) {
        given(snapshot(status, null, null));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                true, PaymentCancellationHints.TRANSACTION, null, null, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void executingWithoutBlockchainEvidenceIsPreliminarilyEligible(String blockchainTransactionId) {
        given(snapshot(ExecutionStatus.EXECUTING, blockchainTransactionId, null));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(new PaymentCancellationHints(
                true, PaymentCancellationHints.TRANSACTION, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network-txid", " network-txid "})
    void executingWithBlockchainEvidenceDoesNotOfferCancellation(String blockchainTransactionId) {
        given(snapshot(ExecutionStatus.EXECUTING, blockchainTransactionId, null));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"INTENT", "VALIDATING", "QUORUM_SYNC", "LOCKED", "EXECUTING"},
            mode = EnumSource.Mode.EXCLUDE)
    @NullSource
    void unavailableClosedOrNetworkExecutionStatusesAreNotEligibleWithoutARequest(ExecutionStatus status) {
        given(snapshot(status, null, null));

        assertThat(service.hintsFor(USER_ID, ID)).isEqualTo(PaymentCancellationHints.none());
    }

    @Test
    void queryFailuresPropagateForTheCallingAdapterToHandle() {
        var failure = new IllegalStateException("query unavailable");
        when(query.findParticipantVisible(USER_ID, ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.hintsFor(USER_ID, ID)).isSameAs(failure);
        verify(query).findParticipantVisible(USER_ID, ID);
        verifyNoMoreInteractions(query);
    }

    private void given(CancellationEligibilitySnapshot snapshot) {
        when(query.findParticipantVisible(USER_ID, ID)).thenReturn(Optional.of(snapshot));
    }

    private CancellationEligibilitySnapshot snapshot(ExecutionStatus status, String blockchainTransactionId,
                                                    PaymentRequestCancellationReference request) {
        return new CancellationEligibilitySnapshot(ID, USER_ID, status, blockchainTransactionId, request);
    }

    private PaymentRequestCancellationReference request(UUID id, long userId,
                                                       PaymentRequestCancellationStatus status) {
        return new PaymentRequestCancellationReference(id, userId, REQUEST_PUBLIC_ID, status);
    }
}
