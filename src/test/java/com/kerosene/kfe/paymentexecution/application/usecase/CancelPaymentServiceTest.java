package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.CancellationEligibilitySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRequestCancellationReference;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelPaymentServiceTest {
    private static final long USER = 42L;
    private static final PaymentExecutionId ID = new PaymentExecutionId(
            UUID.fromString("18fb918d-31c5-4399-aab8-ab8344138020"));
    private static final PaymentExecutionId OTHER = new PaymentExecutionId(
            UUID.fromString("d732ef49-02fd-4358-99a0-5fcd76afac38"));
    private static final UUID REQUEST_ID = UUID.fromString("684dc0f5-de25-4b11-8039-a16f5ae06735");
    private static final UUID WALLET = UUID.fromString("590780ab-7474-4d3b-b08a-901eefb2c098");

    private final PaymentCancellationQueryPort query = mock(PaymentCancellationQueryPort.class);
    private final PaymentCancellationHintsUseCase hints = mock(PaymentCancellationHintsUseCase.class);
    private final PaymentCancellationStatePort state = mock(PaymentCancellationStatePort.class);
    private final PaymentRequestCancellationStatePort requests = mock(PaymentRequestCancellationStatePort.class);
    private final PaymentRequestCancellationLockPort requestLock = mock(PaymentRequestCancellationLockPort.class);
    private final RelatedPaymentLookupPort related = mock(RelatedPaymentLookupPort.class);
    private final PaymentCancellationFencePort fence = mock(PaymentCancellationFencePort.class);
    private final PaymentInvoiceCancellationPort invoices = mock(PaymentInvoiceCancellationPort.class);
    private final PaymentRequestCancellationAuditPort requestAudit = mock(PaymentRequestCancellationAuditPort.class);
    private final CancelPaymentEffectsService effects = mock(CancelPaymentEffectsService.class);
    private final PaymentCancellationNotificationPort notifications = mock(PaymentCancellationNotificationPort.class);
    private final PaymentExecutionQueryRepository results = mock(PaymentExecutionQueryRepository.class);
    private final CancelPaymentService service = new CancelPaymentService(
            query, hints, state, requests, requestLock, related, fence, invoices,
            requestAudit, effects, notifications, results);

    @Test
    void hiddenExecutionIsRejectedBeforeReadingHintsOrTakingLocks() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(hints, state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void recipientVisibilityDoesNotAuthorizeCancellingTheSendersExecution() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER + 1L, null)));

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void queryProjectionForAnotherExecutionIsRejectedBeforeTheFence() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(OTHER, USER, null)));

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void negativePreliminaryEligibilityDoesNotStartCancellation() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER, null)));
        when(hints.hintsFor(USER, ID)).thenReturn(PaymentCancellationHints.none());

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Esta transação não pode ser cancelada (já liquidada, em execução na rede, ou sem invoice aberta).");

        verifyNoInteractions(state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void unknownCancellationTargetFailsClosedBeforeTakingLocks() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER, null)));
        when(hints.hintsFor(USER, ID)).thenReturn(new PaymentCancellationHints(
                true, "UNKNOWN", null, null, null));

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void requestHintWithoutRequestIdFailsClosedBeforeTakingLocks() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER, null)));
        when(hints.hintsFor(USER, ID)).thenReturn(new PaymentCancellationHints(
                true, PaymentCancellationHints.PAYMENT_REQUEST, null, null, "OPEN"));

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(state, requests, requestLock, related, fence,
                invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void singleExecutionAcquiresFenceBeforeRereadingAuthorizationAndApplyingEffects() {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.LOCKED, null));
        var result = mock(PaymentExecutionResult.class);
        when(results.findParticipantVisibleById(USER, ID)).thenReturn(Optional.of(result));

        assertThat(service.cancel(command())).isSameAs(result);

        InOrder order = inOrder(query, hints, fence, state, effects, notifications, results);
        order.verify(query).findParticipantVisible(USER, ID);
        order.verify(hints).hintsFor(USER, ID);
        order.verify(fence).fence(List.of(ID));
        order.verify(state).load(ID);
        order.verify(effects).cancel(eq(ID), anyString());
        order.verify(notifications).publishAfterCommit(USER);
        order.verify(results).findParticipantVisibleById(USER, ID);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(requests, requestLock, related, invoices, requestAudit);
    }

    @Test
    void ownerChangedWhileWaitingForFenceIsRejectedWithoutFinancialEffects() {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(ID, USER + 1L, ExecutionStatus.LOCKED, null));

        assertSingleRejectedWithoutEffects();
    }

    @Test
    void mismatchedLockedSnapshotIsRejectedWithoutFinancialEffects() {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(OTHER, USER, ExecutionStatus.LOCKED, null));

        assertSingleRejectedWithoutEffects();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "SETTLED", "FAILED", "CANCELLED", "BROADCAST", "CONFIRMING", "REQUIRES_RECONCILIATION",
            "CONFLICTED_RECONCILING", "REORG_RECONCILIATION"})
    void nonCancellableStateObservedAfterTheFenceIsRejected(ExecutionStatus status) {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(ID, USER, status, null));

        assertSingleRejectedWithoutEffects();
    }

    @Test
    void networkEvidenceObservedAfterTheFenceIsRejected() {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.EXECUTING, "broadcast-txid"));

        assertSingleRejectedWithoutEffects();
    }

    @Test
    void failedFencePreventsEvenTheFinancialStateRead() {
        prepareSingle();
        var failure = new PaymentCancellationRejected();
        doThrow(failure).when(fence).fence(List.of(ID));

        assertThatThrownBy(() -> service.cancel(command())).isSameAs(failure);

        verifyNoInteractions(state, requests, requestLock, related, invoices, requestAudit,
                effects, notifications, results);
    }

    @Test
    void requestCancellationLocksBeforeDiscoveryAndValidatesEntireBatchBeforeRemoteCall() {
        var request = prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID, OTHER));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.INTENT, null));
        when(state.load(OTHER)).thenReturn(payment(OTHER, USER, ExecutionStatus.LOCKED, null));
        when(invoices.cancel(invoice())).thenReturn(true);

        assertThat(service.cancelPaymentRequest(requestCommand())).isEqualTo(REQUEST_ID);

        InOrder order = inOrder(requestLock, requests, related, fence, state,
                invoices, requestAudit, effects, notifications);
        order.verify(requestLock).lock(USER, REQUEST_ID);
        order.verify(requests).load(USER, REQUEST_ID);
        order.verify(related).findRelated(USER, REQUEST_ID);
        order.verify(fence).fence(List.of(ID, OTHER));
        order.verify(state).load(ID);
        order.verify(state).load(OTHER);
        order.verify(invoices).cancel(invoice());
        order.verify(requests).markCancelled(request);
        order.verify(requestAudit).recordCancelled(request);
        order.verify(effects).cancel(eq(ID), anyString());
        order.verify(effects).cancel(eq(OTHER), anyString());
        order.verify(notifications).publishAfterCommit(USER);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(query, hints, results);
    }

    @Test
    void explicitExecutionIsIncludedWhenAbsentFromRequestDiscoveryAndDuplicatesAreRemoved() {
        prepareLinked();
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.ONCHAIN);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(OTHER, OTHER));
        when(state.load(OTHER)).thenReturn(payment(OTHER, USER, ExecutionStatus.INTENT, null));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.INTENT, null));
        var result = mock(PaymentExecutionResult.class);
        when(results.findParticipantVisibleById(USER, ID)).thenReturn(Optional.of(result));

        assertThat(service.cancel(command())).isSameAs(result);

        verify(fence).fence(List.of(OTHER, ID));
        verify(state).load(OTHER);
        verify(state).load(ID);
        verify(effects).cancel(eq(OTHER), anyString());
        verify(effects).cancel(eq(ID), anyString());
        verify(notifications).publishAfterCommit(USER);
        verifyNoInteractions(invoices);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestCancellationStatus.class, names = {
            "PAID", "HIDDEN", "CANCELLED", "FAILED"})
    void directCancellationOfTerminalRequestIsAnIdempotentNoOp(PaymentRequestCancellationStatus status) {
        prepareRequest(status, PaymentRail.LIGHTNING);

        assertThat(service.cancelPaymentRequest(requestCommand())).isEqualTo(REQUEST_ID);

        verify(requestLock).lock(USER, REQUEST_ID);
        verify(requests).load(USER, REQUEST_ID);
        verifyNoMoreInteractions(requests);
        verifyNoInteractions(query, hints, related, fence, state, invoices,
                requestAudit, effects, notifications, results);
    }

    @Test
    void terminalRequestCannotAuthorizeCancellingAnExplicitExecution() {
        prepareLinked();
        prepareRequest(PaymentRequestCancellationStatus.PAID, PaymentRail.LIGHTNING);

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(related, fence, state, invoices, requestAudit, effects, notifications, results);
        verify(requests, never()).markCancelled(any());
    }

    @Test
    void closedExplicitExecutionIsRejectedEvenThoughClosedRelatedExecutionsAreAllowed() {
        prepareLinked();
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(OTHER));
        when(state.load(OTHER)).thenReturn(payment(OTHER, USER, ExecutionStatus.INTENT, null));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.CANCELLED, null));

        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);

        verify(fence).fence(List.of(OTHER, ID));
        verify(requests, never()).markCancelled(any());
        verifyNoInteractions(invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void foreignRequestSnapshotIsRejectedEvenAfterUserScopedLock() {
        when(requests.load(USER, REQUEST_ID)).thenReturn(request(
                REQUEST_ID, USER + 1L, PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING));

        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand()))
                .isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(related, fence, state, invoices, requestAudit, effects, notifications, results);
        verify(requests, never()).markCancelled(any());
    }

    @Test
    void requestSnapshotForAnotherIdIsRejectedEvenAfterUserScopedLock() {
        when(requests.load(USER, REQUEST_ID)).thenReturn(request(
                UUID.randomUUID(), USER, PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING));

        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand()))
                .isInstanceOf(PaymentCancellationRejected.class);

        verifyNoInteractions(related, fence, state, invoices, requestAudit, effects, notifications, results);
        verify(requests, never()).markCancelled(any());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "SETTLED", "BROADCAST", "CONFIRMING", "REQUIRES_RECONCILIATION",
            "CONFLICTED_RECONCILING", "REORG_RECONCILIATION"})
    void unsafeLastRelatedExecutionPreventsRemoteCallAndEveryFinancialWrite(ExecutionStatus status) {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID, OTHER));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.LOCKED, null));
        when(state.load(OTHER)).thenReturn(payment(OTHER, USER, status, null));

        assertBatchRejectedWithoutEffects();

        verify(state).load(ID);
        verify(state).load(OTHER);
    }

    @Test
    void foreignRelatedExecutionPreventsRemoteCallAndEveryFinancialWrite() {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID, OTHER));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.LOCKED, null));
        when(state.load(OTHER)).thenReturn(payment(OTHER, USER + 1L, ExecutionStatus.LOCKED, null));

        assertBatchRejectedWithoutEffects();
    }

    @Test
    void mismatchedRelatedSnapshotPreventsRemoteCallAndEveryFinancialWrite() {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID));
        when(state.load(ID)).thenReturn(payment(OTHER, USER, ExecutionStatus.LOCKED, null));

        assertBatchRejectedWithoutEffects();
    }

    @Test
    void relatedNetworkEvidencePreventsRemoteCallAndEveryFinancialWrite() {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.EXECUTING, "broadcast-txid"));

        assertBatchRejectedWithoutEffects();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "FAILED", "CANCELLED", "CONFLICTED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED"})
    void alreadyClosedRelatedExecutionDoesNotRepeatFinancialEffects(ExecutionStatus status) {
        var request = prepareRequest(PaymentRequestCancellationStatus.EXPIRED, PaymentRail.ONCHAIN);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID));
        when(state.load(ID)).thenReturn(payment(ID, USER, status, null));

        assertThat(service.cancelPaymentRequest(requestCommand())).isEqualTo(REQUEST_ID);

        verify(requests).markCancelled(request);
        verify(requestAudit).recordCancelled(request);
        verify(notifications).publishAfterCommit(USER);
        verifyNoInteractions(invoices, effects);
    }

    @Test
    void lightningProviderMustConfirmBeforeLocalRequestOrExecutionChanges() {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.INTENT, null));
        when(invoices.cancel(invoice())).thenReturn(false);

        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Não foi possível confirmar o cancelamento da invoice Lightning.");

        verify(requests, never()).markCancelled(any());
        verifyNoInteractions(requestAudit, effects, notifications, results);
    }

    @Test
    void invoiceOnlyRequestCanBeCancelledWithAnEmptyExecutionBatch() {
        var request = prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of());
        when(invoices.cancel(invoice())).thenReturn(true);

        assertThat(service.cancelPaymentRequest(requestCommand())).isEqualTo(REQUEST_ID);

        InOrder order = inOrder(fence, invoices, requests, requestAudit, notifications);
        order.verify(requests).load(USER, REQUEST_ID);
        order.verify(fence).fence(List.of());
        order.verify(invoices).cancel(invoice());
        order.verify(requests).markCancelled(request);
        order.verify(requestAudit).recordCancelled(request);
        order.verify(notifications).publishAfterCommit(USER);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(state, effects, results);
    }

    @Test
    void lightningProviderExceptionIsPreservedAndPreventsLocalChanges() {
        prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.LIGHTNING);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of());
        var remoteFailure = new IllegalStateException("remote provider unavailable");
        when(invoices.cancel(invoice())).thenThrow(remoteFailure);

        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Não foi possível confirmar o cancelamento da invoice Lightning.")
                .hasCause(remoteFailure);

        verify(requests, never()).markCancelled(any());
        verifyNoInteractions(requestAudit, effects, notifications, results);
    }

    @Test
    void requestWriteFailureDoesNotEmitAuditFinancialEffectsOrNotification() {
        var request = prepareRequest(PaymentRequestCancellationStatus.OPEN, PaymentRail.ONCHAIN);
        when(related.findRelated(USER, REQUEST_ID)).thenReturn(List.of(ID));
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.INTENT, null));
        var failure = new IllegalStateException("snapshot changed");
        doThrow(failure).when(requests).markCancelled(request);

        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand())).isSameAs(failure);

        verifyNoInteractions(invoices, requestAudit, effects, notifications, results);
    }

    @Test
    void financialEffectFailurePropagatesWithoutPublishingSuccess() {
        prepareSingle();
        when(state.load(ID)).thenReturn(payment(ID, USER, ExecutionStatus.LOCKED, null));
        var failure = new IllegalStateException("ledger unavailable");
        doThrow(failure).when(effects).cancel(eq(ID), anyString());

        assertThatThrownBy(() -> service.cancel(command())).isSameAs(failure);

        verifyNoInteractions(notifications, results);
    }

    private void assertSingleRejectedWithoutEffects() {
        assertThatThrownBy(() -> service.cancel(command())).isInstanceOf(PaymentCancellationRejected.class);
        verify(fence).fence(List.of(ID));
        verify(state).load(ID);
        verifyNoMoreInteractions(state);
        verifyNoInteractions(requests, requestLock, related, invoices, requestAudit, effects, notifications, results);
    }

    private void assertBatchRejectedWithoutEffects() {
        assertThatThrownBy(() -> service.cancelPaymentRequest(requestCommand()))
                .isInstanceOf(PaymentCancellationRejected.class);
        verify(requests, never()).markCancelled(any());
        verifyNoInteractions(invoices, requestAudit, effects, notifications, results);
    }

    private void prepareSingle() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER, null)));
        when(hints.hintsFor(USER, ID)).thenReturn(new PaymentCancellationHints(
                true, PaymentCancellationHints.TRANSACTION, null, null, null));
    }

    private void prepareLinked() {
        when(query.findParticipantVisible(USER, ID)).thenReturn(Optional.of(eligibility(ID, USER,
                new PaymentRequestCancellationReference(REQUEST_ID, USER, "public-id", PaymentRequestCancellationStatus.OPEN))));
        when(hints.hintsFor(USER, ID)).thenReturn(new PaymentCancellationHints(
                true, PaymentCancellationHints.PAYMENT_REQUEST, REQUEST_ID, "public-id", "OPEN"));
    }

    private PaymentRequestCancellationSnapshot prepareRequest(
            PaymentRequestCancellationStatus status, PaymentRail rail) {
        var snapshot = request(REQUEST_ID, USER, status, rail);
        when(requests.load(USER, REQUEST_ID)).thenReturn(snapshot);
        return snapshot;
    }

    private static CancellationEligibilitySnapshot eligibility(
            PaymentExecutionId id, long owner, PaymentRequestCancellationReference request) {
        return new CancellationEligibilitySnapshot(id, owner, ExecutionStatus.INTENT, null, request);
    }

    private static PaymentCancellationSnapshot payment(
            PaymentExecutionId id, long owner, ExecutionStatus status, String txid) {
        return new PaymentCancellationSnapshot(
                id, owner, status, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, WALLET, null, 1_000L, txid);
    }

    private static PaymentRequestCancellationSnapshot request(
            UUID id, long owner, PaymentRequestCancellationStatus status, PaymentRail rail) {
        return new PaymentRequestCancellationSnapshot(
                id, owner, WALLET, "public-id", status, rail, "payment-hash", "provider-ref", "ln-invoice", null);
    }

    private static CancelPaymentCommand command() {
        return new CancelPaymentCommand(USER, ID);
    }

    private static CancelPaymentRequestCommand requestCommand() {
        return new CancelPaymentRequestCommand(USER, REQUEST_ID);
    }

    private static CancelPaymentInvoiceCommand invoice() {
        return new CancelPaymentInvoiceCommand(USER, "payment-hash", "provider-ref", "ln-invoice");
    }
}
