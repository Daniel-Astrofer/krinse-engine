package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

/** Coordinates legal lifecycle state changes and corresponding audit events. */
public interface PaymentExecutionLifecycleUseCase {

    /** Records an event for an execution already in the specified persisted state. */
    /** @param executionId target payment @param currentStatus required current state @param eventType audit event name @param auditPayload event-specific fields */
    void recordCurrentState(
            PaymentExecutionId executionId,
            ExecutionStatus currentStatus,
            String eventType,
            Map<String, ?> auditPayload);

    /** Applies and persists a legal lifecycle transition and its audit event. */
    /** @param executionId target payment @param targetStatus requested next state @param eventType audit event name @param auditPayload event-specific fields @return confirmed lifecycle transition event */
    PaymentExecutionStatusChanged transition(
            PaymentExecutionId executionId,
            ExecutionStatus targetStatus,
            String eventType,
            Map<String, ?> auditPayload);
}
