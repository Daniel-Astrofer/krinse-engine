package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/**
 * Raw scalar values for application validation; no rail semantics are enforced by this data carrier.
 * @param idempotencyKey raw idempotency key before domain wrapping
 * @param rail selected payment rail
 * @param direction transfer direction
 * @param amountSats requested principal in integer satoshis
 * @param networkFeeSats requested network fee in integer satoshis
 * @param externalReference destination address or external reference to validate
 */
public record ValidatePaymentRequestCommand(String idempotencyKey, PaymentRail rail,
        PaymentDirection direction, long amountSats, long networkFeeSats, String externalReference) {
    /** Returns a redacted diagnostic representation without request or destination data. */
    @Override
    public String toString() { return "ValidatePaymentRequestCommand[REDACTED]"; }
}
