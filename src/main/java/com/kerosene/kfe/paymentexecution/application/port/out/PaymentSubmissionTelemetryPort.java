package com.kerosene.kfe.paymentexecution.application.port.out;

/** Emits operational measurements for submission fee-reserve decisions. */
public interface PaymentSubmissionTelemetryPort {
    /** Reports the client fee, actual reserve, and optional fee-rate target used. */
    void feeReserveRaised(long clientFeeSats, long reservedFeeSats, Long feeRateSatPerVbyte, Integer feeTargetBlocks);
}
