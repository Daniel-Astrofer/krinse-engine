package com.kerosene.kfe.paymentexecution.domain.exception;

/** Domain decision; the inbound compatibility adapter maps the existing public error contract. */
public final class PaymentSelfTransferRejected extends RuntimeException {
    public PaymentSelfTransferRejected() { super("Source and destination must not be the same wallet."); }
}
