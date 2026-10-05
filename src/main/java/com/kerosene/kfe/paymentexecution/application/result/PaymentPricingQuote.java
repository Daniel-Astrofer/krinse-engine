package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Payment Execution's immutable view of the pricing context's calculation.
 * @param grossAmountSats total amount before deductions
 * @param receiverAmountSats net amount delivered to the recipient
 * @param networkFeeSats network fee reserve in integer satoshis
 * @param keroseneFeeSats platform fee in integer satoshis
 * @param totalDebitSats complete source debit including applicable fees
 * @param pricingPolicyVersion version of pricing rules used for this quote
 */
public record PaymentPricingQuote(
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long keroseneFeeSats,
        long totalDebitSats,
        int pricingPolicyVersion) {
}
