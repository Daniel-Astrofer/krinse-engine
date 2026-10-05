package com.kerosene.kfe.ledger.domain;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/** Immutable participant-facing statement projection input.
 * @param userId positive owning user identifier
 * @param transactionId transaction represented in the participant statement
 * @param walletId optional wallet context when the transaction is wallet-scoped
 * @param ledgerCreatedAt authoritative ledger event time used for statement ordering
 * @param expiresAt time after which the projection should no longer be retained or exposed
 * @param payload display data serialized for the statement; defensively copied and made immutable
 */
public record StatementRecord(Long userId, UUID transactionId, UUID walletId,
        Instant ledgerCreatedAt, Instant expiresAt, Map<String, ?> payload) {
    /** Validates statement identity and chronology, and freezes a defensive payload copy. */
    public StatementRecord {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(ledgerCreatedAt, "ledgerCreatedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        if (userId <= 0L || !expiresAt.isAfter(ledgerCreatedAt)) {
            throw new LedgerInvariantViolation("invalid statement identity or expiry");
        }
    }
}
