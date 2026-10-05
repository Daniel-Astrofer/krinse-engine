package com.kerosene.kfe.adapters.in.http.dto.paymentexecution;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;

import java.util.UUID;

/**
 * Validated API input for creating an idempotent payment or transfer transaction.
 * Authentication factors are transient request data and must never be persisted or echoed.
 * @param idempotencyKey caller-generated key used to make retries safe
 * @param rail payment rail selected for execution
 * @param direction transfer direction from the user's perspective
 * @param sourceWalletId wallet funding an outgoing transaction, when required
 * @param destinationWalletId internal destination wallet, when the transfer is internal
 * @param amountSats positive gross transfer amount in satoshis
 * @param networkFeeSats non-negative network or provider fee estimate in satoshis
 * @param externalReference rail destination such as an address or invoice
 * @param memo optional user-visible memo, limited to 255 characters
 * @param totpCode optional one-time password for step-up authorization
 * @param passkeyAssertionJson optional serialized WebAuthn assertion
 * @param confirmationPassphrase optional transaction confirmation passphrase
 * @param appPin optional application PIN for transaction confirmation
 * @param paymentRequestPublicId linked payment request public identifier, if applicable
 * @param feeRateSatPerVbyte explicit on-chain fee rate from the selected fee tier
 * @param feeTargetBlocks confirmation target associated with the selected fee tier
 * @param quoteId persistent quote identifier binding the price and fees to this request
 */
public record KfeSubmitTransactionRequest(
        @NotBlank String idempotencyKey,
        @NotNull KfeRail rail,
        @NotNull KfeDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats,
        String externalReference,
        @Size(max = 255)
        String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        @Size(max = 48)
        String paymentRequestPublicId,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks,
        String quoteId) {

    /** Returns a copy with an internally resolved destination wallet ID.
     * @param resolvedDestinationWalletId destination selected by payment-request or routing resolution
     * @return a new request preserving all other fields
     */
    public KfeSubmitTransactionRequest withDestinationWalletId(UUID resolvedDestinationWalletId) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, resolvedDestinationWalletId, amountSats,
                networkFeeSats, externalReference, memo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks,
                quoteId);
    }

    /** Rewrites only the external destination reference after platform wallet routing.
     * @param resolvedExternalReference validated destination address or rail reference
     * @return a new request preserving the original wallet IDs and other fields
     */
    public KfeSubmitTransactionRequest withExternalReference(String resolvedExternalReference) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats,
                networkFeeSats, resolvedExternalReference, memo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks,
                quoteId);
    }

    /** Returns a copy carrying a normalized memo while preserving all other request data.
     * @param resolvedMemo validated or normalized memo text
     * @return a new request with the supplied memo
     */
    public KfeSubmitTransactionRequest withMemo(String resolvedMemo) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats,
                networkFeeSats, externalReference, resolvedMemo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks,
                quoteId);
    }

    /** Backward-compatible constructor without app PIN, payment-request, or fee-quote fields.
     * @param idempotencyKey caller retry key
     * @param rail selected payment rail
     * @param direction transfer direction
     * @param sourceWalletId funding wallet, if required
     * @param destinationWalletId internal destination, if applicable
     * @param amountSats gross amount in satoshis
     * @param networkFeeSats estimated network fee in satoshis
     * @param externalReference external rail destination
     * @param memo optional user memo
     * @param totpCode optional TOTP factor
     * @param passkeyAssertionJson optional WebAuthn assertion
     * @param confirmationPassphrase optional confirmation passphrase
     */
    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
            UUID sourceWalletId,
            UUID destinationWalletId,
            long amountSats,
            long networkFeeSats,
            String externalReference,
            String memo,
            String totpCode,
            String passkeyAssertionJson,
            String confirmationPassphrase) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, totpCode, passkeyAssertionJson, confirmationPassphrase, null, null,
                null, null, null);
    }

    /** Backward-compatible constructor including app PIN and payment-request reference.
     * @param idempotencyKey caller retry key
     * @param rail selected payment rail
     * @param direction transfer direction
     * @param sourceWalletId funding wallet, if required
     * @param destinationWalletId internal destination, if applicable
     * @param amountSats gross amount in satoshis
     * @param networkFeeSats estimated network fee in satoshis
     * @param externalReference external rail destination
     * @param memo optional user memo
     * @param totpCode optional TOTP factor
     * @param passkeyAssertionJson optional WebAuthn assertion
     * @param confirmationPassphrase optional confirmation passphrase
     * @param appPin optional application PIN
     * @param paymentRequestPublicId payment request associated with this transaction
     */
    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
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
            String paymentRequestPublicId) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, totpCode, passkeyAssertionJson, confirmationPassphrase, appPin,
                paymentRequestPublicId, null, null, null);
    }

    /** Backward-compatible minimal constructor without authentication or quote metadata.
     * @param idempotencyKey caller retry key
     * @param rail selected payment rail
     * @param direction transfer direction
     * @param sourceWalletId funding wallet, if required
     * @param destinationWalletId internal destination, if applicable
     * @param amountSats gross amount in satoshis
     * @param networkFeeSats estimated network fee in satoshis
     * @param externalReference external rail destination
     * @param memo optional user memo
     */
    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
            UUID sourceWalletId,
            UUID destinationWalletId,
            long amountSats,
            long networkFeeSats,
            String externalReference,
            String memo) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, null, null, null, null, null, null, null, null);
    }
}
