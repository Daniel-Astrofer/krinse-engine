package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerBucket;
import com.kerosene.kfe.ledger.domain.LedgerInvariantViolation;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import com.kerosene.kfe.ledger.domain.LedgerPosting;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic internal settlement coordinator. Wallet locks are acquired in a stable
 * UUID order so opposite transfers cannot deadlock at the ledger boundary.
 */
public final class LedgerSettlementService {
    /** Loads both wallet aggregates under database locks for an atomic transfer. */
    private final LedgerAccountPort accounts;
    /** Checks and appends the immutable debit and credit postings for the operation. */
    private final LedgerPostingPort postings;
    /** Supplies one consistent timestamp for the pair of settlement postings. */
    private final Clock clock;

    /**
     * Creates the internal transfer coordinator. The caller must supply a transaction spanning
     * both wallet locks, both postings, and both account saves.
     *
     * @param accounts wallet balance locking and persistence port
     * @param postings idempotent append-only posting port
     * @param clock source used to timestamp the paired postings
     */
    public LedgerSettlementService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts is required");
        this.postings = Objects.requireNonNull(postings, "postings is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Moves an already-reserved amount between two distinct wallets as one balanced operation.
     * Wallet locks are always acquired in UUID order to prevent opposite-direction transfers from
     * deadlocking. Replays are identified by the operation's settlement-debit posting. The caller
     * must provide the transaction boundary that makes posting insertion and balance saves atomic.
     *
     * @param command transfer identity, source/destination wallets, asset, positive amount and audit metadata
     * @throws LedgerInvariantViolation when wallets match, reservations are insufficient, or postings conflict
     */
    public void settle(Command command) {
        Objects.requireNonNull(command, "command is required");
        if (command.debitWalletId().equals(command.creditWalletId())) {
            throw new LedgerInvariantViolation("settlement source and destination must differ");
        }
        boolean debitFirst = command.debitWalletId().toString()
                .compareTo(command.creditWalletId().toString()) < 0;
        LedgerBalance first = accounts.lock(debitFirst ? command.debitWalletId() : command.creditWalletId(), command.asset());
        LedgerBalance second = accounts.lock(debitFirst ? command.creditWalletId() : command.debitWalletId(), command.asset());
        LedgerBalance debit = debitFirst ? first : second;
        LedgerBalance credit = debitFirst ? second : first;
        if (postings.exists(command.operationId(), LedgerMovementType.SETTLE_DEBIT)) {
            return;
        }
        debit.settleReservedDebit(command.amountSats());
        credit.creditSettlement(command.amountSats());
        Instant at = Instant.now(clock);
        LedgerPosting debitPosting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.debitWalletId(), command.asset(), LedgerMovementType.SETTLE_DEBIT,
                command.amountSats(), LedgerBucket.LOCKED, null, command.reason(),
                command.correlationId(), command.causationId(), at);
        LedgerPosting creditPosting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.creditWalletId(), command.asset(), LedgerMovementType.CREDIT,
                command.amountSats(), null, LedgerBucket.AVAILABLE, command.reason(),
                command.correlationId(), command.causationId(), at);
        if (!postings.appendIfAbsent(debitPosting) || !postings.appendIfAbsent(creditPosting)) {
            throw new LedgerInvariantViolation("settlement posting was not unique");
        }
        accounts.save(debit);
        accounts.save(credit);
    }

    /** Immutable input required to settle a reserved internal wallet transfer.
     * @param operationId stable idempotency key shared by the paired postings
     * @param debitWalletId wallet whose locked reservation will be consumed
     * @param creditWalletId destination wallet that receives an available-balance credit
     * @param asset asset code shared by the source and destination balances
     * @param amountSats positive reserved amount transferred in satoshis
     * @param reason optional human-readable reason copied to both postings
     * @param correlationId optional identifier for grouping related ledger work
     * @param causationId optional initiating command or event identifier
     */
    public record Command(UUID operationId, UUID debitWalletId, UUID creditWalletId,
            String asset, long amountSats, String reason, UUID correlationId, UUID causationId) {
        /** Validates mandatory identities and rejects zero or negative settlement amounts. */
        public Command {
            Objects.requireNonNull(operationId, "operationId is required");
            Objects.requireNonNull(debitWalletId, "debitWalletId is required");
            Objects.requireNonNull(creditWalletId, "creditWalletId is required");
            Objects.requireNonNull(asset, "asset is required");
            if (amountSats <= 0L) {
                throw new LedgerInvariantViolation("amountSats must be positive");
            }
        }
    }
}
