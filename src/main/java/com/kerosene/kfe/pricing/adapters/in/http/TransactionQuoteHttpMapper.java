package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;

/** Converts pricing HTTP DTOs to application commands and results back to response DTOs. */
final class TransactionQuoteHttpMapper {

    /** Prevents instantiation of this stateless mapping utility. */
    private TransactionQuoteHttpMapper() {
    }

    /** Maps the validated HTTP fields without applying financial policy in the adapter. */
    static QuoteTransactionCommand toCommand(TransactionQuoteRequest request) {
        return new QuoteTransactionCommand(
                request.rail(),
                request.direction(),
                request.amountSats(),
                request.networkFeeSats());
    }

    /** Projects every calculated amount, expiry, and fee tier into the API response schema. */
    static TransactionQuoteResponse toResponse(TransactionQuoteResult result) {
        return new TransactionQuoteResponse(
                result.rail(),
                result.direction(),
                result.grossAmountSats(),
                result.receiverAmountSats(),
                result.networkFeeSats(),
                result.totalDebitSats(),
                result.keroseneFeeSats(),
                result.totalFeeSats(),
                result.feeRateSatPerVbyte(),
                result.estimatedVbytes(),
                result.estimatedConfirmationBlocks(),
                result.estimatedSettlementSeconds(),
                result.feeSource(),
                result.quoteExpiresAt(),
                result.feeTiers().stream().map(TransactionQuoteHttpMapper::toResponse).toList());
    }

    /** Maps one application fee tier to its HTTP representation. */
    private static FeeTierResponse toResponse(FeeTierResult tier) {
        return new FeeTierResponse(
                tier.name(),
                tier.feeRateSatPerVbyte(),
                tier.networkFeeSats(),
                tier.targetBlocks(),
                tier.estimatedSeconds(),
                tier.source());
    }
}
