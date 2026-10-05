package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoProjectionPolicyTest {
    @Test
    void mergesDuplicateOutpointsUsingTheFreshestConfirmationObservation() {
        var projection = UtxoProjectionPolicy.newProjection();
        var outpoint = new Outpoint("TXID", 0);
        UtxoProjectionPolicy.merge(projection,
                new UtxoSnapshot(outpoint, 1_000L, "0014", "tb1qsource", 1));
        UtxoProjectionPolicy.merge(projection,
                new UtxoSnapshot(outpoint, 1_000L, "0014", "tb1qsource", 6));

        assertThat(projection).hasSize(1);
        assertThat(projection.get(outpoint).confirmations()).isEqualTo(6);
    }
}
