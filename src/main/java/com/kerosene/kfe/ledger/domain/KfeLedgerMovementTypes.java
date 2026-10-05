package com.kerosene.kfe.ledger.domain;

import java.util.List;
import java.util.Set;

/**
 * Canonical movement types that credit the AVAILABLE bucket (or system profit).
 * Used for dual-path guards and partial unique index on {@code balance_movements}.
 */
public final class KfeLedgerMovementTypes {

    /** Inbound on-chain credit after the deposit satisfies the configured confirmation policy. */
    public static final String CREDIT_INBOUND = "CREDIT_INBOUND";
    /** Credit created when a payment request is fulfilled by an incoming transfer. */
    public static final String CREDIT_PAYMENT_REQUEST = "CREDIT_PAYMENT_REQUEST";
    /** Credit representing a custodial deposit credited through the legacy movement table. */
    public static final String CREDIT_CUSTODIAL_DEPOSIT = "CREDIT_CUSTODIAL_DEPOSIT";
    /** Generic available-balance credit used when no more specific source type applies. */
    public static final String CREDIT = "CREDIT";
    /** Platform fee income credit, intentionally excluded from user-only credit projections. */
    public static final String CREDIT_KEROSENE_FEE = "CREDIT_KEROSENE_FEE";

    /** Types protected by unique (transaction_id, movement_type) for available-side credits. */
    public static final List<String> IDEMPOTENT_CREDIT_TYPES = List.of(
            CREDIT_INBOUND,
            CREDIT_PAYMENT_REQUEST,
            CREDIT_CUSTODIAL_DEPOSIT,
            CREDIT,
            CREDIT_KEROSENE_FEE);

    /** Compensating user credit that reverses an erroneous or reverted ledger debit. */
    public static final String REVERSAL_CREDIT = "REVERSAL_CREDIT";
    /** Append-only compensating debit reversing a prior user credit. */
    public static final String REVERSAL_DEBIT = "REVERSAL_DEBIT";
    /** Liability marker for an on-chain credit invalidated by a chain reorganization. */
    public static final String REORG_DEBT = "REORG_DEBT";
    /** Compensating credit that clears debt when a reorged deposit becomes canonical again. */
    public static final String REORG_RESTORE_CREDIT = "REORG_RESTORE_CREDIT";
    /** Reverses a previously credited platform fee without mutating its original movement. */
    public static final String REVERSAL_KEROSENE_FEE = "REVERSAL_KEROSENE_FEE";
    /** Restores platform fee income after the triggering transaction is reinstated. */
    public static final String RESTORE_KEROSENE_FEE = "RESTORE_KEROSENE_FEE";
    /** Append-only correction that adds a missing or under-recorded user credit. */
    public static final String CORRECTION_CREDIT = "CORRECTION_CREDIT";
    /** Append-only correction that removes an excess or incorrectly recorded user credit. */
    public static final String CORRECTION_DEBIT = "CORRECTION_DEBIT";

    /** User wallet available credits (excludes system fee). */
    public static final List<String> USER_AVAILABLE_CREDIT_TYPES = List.of(
            CREDIT_INBOUND,
            CREDIT_PAYMENT_REQUEST,
            CREDIT_CUSTODIAL_DEPOSIT,
            CREDIT);

    /** Immutable membership index for available-side credit types with transaction-level uniqueness. */
    public static final Set<String> IDEMPOTENT_CREDIT_TYPE_SET = Set.copyOf(IDEMPOTENT_CREDIT_TYPES);
    /** Reversal/compensation types whose repeated insertion must not duplicate a correction. */
    public static final Set<String> IDEMPOTENT_REVERSAL_TYPE_SET = Set.of(
            REVERSAL_DEBIT,
            REORG_DEBT,
            REORG_RESTORE_CREDIT,
            REVERSAL_KEROSENE_FEE,
            RESTORE_KEROSENE_FEE);

    /** Prevents instances of this constants-only legacy vocabulary holder. */
    private KfeLedgerMovementTypes() {
    }

    /**
     * Tests whether a persisted movement type is one of the supported idempotent available-side
     * credits. Unknown and null strings fail closed.
     *
     * @param movementType stored movement type to classify
     * @return {@code true} only for a member of {@link #IDEMPOTENT_CREDIT_TYPE_SET}
     */
    public static boolean isIdempotentCreditType(String movementType) {
        return movementType != null && IDEMPOTENT_CREDIT_TYPE_SET.contains(movementType);
    }

    /**
     * Tests whether a movement is protected by the legacy ledger's credit or compensation
     * idempotency rules.
     *
     * @param movementType stored movement type to classify
     * @return {@code true} when the type belongs to either idempotent category
     */
    public static boolean isIdempotentMovementType(String movementType) {
        return isIdempotentCreditType(movementType)
                || (movementType != null && IDEMPOTENT_REVERSAL_TYPE_SET.contains(movementType));
    }
}
