package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerMutationObserver;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerInvariantViolation;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import com.kerosene.kfe.ledger.domain.LedgerPosting;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Coordinates a locked balance mutation and its immutable posting atomically. */
public final class LedgerService {
    /** Locks and reads the wallet/asset balance aggregate before mutation. */
    private final LedgerAccountPort accounts;
    /** Provides idempotency lookup and immutable ledger-posting persistence. */
    private final LedgerPostingPort postings;
    /** Supplies deterministic mutation timestamps for tests and production composition. */
    private final Clock clock;
    /** Notifies projection/notification adapters after a posting and balance save are applied. */
    private final LedgerMutationObserver observer;

    /**
     * Creates a ledger service with a no-op mutation observer for callers without projection hooks.
     * All ports and clock are still validated by the full constructor.
     *
     * @param accounts locked ledger balance account port
     * @param postings idempotent posting store
     * @param clock timestamp source
     */
    public LedgerService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock) {
        this(accounts, postings, clock, (balance, transition) -> { });
    }

    /**
     * Creates the ledger mutation coordinator. Callers must provide a transaction boundary spanning
     * posting append and balance save so a failure cannot commit only one side of the ledger mutation.
     *
     * @param accounts locked ledger balance account port
     * @param postings immutable posting and idempotency port
     * @param clock source used to timestamp postings
     * @param observer callback for downstream balance projections
     */
    public LedgerService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock,
            LedgerMutationObserver observer) {
        this.accounts = Objects.requireNonNull(accounts, "accounts is required");
        this.postings = Objects.requireNonNull(postings, "postings is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.observer = Objects.requireNonNull(observer, "observer is required");
    }

    /**
     * Moves amount from AVAILABLE to RESERVED and appends the corresponding idempotent posting.
     *
     * @param command mutation identity and amount
     * @return updated locked balance, or current balance on same-operation replay
     */
    public LedgerBalance reserve(Command command) { return apply(command, LedgerMovementType.RESERVE); }
    /**
     * Releases amount from RESERVED back to AVAILABLE and appends a release posting.
     *
     * @param command mutation identity and amount
     * @return updated locked balance, or current balance on same-operation replay
     */
    public LedgerBalance release(Command command) { return apply(command, LedgerMovementType.RELEASE_RESERVE); }
    /**
     * Consumes amount from RESERVED as a settled debit and appends a settlement posting.
     *
     * @param command mutation identity and amount
     * @return updated locked balance, or current balance on same-operation replay
     */
    public LedgerBalance settleDebit(Command command) { return apply(command, LedgerMovementType.SETTLE_DEBIT); }
    /**
     * Adds amount to AVAILABLE and appends a credit posting.
     *
     * @param command mutation identity and amount
     * @return updated locked balance, or current balance on same-operation replay
     */
    public LedgerBalance credit(Command command) { return apply(command, LedgerMovementType.CREDIT); }

    /**
     * Applies one typed balance transition. It locks the balance, treats an existing operation/type
     * posting as an idempotent replay, delegates bucket invariants to {@link LedgerBalance}, appends
     * the immutable posting before saving the balance, and invokes the observer after both writes.
     * Posting and account ports must share the caller's transaction boundary.
     *
     * @param command operation identity, wallet, asset, amount, and audit correlation metadata
     * @param type movement type selecting the balance transition
     * @return updated locked aggregate or current balance for an already-posted operation
     * @throws LedgerInvariantViolation when the balance is missing, transition is invalid, or append races
     */
    private LedgerBalance apply(Command command, LedgerMovementType type) {
        Objects.requireNonNull(command, "command is required");
        LedgerBalance balance = accounts.lock(command.walletId(), command.asset());
        if (balance == null) {
            throw new LedgerInvariantViolation("ledger balance not found");
        }
        if (postings.exists(command.operationId(), type)) {
            return balance;
        }
        LedgerBalance.Transition transition = switch (type) {
            case RESERVE -> balance.reserve(command.amountSats());
            case RELEASE_RESERVE -> balance.releaseReserved(command.amountSats());
            case SETTLE_DEBIT -> balance.settleReservedDebit(command.amountSats());
            case CREDIT -> balance.creditAvailable(command.amountSats());
            default -> throw new LedgerInvariantViolation("unsupported ledger mutation " + type);
        };
        LedgerPosting posting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.walletId(), command.asset(), transition.movementType(), command.amountSats(),
                transition.fromBucket(), transition.toBucket(), command.reason(),
                command.correlationId(), command.causationId(), Instant.now(clock));
        if (!postings.appendIfAbsent(posting)) {
            throw new LedgerInvariantViolation("duplicate ledger posting");
        }
        accounts.save(balance);
        observer.afterApplied(balance, transition);
        return balance;
    }

    /**
     * Immutable identity and audit context for one ledger bucket movement.
     *
     * @param operationId stable idempotency scope supplied by the initiating business operation
     * @param walletId wallet balance aggregate to lock and mutate
     * @param asset ledger asset code
     * @param amountSats positive movement amount in satoshis, validated by the domain transition
     * @param reason optional human-readable movement reason
     * @param correlationId optional trace/group identifier
     * @param causationId optional event/command that caused this movement
     */
    public record Command(UUID operationId, UUID walletId, String asset, long amountSats,
            String reason, UUID correlationId, UUID causationId) {
        /**
         * Requires operation, wallet, and asset identity; amount validity is enforced by the balance domain.
         *
         * @param operationId stable idempotency identity
         * @param walletId target wallet
         * @param asset target ledger asset
         * @param amountSats movement amount
         * @param reason optional reason metadata
         * @param correlationId optional correlation metadata
         * @param causationId optional causation metadata
         */
        public Command {
            Objects.requireNonNull(operationId, "operationId is required");
            Objects.requireNonNull(walletId, "walletId is required");
            Objects.requireNonNull(asset, "asset is required");
        }
    }
}
