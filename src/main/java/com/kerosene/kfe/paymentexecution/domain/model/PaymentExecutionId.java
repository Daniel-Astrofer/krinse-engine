package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Strongly typed stable identifier for a payment execution aggregate.
 * @param value non-null UUID assigned to the execution
 */
public record PaymentExecutionId(UUID value) {
    /** Requires a non-null UUID to prevent invalid execution identities entering the domain. */
    public PaymentExecutionId {
        if (value == null) {
            throw new IllegalArgumentException("payment execution id is required");
        }
    }
}
