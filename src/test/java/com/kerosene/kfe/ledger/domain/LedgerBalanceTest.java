package com.kerosene.kfe.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerBalanceTest {
    private final UUID wallet = UUID.randomUUID();

    @Test
    void reserveReleaseConservesSpendableBalance() {
        LedgerBalance balance = new LedgerBalance(wallet, "BTC", 100L, 0L, 0L, 0L, 0L, 0L, 0L);
        balance.reserve(40L);
        assertThat(balance.availableSats()).isEqualTo(60L);
        assertThat(balance.lockedSats()).isEqualTo(40L);
        assertThat(balance.spendableTotal()).isEqualTo(100L);
        balance.releaseReserved(40L);
        assertThat(balance.availableSats()).isEqualTo(100L);
        assertThat(balance.lockedSats()).isZero();
    }

    @Test
    void settlementConsumesOnlyLockedFunds() {
        LedgerBalance balance = new LedgerBalance(wallet, "BTC", 0L, 0L, 50L, 0L, 0L, 0L, 0L);
        var transition = balance.settleReservedDebit(25L);
        assertThat(transition.spendableAfterSats()).isEqualTo(25L);
        assertThat(balance.lockedSats()).isEqualTo(25L);
    }

    @Test
    void creditAllocatedToReorgDebtDoesNotCreateNewSpendableFunds() {
        LedgerBalance balance = new LedgerBalance(wallet, "BTC", 0L, 0L, 0L, 0L, 0L, 50L, 0L);
        var transition = balance.creditAvailable(50L);
        assertThat(transition.spendableBeforeSats()).isEqualTo(0L);
        assertThat(transition.spendableAfterSats()).isEqualTo(0L);
        assertThat(balance.reorgDebtSats()).isZero();
    }

    @Test
    void rejectsNegativeAndOverflowingState() {
        assertThatThrownBy(() -> new LedgerBalance(wallet, "BTC", -1L, 0L, 0L, 0L, 0L, 0L, 0L))
                .isInstanceOf(LedgerInvariantViolation.class);
        LedgerBalance balance = new LedgerBalance(wallet, "BTC", Long.MAX_VALUE, 0L, 0L, 0L, 0L, 0L, 0L);
        assertThatThrownBy(() -> balance.creditAvailable(1L)).isInstanceOf(ArithmeticException.class);
    }
}
