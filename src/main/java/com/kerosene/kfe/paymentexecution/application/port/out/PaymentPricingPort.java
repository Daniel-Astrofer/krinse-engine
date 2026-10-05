package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Anti-corruption boundary: pricing context types do not enter Payment Execution's application. */
public interface PaymentPricingPort {

    /** Produces the pricing context's authoritative gross, receiver, fee, and debit quote. */
    /** @param rail payment rail @param direction transfer direction @param amountSats principal amount @param networkFeeSats fee reserve chosen by payment execution @return quote in integer satoshis and applied pricing policy version */
    PaymentPricingQuote quote(PaymentRail rail, PaymentDirection direction, long amountSats, long networkFeeSats);
}
