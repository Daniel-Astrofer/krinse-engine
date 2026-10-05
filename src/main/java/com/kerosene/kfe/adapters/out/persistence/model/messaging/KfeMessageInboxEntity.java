package com.kerosene.kfe.adapters.out.persistence.model.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable at-least-once consumer inbox that retains messages for audit and replay.
 *
 * <p>The immutable unique message identifier supports deduplication, while message type/version,
 * aggregate version, and causality metadata describe the consumed contract and its ordering
 * context. Pending delivery state, retry scheduling, and tokenized worker leases let a consumer
 * recover after interruption. Quarantine records a terminal handling reason without discarding the
 * original payload. Optimistic versioning protects concurrent claims and acknowledgements.</p>
 */
@Entity
@Table(name = "kfe_message_inbox", schema = "financial", indexes = {
        @Index(name = "idx_kfe_message_inbox_due", columnList = "status,next_attempt_at,created_at")
})
public class KfeMessageInboxEntity {
    /** Stable UUID primary key allocated before the inbox row is stored. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Immutable broker/domain message identifier used to reject duplicate deliveries. */
    @Column(name = "message_id", nullable = false, unique = true, updatable = false)
    private UUID messageId;
    /** Required broad category that selects the consumer's handling family. */
    @Column(name = "message_kind", nullable = false, length = 32)
    private String messageKind;
    /** Required event or command type name identifying the specific message contract. */
    @Column(name = "message_type", nullable = false, length = 128)
    private String messageType;
    /** Version of the serialized message schema used for compatibility-aware decoding. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    /** Absolute instant at which the producer says the business event occurred. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    /** Optional workflow identifier shared by messages participating in the same operation. */
    @Column(name = "correlation_id")
    private UUID correlationId;
    /** Optional identifier of the message or command that directly produced this message. */
    @Column(name = "causation_id")
    private UUID causationId;
    /** Optional identifier of the business aggregate affected by this message. */
    @Column(name = "aggregate_id", length = 160)
    private String aggregateId;
    /** Producer-assigned aggregate sequence used to reason about per-aggregate ordering. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;
    /** Optional command idempotency key retained to deduplicate effects beyond message delivery. */
    @Column(name = "idempotency_key", length = 256)
    private String idempotencyKey;
    /** Required original JSON body retained so failed messages can be audited or replayed. */
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;
    /** Consumer processing state, initially pending and advanced by the inbox worker. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";
    /** Number of processing attempts recorded so far. */
    @Column(name = "attempts", nullable = false)
    private int attempts;
    /** Earliest absolute instant at which a retry should be made eligible. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    /** Worker identity currently holding the processing lease, if claimed. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;
    /** Lease deadline after which a message abandoned by a worker may be reclaimed. */
    @Column(name = "claimed_until")
    private Instant claimedUntil;
    /** Token associated with the active lease so stale workers cannot acknowledge a new claim. */
    @Column(name = "claim_token")
    private UUID claimToken;
    /** Explanation for moving an unprocessable message to quarantine while retaining its payload. */
    @Column(name = "quarantine_reason", length = 255)
    private String quarantineReason;
    /** Absolute instant when consumer processing reached a recorded terminal outcome. */
    @Column(name = "processed_at")
    private Instant processedAt;
    /** Immutable inbox insertion instant used for retention and stable scheduling order. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    /** Optimistic-lock version used to detect competing consumer updates. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Supplies the inbox insertion instant if the producer did not set one. */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** @return stable persistence primary key */
    public UUID getId() { return id; }
    /** @return immutable logical identifier used to deduplicate incoming deliveries */
    public UUID getMessageId() { return messageId; }
    /** @param messageId unique producer or broker message identifier */
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    /** @return broad message category used to route consumer handling */
    public String getMessageKind() { return messageKind; }
    /** @param messageKind required category for this message */
    public void setMessageKind(String messageKind) { this.messageKind = messageKind; }
    /** @return specific message contract name */
    public String getMessageType() { return messageType; }
    /** @param messageType required specific event or command type */
    public void setMessageType(String messageType) { this.messageType = messageType; }
    /** @return payload schema version used by the decoder */
    public int getSchemaVersion() { return schemaVersion; }
    /** @param schemaVersion contract version of the serialized message */
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    /** @return producer-reported business occurrence instant */
    public Instant getOccurredAt() { return occurredAt; }
    /** @param occurredAt absolute instant when the producer says the event occurred */
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    /** @return workflow correlation identifier, if supplied */
    public UUID getCorrelationId() { return correlationId; }
    /** @param correlationId identifier shared across related messages */
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }
    /** @return direct cause message identifier, if supplied */
    public UUID getCausationId() { return causationId; }
    /** @param causationId identifier of the message or command that caused this message */
    public void setCausationId(UUID causationId) { this.causationId = causationId; }
    /** @return affected aggregate identifier, if the message is aggregate-scoped */
    public String getAggregateId() { return aggregateId; }
    /** @param aggregateId affected business aggregate identifier */
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    /** @return producer-assigned aggregate sequence number */
    public long getAggregateVersion() { return aggregateVersion; }
    /** @param aggregateVersion aggregate sequence used to preserve ordering context */
    public void setAggregateVersion(long aggregateVersion) { this.aggregateVersion = aggregateVersion; }
    /** @return command idempotency key, when one was supplied */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** @param idempotencyKey key used to deduplicate effects of retried commands */
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    /** @return retained original JSON message payload */
    public String getPayloadJson() { return payloadJson; }
    /** @param payloadJson original message body to retain for audit and replay */
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    /** @return current processing state string */
    public String getStatus() { return status; }
    /** @param status processing state assigned by the inbox worker */
    public void setStatus(String status) { this.status = status; }
    /** @return processing attempt count recorded so far */
    public int getAttempts() { return attempts; }
    /** @param attempts number of attempts made to process this message */
    public void setAttempts(int attempts) { this.attempts = attempts; }
    /** @return next retry eligibility instant, if a retry is scheduled */
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    /** @param nextAttemptAt absolute instant when processing may be retried */
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    /** @return identity of the worker currently holding the message lease */
    public String getClaimedBy() { return claimedBy; }
    /** @param claimedBy worker identity assigned to the current claim */
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    /** @return current claim lease deadline */
    public Instant getClaimedUntil() { return claimedUntil; }
    /** @param claimedUntil lease expiry after which an abandoned message can be reclaimed */
    public void setClaimedUntil(Instant claimedUntil) { this.claimedUntil = claimedUntil; }
    /** @return token used to reject acknowledgements from stale claim owners */
    public UUID getClaimToken() { return claimToken; }
    /** @param claimToken opaque ownership token assigned to the current worker claim */
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }
    /** @return quarantine explanation, if processing was permanently rejected */
    public String getQuarantineReason() { return quarantineReason; }
    /** @param quarantineReason concise reason for quarantining an unprocessable message */
    public void setQuarantineReason(String quarantineReason) { this.quarantineReason = quarantineReason; }
    /** @return terminal processing instant, or {@code null} before processing completes */
    public Instant getProcessedAt() { return processedAt; }
    /** @param processedAt absolute instant when processing reached a terminal outcome */
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    /** @return immutable inbox insertion instant */
    public Instant getCreatedAt() { return createdAt; }
    /** @return optimistic-lock version for concurrent consumer updates */
    public long getRowVersion() { return rowVersion; }
}
