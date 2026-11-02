package com.kerosene.kfe.liquidity.adapters.in;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class KfeTreasuryPropertiesTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(KfeTreasuryProperties.class)
    static class Config { }
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test void absentPolicyFailsClosedWithoutBreakingStartup() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(KfeTreasuryProperties.class).policy().configured()).isFalse();
        });
    }
    @Test void bindsVersionedNestedPolicy() {
        context.withPropertyValues("kfe.treasury.policy.version=v1",
                "kfe.treasury.policy.capital-wallet-id=00000000-0000-0000-0000-000000000001",
                "kfe.treasury.policy.reserve-floor-sats=100000", "kfe.treasury.policy.min-channel-sats=100000",
                "kfe.treasury.policy.max-channel-sats=500000", "kfe.treasury.policy.max-rebalance-sats=100000",
                "kfe.treasury.policy.max-fee-sats=1000", "kfe.treasury.policy.max-channels=5",
                "kfe.treasury.policy.max-peer-share-bps=10000", "kfe.treasury.policy.target-local-bps=5000",
                "kfe.treasury.policy.tolerance-bps=1000", "kfe.treasury.policy.max-snapshot-age-seconds=120",
                "kfe.treasury.policy.allowed-peers[0]=02"+"a".repeat(64)).run(c -> {
            assertThat(c).hasNotFailed();
            var policy=c.getBean(KfeTreasuryProperties.class).policy();
            assertThat(policy.configured()).isTrue();
            assertThat(policy.version()).isEqualTo("v1");
        });
    }
}
