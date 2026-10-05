package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.springframework.stereotype.Component;

/** Uses the same hash implementation as the existing engine compatibility facade. */
@Component
public class LegacyPaymentRequestFingerprintAdapter implements PaymentRequestFingerprintPort {
    /** Compatibility implementation that computes the established request hash. */
    private final KfeTransactionIdempotencyUseCase delegate;

    /** Creates the adapter with the legacy idempotency hashing use case. */
    public LegacyPaymentRequestFingerprintAdapter(KfeTransactionIdempotencyUseCase delegate) {
        this.delegate = delegate;
    }

    /** Maps the command to the legacy request schema and wraps its hash as a domain fingerprint. */
    @Override
    public RequestFingerprint fingerprint(SubmitPaymentCommand command) {
        return new RequestFingerprint(delegate.requestHash(command.userId(),
                LegacyPaymentSubmissionMapper.toLegacyRequest(command)));
    }
}
