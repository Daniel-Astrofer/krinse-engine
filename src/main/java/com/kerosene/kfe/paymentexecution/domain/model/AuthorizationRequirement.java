package com.kerosene.kfe.paymentexecution.domain.model;

/** Domain-selected factor policy for a payment rail and direction. */
public enum AuthorizationRequirement {
    /** No step-up factor is required by the payment authorization policy. */
    NONE,
    /** Outbound wallet transfer requires the configured wallet-level step-up approval. */
    WALLET_OUTBOUND_STEP_UP,
    /** Local PIN factor must accompany the required server-side assertion. */
    LOCAL_FACTOR_AND_ASSERTION
}
