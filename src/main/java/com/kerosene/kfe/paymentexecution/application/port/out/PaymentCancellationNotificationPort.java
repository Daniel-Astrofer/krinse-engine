package com.kerosene.kfe.paymentexecution.application.port.out;

/** Registers publication in the current cancellation transaction, never before its commit. */
public interface PaymentCancellationNotificationPort {
    /** Schedules account refresh only after the cancellation transaction commits. */
    /** @param userId account whose visible payment history changed */
    void publishAfterCommit(long userId);
}
