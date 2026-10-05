package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;

/** Writes the participant statement in the financial caller's transaction. */
public interface PaymentStatementPort {
    /** Records the participant-facing statement entry described by the command. */
    void record(RecordPaymentStatementCommand command);
}
