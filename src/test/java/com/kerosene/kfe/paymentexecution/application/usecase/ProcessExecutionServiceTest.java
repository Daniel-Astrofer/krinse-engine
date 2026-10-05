package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult.Outcome;
import com.kerosene.kfe.paymentexecution.domain.exception.ExecutionClaimLost;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessExecutionServiceTest {
    private final ExecutionClaimPort claims = mock(ExecutionClaimPort.class);
    private final ExecutionPreparationPort preparation = mock(ExecutionPreparationPort.class);
    private final ExternalExecutionPort executor = mock(ExternalExecutionPort.class);
    private final ExecutionOutcomePort outcomes = mock(ExecutionOutcomePort.class);
    private final ExecutionClaim claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
    private final ExecutionPreparation context = new ExecutionPreparation(
            "ONCHAIN_OUTBOUND", UUID.randomUUID(), 17L, "private-wallet-label",
            UUID.randomUUID(), "private-address", 25_000L, 350L, "private-memo",
            "private-idempotency-key", "private-proposal-hash", 2L, 6);
    private final ProcessExecutionService service = new ProcessExecutionService(
            claims, preparation, List.of(executor), outcomes);

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void dispatchesEveryOutcomeExactlyOnceWithOriginalClaimAndPreparation(Outcome outcome) {
        ready();
        var result = result(outcome);
        when(executor.execute(claim, context)).thenReturn(result);

        service.process(claim);

        var order = inOrder(claims, preparation, executor, outcomes);
        order.verify(claims).heartbeat(same(claim));
        order.verify(preparation).prepare(same(claim));
        order.verify(claims).heartbeat(same(claim));
        order.verify(executor).supports(context.operation());
        order.verify(executor).execute(same(claim), same(context));
        switch (outcome) {
            case COMPLETED, CLAIM_LOST -> verifyNoInteractions(outcomes);
            case UNKNOWN -> order.verify(outcomes).markUnknown(same(claim), eq(context.transactionId()),
                    eq("provider-reference"), eq("private-provider-payload"),
                    eq("External execution outcome requires reconciliation."));
            case RETRYABLE_FAILURE -> order.verify(outcomes).markRetryableFailure(same(claim), eq(context.transactionId()),
                    eq("PROVIDER_RETRYABLE_FAILURE"), eq("External execution preparation failed; retry is required."));
            case FINAL_FAILURE -> order.verify(outcomes).markFinalFailure(same(claim), eq(context.transactionId()),
                    eq("PROVIDER_FINAL_FAILURE"), eq("External execution preparation was rejected."));
        }
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(claims, preparation, executor, outcomes);
    }

    @Test
    void expiredInitialClaimCannotPrepareAndDoesNotExposeTheToken() {
        when(claims.heartbeat(claim)).thenReturn(false);

        assertThatThrownBy(() -> service.process(claim)).isInstanceOf(ExecutionClaimLost.class)
                .hasMessageContaining(claim.outboxId().toString())
                .hasMessageNotContaining(claim.claimToken().toString());

        verify(claims).heartbeat(same(claim));
        verifyNoMoreInteractions(claims);
        verifyNoInteractions(preparation, executor, outcomes);
    }

    @Test
    void skipStopsBeforeSecondHeartbeatSelectionAndExecution() {
        when(claims.heartbeat(claim)).thenReturn(true);
        when(preparation.prepare(claim)).thenReturn(Optional.empty());

        service.process(claim);

        var order = inOrder(claims, preparation);
        order.verify(claims).heartbeat(same(claim));
        order.verify(preparation).prepare(same(claim));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(executor, outcomes);
    }

    @Test
    void ownershipLostAfterPreparationStopsWithoutSelectingOrWritingAnOutcome() {
        when(claims.heartbeat(claim)).thenReturn(true, false);
        when(preparation.prepare(claim)).thenReturn(Optional.of(context));

        service.process(claim);

        var order = inOrder(claims, preparation);
        order.verify(claims).heartbeat(same(claim));
        order.verify(preparation).prepare(same(claim));
        order.verify(claims).heartbeat(same(claim));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(executor, outcomes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first-heartbeat", "preparation", "second-heartbeat", "selection", "execution"})
    void portFailuresPropagateUnchangedWithoutFinancialFallback(String stage) {
        ready();
        var failure = new IllegalArgumentException("private-infrastructure-failure");
        switch (stage) {
            case "first-heartbeat" -> when(claims.heartbeat(claim)).thenThrow(failure);
            case "preparation" -> when(preparation.prepare(claim)).thenThrow(failure);
            case "second-heartbeat" -> when(claims.heartbeat(claim)).thenReturn(true).thenThrow(failure);
            case "selection" -> when(executor.supports(context.operation())).thenThrow(failure);
            case "execution" -> when(executor.execute(claim, context)).thenThrow(failure);
            default -> throw new AssertionError(stage);
        }

        assertThatThrownBy(() -> service.process(claim)).isSameAs(failure);

        verifyNoInteractions(outcomes);
        if (stage.equals("first-heartbeat")) {
            verify(claims).heartbeat(same(claim));
            verifyNoInteractions(preparation, executor);
        } else {
            verify(preparation).prepare(same(claim));
            verify(claims, times(stage.equals("preparation") ? 1 : 2)).heartbeat(same(claim));
            if (stage.equals("preparation") || stage.equals("second-heartbeat")) {
                verifyNoInteractions(executor);
            } else {
                verify(executor).supports(context.operation());
                if (stage.equals("execution")) {
                    verify(executor).execute(same(claim), same(context));
                }
            }
        }
        verifyNoMoreInteractions(claims, preparation, executor);
    }

    @Test
    void claimLostThrownByAnAdapterIsNotSilentlyConvertedIntoAResult() {
        ready();
        var failure = new ExecutionClaimLost(claim.outboxId());
        when(executor.execute(claim, context)).thenThrow(failure);

        assertThatThrownBy(() -> service.process(claim)).isSameAs(failure);

        verify(executor).execute(same(claim), same(context));
        verifyNoInteractions(outcomes);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"UNKNOWN", "RETRYABLE_FAILURE", "FINAL_FAILURE"})
    void failedOutcomePersistencePropagatesWithoutRetryReclassificationOrOtherWrites(Outcome outcome) {
        ready();
        when(executor.execute(claim, context)).thenReturn(result(outcome));
        var failure = new IllegalArgumentException("private-commit-failure");
        switch (outcome) {
            case UNKNOWN -> doThrow(failure).when(outcomes).markUnknown(any(), any(), any(), any(), any());
            case RETRYABLE_FAILURE -> doThrow(failure).when(outcomes).markRetryableFailure(any(), any(), any(), any());
            case FINAL_FAILURE -> doThrow(failure).when(outcomes).markFinalFailure(any(), any(), any(), any());
            default -> throw new AssertionError(outcome);
        }

        assertThatThrownBy(() -> service.process(claim)).isSameAs(failure);

        switch (outcome) {
            case UNKNOWN -> verify(outcomes).markUnknown(same(claim), eq(context.transactionId()),
                    eq("provider-reference"), eq("private-provider-payload"),
                    eq("External execution outcome requires reconciliation."));
            case RETRYABLE_FAILURE -> verify(outcomes).markRetryableFailure(same(claim), eq(context.transactionId()),
                    eq("PROVIDER_RETRYABLE_FAILURE"), eq("External execution preparation failed; retry is required."));
            case FINAL_FAILURE -> verify(outcomes).markFinalFailure(same(claim), eq(context.transactionId()),
                    eq("PROVIDER_FINAL_FAILURE"), eq("External execution preparation was rejected."));
            default -> throw new AssertionError(outcome);
        }
        verify(executor).supports(context.operation());
        verify(executor).execute(same(claim), same(context));
        verifyNoMoreInteractions(executor, outcomes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsupportedOperationWritesOnlyTheFixedFinalOutcomeEvenWhenPersistenceFails(boolean failure) {
        ready();
        when(executor.supports(context.operation())).thenReturn(false);
        var commitFailure = new IllegalStateException("private-commit-failure");
        if (failure) {
            doThrow(commitFailure).when(outcomes).markFinalFailure(any(), any(), any(), any());
            assertThatThrownBy(() -> service.process(claim)).isSameAs(commitFailure);
        } else {
            service.process(claim);
        }

        verify(outcomes).markFinalFailure(same(claim), eq(context.transactionId()),
                eq("UNSUPPORTED_OPERATION"), eq("Unsupported KFE outbox operation."));
        verify(executor).supports(context.operation());
        verifyNoMoreInteractions(executor, outcomes);
    }

    @Test
    void emptyExecutorListIsAnUnsupportedOperationAfterBothOwnershipChecks() {
        ready();
        var emptyService = new ProcessExecutionService(claims, preparation, List.of(), outcomes);

        emptyService.process(claim);

        var order = inOrder(claims, preparation, outcomes);
        order.verify(claims).heartbeat(same(claim));
        order.verify(preparation).prepare(same(claim));
        order.verify(claims).heartbeat(same(claim));
        order.verify(outcomes).markFinalFailure(same(claim), eq(context.transactionId()),
                eq("UNSUPPORTED_OPERATION"), eq("Unsupported KFE outbox operation."));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(executor);
    }

    @Test
    void selectsOnlyTheFirstMatchingExecutorInRegisteredOrder() {
        ready();
        var before = mock(ExternalExecutionPort.class);
        var after = mock(ExternalExecutionPort.class);
        when(before.supports(context.operation())).thenReturn(false);
        when(after.supports(context.operation())).thenReturn(true);
        var orderedService = new ProcessExecutionService(claims, preparation, List.of(before, executor, after), outcomes);

        orderedService.process(claim);

        var order = inOrder(before, executor);
        order.verify(before).supports(context.operation());
        order.verify(executor).supports(context.operation());
        order.verify(executor).execute(same(claim), same(context));
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(before, executor);
        verifyNoInteractions(after, outcomes);
    }

    @Test
    void snapshotsExecutorRegistrationSoLaterMutationCannotChangeDispatch() {
        ready();
        var registrations = new ArrayList<>(List.of(executor));
        var snapshotService = new ProcessExecutionService(claims, preparation, registrations, outcomes);
        registrations.clear();
        registrations.add(null);

        snapshotService.process(claim);

        verify(executor).execute(same(claim), same(context));
        verifyNoInteractions(outcomes);
    }

    @Test
    void unknownWithoutProviderEvidenceStillRequiresReconciliation() {
        ready();
        when(executor.execute(claim, context)).thenReturn(ExternalExecutionResult.unknown(null, null));

        service.process(claim);

        verify(outcomes).markUnknown(same(claim), eq(context.transactionId()), isNull(), isNull(),
                eq("External execution outcome requires reconciliation."));
        verifyNoMoreInteractions(outcomes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"preparation", "execution"})
    void nullPortResultFailsClosedWithoutWritingAnOutcome(String stage) {
        ready();
        if (stage.equals("preparation")) {
            when(preparation.prepare(claim)).thenReturn(null);
        } else {
            when(executor.execute(claim, context)).thenReturn(null);
        }

        assertThatThrownBy(() -> service.process(claim)).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(outcomes);
        if (stage.equals("preparation")) {
            verifyNoInteractions(executor);
            verify(claims).heartbeat(same(claim));
        } else {
            verify(executor).execute(same(claim), same(context));
        }
    }

    @Test
    void nullClaimCannotAccessAnyPort() {
        assertThatThrownBy(() -> service.process(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(claims, preparation, executor, outcomes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claims", "preparation", "executors", "null-executor", "outcomes"})
    void incompleteConstructionFailsBeforeAnyPortAccess(String missing) {
        var registrations = missing.equals("executors") ? null
                : missing.equals("null-executor") ? Arrays.<ExternalExecutionPort>asList(executor, null) : List.of(executor);

        assertThatThrownBy(() -> new ProcessExecutionService(
                missing.equals("claims") ? null : claims,
                missing.equals("preparation") ? null : preparation,
                registrations,
                missing.equals("outcomes") ? null : outcomes)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(claims, preparation, executor, outcomes);
    }

    private void ready() {
        when(claims.heartbeat(claim)).thenReturn(true);
        when(preparation.prepare(claim)).thenReturn(Optional.of(context));
        when(executor.supports(context.operation())).thenReturn(true);
        when(executor.execute(claim, context)).thenReturn(ExternalExecutionResult.completed());
    }

    private static ExternalExecutionResult result(Outcome outcome) {
        return switch (outcome) {
            case COMPLETED -> ExternalExecutionResult.completed();
            case CLAIM_LOST -> ExternalExecutionResult.claimLost();
            case UNKNOWN -> ExternalExecutionResult.unknown("provider-reference", "private-provider-payload");
            case RETRYABLE_FAILURE -> ExternalExecutionResult.retryableFailure();
            case FINAL_FAILURE -> ExternalExecutionResult.finalFailure();
        };
    }
}
