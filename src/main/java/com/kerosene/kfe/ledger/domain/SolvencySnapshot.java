package com.kerosene.kfe.ledger.domain;

/** Immutable solvency result computed from one liability/asset snapshot.
 * @param totalLiabilitiesSats included customer, configured profit, and in-flight withdrawal obligations
 * @param eligibleAssetsSats caller-qualified assets available to back those obligations
 * @param safetyBufferSats additional reserve calculated from the configured basis points
 * @param requiredAssetsSats liabilities plus safety buffer that assets must cover
 * @param coverageRatio eligible assets divided by included liabilities; infinity when liabilities are zero
 * @param minimumCoverageRatio policy floor that coverage must meet
 * @param solvent whether both ratio and required-asset checks passed with known assets
 * @param status stable classification: UNKNOWN, INSOLVENT, NEAR_INSOLVENT, or SOLVENT
 */
public record SolvencySnapshot(
        long totalLiabilitiesSats,
        long eligibleAssetsSats,
        long safetyBufferSats,
        long requiredAssetsSats,
        double coverageRatio,
        double minimumCoverageRatio,
        boolean solvent,
        String status) {
}
