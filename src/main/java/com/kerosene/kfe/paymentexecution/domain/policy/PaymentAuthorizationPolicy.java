package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.AuthorizationRequirement;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Decides the strength of end-user authorization required by a payment. */
public final class PaymentAuthorizationPolicy {

    public AuthorizationRequirement requirementFor(PaymentRail rail, PaymentDirection direction) {
        if (rail == null || direction == null) {
            throw new IllegalArgumentException("rail and direction are required");
        }
        if (rail == PaymentRail.INTERNAL || direction == PaymentDirection.INTERNAL) {
            return AuthorizationRequirement.LOCAL_FACTOR_AND_ASSERTION;
        }
        if (rail == PaymentRail.ONCHAIN && direction == PaymentDirection.OUTBOUND) {
            return AuthorizationRequirement.LOCAL_FACTOR_AND_ASSERTION;
        }
        if (direction == PaymentDirection.OUTBOUND) {
            return AuthorizationRequirement.WALLET_OUTBOUND_STEP_UP;
        }
        return AuthorizationRequirement.NONE;
    }
}
