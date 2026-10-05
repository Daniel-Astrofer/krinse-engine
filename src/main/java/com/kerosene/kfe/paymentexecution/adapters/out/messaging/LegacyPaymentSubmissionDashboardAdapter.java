package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionDashboardPort;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentSubmissionDashboardAdapter implements PaymentSubmissionDashboardPort {
    private final KfeDashboardPublisher publisher;

    public LegacyPaymentSubmissionDashboardAdapter(KfeDashboardPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishAfterCommit(long userId) {
        if (userId <= 0L) { throw new IllegalArgumentException("Dashboard recipient is required."); }
        publisher.publishAfterCommit(userId);
    }
}
