package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * HTTP request carrying validated payment submission data.
 * @param idempotencyKey Required caller-provided key that makes retries resolve to the same submission.
 * @param rail Payment rail requested for this execution.
 * @param direction Whether funds are being sent or received.
 * @param sourceWalletId Source wallet identifier for outbound payments; absent when the flow does not use one.
 * @param destinationWalletId Destination wallet identifier for internal or wallet-addressed payments.
 * @param amountSats Gross transfer amount in satoshis; validation requires a positive value.
 * @param networkFeeSats Client-declared network fee in satoshis; cannot be negative.
 * @param memo Optional user-visible memo limited to 255 characters.
 * @param totpCode TOTP authorization factor; treated as sensitive and excluded from textual rendering.
 * @param passkeyAssertionJson Serialized passkey assertion; sensitive proof excluded from textual rendering.
 * @param confirmationPassphrase Confirmation phrase supplied as an authorization factor and redacted in textual rendering.
 * @param feeRateSatPerVbyte Optional quoted fee rate in satoshis per virtual byte.
 * @param feeTargetBlocks Optional confirmation target used to derive a network fee quote.
 * @param quoteId Optional quote identifier binding this request to a previously issued pricing quote.
 * @param externalReference Rail-specific destination or provider reference, subject to application validation.
 * @param appPin Application PIN authorization factor, redacted in textual rendering.
 * @param paymentRequestPublicId Public payment-request identifier to associate with this submission, if present.
 */
public record SubmitPaymentRequest(
        @NotBlank String idempotencyKey,
        @NotNull PaymentRail rail,
        @NotNull PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats,
        String externalReference,
        @Size(max = 255) String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        @Size(max = 48) String paymentRequestPublicId,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks,
        String quoteId) {

    /** Omits authorization factors from the representation to prevent accidental secret disclosure. */
    @Override
    public String toString() {
        return "SubmitPaymentRequest[idempotencyKey=" + idempotencyKey
                + ", rail=" + rail
                + ", direction=" + direction
                + ", sourceWalletId=" + sourceWalletId
                + ", destinationWalletId=" + destinationWalletId
                + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats
                + ", authorizationFactors=REDACTED]";
    }
}
