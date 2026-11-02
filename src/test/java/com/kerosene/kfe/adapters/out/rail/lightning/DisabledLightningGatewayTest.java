package com.kerosene.kfe.adapters.out.rail.lightning;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledLightningGatewayTest {
    @Test void refusesEveryFinancialOperation() {
        var gateway = new DisabledLightningGateway();
        assertThat(gateway.isLive()).isFalse();
        assertThatThrownBy(() -> gateway.createLightningInvoice(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.getLightningInvoiceStatus(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.cancelLightningInvoice(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.payLightning(null)).isInstanceOf(IllegalStateException.class);
    }
}
