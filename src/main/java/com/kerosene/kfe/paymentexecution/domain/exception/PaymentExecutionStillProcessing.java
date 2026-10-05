package com.kerosene.kfe.paymentexecution.domain.exception;

public final class PaymentExecutionStillProcessing extends IllegalStateException {
    public PaymentExecutionStillProcessing() {
        super("Transaction is currently being processed. Please retry.");
    }
}
