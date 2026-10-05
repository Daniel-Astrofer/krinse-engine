package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Ledger-derived solvency decision and the values used to reach it.
 * @param solvent whether eligible assets meet required liabilities and policy buffer
 * @param coverageRatio observed eligible-asset to liability coverage ratio
 * @param minimumCoverageRatio required minimum coverage ratio
 * @param totalLiabilitiesSats customer liabilities in integer satoshis
 * @param eligibleAssetsSats eligible ledger-observed assets in integer satoshis
 * @param safetyBufferSats required safety buffer in integer satoshis
 */
public record SettlementSolvencySnapshot(boolean solvent, double coverageRatio, double minimumCoverageRatio,
        long totalLiabilitiesSats, long eligibleAssetsSats, long safetyBufferSats) {}
