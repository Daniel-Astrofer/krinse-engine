package com.kerosene.kfe.adapters.out.persistence.model.messaging;

/**
 * Financial event categories that can be persisted for user notification delivery.
 *
 * <p>The names are serialized as stable event types, so each constant describes one meaningful
 * lifecycle signal rather than a presentation string. Consumers can use the category and its
 * payload to render the corresponding user-facing update.</p>
 */
public enum KfeNotificationEventType {
    /** An inbound deposit has been observed but has not yet reached confirmation. */
    DEPOSIT_DETECTED,
    /** The observed deposit is progressing through its required confirmation depth. */
    DEPOSIT_CONFIRMATION_PROGRESS,
    /** The deposit reached the configured confirmation threshold. */
    DEPOSIT_CONFIRMED,
    /** Deposit processing and associated ledger settlement are finalized. */
    DEPOSIT_FINALIZED,
    /** The previously observed deposit disappeared from the canonical chain view. */
    DEPOSIT_DROPPED,
    /** Conflicting chain evidence was found for the observed deposit. */
    DEPOSIT_CONFLICTED,
    /** A previously credited deposit was reversed after a chain reorganization. */
    DEPOSIT_REVERSED,
    /** Deposit observation and internal financial records were reconciled. */
    DEPOSIT_RECONCILED,
    /** Received deposit value was below the requested or expected amount. */
    DEPOSIT_UNDERPAID,

    /** An outgoing payment entered execution processing. */
    PAYMENT_PROCESSING,
    /** The payment transaction was broadcast to the underlying rail. */
    PAYMENT_BROADCAST,
    /** The payment is in flight and awaiting a terminal network outcome. */
    PAYMENT_IN_FLIGHT,
    /** The payment reached its successful confirmation state. */
    PAYMENT_CONFIRMED,
    /** Payment execution failed with a terminal failure outcome. */
    PAYMENT_FAILED,
    /** Conflicting chain or provider evidence was observed for the payment. */
    PAYMENT_CONFLICTED,
    /** The outgoing transaction was replaced by a newer transaction. */
    PAYMENT_REPLACED,
    /** The payment requires reconciliation before its final state can be trusted. */
    PAYMENT_RECONCILIATION_REQUIRED,

    /** A reserve was created and funds or capacity were allocated to it. */
    RESERVE_CREATED,
    /** A reserve was released and its previously allocated funds or capacity returned. */
    RESERVE_RELEASED
}
