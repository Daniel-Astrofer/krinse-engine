package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists an append-only record of value moving between a wallet's ledger buckets.
 *
 * <p>A movement records a positive amount in satoshis, its optional source and destination
 * buckets, and correlation metadata for tracing the operation. The entity validates required
 * values before insertion and rejects updates and deletes so historical ledger evidence cannot
 * be rewritten through JPA.</p>
 */
@Entity
@Table(name = "balance_movements", schema = "financial", indexes = {
        @Index(name = "idx_balance_movements_transaction", columnList = "transaction_id, created_at"),
        @Index(name = "idx_balance_movements_wallet", columnList = "wallet_id, created_at")
})
public class KfeBalanceMovementEntity {

    /** Stable identifier allocated when the movement object is created. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Optional transaction that caused this ledger movement. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** Wallet whose balance buckets are affected; required for every movement. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Required domain category describing why this value moved. */
    @Column(name = "movement_type", nullable = false, length = 32)
    private String movementType;

    /** Positive quantity moved, always represented in satoshis. */
    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    /** Optional ledger bucket debited by the movement. */
    @Column(name = "from_bucket", length = 32)
    private String fromBucket;

    /** Optional ledger bucket credited by the movement. */
    @Column(name = "to_bucket", length = 32)
    private String toBucket;

    /** Optional human-readable cause, retained for audit context without affecting accounting. */
    @Column(name = "reason", length = 128)
    private String reason;

    /** Optional identifier shared by related operations for end-to-end tracing. */
    @Column(name = "correlation_id")
    private UUID correlationId;

    /** Optional identifier of the event or command that directly caused this movement. */
    @Column(name = "causation_id")
    private UUID causationId;

    /** Asset code for the quantity; defaults to BTC for existing callers. */
    @Column(name = "asset", nullable = false, length = 10)
    private String asset = "BTC";

    /** UTC insertion time assigned by the persistence lifecycle and never updated. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Validates the movement's required accounting values and sets its UTC insertion time. */
    @PrePersist
    void onCreate() {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId is required");
        }
        if (movementType == null || movementType.isBlank() || movementType.length() > 32) {
            throw new IllegalArgumentException("movementType is required");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive");
        }
        if (asset == null || asset.isBlank() || asset.length() > 10) {
            throw new IllegalArgumentException("asset is required");
        }
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Rejects ORM updates because a recorded movement is historical ledger evidence. */
    @jakarta.persistence.PreUpdate
    void preventUpdate() {
        throw new IllegalStateException("balance movements are append-only");
    }

    /** Rejects ORM deletion to preserve the append-only movement history. */
    @jakarta.persistence.PreRemove
    void preventDelete() {
        throw new IllegalStateException("balance movements are append-only");
    }

    /** @return the movement's stable UUID */
    public UUID getId() {
        return id;
    }

    /** @return the causative transaction identifier, or {@code null} when unassociated */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId causative transaction identifier, or {@code null} when absent */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return wallet whose ledger buckets are affected */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId required wallet identifier, validated before insertion */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return required domain category for this movement */
    public String getMovementType() {
        return movementType;
    }

    /** @param movementType required movement category, limited to 32 characters */
    public void setMovementType(String movementType) {
        this.movementType = movementType;
    }

    /** @return positive amount moved, in satoshis */
    public long getAmountSats() {
        return amountSats;
    }

    /** @param amountSats quantity in satoshis; must be positive before insertion */
    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    /** @return source bucket name, or {@code null} when the movement has no source bucket */
    public String getFromBucket() {
        return fromBucket;
    }

    /** @param fromBucket optional source bucket name */
    public void setFromBucket(String fromBucket) {
        this.fromBucket = fromBucket;
    }

    /** @return destination bucket name, or {@code null} when the movement has no destination */
    public String getToBucket() {
        return toBucket;
    }

    /** @param toBucket optional destination bucket name */
    public void setToBucket(String toBucket) {
        this.toBucket = toBucket;
    }

    /** @return optional explanation retained for support and audit readers */
    public String getReason() { return reason; }
    /** @param reason optional human-readable cause, limited by the mapped column */
    public void setReason(String reason) { this.reason = reason; }
    /** @return shared trace identifier for related operations, if supplied */
    public UUID getCorrelationId() { return correlationId; }
    /** @param correlationId identifier used to correlate this movement with related work */
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }
    /** @return direct cause identifier for this movement, if supplied */
    public UUID getCausationId() { return causationId; }
    /** @param causationId identifier of the event or command that directly caused this movement */
    public void setCausationId(UUID causationId) { this.causationId = causationId; }
    /** @return asset code associated with the satoshi quantity */
    public String getAsset() { return asset; }
    /** @param asset required asset code, defaulting to BTC when not changed */
    public void setAsset(String asset) { this.asset = asset; }

    /** @return UTC time when this movement was inserted */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
