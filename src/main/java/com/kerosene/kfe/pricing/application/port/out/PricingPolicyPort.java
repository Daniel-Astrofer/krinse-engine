package com.kerosene.kfe.pricing.application.port.out;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;

/** Outbound boundary for retrieving the versioned policy snapshot applicable to a quote. */
public interface PricingPolicyPort {
    /** Resolves the policy by rail and direction so calculation uses one coherent version. */
    PricingPolicySnapshot snapshot(PaymentRail rail, PaymentDirection direction);
}
