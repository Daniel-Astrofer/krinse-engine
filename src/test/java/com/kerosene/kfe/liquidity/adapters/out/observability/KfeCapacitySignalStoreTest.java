package com.kerosene.kfe.liquidity.adapters.out.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KfeCapacitySignalStoreTest {

    @Test
    void accumulatesLiquidityRejectsInWindow() {
        KfeCapacitySignalStore store = new KfeCapacitySignalStore();
        store.recordLiquidityReject();
        store.recordLiquidityReject();
        store.recordGateFail();

        KfeCapacitySignalStore.CapacitySignals snap = store.snapshot(900_000L);
        assertThat(snap.liquidityRejects()).isEqualTo(2L);
        assertThat(snap.gateFails()).isEqualTo(1L);
        assertThat(snap.windowAgeMs()).isGreaterThanOrEqualTo(0L);
    }
}
