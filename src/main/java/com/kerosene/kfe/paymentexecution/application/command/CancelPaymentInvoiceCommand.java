package com.kerosene.kfe.paymentexecution.application.command;

/**
 * Provider invoice identity for cancellation authorized by the owning payment request.
 * @param userId account that owns the payment request
 * @param paymentHash Lightning payment hash, when available
 * @param providerReference provider-side invoice reference, when available
 * @param paymentRequest invoice/payment request text used to identify the provider operation
 */
public record CancelPaymentInvoiceCommand(
        long userId,
        String paymentHash,
        String providerReference,
        String paymentRequest) {

    /** Requires an authenticated request owner before forwarding provider invoice identity. */
    public CancelPaymentInvoiceCommand {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id is required");
        }
    }
}
