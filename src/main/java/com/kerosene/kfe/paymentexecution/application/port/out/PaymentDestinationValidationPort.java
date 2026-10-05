package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Integration boundary for address and external destination validation by payment rail. */
public interface PaymentDestinationValidationPort {
    /** Validates destination syntax and rail-specific constraints without dispatching payment. */
    /** @param rail selected external rail @param externalReference destination value to validate */
    void validate(PaymentRail rail, String externalReference);
}
