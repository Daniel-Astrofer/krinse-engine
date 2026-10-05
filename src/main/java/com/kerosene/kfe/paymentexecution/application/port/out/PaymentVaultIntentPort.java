package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Optional outbound intent notification; does not replace durable execution commands. */
public interface PaymentVaultIntentPort {
    /** Notifies the vault integration about an outbound intent with its rail and amount. */
    void notifyOutbound(PaymentExecutionId executionId, PaymentRail rail, PaymentDirection direction,
                        String externalReference, long grossAmountSats);
}
