package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparePaymentSubmissionServiceTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final IdempotencyKey key = new IdempotencyKey("  opaque-key  ");
    private final RequestFingerprint fingerprint = new RequestFingerprint("request-fingerprint");
    private final PaymentWalletSnapshot sourceWallet = new PaymentWalletSnapshot(source, 7L, true, false, true);
    private final PaymentWalletSnapshot destinationWallet = new PaymentWalletSnapshot(destination, 8L, true, false, true);
    private final PaymentSubmissionPricing quote = new PaymentSubmissionPricing(300L,
            new PaymentPricingQuote(10_000L, 9_910L, 100L, 90L, 10_100L, 4),
            new PaymentDisplaySnapshot(BigDecimal.ONE, BigDecimal.TEN, null, null, null, null));
    private final PaymentSubmissionStatePort state = mock(PaymentSubmissionStatePort.class);
    private final PaymentWalletsUseCase wallets = mock(PaymentWalletsUseCase.class);
    private final PreparePaymentPricingUseCase pricing = mock(PreparePaymentPricingUseCase.class);
    private final PaymentProposalHashPort hash = mock(PaymentProposalHashPort.class);
    private final PaymentSettlementGateUseCase gate = mock(PaymentSettlementGateUseCase.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final PaymentSubmissionTelemetryPort telemetry = mock(PaymentSubmissionTelemetryPort.class);
    private final PreparePaymentSubmissionService service = new PreparePaymentSubmissionService(
            state, wallets, pricing, hash, gate, lifecycle, telemetry);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void preservesPreparationOrderAuthoritativeIdentityAndDistinctReserveForEveryRoute(PaymentRail rail, PaymentDirection direction) {
        var snapshot = ready(rail, direction);
        var result = service.prepare(command());

        assertThat(result.transition()).isEqualTo(new PaymentExecutionStatusChanged(id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC));
        assertThat(result.destinationWallet()).isSameAs(destinationWallet);
        var order = inOrder(state, wallets, pricing, telemetry, hash, gate, lifecycle);
        order.verify(state).lockAndLoad(7L, id);
        order.verify(lifecycle).transition(id, ExecutionStatus.VALIDATING, "KFE_TRANSACTION_VALIDATING",
                Map.of("requestHash", fingerprint.value()));
        order.verify(wallets).resolve(new ResolvePaymentWalletsCommand(7L, rail, direction, source, destination, "  reference  "));
        order.verify(pricing).prepare(new PreparePaymentPricingCommand(rail, direction, 10_000L, 200L, 12L, 3));
        order.verify(telemetry).feeReserveRaised(200L, 300L, 12L, 3);
        order.verify(state).applyPricing(snapshot, quote);
        order.verify(hash).hash(new PaymentProposal(id, 7L, rail, direction, source, destination,
                10_000L, 9_910L, 100L, 90L, 10_100L, "  reference  ", null));
        order.verify(state).recordProposal(7L, id, "proposal-hash");
        // The server reserve (300), not the normalized quote fee (100), is the gate input.
        // An inbound selection has no reservable source; its persisted source remains the fallback.
        order.verify(gate).requirePass(new PaymentSettlementGateCommand(7L, id, source, key, true, rail, direction,
                10_000L, 300L, 10_100L, direction != PaymentDirection.INBOUND, "proposal-hash"));
        order.verify(lifecycle).transition(id, ExecutionStatus.QUORUM_SYNC, "KFE_TRANSACTION_QUORUM_SYNC",
                Map.of("proposalHash", "proposal-hash", "settlementGatePassed", 1, "quorumAckCount", 3));
        order.verify(state).recordQuorum(7L, id, 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void passesSelectedSourceToGateButUsesPersistedWalletsForTheProposal() {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        UUID resolvedSource = UUID.randomUUID();
        when(wallets.resolve(any())).thenReturn(new PaymentWalletSelection(
                new PaymentWalletSnapshot(resolvedSource, 7L, true, false, true), null));
        var result = service.prepare(command());
        assertThat(result.destinationWallet()).isNull();
        verify(gate).requirePass(new PaymentSettlementGateCommand(7L, id, resolvedSource, key, true,
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 10_000L, 300L, 10_100L, true, "proposal-hash"));
        verify(hash).hash(new PaymentProposal(id, 7L, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, destination,
                10_000L, 9_910L, 100L, 90L, 10_100L, "  reference  ", null));
    }

    @Test
    void inboundMayHaveNoSourceAndDoesNotManufactureAReservation() {
        ready(PaymentRail.LIGHTNING, PaymentDirection.INBOUND);
        when(state.lockAndLoad(7L, id)).thenReturn(new PaymentSubmissionSnapshot(id, 7L, ExecutionStatus.INTENT, key,
                PaymentRail.LIGHTNING, PaymentDirection.INBOUND, null, destination, 10_000L, "reference"));
        service.prepare(command());
        verify(wallets).resolve(new ResolvePaymentWalletsCommand(7L, PaymentRail.LIGHTNING, PaymentDirection.INBOUND,
                null, destination, "  reference  "));
        verify(hash).hash(new PaymentProposal(id, 7L, PaymentRail.LIGHTNING, PaymentDirection.INBOUND, null, destination,
                10_000L, 9_910L, 100L, 90L, 10_100L, "  reference  ", null));
        verify(gate).requirePass(new PaymentSettlementGateCommand(7L, id, null, key, true,
                PaymentRail.LIGHTNING, PaymentDirection.INBOUND, 10_000L, 300L, 10_100L, false, "proposal-hash"));
    }

    @ParameterizedTest
    @MethodSource("rawReferences")
    void checksCanonicalReferenceWithoutChangingRawProposalOrWalletInputs(References refs) {
        ready(PaymentRail.INTERNAL, PaymentDirection.INTERNAL);
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, ExecutionStatus.INTENT,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, refs.persisted()));
        service.prepare(new PreparePaymentSubmissionCommand(7L, id, fingerprint, 200L, null, null, refs.external(), refs.publicId()));
        verify(wallets).resolve(new ResolvePaymentWalletsCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                source, destination, refs.external()));
        verify(hash).hash(new PaymentProposal(id, 7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination,
                10_000L, 9_910L, 100L, 90L, 10_100L, refs.external(), refs.publicId()));
    }

    static Stream<References> rawReferences() {
        return Stream.of(new References("  reference  ", null, "reference"),
                new References(null, "  request-public  ", "request-public"),
                new References("  other raw reference  ", "  request-public  ", "request-public"),
                new References(null, null, null), new References("  ", null, null),
                new References("  reference  ", "  ", "reference"));
    }

    @ParameterizedTest
    @CsvSource({"-7,0,false", "-7,1,true", "200,200,false", "200,199,false", "200,201,true"})
    void reportsReserveRaiseOnlyAboveNonnegativeClientFee(long requested, long reserved, boolean raised) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        when(pricing.prepare(any())).thenReturn(new PaymentSubmissionPricing(reserved, quote.quote(), quote.display()));
        service.prepare(new PreparePaymentSubmissionCommand(7L, id, fingerprint, requested, -1L, 0, "reference", null));
        verify(pricing).prepare(new PreparePaymentPricingCommand(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                10_000L, requested, -1L, 0));
        if (raised) { verify(telemetry).feeReserveRaised(Math.max(0L, requested), reserved, -1L, 0); }
        else { verifyNoInteractions(telemetry); }
        verify(gate).requirePass(new PaymentSettlementGateCommand(7L, id, source, key, true,
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 10_000L, reserved, 10_100L, true, "proposal-hash"));
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = "INTENT", mode = EnumSource.Mode.EXCLUDE)
    void rejectsReplayAndEveryNonIntentStateBeforeLifecycleOrWalletAccess(ExecutionStatus status) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(id, 7L, status, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, "reference"));
        assertThatThrownBy(() -> service.prepare(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(wallets, pricing, hash, gate, lifecycle, telemetry);
        verify(state, never()).applyPricing(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id", "internal-rail", "internal-direction"})
    void rejectsForeignIdentityAndIncoherentRoutesBeforeAnyEffects(String invalid) {
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot(invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.equals("owner") ? 8L : 7L, ExecutionStatus.INTENT,
                invalid.equals("internal-rail") ? PaymentRail.INTERNAL : PaymentRail.ONCHAIN,
                invalid.equals("internal-direction") ? PaymentDirection.INTERNAL : PaymentDirection.OUTBOUND, "reference"));
        assertThatThrownBy(() -> service.prepare(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(wallets, pricing, hash, gate, lifecycle, telemetry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"external", "public-id"})
    void cannotReplaceTheReferenceBoundToTheIntent(String invalid) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        assertThatThrownBy(() -> service.prepare(new PreparePaymentSubmissionCommand(7L, id, fingerprint, 200L, null, null,
                invalid.equals("external") ? "different" : "reference", invalid.equals("public-id") ? "different-public" : null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wallets, pricing, hash, gate, lifecycle, telemetry);
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void matchingPublicIdStillCannotBeIntroducedForAnExternalRoute(PaymentRail rail, PaymentDirection direction) {
        ready(rail, direction);
        assertThatThrownBy(() -> service.prepare(new PreparePaymentSubmissionCommand(7L, id, fingerprint, 200L, null, null,
                null, "  reference  "))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wallets, pricing, hash, gate, lifecycle, telemetry);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void snapshotCannotAdmitANonpositiveAmount(long amount) {
        assertThatThrownBy(() -> new PaymentSubmissionSnapshot(id, 7L, ExecutionStatus.INTENT, key,
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, destination, amount, "reference"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(state, wallets, pricing, hash, gate, lifecycle, telemetry);
    }

    @Test
    void commandAndSnapshotDiagnosticsDoNotExposeReferencesKeyOrFingerprint() {
        var command = new PreparePaymentSubmissionCommand(7L, id, fingerprint, 200L, 12L, 3,
                "sensitive-external-reference", "sensitive-public-id");
        var snapshot = snapshot(id, 7L, ExecutionStatus.INTENT, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                "sensitive-external-reference");
        assertThat(command.toString()).contains("REDACTED")
                .doesNotContain("sensitive-external-reference", "sensitive-public-id", fingerprint.value());
        assertThat(snapshot.toString()).contains("REDACTED").doesNotContain("sensitive-external-reference", key.value());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingHashBeforePersistingProposalOrRequiringGate(String proposalHash) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        when(hash.hash(any())).thenReturn(proposalHash);
        assertThatThrownBy(() -> service.prepare(command())).isInstanceOf(IllegalStateException.class);
        verify(state, never()).recordProposal(anyLong(), any(), any());
        verify(state, never()).recordQuorum(anyLong(), any(), anyInt());
        verifyNoInteractions(gate);
        verify(lifecycle, never()).transition(any(), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
    }

    @Test
    void refusesNullGateAcknowledgementBeforeQuorumTransition() {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        when(gate.requirePass(any())).thenReturn(null);
        assertThatThrownBy(() -> service.prepare(command())).isInstanceOf(IllegalStateException.class);
        verify(lifecycle, never()).transition(any(), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
        verify(state, never()).recordQuorum(anyLong(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"validating-null", "validating-id", "validating-previous", "validating-target",
            "quorum-null", "quorum-id", "quorum-previous", "quorum-target"})
    void requiresExactLifecycleAcknowledgementBeforeFollowingSteps(String invalid) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        boolean first = invalid.startsWith("validating");
        ExecutionStatus previous = first ? ExecutionStatus.INTENT : ExecutionStatus.VALIDATING;
        ExecutionStatus target = first ? ExecutionStatus.VALIDATING : ExecutionStatus.QUORUM_SYNC;
        var event = invalid.endsWith("null") ? null : new PaymentExecutionStatusChanged(
                invalid.endsWith("id") ? new PaymentExecutionId(UUID.randomUUID()) : id,
                invalid.endsWith("previous") ? ExecutionStatus.LOCKED : previous,
                invalid.endsWith("target") ? ExecutionStatus.FAILED : target);
        when(lifecycle.transition(eq(id), eq(target), any(), any())).thenReturn(event);
        assertThatThrownBy(() -> service.prepare(command())).isInstanceOf(IllegalStateException.class);
        verify(state, never()).recordQuorum(anyLong(), any(), anyInt());
        if (first) { verifyNoInteractions(wallets, pricing, hash, gate, telemetry); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "validating", "wallets", "pricing", "telemetry", "apply-pricing", "hash",
            "record-proposal", "gate", "quorum", "record-quorum"})
    void propagatesEveryFailureWithoutContinuingThePreparation(String stage) {
        ready(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        var failure = new IllegalStateException("preparation unavailable");
        switch (stage) {
            case "load" -> when(state.lockAndLoad(7L, id)).thenThrow(failure);
            case "validating" -> when(lifecycle.transition(eq(id), eq(ExecutionStatus.VALIDATING), any(), any())).thenThrow(failure);
            case "wallets" -> when(wallets.resolve(any())).thenThrow(failure);
            case "pricing" -> when(pricing.prepare(any())).thenThrow(failure);
            case "telemetry" -> doThrow(failure).when(telemetry).feeReserveRaised(anyLong(), anyLong(), any(), any());
            case "apply-pricing" -> doThrow(failure).when(state).applyPricing(any(), any());
            case "hash" -> when(hash.hash(any())).thenThrow(failure);
            case "record-proposal" -> doThrow(failure).when(state).recordProposal(anyLong(), any(), any());
            case "gate" -> when(gate.requirePass(any())).thenThrow(failure);
            case "quorum" -> when(lifecycle.transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any())).thenThrow(failure);
            case "record-quorum" -> doThrow(failure).when(state).recordQuorum(anyLong(), any(), anyInt());
        }
        assertThatThrownBy(() -> service.prepare(command())).isSameAs(failure);
        List<String> stages = List.of("load", "validating", "wallets", "pricing", "telemetry", "apply-pricing", "hash",
                "record-proposal", "gate", "quorum", "record-quorum");
        int step = stages.indexOf(stage);
        if (step < 1) { verifyNoInteractions(lifecycle); }
        if (step < 2) { verifyNoInteractions(wallets); }
        if (step < 3) { verifyNoInteractions(pricing); }
        if (step < 4) { verifyNoInteractions(telemetry); }
        if (step < 5) { verify(state, never()).applyPricing(any(), any()); }
        if (step < 6) { verifyNoInteractions(hash); }
        if (step < 7) { verify(state, never()).recordProposal(anyLong(), any(), any()); }
        if (step < 8) { verifyNoInteractions(gate); }
        if (step < 9) { verify(lifecycle, never()).transition(any(), eq(ExecutionStatus.QUORUM_SYNC), any(), any()); }
        if (step < 10) { verify(state, never()).recordQuorum(anyLong(), any(), anyInt()); }
    }

    private PaymentSubmissionSnapshot ready(PaymentRail rail, PaymentDirection direction) {
        var snapshot = snapshot(id, 7L, ExecutionStatus.INTENT, rail, direction, "reference");
        when(state.lockAndLoad(7L, id)).thenReturn(snapshot);
        when(wallets.resolve(any())).thenReturn(new PaymentWalletSelection(
                direction == PaymentDirection.INBOUND ? null : sourceWallet, destinationWallet));
        when(pricing.prepare(any())).thenReturn(quote);
        when(hash.hash(any())).thenReturn("proposal-hash");
        when(gate.requirePass(any())).thenReturn(new PaymentSettlementGateResult(3, 4));
        when(lifecycle.transition(eq(id), eq(ExecutionStatus.VALIDATING), any(), any()))
                .thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.INTENT, ExecutionStatus.VALIDATING));
        when(lifecycle.transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any()))
                .thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC));
        return snapshot;
    }

    private PaymentSubmissionSnapshot snapshot(PaymentExecutionId executionId, long userId, ExecutionStatus status,
            PaymentRail rail, PaymentDirection direction, String externalReference) {
        return new PaymentSubmissionSnapshot(executionId, userId, status, key, rail, direction, source, destination, 10_000L, externalReference);
    }

    private PreparePaymentSubmissionCommand command() {
        return new PreparePaymentSubmissionCommand(7L, id, fingerprint, 200L, 12L, 3, "  reference  ", null);
    }

    record References(String external, String publicId, String persisted) {}
}
