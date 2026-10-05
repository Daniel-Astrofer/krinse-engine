package com.kerosene.kfe.messaging.envelope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable message metadata. Transport identity is intentionally absent from the
 * payload: it is supplied by the authenticated channel adapter.
 *
 * @param messageId globally unique identity of this delivery envelope
 * @param messageKind broad envelope family used for routing/monitoring
 * @param type canonical application message type used to choose a handler
 * @param schemaVersion positive producer schema revision for compatibility checks
 * @param occurredAt event time assigned by the producer
 * @param correlationId optional identifier linking messages in one business flow
 * @param causationId optional identity of the command or event that produced this message
 * @param aggregateId optional domain aggregate identity affected by the event
 * @param aggregateVersion non-negative aggregate sequence used to detect stale or reordered events
 * @param idempotencyKey optional consumer-facing deduplication key for the business operation
 * @param payload serialized message body; authenticated workload identity is deliberately excluded
 */
public record MessageEnvelope(
        UUID messageId,
        String messageKind,
        String type,
        int schemaVersion,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String aggregateId,
        long aggregateVersion,
        String idempotencyKey,
        String payload) {

    /** Validates mandatory envelope identifiers, schema version, event time and payload shape. */
    public MessageEnvelope {
        Objects.requireNonNull(messageId, "messageId is required");
        requireText(messageKind, "messageKind");
        requireText(type, "type");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion cannot be negative");
        }
        Objects.requireNonNull(payload, "payload is required");
    }

    /** Rejects absent or whitespace-only values for mandatory textual envelope fields. */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
