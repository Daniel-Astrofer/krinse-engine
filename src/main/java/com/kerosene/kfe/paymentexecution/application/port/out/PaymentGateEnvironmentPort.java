package com.kerosene.kfe.paymentexecution.application.port.out;

/** Runtime environment classification used to forbid simulated balances in production. */
public interface PaymentGateEnvironmentPort {
    /** @return true when the current deployment is production */
    boolean isProduction();
}
