package com.kerosene.kfe.pricing.adapters.out.bitcoin;

import com.kerosene.kfe.adapters.in.http.dto.pricing.KfeFeeTierResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.pricing.application.port.out.NetworkFeePort;
import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import org.springframework.stereotype.Component;

/** Isolates the pricing application from the legacy Bitcoin fee estimator. */
@Component
public class BitcoinNetworkFeeAdapter implements NetworkFeePort {

    /** Existing network fee estimator isolated behind the pricing application port. */
    private final KfeNetworkFeeEstimateService feeEstimateService;

    /** Creates the adapter with the existing fee estimate service. */
    public BitcoinNetworkFeeAdapter(KfeNetworkFeeEstimateService feeEstimateService) {
        this.feeEstimateService = feeEstimateService;
    }

    /** Converts domain rail/direction enums and maps selected fee tiers into application results. */
    @Override
    public NetworkFeeEstimate estimate(
            PaymentRail rail,
            PaymentDirection direction,
            long requestedNetworkFeeSats) {
        KfeNetworkFeeEstimateService.Estimate estimate = feeEstimateService.estimate(
                KfeRail.valueOf(rail.name()),
                KfeDirection.valueOf(direction.name()),
                requestedNetworkFeeSats);
        return new NetworkFeeEstimate(
                estimate.selectedNetworkFeeSats(),
                estimate.selectedFeeRateSatPerVbyte(),
                estimate.selectedTargetBlocks(),
                estimate.selectedEstimatedSeconds(),
                estimate.selectedSource(),
                estimate.estimatedVbytes(),
                estimate.expiresAt(),
                estimate.tiers().stream().map(BitcoinNetworkFeeAdapter::mapTier).toList());
    }

    /** Translates one legacy fee-tier DTO into the application result type. */
    private static FeeTierResult mapTier(KfeFeeTierResponse tier) {
        return new FeeTierResult(
                tier.priority(),
                tier.feeRateSatPerVbyte(),
                tier.networkFeeSats(),
                tier.targetBlocks(),
                tier.estimatedSeconds(),
                tier.source());
    }
}
