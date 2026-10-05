package com.kerosene.kfe.pricing.adapters.in.http;

/**
 * HTTP representation of one alternative network-fee tier.
 *
 * @param name user-facing fee tier label
 * @param feeRateSatPerVbyte fee rate in satoshis per virtual byte
 * @param networkFeeSats estimated network cost in satoshis
 * @param targetBlocks target confirmation horizon
 * @param estimatedSeconds estimated settlement delay
 * @param source provider or estimation source label
 */
public record FeeTierResponse(
        String name,
        long feeRateSatPerVbyte,
        long networkFeeSats,
        int targetBlocks,
        long estimatedSeconds,
        String source) {
}
