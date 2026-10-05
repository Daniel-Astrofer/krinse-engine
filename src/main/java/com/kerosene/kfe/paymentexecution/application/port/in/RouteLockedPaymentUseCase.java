package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRoutingResult;

/** Routes a fully gated and reserved execution to internal settlement or durable external work. */
public interface RouteLockedPaymentUseCase {
    /** @param command locked execution and previously authorized reference fields @return lifecycle and optional outbound outbox result */
    PaymentRoutingResult route(RouteLockedPaymentCommand command);
}
