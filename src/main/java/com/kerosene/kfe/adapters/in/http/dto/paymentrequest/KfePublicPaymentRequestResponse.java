package com.kerosene.kfe.adapters.in.http.dto.paymentrequest;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Sanitized public-facing payment request DTO.
 * Exposes only fields safe for unauthenticated consumers.
 * Internal identifiers, payer hints, transaction references, and timestamps are stripped.
 * @param publicId shareable public identifier used to retrieve the request
 * @param merchantDisplayName safe merchant or receiver name shown to a payer
 * @param amount requested amount in the selected currency, or {@code null} for open amount
 * @param currency currency code associated with {@code amount}
 * @param publicDescription description explicitly approved for public display
 * @param status current payment request lifecycle state
 * @param expiresAt time after which the request can no longer be paid
 * @param payableRails currently active rails that can satisfy this request
 * @param createdAt public request creation timestamp
 */
public record KfePublicPaymentRequestResponse(
        String publicId,
        String merchantDisplayName,
        BigDecimal amount,
        String currency,
        String publicDescription,
        KfePaymentRequestStatus status,
        Instant expiresAt,
        List<String> payableRails,
        Instant createdAt) {

    /**
     * Normalizes payable rails so the public response always exposes an immutable, non-null list.
     *
     * @param payableRails configured rails copied into an immutable list, or an empty list when absent
     */
    public KfePublicPaymentRequestResponse {
        payableRails = payableRails == null || payableRails.isEmpty()
                ? List.of()
                : List.copyOf(payableRails);
    }
}
