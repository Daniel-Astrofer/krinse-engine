package com.kerosene.kfe.ledger.adapters.out.persistence.balance;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KfeBalancePrimarySatsTest {

    @Test
    void coldPrimaryIsObserved() {
        KfeBalanceEntity balance = KfeBalanceEntity.empty(UUID.randomUUID(), "BTC", "h");
        balance.setAvailableSats(1000L);
        balance.setObservedSats(50_000L);
        assertThat(KfeBalanceService.primarySatsFor(KfeWalletKind.WATCH_ONLY, balance)).isEqualTo(50_000L);
    }

    @Test
    void custodialPrimaryIsAvailableNotObserved() {
        KfeBalanceEntity balance = KfeBalanceEntity.empty(UUID.randomUUID(), "BTC", "h");
        balance.setAvailableSats(12_000L);
        balance.setObservedSats(99_000L);
        assertThat(KfeBalanceService.primarySatsFor(KfeWalletKind.CUSTODIAL_ONCHAIN, balance)).isEqualTo(12_000L);
    }

    @Test
    void internalPrimaryIsAvailable() {
        KfeBalanceEntity balance = KfeBalanceEntity.empty(UUID.randomUUID(), "BTC", "h");
        balance.setAvailableSats(7_000L);
        balance.setObservedSats(0L);
        assertThat(KfeBalanceService.primarySatsFor(KfeWalletKind.INTERNAL, balance)).isEqualTo(7_000L);
    }
}
