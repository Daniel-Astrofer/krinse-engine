package com.kerosene.kfe.pricing.application.result;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

/**
 * Application result independent from HTTP serialization.
 *
 * @param rail transfer rail priced by the policy
 * @param direction inbound/outbound direction used for calculation
 * @param grossAmountSats amount supplied to the pricing calculation
 * @param receiverAmountSats net amount delivered to the receiving side
 * @param networkFeeSats fee paid to the underlying settlement network
 * @param totalDebitSats total amount debited from the sender
 * @param keroseneFeeSats platform fee charged by the configured policy
 * @param totalFeeSats sum of network and platform fees
 * @param feeRateSatPerVbyte selected on-chain fee rate, or provider value for other rails
 * @param estimatedVbytes transaction virtual size used by the network fee estimate
 * @param estimatedConfirmationBlocks selected confirmation target
 * @param estimatedSettlementSeconds estimated settlement delay
 * @param feeSource source label associated with the fee estimate
 * @param quoteExpiresAt expiration instant for the fee-sensitive quote
 * @param feeTiers immutable alternative network-fee choices
 * @param pricingPolicyVersion policy version used to compute all amounts
 */
public record TransactionQuoteResult(
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
        List<FeeTierResult> feeTiers,
        int pricingPolicyVersion) {

    /** Copies tier options and treats null as an empty immutable list. */
    public TransactionQuoteResult {
        feeTiers = feeTiers == null ? List.of() : List.copyOf(feeTiers);
    }
}
