package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.UUID;

/** Notification intent; the compatibility adapter still uses the existing best-effort transport. */
public interface InternalPaymentNotificationPort {
    /** Requests a sender-facing notification after the internal debit is settled. */
    /** @param userId sender account @param executionId settled payment @param walletId debited wallet @param amountSats total sender debit */
    void sent(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats);
    /** Requests a recipient-facing notification after the internal credit is settled. */
    /** @param userId recipient account @param executionId settled payment @param walletId credited wallet @param amountSats receiver credit */
    void received(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats);
}
