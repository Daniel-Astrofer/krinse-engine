package com.kerosene.kfe.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable double-entry-facing posting record. Corrections are new postings.
 * @param postingId unique identity of this immutable ledger event
 * @param operationId idempotency scope shared by postings from one business operation
 * @param walletId wallet liability account affected by this event
 * @param asset validated asset ticker or ledger unit
 * @param movementType canonical semantic kind of the balance change
 * @param amountSats positive magnitude of the movement in satoshis
 * @param fromBucket source bucket, or {@code null} for an external credit
 * @param toBucket destination bucket, or {@code null} for a settled debit
 * @param reason optional human-readable business explanation
 * @param correlationId optional trace/group identifier connecting related operations
 * @param causationId optional command/event identity that initiated this posting
 * @param createdAt timestamp assigned when the posting is created
 */
public record LedgerPosting(
        UUID postingId,
        UUID operationId,
        UUID walletId,
        String asset,
        LedgerMovementType movementType,
        long amountSats,
        LedgerBucket fromBucket,
        LedgerBucket toBucket,
        String reason,
        UUID correlationId,
        UUID causationId,
        Instant createdAt) {

    /** Validates posting identity, amount, asset bounds, and the one-sided or inter-bucket movement shape. */
    public LedgerPosting {
        Objects.requireNonNull(postingId, "postingId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(walletId, "walletId is required");
        Objects.requireNonNull(asset, "asset is required");
        Objects.requireNonNull(movementType, "movementType is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (asset.isBlank() || asset.length() > 16) {
            throw new LedgerInvariantViolation("asset is required and must be at most 16 characters");
        }
        if (amountSats <= 0L) {
            throw new LedgerInvariantViolation("posting amount must be positive");
        }
        if (fromBucket == null && toBucket == null) {
            throw new LedgerInvariantViolation("posting must identify a source or destination bucket");
        }
        if (fromBucket != null && fromBucket == toBucket) {
            throw new LedgerInvariantViolation("posting buckets must differ");
        }
    }
}
