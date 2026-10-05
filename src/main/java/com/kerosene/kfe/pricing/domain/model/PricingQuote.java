package com.kerosene.kfe.pricing.domain.model;

/**
 * Result of applying a pricing policy to an amount and network fee.
 *
 * @param grossAmount amount before any platform fee is deducted or added
 * @param receiverAmount amount credited to the receiving side
 * @param networkFee external settlement network fee
 * @param totalDebit complete sender debit; zero for inbound quotes in this model
 * @param keroseneFee platform fee computed from the policy snapshot
 * @param pricingPolicyVersion policy revision used for this result
 */
public record PricingQuote(
        SatoshiAmount grossAmount,
        SatoshiAmount receiverAmount,
        SatoshiAmount networkFee,
        SatoshiAmount totalDebit,
        SatoshiAmount keroseneFee,
        int pricingPolicyVersion) {
}
