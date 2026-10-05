package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRequestLinkServiceTest {
    private final PaymentRequestLinkStatePort state = mock(PaymentRequestLinkStatePort.class);
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final Instant now = Instant.parse("2026-09-12T12:00:00Z");
    private final PaymentRequestLinkService service = new PaymentRequestLinkService(state, wallets, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID requestId = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void noReferenceDoesNotTouchRequestsOrWallets(String reference) {
        assertThat(service.prepare(command(reference))).isEmpty();
        verifyNoInteractions(state, wallets);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRail.class, names = {"INTERNAL", "LIGHTNING"})
    void acceptsRecipientOwnedRequestOnLedgerWithCanonicalPublicId(PaymentRail rail) {
        var request = snapshot(rail, true, 10_000L, null, null);
        ready(request);
        assertThat(service.prepare(command(" public-id "))).contains(new PreparedPaymentRequestLink(7L, request, 10_000L));
        var order = inOrder(state, wallets);
        order.verify(state).lockByPublicId("public-id");
        order.verify(wallets).findOwnedDestination(8L, destination);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "INTERNAL,INBOUND", "ONCHAIN,INTERNAL"})
    void suppliedPublicIdRequiresInternalExecutionBeforeLookup(PaymentRail rail, PaymentDirection direction) {
        assertThatThrownBy(() -> service.prepare(new PreparePaymentRequestLinkCommand(
                7L, rail, direction, destination, 10_000L, "public-id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentRequestPublicId is only supported for INTERNAL payments.");
        verifyNoInteractions(state, wallets);
    }

    @Test
    void payerMayOwnTheRequestWhenWalletSelectionAllowsRebalancing() {
        var request = snapshot(PaymentRail.INTERNAL, true, 10_000L, null, null);
        ready(request);
        assertThat(service.prepare(new PreparePaymentRequestLinkCommand(8L, PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL, destination, 10_000L, "public-id")))
                .contains(new PreparedPaymentRequestLink(8L, request, 10_000L));
    }

    @Test
    void acceptsOpenAmountRequestWithoutReplacingPayersAmount() {
        var request = snapshot(PaymentRail.INTERNAL, true, null, null, null);
        ready(request);
        assertThat(service.prepare(command("public-id")).orElseThrow().amountSats()).isEqualTo(10_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 1L})
    void expirationIsInclusiveAtNowAndCheckedAtAcceptance(long offset) {
        ready(snapshot(PaymentRail.INTERNAL, true, 10_000L, now.plusSeconds(offset), null));
        if (offset <= 0L) {
            assertThatThrownBy(() -> service.prepare(command("public-id")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("KFE payment request has expired.");
            verifyNoInteractions(wallets);
        } else {
            assertThat(service.prepare(command("public-id"))).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rail", "closed", "paid-link", "amount", "destination", "public-id"})
    void rejectsMismatchedOrIneligibleRequestBeforeWalletReads(String invalid) {
        var request = new PaymentRequestLinkSnapshot(requestId, invalid.equals("public-id") ? "another" : "public-id",
                8L, invalid.equals("destination") ? UUID.randomUUID() : destination,
                invalid.equals("rail") ? PaymentRail.ONCHAIN : PaymentRail.INTERNAL, !invalid.equals("closed"),
                invalid.equals("amount") ? 9999L : 10_000L, null,
                invalid.equals("paid-link") ? UUID.randomUUID() : null);
        when(state.lockByPublicId("public-id")).thenReturn(request);
        assertThatThrownBy(() -> service.prepare(command("public-id"))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(wallets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner"})
    void recipientIdentityComesFromPersistedWalletNotPayerOrReference(String invalid) {
        ready(snapshot(PaymentRail.INTERNAL, true, 10_000L, null, null));
        when(wallets.findOwnedDestination(8L, destination)).thenReturn(invalid.equals("missing") ? Optional.empty()
                : Optional.of(new PaymentWalletSnapshot(invalid.equals("id") ? UUID.randomUUID() : destination,
                        invalid.equals("owner") ? 7L : 8L, true, false, true)));
        assertThatThrownBy(() -> service.prepare(command("public-id")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request recipient wallet not found.");
    }

    @Test
    void completesByLockingRecipientRequestThenPayerExecutionThenWritingPaid() {
        var accepted = accepted();
        readyCompletion(accepted);
        service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId));
        var order = inOrder(state);
        order.verify(state).lockById(8L, requestId);
        order.verify(state).lockExecution(7L, executionId);
        order.verify(state).markPaid(accepted.request(), executionId);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(wallets);
    }

    @Test
    void acceptedRequestCanFinishAfterExpiryWithoutCheckingClockAgain() {
        var accepted = new PreparedPaymentRequestLink(7L,
                snapshot(PaymentRail.LIGHTNING, true, 10_000L, now.minusSeconds(1), null), 10_000L);
        readyCompletion(accepted);
        service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId));
        verify(state).markPaid(accepted.request(), executionId);
    }

    @Test
    void replayingTheSameCompletionIsIdempotentWithoutRelockingExecution() {
        var accepted = accepted();
        var paid = snapshot(PaymentRail.INTERNAL, false, 10_000L, null, executionId.value());
        when(state.lockById(8L, requestId)).thenReturn(paid);

        service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId));

        verify(state, never()).lockExecution(anyLong(), any());
        verify(state, never()).markPaid(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "paid", "wallet", "owner", "amount", "expiry", "public-id", "rail"})
    void requestChangedAfterAcceptanceCannotAttachAnyExecution(String changed) {
        var accepted = accepted();
        var current = new PaymentRequestLinkSnapshot(requestId, changed.equals("public-id") ? "changed" : "public-id",
                changed.equals("owner") ? 9L : 8L, changed.equals("wallet") ? UUID.randomUUID() : destination,
                changed.equals("rail") ? PaymentRail.LIGHTNING : PaymentRail.INTERNAL,
                !changed.equals("closed"), changed.equals("amount") ? 9999L : 10_000L,
                changed.equals("expiry") ? now.plusSeconds(1) : null,
                changed.equals("paid") ? UUID.randomUUID() : null);
        when(state.lockById(8L, requestId)).thenReturn(current);
        assertThatThrownBy(() -> service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                .isInstanceOf(IllegalStateException.class);
        verify(state, never()).lockExecution(anyLong(), any());
        verify(state, never()).markPaid(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "SETTLED", mode = EnumSource.Mode.EXCLUDE)
    void nonSettledExecutionCannotMarkRequestPaid(ExecutionStatus status) {
        var accepted = accepted();
        readyCompletion(accepted);
        when(state.lockExecution(7L, executionId)).thenReturn(execution(status, 7L, executionId,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination, 10_000L, "public-id"));
        assertThatThrownBy(() -> service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KFE payment request transaction must be settled before marking it paid.");
        verify(state, never()).markPaid(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id", "destination", "gross-amount", "rail", "direction", "reference"})
    void settledButUnrelatedExecutionCannotBeAttached(String mismatch) {
        var accepted = accepted();
        readyCompletion(accepted);
        when(state.lockExecution(7L, executionId)).thenReturn(execution(ExecutionStatus.SETTLED,
                mismatch.equals("owner") ? 9L : 7L, mismatch.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : executionId,
                mismatch.equals("rail") ? PaymentRail.LIGHTNING : PaymentRail.INTERNAL,
                mismatch.equals("direction") ? PaymentDirection.OUTBOUND : PaymentDirection.INTERNAL,
                mismatch.equals("destination") ? UUID.randomUUID() : destination,
                mismatch.equals("gross-amount") ? 9910L : 10_000L, mismatch.equals("reference") ? "another" : "public-id"));
        assertThatThrownBy(() -> service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Settled execution does not match the accepted payment request.");
        verify(state, never()).markPaid(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "execution", "write"})
    void persistenceFailuresPropagateAndStopCompletion(String stage) {
        var accepted = accepted();
        readyCompletion(accepted);
        var failure = new IllegalStateException("storage unavailable");
        if (stage.equals("request")) { when(state.lockById(8L, requestId)).thenThrow(failure); }
        if (stage.equals("execution")) { when(state.lockExecution(7L, executionId)).thenThrow(failure); }
        if (stage.equals("write")) { doThrow(failure).when(state).markPaid(accepted.request(), executionId); }
        assertThatThrownBy(() -> service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId))).isSameAs(failure);
        if (!stage.equals("write")) { verify(state, never()).markPaid(any(), any()); }
    }

    @Test
    void forgedAcceptedAmountCannotOverrideFixedRequestAmount() {
        var accepted = new PreparedPaymentRequestLink(7L, snapshot(PaymentRail.INTERNAL, true, 10_000L, null, null), 9999L);
        readyCompletion(accepted);
        when(state.lockExecution(7L, executionId)).thenReturn(execution(ExecutionStatus.SETTLED, 7L, executionId,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination, 9999L, "public-id"));
        assertThatThrownBy(() -> service.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                .isInstanceOf(IllegalStateException.class);
        verify(state, never()).markPaid(any(), any());
    }

    @Test
    void commandDoesNotAllowSwappingPayerAndDiagnosticsDoNotExposeReference() {
        var accepted = accepted();
        assertThatThrownBy(() -> new CompletePaymentRequestLinkCommand(8L, accepted, executionId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(command("private-public-id").toString()).doesNotContain("private-public-id");
        assertThat(accepted.toString()).doesNotContain("public-id");
        assertThat(new CompletePaymentRequestLinkCommand(7L, accepted, executionId).toString()).doesNotContain("public-id");
        assertThat(execution(ExecutionStatus.SETTLED, 7L, executionId, PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL, destination, 10_000L, "secret-reference").toString()).doesNotContain("secret-reference");
    }

    private void ready(PaymentRequestLinkSnapshot request) {
        when(state.lockByPublicId("public-id")).thenReturn(request);
        when(wallets.findOwnedDestination(8L, destination)).thenReturn(Optional.of(new PaymentWalletSnapshot(destination, 8L, true, false, true)));
    }
    private void readyCompletion(PreparedPaymentRequestLink accepted) {
        when(state.lockById(8L, requestId)).thenReturn(accepted.request());
        when(state.lockExecution(7L, executionId)).thenReturn(execution(ExecutionStatus.SETTLED, 7L, executionId,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination, 10_000L, "public-id"));
    }
    private PreparedPaymentRequestLink accepted() { return new PreparedPaymentRequestLink(7L, snapshot(PaymentRail.INTERNAL, true, 10_000L, null, null), 10_000L); }
    private PaymentRequestLinkSnapshot snapshot(PaymentRail rail, boolean open, Long amount, Instant expires, UUID paid) {
        return new PaymentRequestLinkSnapshot(requestId, "public-id", 8L, destination, rail, open, amount, expires, paid);
    }
    private PreparePaymentRequestLinkCommand command(String reference) { return new PreparePaymentRequestLinkCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination, 10_000L, reference); }
    private PaymentRequestLinkExecution execution(ExecutionStatus status, long userId, PaymentExecutionId id,
            PaymentRail rail, PaymentDirection direction, UUID wallet, long amount, String ref) {
        return new PaymentRequestLinkExecution(id, userId, status, rail, direction, wallet, amount, ref);
    }
}
