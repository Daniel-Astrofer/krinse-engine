package com.kerosene.kfe.ledger.adapters.in;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.ledger.adapters.in.KfeProofOfReservesService.SolvencySnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KfeProofOfReservesService} verifying solvency computation
 * with independently-provided asset data (no longer hardcoded to zero).
 */
class KfeProofOfReservesServiceTest {

    private final KfeProofOfReservesService service = new KfeProofOfReservesService(
            true,   // porEnabled
            5000,   // safetyBufferBps = 50%
            1.0,    // minimumCoverageRatio
            true);  // profitReconcileWithVault

    @Test
    void solventWhenAssetsExceedLiabilitiesPlusBuffer() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L,   // customerLiabilities
                10_000L,    // systemProfit
                0L,         // inFlightWithdrawals
                200_000L,   // eligibleAssets
                200_000L,   // onchain
                0L,         // lightning
                "height:800000");

        assertThat(snapshot.solvent()).isTrue();
        assertThat(snapshot.status()).isEqualTo("SOLVENT");
        // total liabilities = 100k + 10k = 110k
        assertThat(snapshot.totalLiabilitiesSats()).isEqualTo(110_000L);
        // safety buffer = 110k * 5000 / 10000 = 55k
        assertThat(snapshot.safetyBufferSats()).isEqualTo(55_000L);
        // coverage = 200k / 110k = 1.818...
        assertThat(snapshot.coverageRatio()).isGreaterThan(1.0);
        assertThat(snapshot.eligibleAssetsSats()).isEqualTo(200_000L);
    }

    @Test
    void insolventWhenAssetsInsufficient() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L,   // customerLiabilities
                0L,
                0L,
                50_000L,    // eligibleAssets < liabilities
                50_000L,
                0L,
                null);

        assertThat(snapshot.solvent()).isFalse();
        assertThat(snapshot.status()).isEqualTo("INSOLVENT");
        assertThat(snapshot.coverageRatio()).isLessThan(1.0);
    }

    @Test
    void nearInsolventWhenCoverageBelowMinimumButAboveOne() {
        // minimumCoverageRatio = 2.0, safety buffers off
        KfeProofOfReservesService strict = new KfeProofOfReservesService(
                true, 0, 2.0, false);

        SolvencySnapshot snapshot = strict.computeSnapshot(
                100_000L, 0L, 0L,
                150_000L, 150_000L, 0L, null);

        // coverage = 1.5, which is >= 1.0 but < 2.0 minimum
        assertThat(snapshot.solvent()).isFalse();
        assertThat(snapshot.status()).isEqualTo("NEAR_INSOLVENT");
        assertThat(snapshot.coverageRatio()).isEqualTo(1.5);
    }

    @Test
    void zeroAssetsAlwaysInsolventWhenLiabilitiesPositive() {
        // Old behavior preserved as explicit contract
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L, 0L, 0L,
                0L, 0L, 0L, null);

        assertThat(snapshot.solvent()).isFalse();
        assertThat(snapshot.status()).isEqualTo("INSOLVENT");
        assertThat(snapshot.coverageRatio()).isEqualTo(0.0);
    }

    @Test
    void zeroLiabilitiesWithPositiveAssetsIsSolvent() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                0L, 0L, 0L,
                100_000L, 100_000L, 0L, null);

        assertThat(snapshot.solvent()).isTrue();
        assertThat(snapshot.status()).isEqualTo("SOLVENT");
        assertThat(snapshot.coverageRatio()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void profitReconcileWithVaultAddsProfitToLiabilities() {
        SolvencySnapshot withProfit = service.computeSnapshot(
                100_000L, 50_000L, 0L,
                200_000L, 200_000L, 0L, null);

        assertThat(withProfit.totalLiabilitiesSats()).isEqualTo(150_000L);

        // Without profit reconciliation
        KfeProofOfReservesService noProfit = new KfeProofOfReservesService(
                true, 5000, 1.0, false);

        SolvencySnapshot withoutProfit = noProfit.computeSnapshot(
                100_000L, 50_000L, 0L,
                200_000L, 200_000L, 0L, null);

        assertThat(withoutProfit.totalLiabilitiesSats()).isEqualTo(100_000L);
    }

    @Test
    void liabilitiesOnlyOverloadProducesInsolventSnapshot() {
        SolvencySnapshot snapshot = service.computeSnapshotLiabilitiesOnly(
                100_000L, 10_000L, 0L);

        assertThat(snapshot.eligibleAssetsSats()).isEqualTo(0L);
        assertThat(snapshot.solvent()).isFalse();
        assertThat(snapshot.status()).isEqualTo("INSOLVENT");
    }

    @Test
    void snapshotContainsBlockHash() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L, 0L, 0L,
                200_000L, 200_000L, 0L,
                "00000000000000000002406e0584f7aa6b9fb2803b6c8a0a6e6e6f6d6f6f6f6f6");

        assertThat(snapshot.snapshotBlockHash()).startsWith("00000");
        assertThat(snapshot.snapshotAt()).isNotNull();
    }

    @Test
    void equityIsAssetsMinusLiabilities() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L, 10_000L, 0L,
                200_000L, 200_000L, 0L, null);

        // equity = 200k - 110k = 90k
        assertThat(snapshot.equitySats()).isEqualTo(90_000L);
    }

    @Test
    void lightningAssetsTrackedSeparately() {
        SolvencySnapshot snapshot = service.computeSnapshot(
                100_000L, 0L, 0L,
                200_000L, 150_000L, 50_000L, null);

        assertThat(snapshot.onchainReserveAssetsSats()).isEqualTo(150_000L);
        assertThat(snapshot.lightningReserveAssetsSats()).isEqualTo(50_000L);
        assertThat(snapshot.eligibleAssetsSats()).isEqualTo(200_000L);
    }
}
