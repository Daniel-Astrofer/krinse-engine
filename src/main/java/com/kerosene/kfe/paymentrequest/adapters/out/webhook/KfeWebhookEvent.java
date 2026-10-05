package com.kerosene.kfe.paymentrequest.adapters.out.webhook;

/**
 * Event types emitted by the KFE payment webhook system.
 */
public enum KfeWebhookEvent {
    PAYMENT_RECEIVED,
    PAYMENT_EXPIRED,
    PAYMENT_SETTLED,
    PAYMENT_RECONCILED
}
