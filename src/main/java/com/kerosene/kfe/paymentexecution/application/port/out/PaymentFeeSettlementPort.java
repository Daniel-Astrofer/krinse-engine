package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Idempotent settlement of a payment's recorded fee in the owning transaction. */
public interface PaymentFeeSettlementPort {

    /** Settles the execution's fee once and joins the owning payment transaction. */
    /** @param executionId payment whose recorded fee is being settled */
    void settleFee(PaymentExecutionId executionId);
}
