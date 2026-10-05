package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.util.UUID;

/**
 * Wallet resolution input; the account comes from authentication, never destination metadata.
 * @param userId authenticated account requesting wallet selection
 * @param rail selected payment rail
 * @param direction transfer direction
 * @param sourceWalletId optional source wallet to validate/lock
 * @param destinationWalletId optional destination wallet already resolved by an earlier step
 * @param externalReference address, wallet identifier, or username used to resolve the destination
 */
public record ResolvePaymentWalletsCommand(long userId, PaymentRail rail, PaymentDirection direction,
        UUID sourceWalletId, UUID destinationWalletId, String externalReference) {
    /** Requires authenticated identity and a valid rail/direction pair. */
    public ResolvePaymentWalletsCommand {
        if (userId <= 0L || rail == null || direction == null) {
            throw new IllegalArgumentException("authenticated user, rail and direction are required");
        }
    }

    /** Determines whether source funds must be reserved for the requested direction. */
    /** @return true for outbound or internal transfers */
    public boolean requiresSourceReserve() {
        return direction == PaymentDirection.OUTBOUND || direction == PaymentDirection.INTERNAL;
    }

    /** Returns routing metadata while redacting destination reference content. */
    @Override
    public String toString() {
        return "ResolvePaymentWalletsCommand[userId=" + userId + ", rail=" + rail
                + ", direction=" + direction + ", reference=REDACTED]";
    }
}
