package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import org.springframework.stereotype.Component;

/** Keeps the existing rate/target-aware reservation floor, distinct from a public fee quote. */
@Component
public class BitcoinPaymentNetworkFeeFloorAdapter implements PaymentNetworkFeeFloorPort {

    /** Existing estimator that derives a reserve floor from fee rate or confirmation target. */
    private final KfeNetworkFeeEstimateService feeEstimateService;

    /** Creates the adapter with the KFE Bitcoin fee estimator. */
    public BitcoinPaymentNetworkFeeFloorAdapter(KfeNetworkFeeEstimateService feeEstimateService) {
        this.feeEstimateService = feeEstimateService;
    }

    /** Returns the minimum reserve floor without producing or refreshing a public fee quote. */
    @Override
    public long minimumReserve(Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
        return feeEstimateService.reservedFeeFloorSats(feeRateSatPerVbyte, feeTargetBlocks);
    }
}
