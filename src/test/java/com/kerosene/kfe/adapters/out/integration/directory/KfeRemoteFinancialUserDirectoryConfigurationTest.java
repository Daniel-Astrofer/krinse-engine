package com.kerosene.kfe.adapters.out.integration.directory;

import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import com.kerosene.kfe.bootstrap.config.financial.KfeFinancialFallbackConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KfeRemoteFinancialUserDirectoryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("kfe"))
            .withBean(
                    InternalServiceRestTemplateFactory.class,
                    () -> WorkloadIdentityTestClients.legacy("credential"))
            .withUserConfiguration(
                    KfeFinancialFallbackConfiguration.class,
                    KfeRemoteFinancialUserDirectoryClient.class)
            .withPropertyValues("kfe.internal.shared-secret=credential");

    @Test
    void kfeProfileUsesRemoteDirectoryInsteadOfEmptyFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FinancialUserDirectoryPort.class);
            assertThat(context.getBean(FinancialUserDirectoryPort.class))
                    .isInstanceOf(KfeRemoteFinancialUserDirectoryClient.class);
        });
    }
}
