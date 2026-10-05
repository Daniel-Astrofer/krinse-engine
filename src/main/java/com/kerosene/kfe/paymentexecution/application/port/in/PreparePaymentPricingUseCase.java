package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentPricingCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;

/** Calculates authoritative submission pricing and presentation-only currency snapshots. */
public interface PreparePaymentPricingUseCase {
    /** @param command validated rail, amount, and fee inputs @return reserved fee, quote, and display snapshot */
    PaymentSubmissionPricing prepare(PreparePaymentPricingCommand command);
}
