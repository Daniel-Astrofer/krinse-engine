package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.InternalPaymentSettlementSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Requires the authorized submission transaction and any linked payment-request lock. */
public interface InternalPaymentSettlementStatePort {
    /** Reloads and locks the payer-owned internal execution inside its submit transaction. */
    /** @param userId authenticated payer @param executionId payment to settle @return authoritative participants, wallets, amounts, and lifecycle snapshot */
    InternalPaymentSettlementSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
}
