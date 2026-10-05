package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;

/** Canonicalizes, validates, fingerprints, replays, and authorizes a payment before intent creation. */
public interface PreflightPaymentUseCase {
    /** @param command raw transport-independent payment request @return canonical request, semantic fingerprint, and optional replay result */
    PaymentPreflightResult preflight(SubmitPaymentCommand command);
}
