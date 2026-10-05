package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;

/** Settles an INTERNAL transfer in the owning submit transaction. */
public interface SettleInternalPaymentUseCase {
    /** @param command authenticated sender and locked execution @return confirmed LOCKED-to-SETTLED event */
    PaymentExecutionStatusChanged settle(SettleInternalPaymentCommand command);
}
