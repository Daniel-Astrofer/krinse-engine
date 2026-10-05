package com.kerosene.kfe.adapters.in.http.dto.pricing;

/** Fee estimate for one urgency tier of a Bitcoin transaction.
 *
 * @param priority human-readable urgency label for the tier
 * @param feeRateSatPerVbyte recommended fee rate in satoshis per virtual byte
 * @param networkFeeSats estimated total network fee in satoshis
 * @param targetBlocks expected confirmation target in blocks
 * @param estimatedSeconds estimated time to confirmation in seconds
 * @param source provider or estimator that produced the fee quote
 */
public record KfeFeeTierResponse(
        String priority,
        long feeRateSatPerVbyte,
        long networkFeeSats,
        int targetBlocks,
        long estimatedSeconds,
        String source) {
}
