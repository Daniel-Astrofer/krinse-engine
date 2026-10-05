package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;

/** Initial persistence only; lifecycle audit and subsequent financial effects remain the caller's responsibility. */
public interface PaymentIntentStore {

    /** Persists an initial intent before pricing and authorization gate preparation. */
    /** @param intent validated creation-only payment intent @return generated payment execution identity */
    PaymentExecutionId create(PaymentIntent intent);
}
