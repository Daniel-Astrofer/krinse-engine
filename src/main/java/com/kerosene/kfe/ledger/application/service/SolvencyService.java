package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.domain.SolvencyPolicy;
import com.kerosene.kfe.ledger.domain.SolvencySnapshot;

/** Application boundary for the pure solvency policy. */
public final class SolvencyService {
    /** Pure domain policy that owns the solvency thresholds and arithmetic. */
    private final SolvencyPolicy policy;

    /**
     * Exposes the pure solvency calculation through the application layer.
     *
     * @param policy validated domain policy to apply
     */
    public SolvencyService(SolvencyPolicy policy) {
        this.policy = policy;
    }

    /**
     * Delegates one set of financial totals to the configured domain policy.
     *
     * @param customerLiabilitiesSats customer obligations in satoshis
     * @param systemProfitSats recognized system profit in satoshis, conditionally included by policy
     * @param inFlightWithdrawalSats pending withdrawal obligations in satoshis
     * @param eligibleAssetsSats custody assets allowed to back liabilities, in satoshis
     * @return computed solvency status and required coverage amounts
     */
    public SolvencySnapshot evaluate(long customerLiabilitiesSats, long systemProfitSats,
            long inFlightWithdrawalSats, long eligibleAssetsSats) {
        return policy.evaluate(customerLiabilitiesSats, systemProfitSats,
                inFlightWithdrawalSats, eligibleAssetsSats);
    }
}
