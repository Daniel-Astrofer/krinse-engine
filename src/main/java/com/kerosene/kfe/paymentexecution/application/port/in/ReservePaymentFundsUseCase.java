package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;

/** Reserves source funds and rail capacity after settlement authorization has passed. */
public interface ReservePaymentFundsUseCase {
    /** @param command current authorized execution identity @return confirmed transition from quorum-ready to LOCKED */
    PaymentExecutionStatusChanged reserve(ReservePaymentFundsCommand command);
}
