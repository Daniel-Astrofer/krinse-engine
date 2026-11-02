package com.kerosene.kfe.liquidity.adapters.in;

import com.kerosene.kfe.liquidity.domain.treasury.TreasuryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds runtime configuration without introducing Spring into the financial domain. */
@ConfigurationProperties("kfe.treasury")
public record KfeTreasuryProperties(TreasuryPolicy policy) {
    public KfeTreasuryProperties {
        if (policy == null) policy = TreasuryPolicy.unconfigured();
    }
}
