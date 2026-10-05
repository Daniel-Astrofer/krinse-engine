package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import java.util.UUID;

/**
 * Inputs used to accept a recipient-owned payment request during an internal payment.
 * @param userId authenticated paying account
 * @param rail selected payment rail
 * @param direction selected payment direction
 * @param destinationWalletId resolved recipient wallet identifier
 * @param amountSats proposed transfer amount in integer satoshis
 * @param publicId client-visible payment request identifier, if supplied
 */
public record PreparePaymentRequestLinkCommand(long userId, PaymentRail rail, PaymentDirection direction,
        UUID destinationWalletId, long amountSats, String publicId) {
    /** Requires authenticated identity, rail/direction, and a positive transfer amount. */
    public PreparePaymentRequestLinkCommand {
        if (userId <= 0L || rail == null || direction == null || amountSats <= 0L) {
            throw new IllegalArgumentException("authenticated payment inputs are required");
        }
    }

    /** Returns request metadata while redacting the public request identifier. */
    @Override
    public String toString() {
        return "PreparePaymentRequestLinkCommand[userId=" + userId + ", rail=" + rail
                + ", direction=" + direction + ", reference=REDACTED]";
    }
}
