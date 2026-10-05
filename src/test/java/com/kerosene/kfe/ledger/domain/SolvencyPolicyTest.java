package com.kerosene.kfe.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolvencyPolicyTest {
    @Test
    void usesCheckedBufferArithmeticAndFailsClosedWhenProbesAreEmpty() {
        var policy = new SolvencyPolicy(new SolvencyPolicy.Config(5_000L, 1.0, true));
        var insolvent = policy.evaluate(100L, 0L, 0L, 0L);
        assertThat(insolvent.solvent()).isFalse();
        assertThat(insolvent.status()).isEqualTo("INSOLVENT");
        assertThat(policy.evaluate(0L, 0L, 0L, 0L).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void rejectsUnsafeConfigurationAndInput() {
        assertThatThrownBy(() -> new SolvencyPolicy.Config(10_001L, 1.0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolvencyPolicy.Config(0L, Double.NaN, true))
                .isInstanceOf(IllegalArgumentException.class);
        var policy = new SolvencyPolicy(new SolvencyPolicy.Config(10_000L, 1.0, true));
        assertThatThrownBy(() -> policy.evaluate(Long.MAX_VALUE, 1L, 0L, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
