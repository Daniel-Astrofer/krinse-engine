package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists Lightning liquidity held for one payment until that payment resolves.
 *
 * <p>The unique transaction association prevents duplicate reservations for the same payment. A
 * reservation begins in {@link KfeLiquidityReservationStatus#HELD}, then is either released after
 * failure/cancellation or consumed after successful payment. UTC timestamps record creation,
 * changes, and the terminal release/consumption time. The version field fences concurrent terminal
 * transitions so two competing outcomes cannot silently overwrite each other.</p>
 */
@Entity
@Table(name = "lightning_liquidity_reservations", schema = "financial")
public class KfeLightningLiquidityReservationEntity {

    /** Stable UUID assigned before insertion and used as the reservation primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Unique payment transaction for which this liquidity amount is held. */
    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    /** Capacity reserved from Lightning liquidity, expressed in satoshis. */
    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    /** Current reservation lifecycle state, initially held and persisted by enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeLiquidityReservationStatus status = KfeLiquidityReservationStatus.HELD;

    /** UTC time when the reservation was first persisted; immutable afterward. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest persisted state or amount change. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UTC terminal time when held capacity was released or consumed; null while still held. */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** Optimistic fence prevents two terminal transitions from winning concurrently. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Initializes creation and modification timestamps from the same UTC instant on insert. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before a reservation update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Makes held capacity available again after a failed or cancelled payment.
     *
     * <p>The terminal timestamp records when the release was applied. Callers must use the
     * optimistic-lock version when coordinating this transition against payment success.</p>
     */
    public void markReleased() {
        status = KfeLiquidityReservationStatus.RELEASED;
        releasedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Marks the reserved capacity as spent after successful Lightning payment settlement.
     *
     * <p>The terminal timestamp records when the payment consumed the reservation. Persistence
     * versioning arbitrates races against a competing release transition.</p>
     */
    public void markConsumed() {
        status = KfeLiquidityReservationStatus.CONSUMED;
        releasedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable reservation UUID */
    public UUID getId() {
        return id;
    }

    /** @return unique payment transaction associated with the reservation */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId payment transaction that owns this reservation */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return held capacity in satoshis */
    public long getAmountSats() {
        return amountSats;
    }

    /** @param amountSats capacity to hold, expressed in satoshis */
    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    /** @return current held, released, or consumed lifecycle state */
    public KfeLiquidityReservationStatus getStatus() {
        return status;
    }

    /** @param status reservation lifecycle state to persist */
    public void setStatus(KfeLiquidityReservationStatus status) {
        this.status = status;
    }

    /** @return optimistic-lock version used to detect concurrent state transitions */
    public long getRowVersion() {
        return rowVersion;
    }
}
