package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immediate dispatch through the same claim and processing ports used by the scheduled worker. */
@Component
public class OutboxExecutionCommandDispatcher implements ExecutionCommandDispatcher {

    private final ExecutionClaimPort outboxService;
    private final ProcessExecutionUseCase processor;

    public OutboxExecutionCommandDispatcher(
            ExecutionClaimPort outboxService,
            ProcessExecutionUseCase processor) {
        this.outboxService = Objects.requireNonNull(outboxService);
        this.processor = Objects.requireNonNull(processor);
    }

    @Override
    public DispatchResult dispatchImmediately(UUID outboxId, String workerId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Immediate execution must start outside an existing transaction.");
        }
        Optional<ExecutionClaim> claim = outboxService.claimImmediate(outboxId, workerId);
        if (claim.isEmpty()) {
            return DispatchResult.ALREADY_CLAIMED;
        }
        processor.process(claim.orElseThrow());
        return DispatchResult.PROCESSED;
    }
}
