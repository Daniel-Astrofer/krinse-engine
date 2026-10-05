package com.kerosene.kfe.pricing.application.result;

/** One fee-speed option returned with a quote. */
public record FeeTierResult(
        String name,
        long feeRateSatPerVbyte,
        long networkFeeSats,
        int targetBlocks,
        long estimatedSeconds,
        String source) {
}
