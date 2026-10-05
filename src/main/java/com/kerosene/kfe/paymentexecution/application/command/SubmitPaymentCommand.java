package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/**
 * Transport-independent request to submit a payment for execution.
 * Constructor validation establishes authenticated identity, required routing/idempotency fields,
 * and basic integer-satoshi bounds; rail-specific authorization and risk checks occur later.
 * Secret-bearing authorization factors are intentionally omitted from {@link #toString()}.
 * @param userId authenticated account identifier; must be positive
 * @param idempotencyKey key reserved to make submission retries safe
 * @param rail payment network or internal transfer rail
 * @param direction transfer direction as interpreted by the selected rail
 * @param sourceWalletId optional source wallet identifier
 * @param destinationWalletId optional resolved destination wallet identifier
 * @param amountSats positive principal amount in integer satoshis
 * @param networkFeeSats nonnegative network fee in integer satoshis
 * @param externalReference canonical rail-specific destination or external reference
 * @param memo user or payment-request memo associated with the transfer
 * @param totpCode one-time TOTP factor when requested by policy
 * @param passkeyAssertionJson serialized passkey assertion when requested by policy
 * @param confirmationPassphrase passphrase confirmation factor when requested by policy
 * @param appPin device PIN factor when requested by policy
 * @param paymentRequestPublicId public payment-request identifier being fulfilled, if any
 * @param feeRateSatPerVbyte optional quoted Bitcoin fee rate
 * @param feeTargetBlocks optional desired Bitcoin confirmation target
 * @param quoteId optional server-issued pricing quote identifier
 * @param deviceHash optional device binding used by risk and authorization checks
 */
public record SubmitPaymentCommand(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        String externalReference,
        String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        String paymentRequestPublicId,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks,
        String quoteId,
        String deviceHash) {

    /** Validates identity, required selectors, and basic integer-satoshi amount bounds. */
    public SubmitPaymentCommand {
        if (userId <= 0L) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
        if (idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("idempotency key, rail and direction are required");
        }
        if (amountSats <= 0L || networkFeeSats < 0L) {
            throw new IllegalArgumentException("payment amount must be positive and network fee non-negative");
        }
    }

    /** Returns a copy with the resolved destination wallet while preserving every other input. */
    /** @param destination resolved destination wallet identifier @return copied command with destination set */
    public SubmitPaymentCommand withDestinationWalletId(UUID destination) {
        return new SubmitPaymentCommand(userId, idempotencyKey, rail, direction, sourceWalletId,
                destination, amountSats, networkFeeSats, externalReference, memo, totpCode,
                passkeyAssertionJson, confirmationPassphrase, appPin, paymentRequestPublicId,
                feeRateSatPerVbyte, feeTargetBlocks, quoteId, deviceHash);
    }

    /** Returns a copy with canonical rail reference and memo produced by request parsing. */
    /** @param reference normalized external destination reference @param canonicalMemo normalized memo @return copied command with canonical destination fields */
    public SubmitPaymentCommand withCanonicalDestination(String reference, String canonicalMemo) {
        return new SubmitPaymentCommand(userId, idempotencyKey, rail, direction, sourceWalletId,
                destinationWalletId, amountSats, networkFeeSats, reference, canonicalMemo, totpCode,
                passkeyAssertionJson, confirmationPassphrase, appPin, paymentRequestPublicId,
                feeRateSatPerVbyte, feeTargetBlocks, quoteId, deviceHash);
    }

    /** Returns a safe diagnostic representation without TOTP, passkey, passphrase, or PIN values. */
    @Override
    public String toString() {
        return "SubmitPaymentCommand[userId=" + userId
                + ", idempotencyKey=" + idempotencyKey
                + ", rail=" + rail
                + ", direction=" + direction
                + ", sourceWalletId=" + sourceWalletId
                + ", destinationWalletId=" + destinationWalletId
                + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats
                + ", authorizationFactors=REDACTED]";
    }
}
