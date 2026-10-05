package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Serializable composite identity for a per-user financial idempotency record.
 *
 * <p>Both fields participate in equality and hashing: a key is unique within one user's namespace,
 * and the same text key supplied by another user identifies a different record.</p>
 */
@Embeddable
public class KfeIdempotencyId implements Serializable {

    /** User namespace that scopes this caller-provided idempotency key. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Caller-provided stable key used to identify retries of one logical request. */
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    /** Required no-argument constructor for JPA to materialize the embedded key. */
    public KfeIdempotencyId() {
    }

    /** Creates the composite identity from its namespace and request key.
     * @param userId owning user identifier
     * @param idempotencyKey caller's request key
     */
    public KfeIdempotencyId(Long userId, String idempotencyKey) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
    }

    /** @return owning user identifier component */
    public Long getUserId() {
        return userId;
    }

    /** @param userId user identifier component to assign */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return caller-provided idempotency key component */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @param idempotencyKey caller key to assign to this identity */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Compares both user namespace and caller key.
     * @param o candidate value
     * @return {@code true} when both identity components are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KfeIdempotencyId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    /**
     * Computes a hash from both identity components to match {@link #equals(Object)}.
     * @return hash code for use in hash-based collections
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId, idempotencyKey);
    }
}
