package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import org.springframework.stereotype.Component;

/** Keeps the shared legacy router behind a boundary that can rewrite only address and memo. */
@Component
public class LegacyPaymentCanonicalDestinationAdapter implements PaymentCanonicalDestinationPort {
    private final KfePlatformOnchainDestinationRouter router;

    public LegacyPaymentCanonicalDestinationAdapter(KfePlatformOnchainDestinationRouter router) {
        this.router = router;
    }

    @Override
    public CanonicalPaymentDestination resolve(SubmitPaymentCommand command) {
        var request = router.resolve(LegacyPaymentSubmissionMapper.toLegacyRequest(command));
        if (request == null) { throw new IllegalStateException("Canonical payment destination is missing."); }
        return new CanonicalPaymentDestination(request.externalReference(), request.memo());
    }
}
