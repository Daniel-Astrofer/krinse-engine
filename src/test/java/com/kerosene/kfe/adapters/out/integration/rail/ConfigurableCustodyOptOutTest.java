package com.kerosene.kfe.adapters.out.integration.rail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.custody.ConfigurableCustodyGateway;
import com.kerosene.common.financial.operations.FinancialRailHealthPort.HealthState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableCustodyOptOutTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("custodyRestTemplate", RestTemplate.class, RestTemplate::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(ConfigurableCustodyGateway.class, KfeFinancialRailHealthAdapter.class);

    @Test
    void explicitNativeCustodyOptOutDoesNotInstantiateExternalGateway() {
        runner.withPropertyValues("custody.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ConfigurableCustodyGateway.class);
            assertThat(context.getBean(KfeFinancialRailHealthAdapter.class).custodyProviderHealth()).isNull();
        });
    }

    @Test
    void enabledGatewayWithoutCredentialsRemainsUnavailable() {
        runner.withPropertyValues("custody.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ConfigurableCustodyGateway.class);
            assertThat(context.getBean(KfeFinancialRailHealthAdapter.class).custodyProviderHealth().state())
                    .isEqualTo(HealthState.UNAVAILABLE);
        });
    }

    @Test
    void existingDeploymentsRetainGatewayWhenFlagIsAbsent() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConfigurableCustodyGateway.class));
    }
}
