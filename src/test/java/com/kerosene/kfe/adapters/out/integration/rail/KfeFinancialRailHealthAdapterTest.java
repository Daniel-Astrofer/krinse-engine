package com.kerosene.kfe.adapters.out.integration.rail;

import com.kerosene.kfe.adapters.out.rail.custody.BtcPayServerCustodyGateway;
import com.kerosene.kfe.adapters.out.rail.custody.ConfigurableCustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeFinancialRailHealthAdapterTest {
    @Test
    void custodyHealthResolvesGatewayEvenWhenLightningBeansReferToSameInstance() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var gateway = mock(ConfigurableCustodyGateway.class);
            when(gateway.providerName()).thenReturn("configured");
            context.registerBean("kfeConfigurableCustodyGateway", ConfigurableCustodyGateway.class, () -> gateway);
            context.registerBean("kfeExternalLightningInvoiceGateway", LightningInvoiceGateway.class, () -> gateway);
            context.registerBean("kfeExternalLightningPaymentGateway", LightningPaymentGateway.class, () -> gateway);
            context.register(KfeFinancialRailHealthAdapter.class);
            context.refresh();
            var health = context.getBean(KfeFinancialRailHealthAdapter.class).custodyProviderHealth();
            assertThat(health.providerName()).isEqualTo("configured");
            assertThat(health.state()).isEqualTo(com.kerosene.common.financial.operations.FinancialRailHealthPort.HealthState.UNAVAILABLE);
        }
    }

    @Test
    void prefersBtcpayWhenBothCustodyImplementationsAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("test", java.util.Map.of("btcpay.enabled", "true")));
            var gateway = mock(BtcPayServerCustodyGateway.class);
            when(gateway.providerName()).thenReturn("btcpay");
            context.registerBean("kfeBtcpayCustodyGateway", BtcPayServerCustodyGateway.class, () -> gateway);
            context.registerBean("kfeConfigurableCustodyGateway", ConfigurableCustodyGateway.class, () -> mock(ConfigurableCustodyGateway.class));
            context.register(KfeFinancialRailHealthAdapter.class);
            context.refresh();
            assertThat(context.getBean(KfeFinancialRailHealthAdapter.class).custodyProviderHealth().providerName()).isEqualTo("btcpay");
        }
    }
}
