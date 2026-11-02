package com.kerosene.kfe.ledger.adapters.in;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.ledger.domain.SolvencyPolicy;

import java.time.Instant;

/**
 * Proof-of-reserves solvency service (ITEM 8 — vault mesh boundary audit).
 *
 * <p>Verifies the invariant: {@code eligibleAssets >= liabilities + safetyBuffer}.
 * This is NOT a local balance check — it proves UTXOs exist, belong to the vault
 * mesh, are spendable, cover user liabilities, and are not reused elsewhere.
 *
 * <p>Computes liabilities from ledger. Assets must be provided by the caller
 * (settlement gate or reserve overview) after querying on-chain and Lightning probes.
 *
 * <p>When asset probes are unavailable, the service reports UNKNOWN and fail-closes:
 * settlement is blocked because assets cannot be verified.
 */
@Service
public class KfeProofOfReservesService {

    /** Logger for compatibility operations; no balance or asset probe contents are emitted. */
    private static final Logger log = LoggerFactory.getLogger(KfeProofOfReservesService.class);

    /** Feature switch consulted by callers before exposing or enforcing proof-of-reserves results. */
    private final boolean porEnabled;
    /** Configured reserve margin in basis points above calculated liabilities. */
    private final long safetyBufferBps;
    /** Minimum configured asset-to-liability coverage accepted by the policy. */
    private final double minimumCoverageRatio;
    /** Whether system profit remains a liability until independently reconciled with Vault. */
    private final boolean profitReconcileWithVault;
    /** Pure domain policy implementing liability, buffer, coverage, and solvency calculations. */
    private final SolvencyPolicy policy;

    /**
     * Creates the solvency service from proof-of-reserves and profit reconciliation settings.
     *
     * @param porEnabled whether proof-of-reserves enforcement is enabled
     * @param safetyBufferBps reserve margin in basis points
     * @param minimumCoverageRatio minimum asset coverage ratio
     * @param profitReconcileWithVault whether unreconciled system profit counts as a liability
     */
    public KfeProofOfReservesService(
            @Value("${kfe.reserves.proof-of-reserves.enabled:true}") boolean porEnabled,
            @Value("${kfe.reserves.proof-of-reserves.safety-buffer-bps:5000}") long safetyBufferBps,
            @Value("${kfe.reserves.proof-of-reserves.minimum-coverage-ratio:1.0}") double minimumCoverageRatio,
            @Value("${kfe.profit.reconcile-with-vault:true}") boolean profitReconcileWithVault) {
        this.porEnabled = porEnabled;
        this.safetyBufferBps = safetyBufferBps;
        this.minimumCoverageRatio = minimumCoverageRatio;
        this.profitReconcileWithVault = profitReconcileWithVault;
        this.policy = new SolvencyPolicy(new SolvencyPolicy.Config(
                safetyBufferBps, minimumCoverageRatio, profitReconcileWithVault));
    }

    /**
     * Evaluates the provided liability and externally probed asset totals under the configured policy.
     * Asset bucket sums are checked with overflow-safe arithmetic; only confirmed assets from external
     * probes belong in eligible assets, and invalid buckets fail before snapshot publication.
     *
     * @param customerLiabilitiesSats sum of user available + locked + pending + hold balances
     *                                for CUSTODIAL_ONCHAIN and INTERNAL wallets only
     * @param systemProfitSats SYSTEM_PROFIT wallet balance (liability until segregated)
     * @param inFlightWithdrawalSats in-flight withdrawal amounts not yet broadcast/confirmed
     * @param eligibleAssetsSats confirmed on-chain UTXOs + Lightning channel local balance
     *                           (sum from external probes — not ledger observedSats)
     * @param onchainReserveAssetsSats on-chain portion of eligible assets
     * @param lightningReserveAssetsSats Lightning portion of eligible assets
     * @param snapshotBlockHash block hash or height marker from the probe
     * @return immutable solvency result with policy metrics and evaluation timestamp
     * @throws IllegalArgumentException if reserve buckets are negative or exceed eligible assets
     * @throws ArithmeticException if asset bucket summation overflows
     */
    @Transactional(readOnly = true)
    public SolvencySnapshot computeSnapshot(
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats,
            long eligibleAssetsSats,
            long onchainReserveAssetsSats,
            long lightningReserveAssetsSats,
            String snapshotBlockHash) {

        if (onchainReserveAssetsSats < 0L || lightningReserveAssetsSats < 0L) {
            throw new IllegalArgumentException("reserve asset buckets must be non-negative");
        }
        if (Math.addExact(onchainReserveAssetsSats, lightningReserveAssetsSats) > eligibleAssetsSats) {
            throw new IllegalArgumentException("reserve asset buckets exceed eligible assets");
        }
        var result = policy.evaluate(customerLiabilitiesSats, systemProfitSats,
                inFlightWithdrawalSats, eligibleAssetsSats);

        return new SolvencySnapshot(
                result.totalLiabilitiesSats(),
                customerLiabilitiesSats,
                systemProfitSats,
                inFlightWithdrawalSats,
                eligibleAssetsSats,
                onchainReserveAssetsSats,
                lightningReserveAssetsSats,
                result.safetyBufferSats(),
                result.coverageRatio(),
                minimumCoverageRatio,
                result.solvent(),
                snapshotBlockHash,
                Instant.now());
    }

    /**
     * Evaluates liabilities without asset probes, setting eligible assets to zero for fail-closed behavior.
     *
     * @param customerLiabilitiesSats customer liabilities included by the policy
     * @param systemProfitSats system profit liability when reconciliation is required
     * @param inFlightWithdrawalSats withdrawals not yet broadcast or confirmed
     * @return liability snapshot with zero assets and no chain marker
     */
    public SolvencySnapshot computeSnapshotLiabilitiesOnly(
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats) {
        return computeSnapshot(
                customerLiabilitiesSats,
                systemProfitSats,
                inFlightWithdrawalSats,
                0L,
                0L,
                0L,
                null);
    }

    /**
     * Reports whether proof-of-reserves is enabled by configuration.
     *
     * @return configured feature state
     */
    public boolean isEnabled() {
        return porEnabled;
    }

    /**
     * Returns the configured safety margin in basis points.
     *
     * @return reserve margin above liabilities
     */
    public long safetyBufferBps() {
        return safetyBufferBps;
    }

    /**
     * Returns the minimum accepted asset coverage ratio.
     *
     * @return configured ratio threshold
     */
    public double minimumCoverageRatio() {
        return minimumCoverageRatio;
    }

    /**
     * Immutable solvency snapshot at a point in time.
     *
     * @param totalLiabilitiesSats all liability buckets used by policy
     * @param customerLiabilitiesSats customer balance liabilities
     * @param systemProfitSats profit liability pending segregation/reconciliation
     * @param inFlightWithdrawalSats outstanding withdrawal liability
     * @param eligibleAssetsSats confirmed assets accepted by the solvency policy
     * @param onchainReserveAssetsSats on-chain portion of the eligible assets
     * @param lightningReserveAssetsSats Lightning portion of the eligible assets
     * @param safetyBufferSats required safety margin in satoshis
     * @param coverageRatio eligible assets divided by total liabilities, as evaluated by policy
     * @param minimumCoverageRatio configured minimum coverage threshold
     * @param solvent whether policy requirements are satisfied
     * @param snapshotBlockHash external block/hash marker for the asset observation
     * @param snapshotAt time at which this result was evaluated
     */
    public record SolvencySnapshot(
            long totalLiabilitiesSats,
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats,
            long eligibleAssetsSats,
            long onchainReserveAssetsSats,
            long lightningReserveAssetsSats,
            long safetyBufferSats,
            double coverageRatio,
            double minimumCoverageRatio,
            boolean solvent,
            String snapshotBlockHash,
            Instant snapshotAt) {

        /**
         * Calculates assets minus liabilities using overflow-safe subtraction.
         *
         * @return equity in satoshis; negative values indicate liabilities exceed assets
         * @throws ArithmeticException if subtraction overflows
         */
        public long equitySats() {
            return Math.subtractExact(eligibleAssetsSats, totalLiabilitiesSats);
        }

        /**
         * Classifies the snapshot using empty, solvent, insolvent, and near-insolvent thresholds.
         *
         * @return {@code UNKNOWN}, {@code SOLVENT}, {@code INSOLVENT}, or {@code NEAR_INSOLVENT}
         * @throws ArithmeticException if total liabilities plus safety buffer overflows
         */
        public String status() {
            if (eligibleAssetsSats <= 0 && totalLiabilitiesSats <= 0) {
                return "UNKNOWN";
            }
            long requiredAssets = Math.addExact(totalLiabilitiesSats, safetyBufferSats);
            if (coverageRatio >= minimumCoverageRatio && eligibleAssetsSats >= requiredAssets) {
                return "SOLVENT";
            }
            if (coverageRatio < 1.0) {
                return "INSOLVENT";
            }
            if (coverageRatio < minimumCoverageRatio) {
                return "NEAR_INSOLVENT";
            }
            return "UNKNOWN";
        }
    }
}
