package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KfeBalanceEntityReorgTest {

    @Test
    void reorgNeverMakesAvailableNegativeAndFutureCreditRepaysDebtFirst() {
        KfeBalanceEntity balance = KfeBalanceEntity.empty(UUID.randomUUID(), "BTC", "genesis");
        balance.creditAvailable(400L);

        balance.reverseAvailableCreditForReorg(1_000L);

        assertThat(balance.getAvailableSats()).isZero();
        assertThat(balance.getReorgDebtSats()).isEqualTo(600L);

        balance.creditAvailable(750L);

        assertThat(balance.getReorgDebtSats()).isZero();
        assertThat(balance.getAvailableSats()).isEqualTo(150L);
    }
}
