package com.kerosene.kfe.ledger.domain;

import java.math.BigInteger;

/** Pure, fail-closed solvency calculation with checked configuration and arithmetic. */
public final class SolvencyPolicy {
    /** Denominator used to convert basis points into an integer satoshi buffer. */
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000L);
    /** Immutable policy thresholds applied to every solvency snapshot. */
    private final Config config;

    /**
     * Creates a deterministic solvency evaluator with validated thresholds.
     *
     * @param config safety buffer, minimum coverage ratio, and profit reconciliation policy
     */
    public SolvencyPolicy(Config config) {
        this.config = config;
    }

    /**
     * Calculates liabilities, the required safety buffer, asset coverage and the fail-closed
     * classification. Optional system profit contributes to liabilities only when configured;
     * in-flight withdrawals always remain liabilities. Exact integer arithmetic is used for the
     * satoshi totals and buffer, so overflow is reported instead of silently wrapping.
     *
     * @param customerLiabilitiesSats customer funds owed by the system
     * @param systemProfitSats recognized system profit included only when reconciliation is enabled
     * @param inFlightWithdrawalSats withdrawals initiated but not yet completed
     * @param eligibleAssetsSats assets permitted by the caller's custody policy to back liabilities
     * @return immutable snapshot with coverage, required assets, and status
     * @throws IllegalArgumentException if any input is negative
     * @throws ArithmeticException if a computed satoshi total exceeds {@code long}
     */
    public SolvencySnapshot evaluate(long customerLiabilitiesSats, long systemProfitSats,
            long inFlightWithdrawalSats, long eligibleAssetsSats) {
        nonNegative(customerLiabilitiesSats, "customerLiabilitiesSats");
        nonNegative(systemProfitSats, "systemProfitSats");
        nonNegative(inFlightWithdrawalSats, "inFlightWithdrawalSats");
        nonNegative(eligibleAssetsSats, "eligibleAssetsSats");

        BigInteger total = BigInteger.valueOf(customerLiabilitiesSats)
                .add(config.reconcileProfit ? BigInteger.valueOf(systemProfitSats) : BigInteger.ZERO)
                .add(BigInteger.valueOf(inFlightWithdrawalSats));
        long totalLong = total.longValueExact();
        long buffer = total.multiply(BigInteger.valueOf(config.safetyBufferBps))
                .divide(TEN_THOUSAND).longValueExact();
        long required = Math.addExact(totalLong, buffer);
        double coverage = totalLong == 0L
                ? Double.POSITIVE_INFINITY
                : (double) eligibleAssetsSats / (double) totalLong;
        boolean assetsKnown = eligibleAssetsSats > 0L || totalLong > 0L;
        boolean solvent = assetsKnown && coverage >= config.minimumCoverageRatio
                && eligibleAssetsSats >= required;
        String status = !assetsKnown ? "UNKNOWN"
                : coverage < 1.0 ? "INSOLVENT"
                : !solvent ? "NEAR_INSOLVENT"
                : "SOLVENT";
        return new SolvencySnapshot(totalLong, eligibleAssetsSats, buffer, required, coverage,
                config.minimumCoverageRatio, solvent, status);
    }

    /** Immutable, validated thresholds controlling a solvency evaluation.
     * @param safetyBufferBps extra asset reserve as basis points of included liabilities
     * @param minimumCoverageRatio minimum eligible-assets to liabilities ratio
     * @param reconcileProfit whether recognized system profit is counted among liabilities
     */
    public record Config(
            long safetyBufferBps,
            double minimumCoverageRatio,
            boolean reconcileProfit) {
        /**
         * Rejects buffers outside the basis-point range and non-finite or sub-100% coverage floors.
         *
         * @param safetyBufferBps reserve from zero through 10,000 basis points
         * @param minimumCoverageRatio finite ratio of at least 1.0
         * @param reconcileProfit whether profit is included among liabilities
         */
        public Config {
            if (safetyBufferBps < 0L || safetyBufferBps > 10_000L) {
                throw new IllegalArgumentException("safetyBufferBps must be between 0 and 10000");
            }
            if (!Double.isFinite(minimumCoverageRatio) || minimumCoverageRatio < 1.0) {
                throw new IllegalArgumentException("minimumCoverageRatio must be finite and at least 1.0");
            }
        }
    }

    /** Rejects negative monetary inputs before the policy performs any ratio calculation. */
    private static void nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
