package com.kerosene.kfe.paymentexecution.adapters.in;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Compatibility facade only. Worker decisions belong to the payment execution application core. */
@Service
public class KfeExecutionOutboxProcessor {
    /** Core execution workflow that applies lease-fenced provider and lifecycle decisions. */
    private final ProcessExecutionUseCase execution;

    /** Supplies the application workflow used to process claimed outbox commands. */
    public KfeExecutionOutboxProcessor(ProcessExecutionUseCase execution) {
        this.execution = Objects.requireNonNull(execution);
    }

    /** Converts the legacy claim DTO and delegates processing to the application core. */
    public void process(KfeExecutionOutboxService.ExecutionClaim claim) {
        Objects.requireNonNull(claim, "execution claim is required");
        execution.process(new ExecutionClaim(claim.outboxId(), claim.claimToken()));
    }
}
