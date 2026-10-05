package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentFundsReservationSnapshot;

/** Loads reservation-eligible payment facts while holding the execution lock. */
public interface PaymentFundsReservationStatePort {
    /** @param userId authenticated execution owner @param executionId locked execution identity @return authoritative reserve amount, source wallet, and quorum state */
    PaymentFundsReservationSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
}
