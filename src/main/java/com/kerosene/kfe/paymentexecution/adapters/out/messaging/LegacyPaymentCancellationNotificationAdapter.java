package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility publisher; the cancellation use case does not know dashboard transport. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentCancellationNotificationAdapter implements PaymentCancellationNotificationPort {
    private final KfeDashboardPublisher publisher;

    public LegacyPaymentCancellationNotificationAdapter(KfeDashboardPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishAfterCommit(long userId) {
        publisher.publishAfterCommit(userId);
    }
}
