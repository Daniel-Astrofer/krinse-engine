package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentInFlightException;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeRailExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyExternalExecutionAdapterTest {
    private final KfeRailExecution executor = mock(KfeRailExecution.class);
    private final LegacyExternalExecutionAdapter adapter = new LegacyExternalExecutionAdapter(executor);
    private final ExecutionClaim claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
    private final ExecutionPreparation preparation = new ExecutionPreparation(
            "ONCHAIN_OUTBOUND", UUID.randomUUID(), 17L, "private wallet", UUID.randomUUID(),
            "private reference", 57_123L, 123L, "private memo", "private idempotency", "private quorum", 12L, 3);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void successfulExecutionMapsAllFieldsAndCurrentClaimWithoutChangingAmounts() {
        assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.completed());

        var captured = ArgumentCaptor.forClass(KfeExecutionTransactionHelper.PreparationResult.class);
        verify(executor).execute(eq(claim.outboxId()), captured.capture());
        assertThat(captured.getValue()).isEqualTo(new KfeExecutionTransactionHelper.PreparationResult(
                true, preparation.operation(), preparation.transactionId(), preparation.userId(),
                preparation.sourceWalletLabel(), preparation.sourceWalletId(), preparation.externalReference(),
                preparation.amountSats(), preparation.networkFeeSats(), preparation.memo(), preparation.idempotencyKey(),
                preparation.quorumProposalHash(), preparation.feeRateSatsPerVbyte(), preparation.feeTargetBlocks(), claim.claimToken()));
        verifyNoMoreInteractions(executor);
    }

    @Test
    void supportsDelegatesWithoutNormalizingOperationOrCapturingSelectionErrors() {
        when(executor.supports("ONCHAIN_OUTBOUND")).thenReturn(true);
        assertThat(adapter.supports("ONCHAIN_OUTBOUND")).isTrue();
        assertThat(adapter.supports("OTHER")).isFalse();
        var failure = new IllegalArgumentException("bad selection");
        when(executor.supports("bad")).thenThrow(failure);
        assertThatThrownBy(() -> adapter.supports("bad")).isSameAs(failure);
        verify(executor).supports("ONCHAIN_OUTBOUND");
        verify(executor).supports("OTHER");
        verify(executor).supports("bad");
        verifyNoMoreInteractions(executor);
    }

    @Test
    void lostClaimHasNoFinancialFailureClassification() {
        doThrow(new KfeExecutionClaimLostException(claim.outboxId())).when(executor).execute(any(), any());
        assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.claimLost());
    }

    @Test
    void wrappedClaimLossWinsOverPermanentLookingWrapper() {
        doThrow(new IllegalArgumentException("invalid macaroon SECRET", new KfeExecutionClaimLostException(claim.outboxId())))
                .when(executor).execute(any(), any());
        assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.claimLost());
    }

    static Stream<RuntimeException> uncertainFailures() {
        return Stream.of(
                new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous("invalid macaroon SECRET", "private-reference", "private-payload",
                        new IllegalArgumentException("invoice expired")),
                new LightningPaymentInFlightException("invoice expired SECRET", "private-reference", "private-payload"),
                new IllegalArgumentException("invalid macaroon SECRET", new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                        "SECRET", "private-reference", "private-payload", new UnsupportedOperationException("SECRET"))),
                new UnsupportedOperationException("permission denied SECRET", new LightningPaymentInFlightException(
                        "SECRET", "private-reference", "private-payload")));
    }

    @ParameterizedTest
    @MethodSource("uncertainFailures")
    void ambiguityPrecedesGenericClassificationAndPreservesOnlyInternalReferenceAndPayload(RuntimeException failure) {
        doThrow(failure).when(executor).execute(any(), any());

        var result = adapter.execute(claim, preparation);

        assertThat(result).isEqualTo(ExternalExecutionResult.unknown("private-reference", "private-payload"));
        assertThat(result.message()).isEqualTo("External execution outcome requires reconciliation.")
                .doesNotContain("SECRET", "invalid macaroon", "invoice expired", "private-reference", "private-payload");
        assertThat(result.toString()).doesNotContain("SECRET", "private-reference", "private-payload");
    }

    @Test
    void uncertainExecutionMayCarryNoProviderEvidence() {
        doThrow(new LightningPaymentInFlightException("SECRET", null, null)).when(executor).execute(any(), any());
        assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.unknown(null, null));
    }

    static Stream<RuntimeException> permanentPreparationFailures() {
        return Stream.of(new IllegalArgumentException("SECRET"), new UnsupportedOperationException("SECRET"),
                new IllegalStateException("INVOICE EXPIRED SECRET"),
                new IllegalStateException("SECRET", new IllegalArgumentException("SECRET")),
                new RuntimeException("SECRET", new RuntimeException("SECRET", new UnsupportedOperationException("SECRET"))),
                new IllegalStateException("SECRET", new RuntimeException("permission denied SECRET")));
    }

    @ParameterizedTest
    @MethodSource("permanentPreparationFailures")
    void rejectsKnownPermanentPreparationFailuresWithoutPersistingTheirMessage(RuntimeException failure) {
        doThrow(failure).when(executor).execute(any(), any());
        var result = adapter.execute(claim, preparation);
        assertThat(result).isEqualTo(ExternalExecutionResult.finalFailure());
        assertThat(result.message()).isEqualTo("External execution preparation was rejected.").doesNotContain("SECRET");
        assertThat(result.toString()).doesNotContain("SECRET");
    }

    static Stream<RuntimeException> retryablePreparationFailures() {
        return Stream.of(new RuntimeException(), new IllegalStateException(" "),
                new IllegalStateException("timeout SECRET"), new RuntimeException("SECRET", new RuntimeException("transient SECRET")));
    }

    @ParameterizedTest
    @MethodSource("retryablePreparationFailures")
    void retriesOtherPreparationFailuresWithoutPersistingTheirMessage(RuntimeException failure) {
        doThrow(failure).when(executor).execute(any(), any());
        var result = adapter.execute(claim, preparation);
        assertThat(result).isEqualTo(ExternalExecutionResult.retryableFailure());
        assertThat(result.message()).isEqualTo("External execution preparation failed; retry is required.").doesNotContain("SECRET");
    }

    @Test
    void cyclicCauseChainTerminatesAndPreservesPermanentClassification() {
        var first = new RuntimeException("transient");
        var second = new IllegalStateException("invalid payment request");
        first.initCause(second);
        second.initCause(first);
        doThrow(first).when(executor).execute(any(), any());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.finalFailure()));
    }

    @Test
    void cyclicTransientCauseChainTerminatesWithoutInventingPermanentFailure() {
        var first = new RuntimeException("transient");
        var second = new RuntimeException("transient");
        first.initCause(second);
        second.initCause(first);
        doThrow(first).when(executor).execute(any(), any());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.retryableFailure()));
    }

    @Test
    void traversalUsesIdentityRatherThanExceptionEquality() {
        var first = new EqualException("transient");
        first.initCause(new EqualException("invoice expired"));
        doThrow(first).when(executor).execute(any(), any());
        assertThat(adapter.execute(claim, preparation)).isEqualTo(ExternalExecutionResult.finalFailure());
    }

    @Test
    void activeTransactionIsRejectedBeforeAnyProviderCallOrOutcomeTranslation() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> adapter.execute(claim, preparation)).isInstanceOf(IllegalStateException.class)
                .hasMessage("External execution must start outside an existing transaction.");
        verifyNoInteractions(executor);
    }

    @Test
    void invalidInvocationFailsBeforeAnyProviderCallOrOutcomeTranslation() {
        assertThatThrownBy(() -> adapter.execute(null, preparation)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.execute(claim, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void fatalErrorsAreNotPresentedAsAProviderOutcome() {
        var error = new AssertionError("fatal");
        doThrow(error).when(executor).execute(any(), any());
        assertThatThrownBy(() -> adapter.execute(claim, preparation)).isSameAs(error);
    }

    @Test
    void bridgeRequiresExplicitCompositionAndNeverDeclaresATransaction() {
        assertThat(LegacyExternalExecutionAdapter.class.getAnnotation(Component.class)).isNull();
        assertThat(LegacyExternalExecutionAdapter.class.getAnnotation(Transactional.class)).isNull();
        for (var method : LegacyExternalExecutionAdapter.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class)).isNull();
        }
    }

    private static final class EqualException extends RuntimeException {
        private EqualException(String message) { super(message); }
        @Override public boolean equals(Object other) { return other instanceof EqualException; }
        @Override public int hashCode() { return 1; }
    }
}
