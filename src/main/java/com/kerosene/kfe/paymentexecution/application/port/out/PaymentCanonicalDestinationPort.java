package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;

/** Resolves rail-specific reference and memo fields while leaving identity, value, and factors unchanged. */
public interface PaymentCanonicalDestinationPort {
    /** @param command payment request after wallet resolution @return canonical destination/reference and memo */
    CanonicalPaymentDestination resolve(SubmitPaymentCommand command);
}
