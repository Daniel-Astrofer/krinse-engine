package com.kerosene.kfe.wallet.domain.service;

/** Confirmation policy shared by cold-wallet observers and confirmation refreshers. */
public final class ColdObservationPolicy {
    /** Prevents instantiation of the stateless policy utility. */
    private ColdObservationPolicy() {
    }

    /** Returns whether an observation meets the configured finality threshold. */
    public static boolean isCreditFinal(int confirmations, int requiredConfirmations) {
        return Math.max(0, confirmations) >= Math.max(1, requiredConfirmations);
    }

    /** Allows confirmation state to advance monotonically, never regress on stale observations. */
    public static boolean mayAdvance(int currentConfirmations, int observedConfirmations) {
        return Math.max(0, observedConfirmations) >= Math.max(0, currentConfirmations);
    }
}
