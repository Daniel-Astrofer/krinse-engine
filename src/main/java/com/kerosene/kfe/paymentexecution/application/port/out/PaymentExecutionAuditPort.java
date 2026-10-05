package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

/** Forensic audit boundary for lifecycle decisions. Payloads must already exclude secrets. */
public interface PaymentExecutionAuditPort {

    /** Appends a lifecycle event inside the caller's financial transaction. */
    /** @param executionId affected payment @param eventType stable event discriminator @param previousStatus prior lifecycle state, or null for initial state @param currentStatus resulting/current state @param payload secret-free event fields */
    void record(
            PaymentExecutionId executionId,
            String eventType,
            ExecutionStatus previousStatus,
            ExecutionStatus currentStatus,
            Map<String, ?> payload);
}
