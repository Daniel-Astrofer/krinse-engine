package com.kerosene.kfe.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Framework-free balance aggregate. All arithmetic is checked and every mutation
 * returns an immutable transition describing the conservation delta.
 */
public final class LedgerBalance {
    /** Wallet whose balances and movements this aggregate represents. */
    private final UUID walletId;
    /** Ledger asset code associated with every amount in this aggregate. */
    private final String asset;
    /** Funds currently available for new spending. */
    private long availableSats;
    /** Funds credited provisionally but not yet available for spending. */
    private long pendingSats;
    /** Funds reserved for an in-flight debit and unavailable to other operations. */
    private long lockedSats;
    /** Funds held from ordinary spending by an automatic hold policy. */
    private long autoHoldSats;
    /** Absolute chain-observed amount, tracked separately from internal liabilities. */
    private long observedSats;
    /** Unrecovered debt created when a chain reorganization reverses a prior credit. */
    private long reorgDebtSats;
    /** Monotonic mutation version used to detect stale aggregate snapshots. */
    private long version;

    /**
     * Creates a balance snapshot after validating identity, asset, and every nonnegative bucket.
     * @param walletId wallet identity owning this balance
     * @param asset asset code associated with the amounts
     * @param availableSats spendable amount
     * @param pendingSats provisional amount
     * @param lockedSats reserved amount
     * @param autoHoldSats amount held by policy
     * @param observedSats amount seen on chain
     * @param reorgDebtSats outstanding compensation debt
     * @param version persisted aggregate version
     */
    public LedgerBalance(UUID walletId, String asset, long availableSats, long pendingSats,
            long lockedSats, long autoHoldSats, long observedSats, long reorgDebtSats, long version) {
        this.walletId = Objects.requireNonNull(walletId, "walletId is required");
        this.asset = requireAsset(asset);
        this.availableSats = nonNegative(availableSats, "availableSats");
        this.pendingSats = nonNegative(pendingSats, "pendingSats");
        this.lockedSats = nonNegative(lockedSats, "lockedSats");
        this.autoHoldSats = nonNegative(autoHoldSats, "autoHoldSats");
        this.observedSats = nonNegative(observedSats, "observedSats");
        this.reorgDebtSats = nonNegative(reorgDebtSats, "reorgDebtSats");
        this.version = nonNegative(version, "version");
    }

    /** Moves a positive amount from available to locked, failing when spendable funds are insufficient. */
    public Transition reserve(long amountSats) {
        positive(amountSats);
        if (availableSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient available balance");
        }
        long before = spendableTotal();
        availableSats = Math.subtractExact(availableSats, amountSats);
        lockedSats = Math.addExact(lockedSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.RESERVE, amountSats, 0L,
                LedgerBucket.AVAILABLE, LedgerBucket.LOCKED);
    }

    /** Returns a positive reservation from locked to available without changing spendable total. */
    public Transition releaseReserved(long amountSats) {
        positive(amountSats);
        if (lockedSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient locked balance");
        }
        long before = spendableTotal();
        lockedSats = Math.subtractExact(lockedSats, amountSats);
        availableSats = Math.addExact(availableSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.RELEASE_RESERVE, amountSats, 0L,
                LedgerBucket.LOCKED, LedgerBucket.AVAILABLE);
    }

    /** Consumes a positive locked reservation as a settled debit and decreases spendable total. */
    public Transition settleReservedDebit(long amountSats) {
        positive(amountSats);
        if (lockedSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient locked balance");
        }
        long before = spendableTotal();
        lockedSats = Math.subtractExact(lockedSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.SETTLE_DEBIT, amountSats, Math.negateExact(amountSats),
                LedgerBucket.LOCKED, null);
    }

    /** Applies an ordinary positive credit to reorg debt first, then credits any remainder as available. */
    public Transition creditAvailable(long amountSats) {
        positive(amountSats);
        long before = spendableTotal();
        long debtPayment = Math.min(reorgDebtSats, amountSats);
        reorgDebtSats = Math.subtractExact(reorgDebtSats, debtPayment);
        availableSats = Math.addExact(availableSats, Math.subtractExact(amountSats, debtPayment));
        bump();
        return transition(before, debtPayment == amountSats
                ? LedgerMovementType.COMPENSATING_CREDIT : LedgerMovementType.CREDIT,
                amountSats, Math.subtractExact(amountSats, debtPayment), null, LedgerBucket.AVAILABLE);
    }

    /** Settlement credit is a liability transfer and must not be consumed by reorg debt. */
    /** Credits a settlement liability directly to available without applying reorg-debt offset. */
    public Transition creditSettlement(long amountSats) {
        positive(amountSats);
        long before = spendableTotal();
        availableSats = Math.addExact(availableSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.CREDIT, amountSats, amountSats,
                null, LedgerBucket.AVAILABLE);
    }

    /** Returns available, pending, locked, and auto-held liabilities using overflow-checked addition. */
    public long spendableTotal() {
        return Math.addExact(Math.addExact(availableSats, pendingSats),
                Math.addExact(lockedSats, autoHoldSats));
    }

    /** Verifies the expected conservation delta and builds the immutable movement result. */
    private Transition transition(long before, LedgerMovementType type, long amount, long expectedDelta,
            LedgerBucket from, LedgerBucket to) {
        long after = spendableTotal();
        if (Math.subtractExact(after, before) != expectedDelta) {
            throw new LedgerInvariantViolation("ledger conservation invariant violated");
        }
        return new Transition(type, amount, from, to, before, after, version);
    }

    /** Advances the aggregate version with overflow checking after each successful mutation. */
    private void bump() {
        version = Math.addExact(version, 1L);
    }

    /** Validates and returns a nonblank asset code no longer than the persisted limit. */
    private static String requireAsset(String asset) {
        if (asset == null || asset.isBlank() || asset.length() > 16) {
            throw new LedgerInvariantViolation("asset is required and must be at most 16 characters");
        }
        return asset;
    }

    /** Rejects a negative bucket or version value with a named invariant error. */
    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new LedgerInvariantViolation(name + " must be non-negative");
        }
        return value;
    }

    /** Requires a strictly positive amount for every public movement operation. */
    private static void positive(long value) {
        if (value <= 0L) {
            throw new LedgerInvariantViolation("amountSats must be positive");
        }
    }

    /** Returns the owning wallet identity. */
    public UUID walletId() { return walletId; }
    /** Returns the asset code for all balance amounts. */
    public String asset() { return asset; }
    /** Returns funds available for spending. */
    public long availableSats() { return availableSats; }
    /** Returns provisional funds not yet made available. */
    public long pendingSats() { return pendingSats; }
    /** Returns funds reserved for an in-flight debit. */
    public long lockedSats() { return lockedSats; }
    /** Returns funds held by automatic policy. */
    public long autoHoldSats() { return autoHoldSats; }
    /** Returns the independently tracked chain-observed amount. */
    public long observedSats() { return observedSats; }
    /** Returns outstanding debt from reversed chain credits. */
    public long reorgDebtSats() { return reorgDebtSats; }
    /** Returns the monotonic mutation version. */
    public long version() { return version; }

    /** Immutable accounting result describing the movement and conservation totals.
     * @param movementType semantic operation applied to the ledger
     * @param amountSats positive movement amount
     * @param fromBucket source bucket, or null for an external credit
     * @param toBucket destination bucket, or null for a settled debit
     * @param spendableBeforeSats spendable liability total before mutation
     * @param spendableAfterSats spendable liability total after mutation
     * @param version aggregate version after the mutation
     */
    public record Transition(LedgerMovementType movementType, long amountSats,
            LedgerBucket fromBucket, LedgerBucket toBucket, long spendableBeforeSats,
            long spendableAfterSats, long version) { }
}
