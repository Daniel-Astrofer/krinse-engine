package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

/**
 * HTTP response with the complete quote, expiry, and available alternative fee tiers.
 *
 * @param rail selected payment rail
 * @param direction selected value movement direction
 * @param grossAmountSats original amount supplied to the pricing policy
 * @param receiverAmountSats amount expected to reach the recipient
 * @param networkFeeSats estimated fee paid to the settlement network
 * @param totalDebitSats amount debited from the sender
 * @param keroseneFeeSats platform fee charged by the current policy
 * @param totalFeeSats network fee plus platform fee
 * @param feeRateSatPerVbyte selected network fee rate
 * @param estimatedVbytes estimated transaction virtual size
 * @param estimatedConfirmationBlocks selected confirmation target
 * @param estimatedSettlementSeconds estimated settlement time
 * @param feeSource provider/source of the selected network estimate
 * @param quoteExpiresAt expiry instant after which a fresh fee estimate is required
 * @param feeTiers alternative fee/latency choices returned by the estimator
 */
public record TransactionQuoteResponse(
        PaymentRail rail,
        PaymentDirection direction,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long totalDebitSats,
        long keroseneFeeSats,
        long totalFeeSats,
        long feeRateSatPerVbyte,
        int estimatedVbytes,
        int estimatedConfirmationBlocks,
        long estimatedSettlementSeconds,
        String feeSource,
        Instant quoteExpiresAt,
        List<FeeTierResponse> feeTiers) {
}
