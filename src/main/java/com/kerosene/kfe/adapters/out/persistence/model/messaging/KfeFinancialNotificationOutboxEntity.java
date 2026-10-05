package com.kerosene.kfe.adapters.out.persistence.model.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores financial notification events until asynchronous delivery succeeds.
 *
 * <p>The unique event identifier supports deduplicated publication, while attempt scheduling and
 * worker claims allow delivery to be retried after transient failures. Claim owner, expiry, and
 * token describe the active lease; optimistic row versioning protects competing workers. Event
 * payload version and correlation/causation identifiers preserve compatibility and traceability
 * across service boundaries. Timestamps use {@link Instant} to represent absolute instants.</p>
 */
@Entity
@Table(name = "kfe_financial_notification_outbox", schema = "financial", indexes = {
        @Index(name = "idx_kfe_fin_notif_outbox_status", columnList = "status, next_attempt_at"),
        @Index(name = "idx_kfe_fin_notif_outbox_event", columnList = "event_id")
})
public class KfeFinancialNotificationOutboxEntity {

    /** Stable UUID primary key allocated before the outbox record is inserted. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Unique logical event identifier used to prevent duplicate notification publication. */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /** Recipient user for whom the notification was produced. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Optional financial transaction associated with this notification. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** Required event category used by consumers to select notification handling. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Payload contract version, initialized to version 1 for new records. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    public long getEventSequence() { return eventSequence; }
    public void setEventSequence(long value) { eventSequence = value; }

    /** Optional trace identifier shared by related events in one business flow. */
    @Column(name = "correlation_id")
    private UUID correlationId;

    /** Optional identifier of the event or command that directly caused this notification. */
    @Column(name = "causation_id")
    private UUID causationId;

    /** Serialized notification data consumed by the delivery worker; may be absent for empty events. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** Delivery state string, initially {@code PENDING}; state transitions are managed by workers. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    /** Number of delivery attempts already made for this event. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Earliest instant at which a scheduled retry may be attempted. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** Worker identity currently holding the delivery lease, if claimed. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    /** Lease expiry instant after which an abandoned claim can be recovered. */
    @Column(name = "claimed_until")
    private Instant claimedUntil;

    /** Absolute UTC instant when this outbox event was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Absolute UTC instant when delivery was acknowledged successfully. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** Most recent delivery failure detail retained for diagnosis and retry decisions. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Opaque token associated with the current lease to reject stale worker acknowledgements. */
    @Column(name = "claim_token")
    private UUID claimToken;

    /** Optimistic-lock version used to arbitrate concurrent claims and delivery updates. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /**
     * Initializes creation time and supplies the event identifier when the producer omitted it.
     *
     * <p>Using the row UUID as the fallback event UUID keeps the unique logical identifier
     * deterministic for this persisted record.</p>
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        if (eventId == null) {
            eventId = id;
        }
    }

    /**
     * JPA update hook reserved for outbox update lifecycle behavior.
     *
     * <p>The current mapping intentionally does not maintain a separate modification timestamp;
     * delivery scheduling is represented by the attempt and lease fields.</p>
     */
    @PreUpdate
    void onUpdate() {
    }

    /** @return stable primary key of the persisted outbox record */
    public UUID getId() {
        return id;
    }

    /** @return unique logical notification event identifier */
    public UUID getEventId() {
        return eventId;
    }

    /** @param eventId unique logical event identifier; defaults to the row ID before insert */
    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    /** @return notification recipient's user identifier */
    public Long getUserId() {
        return userId;
    }

    /** @param userId recipient user identifier */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return associated financial transaction identifier, if present */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId optional financial transaction associated with the event */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return event category consumed by notification handlers */
    public String getEventType() {
        return eventType;
    }

    /** @param eventType required notification event category */
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /** @return version of the serialized payload contract */
    public int getSchemaVersion() { return schemaVersion; }
    /** @param schemaVersion payload contract version understood by notification consumers */
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    /** @return shared trace identifier for related events, if supplied */
    public UUID getCorrelationId() { return correlationId; }
    /** @param correlationId identifier used to correlate related work across services */
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }
    /** @return identifier of the direct cause of this event, if supplied */
    public UUID getCausationId() { return causationId; }
    /** @param causationId identifier of the event or command that directly caused this one */
    public void setCausationId(UUID causationId) { this.causationId = causationId; }

    /** @return serialized payload to pass to the notification delivery adapter */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** @param payloadJson serialized notification data for downstream consumers */
    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    /** @return current delivery state, represented as a string for forward-compatible persistence */
    public String getStatus() {
        return status;
    }

    /** @param status delivery state selected by the outbox worker */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return number of delivery attempts already recorded */
    public int getAttempts() {
        return attempts;
    }

    /** @param attempts number of attempts to persist after a worker execution */
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /** @return next eligible retry instant, or {@code null} when no retry is scheduled */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** @param nextAttemptAt earliest instant at which a retry may run */
    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    /** @return worker holding the current delivery lease, if any */
    public String getClaimedBy() {
        return claimedBy;
    }

    /** @param claimedBy identity of the worker claiming this event */
    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    /** @return lease expiry instant, or {@code null} when the event is not claimed */
    public Instant getClaimedUntil() {
        return claimedUntil;
    }

    /** @param claimedUntil expiry instant after which a stale claim may be recovered */
    public void setClaimedUntil(Instant claimedUntil) {
        this.claimedUntil = claimedUntil;
    }

    /** @return immutable creation instant assigned before insert */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return successful delivery acknowledgement instant, or {@code null} before delivery */
    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** @param deliveredAt instant at which a downstream delivery was acknowledged */
    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    /** @return latest delivery error detail, or {@code null} when no failure was recorded */
    public String getLastError() {
        return lastError;
    }

    /** @param lastError latest delivery error detail for retry or operational review */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /** @return token associated with the current worker lease, if any */
    public UUID getClaimToken() {
        return claimToken;
    }

    /** @param claimToken opaque token assigned to the current worker claim */
    public void setClaimToken(UUID claimToken) {
        this.claimToken = claimToken;
    }

    /** @return optimistic-lock version used to detect stale concurrent writes */
    public long getRowVersion() {
        return rowVersion;
    }
}
