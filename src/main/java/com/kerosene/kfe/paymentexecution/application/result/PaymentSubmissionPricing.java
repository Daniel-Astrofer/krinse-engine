package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Prepared pricing, without persistence or financial mutations.
 * The gate input remains distinct from the quote fee, which the pricing policy may normalize.
 * @param reservedNetworkFeeSats amount held for network fees in integer satoshis
 * @param quote authoritative quote produced by the pricing policy
 * @param display informational fiat conversions for response presentation
 */
public record PaymentSubmissionPricing(
        long reservedNetworkFeeSats,
        PaymentPricingQuote quote,
        PaymentDisplaySnapshot display) {
}
