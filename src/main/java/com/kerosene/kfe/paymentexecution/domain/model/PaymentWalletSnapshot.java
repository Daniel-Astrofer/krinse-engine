package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Payment Execution's minimal view of a wallet; it is not the Wallet aggregate.
 * @param id wallet identity
 * @param userId owning account identifier
 * @param active whether the wallet can currently be used
 * @param watchOnly whether the wallet cannot authorize outgoing funds
 * @param spendable whether policy permits wallet funds to move
 */
public record PaymentWalletSnapshot(UUID id, long userId, boolean active, boolean watchOnly, boolean spendable) {
    /** Validates that the snapshot has a wallet identity and positive account owner. */
    public PaymentWalletSnapshot {
        if (id == null || userId <= 0L) {
            throw new IllegalArgumentException("wallet id and owner are required");
        }
    }

    /** Returns whether the wallet is active, not watch-only, and marked spendable. */
    /** @return true only when all three spendability requirements hold */
    public boolean usable() { return active && !watchOnly && spendable; }

    /** Requires the wallet to be active and spendable for the supplied payment role. */
    /** @param role source/destination label included in validation errors @throws IllegalStateException when inactive or unable to spend */
    public void requireSpendable(String role) {
        if (!active) {
            throw new IllegalStateException(role + " wallet is not active.");
        }
        if (watchOnly || !spendable) {
            throw new IllegalStateException(role + " wallet is watch-only and cannot move funds.");
        }
    }
}
