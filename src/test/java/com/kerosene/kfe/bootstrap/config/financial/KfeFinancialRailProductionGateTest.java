package com.kerosene.kfe.bootstrap.config.financial;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class KfeFinancialRailProductionGateTest {
    @Test void rejectsUnsafeRailConfiguration() {
        var gate = new KfeFinancialRailProductionGate(() -> List.of("unsafe signer"));
        assertThatThrownBy(() -> gate.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("unsafe signer");
    }
    @Test void acceptsVerifiedRailConfiguration() {
        var gate = new KfeFinancialRailProductionGate(List::of);
        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }
}
