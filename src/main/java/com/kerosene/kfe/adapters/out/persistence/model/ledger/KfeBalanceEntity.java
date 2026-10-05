package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persisted per-wallet/per-asset ledger totals and their observation/integrity metadata.
 * Domain mutation methods keep monetary buckets nonnegative, increment the ledger nonce, and
 * call {@link #validateInvariant()} around balance transitions. JPA's {@code version} separately
 * protects concurrent writes through optimistic locking.
 */
@Entity
@Table(name = "balances_core", schema = "financial")
public class KfeBalanceEntity {

    /** Composite wallet-and-asset key identifying the balance row. */
    @EmbeddedId
    private KfeBalanceId id;

    /** Funds available for new debits, measured in the asset's smallest unit (satoshis for BTC). */
    @Column(name = "available_sats", nullable = false)
    private long availableSats;

    /** Credits not yet moved into the available bucket, measured in satoshis. */
    @Column(name = "pending_sats", nullable = false)
    private long pendingSats;

    /** Funds reserved by in-flight operations and unavailable for new spending. */
    @Column(name = "locked_sats", nullable = false)
    private long lockedSats;

    /** Funds held automatically by policy and excluded from ordinary availability. */
    @Column(name = "auto_hold_sats", nullable = false)
    private long autoHoldSats;

    /** Latest externally observed balance used for reconciliation against the ledger. */
    @Column(name = "observed_sats", nullable = false)
    private long observedSats;

    /** Amount of reversed prior credits not covered by remaining available funds. */
    @Column(name = "reorg_debt_sats", nullable = false)
    private long reorgDebtSats;

    /** Last successful observed probe quality (LIVE_MEMPOOL_AWARE, OPTIMISTIC_DELTA, …). */
    @Column(name = "observed_probe_quality", length = 32)
    private String observedProbeQuality;

    /** Time of the last successful external balance probe. */
    @Column(name = "observed_probe_at")
    private LocalDateTime observedProbeAt;

    /** Provider or probe identifier that supplied the most recent observed balance. */
    @Column(name = "observed_probe_source", length = 96)
    private String observedProbeSource;

    /** Monotonic ledger mutation counter used to distinguish successive balance states. */
    @Column(name = "nonce", nullable = false)
    private long nonce;

    /** Previous balance-chain digest used to link this ledger state to its predecessor. */
    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    /** Integrity signature associated with the current balance-chain state. */
    @Column(name = "balance_signature", nullable = false, length = 256)
    private String balanceSignature;

    /** JPA optimistic-lock revision incremented on each persisted update. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /** UTC timestamp assigned by the JPA write lifecycle callback. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a zeroed balance row for a wallet and asset, seeding both integrity markers with
     * the caller-provided initial chain hash.
     *
     * @param walletId wallet that owns this balance
     * @param asset asset ticker, defaulted to BTC by {@link KfeBalanceId} when null
     * @param initialHash initial ledger-chain hash for the new row
     * @return unsaved entity initialized with its composite key and integrity seed
     */
    public static KfeBalanceEntity empty(UUID walletId, String asset, String initialHash) {
        KfeBalanceEntity entity = new KfeBalanceEntity();
        entity.setId(new KfeBalanceId(walletId, asset));
        entity.setLastHash(initialHash);
        entity.setBalanceSignature(initialHash);
        return entity;
    }

    /** Updates {@code updatedAt} in UTC immediately before the entity is inserted or updated. */
    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Moves a positive amount from available to locked funds and advances the ledger nonce.
     *
     * @param amountSats amount to reserve in satoshis
     * @throws IllegalArgumentException when the amount is not positive or an invariant is already broken
     * @throws IllegalStateException when available funds are insufficient
     * @throws ArithmeticException if the locked total or nonce overflows
     */
    public void reserve(long amountSats) {
        validateInvariant();
        requirePositive(amountSats);
        if (availableSats < amountSats) {
            throw new IllegalStateException("Insufficient available balance.");
        }
        availableSats -= amountSats;
        lockedSats = Math.addExact(lockedSats, amountSats);
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /**
     * Permanently debits an already locked amount after settlement; it is removed from the locked bucket.
     *
     * @param amountSats amount to debit from locked funds in satoshis
     * @throws IllegalArgumentException when the amount is not positive or the invariant is broken
     * @throws IllegalStateException when locked funds are insufficient
     */
    public void settleReservedDebit(long amountSats) {
        validateInvariant();
        requirePositive(amountSats);
        if (lockedSats < amountSats) {
            throw new IllegalStateException("Insufficient locked balance.");
        }
        lockedSats -= amountSats;
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /** Returns a reserved amount from locked to available funds and advances the nonce.
     * @param amountSats amount to release in satoshis
     * @throws IllegalArgumentException when the amount is not positive or an invariant is broken
     * @throws IllegalStateException when locked funds are insufficient
     */
    public void releaseReserved(long amountSats) {
        validateInvariant();
        requirePositive(amountSats);
        if (lockedSats < amountSats) {
            throw new IllegalStateException("Insufficient locked balance.");
        }
        lockedSats -= amountSats;
        availableSats = Math.addExact(availableSats, amountSats);
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /**
     * Credits a positive amount, first paying down reorganization debt and adding only the remainder
     * to available funds. This prevents a reversed chain credit from being recreated as spendable value.
     *
     * @param amountSats incoming amount in satoshis
     * @throws IllegalArgumentException when the amount is not positive or an invariant is broken
     * @throws ArithmeticException if the available total or nonce overflows
     */
    public void creditAvailable(long amountSats) {
        validateInvariant();
        requirePositive(amountSats);
        long debtPayment = Math.min(reorgDebtSats, amountSats);
        reorgDebtSats -= debtPayment;
        availableSats = Math.addExact(availableSats, amountSats - debtPayment);
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /**
     * Reverses an earlier available credit after a chain reorganization, consuming available funds
     * first and recording any uncovered remainder as reorg debt.
     *
     * @param amountSats credited amount to reverse in satoshis
     * @throws IllegalArgumentException when the amount is not positive or an invariant is broken
     * @throws ArithmeticException if debt or nonce arithmetic overflows
     */
    public void reverseAvailableCreditForReorg(long amountSats) {
        validateInvariant();
        requirePositive(amountSats);
        long availableDebit = Math.min(availableSats, amountSats);
        availableSats -= availableDebit;
        reorgDebtSats = Math.addExact(reorgDebtSats, amountSats - availableDebit);
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /** Updates the externally observed balance and increments the ledger nonce for reconciliation.
     * @param observedSats nonnegative observed balance in satoshis
     * @throws IllegalArgumentException when the value is negative
     */
    public void setObservedBalance(long observedSats) {
        if (observedSats < 0) {
            throw new IllegalArgumentException("observedSats must be non-negative.");
        }
        this.observedSats = observedSats;
        nonce = Math.addExact(nonce, 1L);
        validateInvariant();
    }

    /** Stores quality, timestamp, and source metadata from the same external balance probe.
     * @param quality probe quality label, such as live-mempool-aware or optimistic-delta
     * @param probedAt instant at which the probe completed
     * @param source provider or observation source identifier
     */
    public void setObservedProbeMeta(String quality, LocalDateTime probedAt, String source) {
        this.observedProbeQuality = quality;
        this.observedProbeAt = probedAt;
        this.observedProbeSource = source;
    }

    /** @return quality label for the last successful observed-balance probe */
    public String getObservedProbeQuality() {
        return observedProbeQuality;
    }

    /** @param observedProbeQuality quality label associated with the latest observation */
    public void setObservedProbeQuality(String observedProbeQuality) {
        this.observedProbeQuality = observedProbeQuality;
    }

    /** @return timestamp of the latest external balance probe, or null if no probe has succeeded */
    public LocalDateTime getObservedProbeAt() {
        return observedProbeAt;
    }

    /** @param observedProbeAt timestamp at which the provider observation was made */
    public void setObservedProbeAt(LocalDateTime observedProbeAt) {
        this.observedProbeAt = observedProbeAt;
    }

    /** @return provider or source identifier for the latest external balance observation */
    public String getObservedProbeSource() {
        return observedProbeSource;
    }

    /** @param observedProbeSource provider or source identifier for the latest observation */
    public void setObservedProbeSource(String observedProbeSource) {
        this.observedProbeSource = observedProbeSource;
    }

    /** Rejects zero or negative monetary mutations before any balance bucket is changed. */
    private void requirePositive(long amountSats) {
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
    }

    /** Validates the database CHECK invariant before and after every domain mutation. */
    /** Validates that every monetary bucket and the nonce satisfy their nonnegative persistence invariant. */
    public void validateInvariant() {
        requireNonNegative(availableSats, "availableSats");
        requireNonNegative(pendingSats, "pendingSats");
        requireNonNegative(lockedSats, "lockedSats");
        requireNonNegative(autoHoldSats, "autoHoldSats");
        requireNonNegative(observedSats, "observedSats");
        requireNonNegative(reorgDebtSats, "reorgDebtSats");
        requireNonNegative(nonce, "nonce");
    }

    /** Enforces the nonnegative database invariant with the bucket name in the exception message. */
    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative.");
        }
    }

    /** @return composite wallet and asset key for this balance row */
    public KfeBalanceId getId() {
        return id;
    }

    /** @param id composite key identifying the wallet and asset represented by this row */
    public void setId(KfeBalanceId id) {
        this.id = id;
    }

    /** @return currently spendable satoshi balance */
    public long getAvailableSats() {
        return availableSats;
    }

    /** Sets the available balance while rejecting negative values.
     * @param availableSats spendable satoshi amount
     * @throws IllegalArgumentException when the amount is negative
     */
    public void setAvailableSats(long availableSats) {
        requireNonNegative(availableSats, "availableSats");
        this.availableSats = availableSats;
    }

    /** @return credits awaiting transition into another ledger bucket */
    public long getPendingSats() {
        return pendingSats;
    }

    /** Sets pending credits while preserving the nonnegative balance invariant.
     * @param pendingSats pending satoshi amount
     * @throws IllegalArgumentException when the amount is negative
     */
    public void setPendingSats(long pendingSats) {
        requireNonNegative(pendingSats, "pendingSats");
        this.pendingSats = pendingSats;
    }

    /** @return amount reserved by in-progress debits */
    public long getLockedSats() {
        return lockedSats;
    }

    /** Sets reserved funds while preserving the nonnegative balance invariant.
     * @param lockedSats locked satoshi amount
     * @throws IllegalArgumentException when the amount is negative
     */
    public void setLockedSats(long lockedSats) {
        requireNonNegative(lockedSats, "lockedSats");
        this.lockedSats = lockedSats;
    }

    /** @return funds currently withheld by automatic risk or policy holds */
    public long getAutoHoldSats() {
        return autoHoldSats;
    }

    /** Sets automatic hold funds while preserving the nonnegative balance invariant.
     * @param autoHoldSats held satoshi amount
     * @throws IllegalArgumentException when the amount is negative
     */
    public void setAutoHoldSats(long autoHoldSats) {
        requireNonNegative(autoHoldSats, "autoHoldSats");
        this.autoHoldSats = autoHoldSats;
    }

    /** @return most recently observed external balance in satoshis */
    public long getObservedSats() {
        return observedSats;
    }

    /** @return uncovered amount from reversed chain credits that future credits must repay */
    public long getReorgDebtSats() {
        return reorgDebtSats;
    }

    /** Sets uncovered reorganization debt while preserving the nonnegative invariant.
     * @param reorgDebtSats uncovered satoshi debt
     * @throws IllegalArgumentException when the debt is negative
     */
    public void setReorgDebtSats(long reorgDebtSats) {
        if (reorgDebtSats < 0L) {
            throw new IllegalArgumentException("reorgDebtSats must be non-negative.");
        }
        this.reorgDebtSats = reorgDebtSats;
    }

    /** Sets the latest external balance observation without incrementing the domain nonce.
     * @param observedSats nonnegative observed satoshi amount
     * @throws IllegalArgumentException when the amount is negative
     */
    public void setObservedSats(long observedSats) {
        requireNonNegative(observedSats, "observedSats");
        this.observedSats = observedSats;
    }

    /** @return current monotonic balance mutation counter */
    public long getNonce() {
        return nonce;
    }

    /** Sets a nonnegative ledger mutation counter.
     * @param nonce version-like counter associated with this balance chain state
     * @throws IllegalArgumentException when the counter is negative
     */
    public void setNonce(long nonce) {
        requireNonNegative(nonce, "nonce");
        this.nonce = nonce;
    }

    /** @return preceding ledger-chain digest recorded for this row */
    public String getLastHash() {
        return lastHash;
    }

    /** @param lastHash preceding digest in the balance integrity chain */
    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    /** @return signature associated with the current persisted balance state */
    public String getBalanceSignature() {
        return balanceSignature;
    }

    /** @param balanceSignature integrity signature associated with the current balance state */
    public void setBalanceSignature(String balanceSignature) {
        this.balanceSignature = balanceSignature;
    }

    /** @return JPA optimistic-lock revision, incremented by the persistence provider */
    public Long getVersion() {
        return version;
    }

    /** @return UTC timestamp assigned by {@link #onWrite()} before persistence */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
