package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Outbound liquidity effects joined to the owning payment transaction. */
public interface PaymentLiquidityPort {

    /** Reserves outbound rail liquidity in the owning transaction. */
    /** @param executionId reservation idempotency identity @param amountSats total outbound debit in satoshis */
    void reserve(PaymentExecutionId executionId, long amountSats);

    /** Releases liquidity reserved for an execution that was cancelled. */
    /** @param executionId execution owning the reservation */
    void release(PaymentExecutionId executionId);
}
