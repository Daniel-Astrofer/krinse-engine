package com.kerosene.kfe.wallet.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColdObservationPolicyTest {
    @Test
    void creditsOnlyAfterConfiguredFinality() {
        assertThat(ColdObservationPolicy.isCreditFinal(2, 3)).isFalse();
        assertThat(ColdObservationPolicy.isCreditFinal(3, 3)).isTrue();
        assertThat(ColdObservationPolicy.isCreditFinal(-1, 0)).isFalse();
    }

    @Test
    void neverMovesConfirmationProjectionBackwards() {
        assertThat(ColdObservationPolicy.mayAdvance(6, 3)).isFalse();
        assertThat(ColdObservationPolicy.mayAdvance(3, 6)).isTrue();
    }
}
