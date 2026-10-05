package com.kerosene.kfe.adapters.in.http.dto.paymentrequest;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API projection of a payment request, including receiving instructions and settlement progress.
 * The compact constructor defensively copies the active rail details so callers cannot mutate the response afterward.
 * @param id internal payment-request identifier
 * @param publicId shareable identifier safe to expose to a payer
 * @param userId owner of the payment request
 * @param walletId wallet credited when the request is paid
 * @param addressId generated on-chain address record, when applicable
 * @param address legacy primary receiving payload, such as an address or Lightning hash URI
 * @param paymentRequest BOLT11 invoice when Lightning is the primary receiving rail
 * @param paymentHash Lightning invoice payment hash, when available
 * @param rail legacy primary rail designation
 * @param rails active rail-specific receiving instructions
 * @param status payment request lifecycle state
 * @param amountSats fixed requested amount, or {@code null} for an open-amount request
 * @param description user-facing request description
 * @param memo optional payer or merchant memo
 * @param payerHint optional hint shown to the payer
 * @param paidTransactionId transaction that first satisfied the request
 * @param settlementTransactionId transaction currently representing settlement
 * @param settlementStatus lifecycle state of the settlement transaction
 * @param blockchainTxid on-chain transaction identifier when paid on chain
 * @param confirmations observed confirmations for the on-chain payment
 * @param grossAmountSats amount received before downstream fees
 * @param receiverAmountSats amount credited to the receiver
 * @param expiresAt request expiration instant
 * @param createdAt request creation instant
 * @param updatedAt last update instant
 * @param behaviorContract versioned payment behavior contract captured at creation
 * @param partialPaymentReceived cumulative received amount for an open-amount request
 * @param webhookUrl optional configured callback URL for payment events
 */
public record KfePaymentRequestResponse(
        UUID id,
        String publicId,
        Long userId,
        UUID walletId,
        UUID addressId,
        String address,
        String paymentRequest,
        String paymentHash,
        KfeRail rail,
        List<RailDetail> rails,
        KfePaymentRequestStatus status,
        Long amountSats,
        String description,
        String memo,
        String payerHint,
        UUID paidTransactionId,
        UUID settlementTransactionId,
        KfeTransactionStatus settlementStatus,
        String blockchainTxid,
        Integer confirmations,
        Long grossAmountSats,
        Long receiverAmountSats,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        String behaviorContract,
        Long partialPaymentReceived,
        String webhookUrl) {

    /**
     * Normalizes absent rail details to an empty list and snapshots non-empty input immutably.
     * @param id internal payment-request identifier
     * @param publicId shareable identifier safe to expose to a payer
     * @param userId owner of the payment request
     * @param walletId wallet credited when the request is paid
     * @param addressId generated on-chain address record, when applicable
     * @param address legacy primary receiving payload
     * @param paymentRequest BOLT11 invoice when Lightning is the primary rail
     * @param paymentHash Lightning invoice payment hash, when available
     * @param rail legacy primary rail designation
     * @param rails active rail-specific receiving instructions, copied defensively
     * @param status payment request lifecycle state
     * @param amountSats fixed requested amount, or {@code null} for open amount
     * @param description user-facing request description
     * @param memo optional payer or merchant memo
     * @param payerHint optional hint shown to the payer
     * @param paidTransactionId transaction that first satisfied the request
     * @param settlementTransactionId transaction representing settlement
     * @param settlementStatus lifecycle state of the settlement transaction
     * @param blockchainTxid on-chain transaction identifier when paid on chain
     * @param confirmations observed confirmations for the on-chain payment
     * @param grossAmountSats amount received before downstream fees
     * @param receiverAmountSats amount credited to the receiver
     * @param expiresAt request expiration instant
     * @param createdAt request creation instant
     * @param updatedAt last update instant
     * @param behaviorContract behavior contract captured at creation
     * @param partialPaymentReceived cumulative amount received for open-amount requests
     * @param webhookUrl optional configured callback URL
     */
    public KfePaymentRequestResponse {
        rails = rails == null || rails.isEmpty() ? List.of() : List.copyOf(rails);
    }
}
