package com.kerosene.kfe.adapters.out.persistence.model.paymentrequest;

/** Lifecycle states persisted for an invoice or payment request. */
public enum KfePaymentRequestStatus {
    /** Request is available to receive a matching payment. */
    OPEN,
    /** Request has been satisfied by a payment. */
    PAID,
    /** Request's configured validity period ended before payment. */
    EXPIRED,
    /** Request was hidden from normal presentation while retained in storage. */
    HIDDEN,
    /** Request was explicitly cancelled before payment. */
    CANCELLED,
    /** Processing of a payment for this request ended in failure. */
    FAILED
}
