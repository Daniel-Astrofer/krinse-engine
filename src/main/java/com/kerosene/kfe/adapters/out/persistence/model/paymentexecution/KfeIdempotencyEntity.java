package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stores the result identity of an idempotent financial request for one user.
 *
 * <p>The embedded key combines user and caller-supplied idempotency key, so retries by the same
 * user resolve to one record while different users remain isolated. The request hash allows the
 * application to detect reuse of a key with different request content. Transaction and status
 * retain the outcome to return, and expiry bounds how long the key remains reserved.</p>
 */
@Entity
@Table(name = "transaction_idempotency", schema = "financial")
public class KfeIdempotencyEntity {

    /** Composite identity consisting of the owning user and caller's idempotency key. */
    @jakarta.persistence.EmbeddedId
    private KfeIdempotencyId id;

    /** Financial transaction created or associated with the idempotent request, if available. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** Digest of the canonical request content used to detect conflicting key reuse. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** Required outcome state that a retry can use to recover the original request result. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** UTC creation time assigned once when the idempotency record is inserted. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Optional UTC expiration after which the caller key may be eligible for reuse. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Assigns the record's creation instant in UTC before its first insert. */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return composite user/key identity used by the persistence repository */
    public KfeIdempotencyId getId() {
        return id;
    }

    /** @param id composite identity to assign to this idempotency record */
    public void setId(KfeIdempotencyId id) {
        this.id = id;
    }

    /** @return caller key, or {@code null} until the embedded identity has been created */
    public String getIdempotencyKey() {
        return id != null ? id.getIdempotencyKey() : null;
    }

    /**
     * Sets the caller key, lazily creating the embedded key object when needed.
     * @param idempotencyKey key supplied to deduplicate retries from one user
     */
    public void setIdempotencyKey(String idempotencyKey) {
        if (this.id == null) {
            this.id = new KfeIdempotencyId();
        }
        this.id.setIdempotencyKey(idempotencyKey);
    }

    /** @return owning user identifier, or {@code null} before key construction */
    public Long getUserId() {
        return id != null ? id.getUserId() : null;
    }

    /**
     * Sets the key's user component, lazily creating the embedded key object when needed.
     * @param userId user whose requests share this idempotency namespace
     */
    public void setUserId(Long userId) {
        if (this.id == null) {
            this.id = new KfeIdempotencyId();
        }
        this.id.setUserId(userId);
    }

    /** @return associated transaction identifier, if request processing created one */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId transaction associated with the original request outcome */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return digest used to compare retried request contents */
    public String getRequestHash() {
        return requestHash;
    }

    /** @param requestHash canonical request digest stored for conflict detection */
    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    /** @return saved processing outcome state for the original request */
    public String getStatus() {
        return status;
    }

    /** @param status outcome state to return or resolve for matching retries */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return immutable UTC creation time */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return key expiration instant, or {@code null} when no expiry is configured */
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    /** @param expiresAt UTC instant after which the key may be eligible for reuse */
    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
