package com.kerosene.kfe.pricing.adapters.out.bitcoin;

import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeNetworkFeeEstimateServiceTest {

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final String sharedSecret = "test-shared-secret-for-quote-signing";

    @Test
    void usesServerRatesForOnchainOutboundAndReturnsThreeTiers() {
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(2)).thenReturn(8L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(3)).thenReturn(5L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(6)).thenReturn(2L);
        KfeNetworkFeeEstimateService service = service();

        KfeNetworkFeeEstimateService.Estimate estimate = service.estimate(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                1L);

        // rate * vbytes * safetyMargin (5 * 200 * 1.0 with test margin)
        assertThat(estimate.selectedNetworkFeeSats()).isEqualTo(1_000L);
        assertThat(estimate.selectedFeeRateSatPerVbyte()).isEqualTo(5L);
        assertThat(estimate.selectedTargetBlocks()).isEqualTo(3);
        assertThat(estimate.selectedEstimatedSeconds()).isEqualTo(1_800L);
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.BITCOIN_CORE_SOURCE);
        assertThat(estimate.tiers())
                .extracting(tier -> tier.priority() + ":" + tier.networkFeeSats())
                .containsExactly("FAST:1600", "STANDARD:1000", "SLOW:400");
    }

    @Test
    void appliesSafetyMarginToReservedFee() {
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(2)).thenReturn(10L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(3)).thenReturn(10L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(6)).thenReturn(10L);
        KfeNetworkFeeEstimateService service = service(200, 1.5d);

        KfeNetworkFeeEstimateService.Estimate estimate = service.estimate(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                0L);

        // ceil(10 * 200 * 1.5) = 3000
        assertThat(estimate.selectedNetworkFeeSats()).isEqualTo(3_000L);
        assertThat(service.reservedFeeFloorSats(10L, null)).isEqualTo(3_000L);
        assertThat(service.reservedFeeFloorSats(null, 3)).isEqualTo(3_000L);
    }

    @Test
    void identifiesConfiguredFallbackWhenBitcoinCoreHasNoEstimate() {
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(null);

        KfeNetworkFeeEstimateService.Estimate estimate = service().estimate(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                0L);

        assertThat(estimate.selectedNetworkFeeSats()).isEqualTo(2_400L);
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.FALLBACK_SOURCE);
        assertThat(estimate.tiers()).allSatisfy(tier ->
                assertThat(tier.source()).isEqualTo(KfeNetworkFeeEstimateService.FALLBACK_SOURCE));
    }

    @Test
    void internalQuoteHasNoNetworkFeeOrSyntheticEta() {
        KfeNetworkFeeEstimateService.Estimate estimate = service().estimate(
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                50_000L);

        assertThat(estimate.selectedNetworkFeeSats()).isZero();
        assertThat(estimate.selectedEstimatedSeconds()).isZero();
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.NOT_APPLICABLE_SOURCE);
        assertThat(estimate.tiers()).isEmpty();
    }

    private KfeNetworkFeeEstimateService service() {
        return service(200, 1.0d);
    }

    private KfeNetworkFeeEstimateService service(int estimatedVbytes, double safetyMargin) {
        return new KfeNetworkFeeEstimateService(
                bitcoinCoreProvider,
                pricingService,
                estimatedVbytes,
                safetyMargin,
                2,
                3,
                6,
                25,
                12,
                6,
                600,
                120,
                "mainnet",
                sharedSecret);
    }
}
