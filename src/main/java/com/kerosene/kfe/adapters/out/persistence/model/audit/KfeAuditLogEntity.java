package com.kerosene.kfe.adapters.out.persistence.model.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists one immutable-in-intent entry in the financial audit event stream.
 *
 * <p>The row stores event context and hashes instead of the original payload. {@code previousHash}
 * links the entry to the preceding event and {@code eventHash} identifies this event in that
 * chain; the generated sequence provides a database ordering key for querying the stream. This
 * entity maps persistence data only: callers are responsible for calculating and validating the
 * hashes before saving the row.</p>
 */
@Entity
@Table(name = "financial_audit_log", schema = "financial", indexes = {
        @Index(name = "idx_financial_audit_log_transaction", columnList = "transaction_id, sequence_number"),
        @Index(name = "idx_financial_audit_log_wallet", columnList = "wallet_id, sequence_number")
})
public class KfeAuditLogEntity {

    /**
     * Database-generated position of this row in the audit log.
     *
     * <p>The identity value is assigned by the database when the row is inserted and cannot be
     * changed afterward. It is useful for stable ordering, but does not by itself prove that event
     * hashes form a valid chain.</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private Long sequenceNumber;

    /** Stable UUID identifier allocated when this entity instance is created. */
    @Column(name = "id", nullable = false, unique = true, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Optional transaction whose lifecycle or processing produced this event. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** Optional wallet context for events that are scoped to a wallet. */
    @Column(name = "wallet_id")
    private UUID walletId;

    /** Required domain event name, bounded to the database column's 96-character limit. */
    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    /** Optional state before the transition represented by this event. */
    @Column(name = "from_status", length = 32)
    private String fromStatus;

    /** Optional state after the transition represented by this event. */
    @Column(name = "to_status", length = 32)
    private String toStatus;

    /** Required 64-character digest of the event payload, retained without storing that payload. */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    /** Required digest of the preceding event, or the defined chain seed for the first event. */
    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    /** Required unique digest calculated for this event together with its chain context. */
    @Column(name = "event_hash", nullable = false, unique = true, length = 64)
    private String eventHash;

    /** UTC creation instant assigned on insert and kept immutable by the persistence mapping. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Supplies the creation timestamp immediately before persistence.
     *
     * <p>The value is captured in UTC to keep event ordering independent of the host's local time
     * zone. JPA invokes this lifecycle callback during insertion; it does not alter timestamps on
     * subsequent updates.</p>
     */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return the database-assigned ordering value, or {@code null} before insertion */
    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    /** @return the stable UUID assigned to this entity instance */
    public UUID getId() {
        return id;
    }

    /** @return the associated transaction identifier, or {@code null} for non-transaction events */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId transaction context to associate, or {@code null} when absent */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return the associated wallet identifier, or {@code null} for non-wallet events */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId wallet context to associate, or {@code null} when absent */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return the required domain event name */
    public String getEventType() {
        return eventType;
    }

    /** @param eventType required domain event name; persistence limits it to 96 characters */
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /** @return the state before the event, or {@code null} when no transition applies */
    public String getFromStatus() {
        return fromStatus;
    }

    /** @param fromStatus prior state, or {@code null} when the event has no prior state */
    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    /** @return the state after the event, or {@code null} when no transition applies */
    public String getToStatus() {
        return toStatus;
    }

    /** @param toStatus resulting state, or {@code null} when the event has no resulting state */
    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    /** @return the digest of the payload represented by this event */
    public String getPayloadHash() {
        return payloadHash;
    }

    /** @param payloadHash 64-character payload digest computed by the event producer */
    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    /** @return the digest linking this row to the preceding event or chain seed */
    public String getPreviousHash() {
        return previousHash;
    }

    /** @param previousHash preceding event digest or the chain's defined initial seed */
    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    /** @return the unique digest calculated for this event */
    public String getEventHash() {
        return eventHash;
    }

    /** @param eventHash 64-character digest calculated for this event and its chain context */
    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    /** @return the UTC timestamp set by {@link #onCreate()} before insertion */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
