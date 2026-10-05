package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;

/** Applies the domain-selected authorization requirement to a canonical payment submission. */
public interface AuthorizePaymentUseCase {
    /** Enforces required local, wallet, or assertion factors before payment intent effects. */
    /** @param command canonical payment request and submitted factors */
    void authorize(SubmitPaymentCommand command);
}
