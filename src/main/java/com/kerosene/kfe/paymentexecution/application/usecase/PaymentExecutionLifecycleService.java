package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionRepository;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

/** Framework-free lifecycle coordination. Transactionality is supplied by an inbound adapter. */
public final class PaymentExecutionLifecycleService {

    /** Loads and persists aggregate execution state transitions. */
    private final PaymentExecutionRepository repository;
    /** Writes a corresponding audit row in the caller's transaction. */
    private final PaymentExecutionAuditPort auditPort;

    /** Creates lifecycle coordination with persistence and audit ports. */
    /** @param repository execution aggregate repository @param auditPort transactional lifecycle audit writer */
    public PaymentExecutionLifecycleService(
            PaymentExecutionRepository repository,
            PaymentExecutionAuditPort auditPort) {
        this.repository = repository;
        this.auditPort = auditPort;
    }

    /** Records an audit event only if persisted state still equals the expected current status. */
    /** @param executionId target execution @param currentStatus expected current state @param eventType audit event discriminator @param auditPayload event-specific audit fields @throws IllegalStateException when current status has changed */
    public void recordCurrentState(
            PaymentExecutionId executionId,
            ExecutionStatus currentStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        PaymentExecution execution = load(executionId);
        if (execution.status() != currentStatus) {
            throw new IllegalStateException(
                    "Payment execution status changed before audit: expected "
                            + currentStatus + " but was " + execution.status());
        }
        auditPort.record(executionId, eventType, null, currentStatus, auditPayload);
    }

    /** Transitions an execution aggregate, persists the new state, and records its audit event. */
    /** @param executionId target execution @param targetStatus requested next state @param eventType audit event discriminator @param auditPayload event-specific audit fields @return immutable state-change event */
    public PaymentExecutionStatusChanged transition(
            PaymentExecutionId executionId,
            ExecutionStatus targetStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        PaymentExecution execution = load(executionId);
        PaymentExecutionStatusChanged event = execution.transitionTo(targetStatus);
        repository.save(execution);
        auditPort.record(
                executionId,
                eventType,
                event.previousStatus(),
                event.currentStatus(),
                auditPayload);
        return event;
    }

    /** Loads an execution aggregate or reports that the requested transaction does not exist. */
    /** @param executionId target execution identity @return persisted aggregate to transition */
    private PaymentExecution load(PaymentExecutionId executionId) {
        return repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }
}
