package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persists a user's display-ready transaction statement for the short statement-history window.
 *
 * <p>The row associates one user with one transaction, optionally records the wallet context,
 * stores a presentation payload, and carries an expiry time. Its fixed creation-order key is kept
 * separately from refresh timestamps so status updates do not reorder statement history. The
 * {@link Persistable} state flag lets Spring Data distinguish a first insert from an update even
 * though this entity assigns its UUID before persistence.</p>
 */
@Entity
@Table(
        name = "user_statement_24h",
        schema = "financial",
        indexes = {
                @Index(name = "idx_user_statement_24h_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_user_statement_24h_expiry", columnList = "expires_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_statement_24h_user_tx",
                        columnNames = {"user_id", "transaction_id"})
        })
public class KfeUserStatementEntity implements Persistable<UUID> {

    /** Stable UUID allocated before insertion and used as the row's primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /**
     * Assigned UUIDs make Spring Data treat entities as existing (merge/INSERT by PK).
     * Track true first insert so JPA save uses persist when appropriate.
     */
    @Transient
    /** Tracks first-insert state for Spring Data because {@link #id} is assigned in advance. */
    private boolean isNew = true;

    /** User who owns and may retrieve this statement. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Transaction summarized by this statement; unique together with the owning user. */
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /** Optional wallet context used when rendering the statement. */
    @Column(name = "wallet_id")
    private UUID walletId;

    /** Required JSON presentation snapshot shown to the user without reconstructing the response. */
    @Column(name = "display_payload_json", nullable = false, columnDefinition = "TEXT")
    private String displayPayloadJson;

    /** Time after which this short-lived statement should no longer be returned as current. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Fixed order key — set once from the ledger {@code transactions_master.created_at}
     * (or first insert time). Never updated on status refresh.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest statement refresh or persisted change. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Marks an entity materialized from storage as existing for Spring Data save semantics. */
    @PostLoad
    void onLoad() {
        isNew = false;
    }

    /** Sets initial timestamps and clears the first-insert flag immediately before insertion. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        isNew = false;
    }

    /** Refreshes modification time and clears the first-insert flag before an update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        isNew = false;
    }

    /** @return primary-key UUID used by Spring Data repository operations */
    @Override
    public UUID getId() {
        return id;
    }

    /** @return {@code true} until the entity has been inserted or loaded from persistence */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Mark this instance as a first insert (required when id is pre-assigned). */
    /** Marks this instance for insertion when its UUID was assigned before calling repository save. */
    public void markNew() {
        this.isNew = true;
    }

    /** Mark as existing after load from the database. */
    /** Marks this instance as already persisted, so repository save follows update semantics. */
    public void markNotNew() {
        this.isNew = false;
    }

    /** @return user who owns this statement */
    public Long getUserId() {
        return userId;
    }

    /** @param userId owner identifier to persist with the statement */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return summarized transaction identifier */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId transaction summarized by this statement */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return associated wallet context, or {@code null} when not applicable */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId optional wallet context for rendering the statement */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return serialized presentation snapshot used by the statement response */
    public String getDisplayPayloadJson() {
        return displayPayloadJson;
    }

    /** @param displayPayloadJson required JSON snapshot to store for presentation */
    public void setDisplayPayloadJson(String displayPayloadJson) {
        this.displayPayloadJson = displayPayloadJson;
    }

    /** @return the instant after which this statement expires */
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    /** @param expiresAt expiry instant used to bound the statement's availability */
    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** @return stable history-order timestamp, initialized on first persistence when absent */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt fixed history-order timestamp, normally sourced from the ledger transaction */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** @return last modification time maintained by persistence callbacks */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt modification timestamp to initialize or override before persistence */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
