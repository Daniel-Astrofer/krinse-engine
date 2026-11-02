package com.kerosene.kfe.adapters.out.integration.rail;

import com.kerosene.kfe.adapters.out.rail.lightning.DisabledLightningGateway;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class KfeFinancialRailProductionSafetyAdapterTest {
    @Test void validatesCurrentBeanNamesAndIntentionalDisabledLightning() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("kfeExternalLightningInvoiceGateway", new DisabledLightningGateway());
        beans.registerSingleton("kfeExternalLightningPaymentGateway", new DisabledLightningGateway());
        var onchain = mock(KfeOnchainPaymentGateway.class);
        when(onchain.providerName()).thenReturn("BITCOIN_CORE_QUORUM");
        beans.registerSingleton("bitcoinCorePsbtKfeOnchainPaymentGateway", onchain);
        assertThat(new KfeFinancialRailProductionSafetyAdapter(beans).collectProductionViolations()).isEmpty();
        beans.destroySingleton("kfeExternalLightningPaymentGateway");
        assertThat(new KfeFinancialRailProductionSafetyAdapter(beans).collectProductionViolations()).anyMatch(v -> v.contains("must be available"));
    }
}
