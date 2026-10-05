package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** The ports own short state transactions; this orchestration must never enclose external RPC in one. */
@Component
public class ProcessExecutionAdapter implements ProcessExecutionUseCase {
    /** Execution workflow whose ports own only short state transactions. */
    private final ProcessExecutionService service;

    /** Wires the application service whose transaction semantics this adapter enforces. */
    public ProcessExecutionAdapter(ProcessExecutionService service) {
        this.service = Objects.requireNonNull(service);
    }

    /** Starts lease-fenced execution outside a transaction so provider RPC cannot hold a database connection. */
    @Override
    public void process(ExecutionClaim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        service.process(claim);
    }
}
