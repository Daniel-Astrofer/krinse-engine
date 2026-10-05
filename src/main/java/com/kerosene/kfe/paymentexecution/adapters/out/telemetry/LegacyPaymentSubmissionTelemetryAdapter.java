package com.kerosene.kfe.paymentexecution.adapters.out.telemetry;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionTelemetryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Preserves operational telemetry for payment submission fee-reserve adjustments. */
@Component
public class LegacyPaymentSubmissionTelemetryAdapter implements PaymentSubmissionTelemetryPort {
    /** Logger used to report when the minimum reserve exceeds the client-provided network fee. */
    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentSubmissionTelemetryAdapter.class);

    /** Emits the fee, reserve floor, requested fee rate, and confirmation target for diagnosis. */
    @Override
    public void feeReserveRaised(long clientFeeSats, long reservedFeeSats, Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
        log.warn("[KFE Submit] raising on-chain fee reserve clientFeeSats={} floorFeeSats={} feeRate={} targetBlocks={}",
                clientFeeSats, reservedFeeSats, feeRateSatPerVbyte, feeTargetBlocks);
    }
}
