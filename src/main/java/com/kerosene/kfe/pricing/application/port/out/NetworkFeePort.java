package com.kerosene.kfe.pricing.application.port.out;

import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

/** Outbound boundary for rail-aware network-fee estimates and alternative fee tiers. */
public interface NetworkFeePort {

    /** Selects a fee estimate for the requested rail/direction and optional fee preference. */
    NetworkFeeEstimate estimate(
            PaymentRail rail,
            PaymentDirection direction,
            long requestedNetworkFeeSats);

    /**
     * Selected fee and alternatives returned by a network fee provider.
     *
     * @param selectedNetworkFeeSats selected network cost in satoshis
     * @param selectedFeeRateSatPerVbyte selected fee rate in satoshis per virtual byte
     * @param selectedTargetBlocks confirmation horizon associated with the selected tier
     * @param selectedEstimatedSeconds estimated settlement delay for the selected tier
     * @param selectedSource provider/source label used for the selected estimate
     * @param estimatedVbytes transaction virtual-size estimate used in fee calculation
     * @param expiresAt instant after which callers must obtain a fresh estimate
     * @param tiers available alternative speed/cost options
     */
    record NetworkFeeEstimate(
            long selectedNetworkFeeSats,
            long selectedFeeRateSatPerVbyte,
            int selectedTargetBlocks,
            long selectedEstimatedSeconds,
            String selectedSource,
            int estimatedVbytes,
            Instant expiresAt,
            List<FeeTierResult> tiers) {

        /** Copies tiers and normalizes null to an immutable empty list. */
        public NetworkFeeEstimate {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }
}
