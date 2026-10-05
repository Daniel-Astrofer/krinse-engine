package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.AuthorizationRequirement;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAuthorizationPolicyTest {

    private final PaymentAuthorizationPolicy policy = new PaymentAuthorizationPolicy();

    @Test
    void internalAndOnchainOutboundRequireLocalFactorAndAssertion() {
        assertThat(policy.requirementFor(PaymentRail.INTERNAL, PaymentDirection.INTERNAL))
                .isEqualTo(AuthorizationRequirement.LOCAL_FACTOR_AND_ASSERTION);
        assertThat(policy.requirementFor(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND))
                .isEqualTo(AuthorizationRequirement.LOCAL_FACTOR_AND_ASSERTION);
    }

    @Test
    void lightningOutboundRequiresWalletStepUp() {
        assertThat(policy.requirementFor(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND))
                .isEqualTo(AuthorizationRequirement.WALLET_OUTBOUND_STEP_UP);
    }

    @Test
    void inboundObservationDoesNotRequestTransactionalStepUp() {
        assertThat(policy.requirementFor(PaymentRail.ONCHAIN, PaymentDirection.INBOUND))
                .isEqualTo(AuthorizationRequirement.NONE);
        assertThat(policy.requirementFor(PaymentRail.LIGHTNING, PaymentDirection.INBOUND))
                .isEqualTo(AuthorizationRequirement.NONE);
    }
}
