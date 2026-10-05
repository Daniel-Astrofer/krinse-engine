package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxExecutionCommandDispatcherTest {

    private final ExecutionClaimPort outboxService = mock(ExecutionClaimPort.class);
    private final ProcessExecutionUseCase processor = mock(ProcessExecutionUseCase.class);
    private final OutboxExecutionCommandDispatcher dispatcher =
            new OutboxExecutionCommandDispatcher(outboxService, processor);

    @Test
    void claimsAndProcessesThroughTheSamePortsAsTheWorker() {
        UUID outboxId = UUID.randomUUID();
        var claim = new ExecutionClaim(outboxId, UUID.randomUUID());
        when(outboxService.claimImmediate(outboxId, "request-worker")).thenReturn(Optional.of(claim));

        var result = dispatcher.dispatchImmediately(outboxId, "request-worker");

        assertThat(result).isEqualTo(ExecutionCommandDispatcher.DispatchResult.PROCESSED);
        verify(processor).process(claim);
    }

    @Test
    void reportsConcurrentClaimWithoutExecutingTwice() {
        UUID outboxId = UUID.randomUUID();
        when(outboxService.claimImmediate(outboxId, "request-worker")).thenReturn(Optional.empty());

        var result = dispatcher.dispatchImmediately(outboxId, "request-worker");

        assertThat(result).isEqualTo(ExecutionCommandDispatcher.DispatchResult.ALREADY_CLAIMED);
        verify(processor, never()).process(org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void clearTransactionMarker() { TransactionSynchronizationManager.clear(); }

    @Test
    void rejectsEncompassingTransactionBeforeAcquiringAClaim() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> dispatcher.dispatchImmediately(UUID.randomUUID(), "request-worker"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outboxService, processor);
    }

    @Test
    void processingFailurePropagatesWithoutReportingSuccessOrReclaiming() {
        var claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
        var failure = new IllegalStateException("persistence unavailable");
        when(outboxService.claimImmediate(claim.outboxId(), "request-worker")).thenReturn(Optional.of(claim));
        org.mockito.Mockito.doThrow(failure).when(processor).process(claim);
        assertThatThrownBy(() -> dispatcher.dispatchImmediately(claim.outboxId(), "request-worker")).isSameAs(failure);
        verify(outboxService).claimImmediate(claim.outboxId(), "request-worker");
        verify(processor).process(claim);
        org.mockito.Mockito.verifyNoMoreInteractions(outboxService, processor);
    }
}
