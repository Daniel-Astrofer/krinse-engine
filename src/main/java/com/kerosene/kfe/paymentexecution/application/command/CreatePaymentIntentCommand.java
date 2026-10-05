package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/**
 * Creation-only input for the initial payment intent. Authorization factors and quote/provider
 * execution data are intentionally supplied or produced by later stages.
 * @param userId authenticated account identifier
 * @param idempotencyKey key that binds this creation attempt to a stable result
 * @param rail selected payment rail
 * @param direction transfer direction for the rail
 * @param sourceWalletId optional source wallet identifier
 * @param destinationWalletId optional destination wallet identifier before resolution
 * @param amountSats requested principal amount in integer satoshis
 * @param externalReference raw or preflight canonical external destination reference
 * @param memo optional payment memo
 * @param paymentRequestPublicId optional public request identifier fulfilled by this intent
 */
public record CreatePaymentIntentCommand(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        String externalReference,
        String memo,
        String paymentRequestPublicId) {

    /** Returns non-sensitive intent metadata while redacting external references. */
    @Override
    public String toString() {
        return "CreatePaymentIntentCommand[userId=" + userId + ", rail=" + rail + ", direction=" + direction
                + ", amountSats=" + amountSats + ", references=REDACTED]";
    }
}
