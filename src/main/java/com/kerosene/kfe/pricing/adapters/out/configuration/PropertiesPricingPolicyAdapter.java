package com.kerosene.kfe.pricing.adapters.out.configuration;

import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import org.springframework.stereotype.Component;

/** Translates Spring-bound configuration into an immutable domain snapshot. */
@Component
public class PropertiesPricingPolicyAdapter implements PricingPolicyPort {

    /** Spring-bound properties supplying the version and per-rail basis-point/clamp settings. */
    private final PricingProperties policy;

    /** Creates the adapter with the bound pricing policy. */
    public PropertiesPricingPolicyAdapter(PricingProperties policy) {
        this.policy = policy;
    }

    /** Resolves the exact rail-direction key and maps absent settings to the domain default. */
    @Override
    public PricingPolicySnapshot snapshot(PaymentRail rail, PaymentDirection direction) {
        PricingProperties.RailPricing configured = policy.forRailDirection(rail.name() + "-" + direction.name());
        PricingPolicySnapshot.RailPricing railPricing = configured == null
                ? null
                : new PricingPolicySnapshot.RailPricing(
                        configured.getBasisPoints(),
                        asLong(configured.getMinSats()),
                        asLong(configured.getMaxSats()));
        return new PricingPolicySnapshot(policy.getVersion(), railPricing);
    }

    /** Converts a nullable integer property into the domain's nullable long amount. */
    private static Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
