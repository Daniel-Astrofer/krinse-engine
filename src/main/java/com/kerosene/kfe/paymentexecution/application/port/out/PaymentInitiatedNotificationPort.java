package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Existing initiation notification boundary; durable delivery is not guaranteed by this port. */
public interface PaymentInitiatedNotificationPort {
    /** Requests the existing initiation notification for a newly routed payment. */
    /** @param userId affected account @param executionId payment identity @param walletId statement wallet @param rail selected payment rail @param grossAmountSats gross amount in integer satoshis */
    void initiated(long userId, PaymentExecutionId executionId, UUID walletId, PaymentRail rail, long grossAmountSats);
}
