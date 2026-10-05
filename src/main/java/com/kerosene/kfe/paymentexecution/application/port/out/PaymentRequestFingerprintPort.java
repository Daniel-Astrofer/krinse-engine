package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;

/** Produces the canonical request fingerprint used to detect equivalent submissions. */
public interface PaymentRequestFingerprintPort {
    /** Computes a stable fingerprint from the normalized command fields. */
    RequestFingerprint fingerprint(SubmitPaymentCommand command);
}
