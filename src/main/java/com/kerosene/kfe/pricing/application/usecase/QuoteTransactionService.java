package com.kerosene.kfe.pricing.application.usecase;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import com.kerosene.kfe.pricing.application.port.out.NetworkFeePort;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;
import com.kerosene.kfe.pricing.domain.model.PricingQuote;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;

/** Coordinates fee estimation and the pure pricing policy. */
public final class QuoteTransactionService implements QuoteTransactionUseCase {

    /** Fee source used before price calculation so quoted debit includes current network cost. */
    private final NetworkFeePort networkFeePort;
    /** Versioned policy source used to price the requested rail and direction. */
    private final PricingPolicyPort pricingPolicyPort;
    /** Pure calculator that applies fee and amount invariants without infrastructure dependencies. */
    private final PricingCalculator calculator;

    /** Creates the quote use case with network estimate, policy snapshot, and calculation ports. */
    public QuoteTransactionService(
            NetworkFeePort networkFeePort,
            PricingPolicyPort pricingPolicyPort,
            PricingCalculator calculator) {
        this.networkFeePort = networkFeePort;
        this.pricingPolicyPort = pricingPolicyPort;
        this.calculator = calculator;
    }

    /** Produces an expiring quote and checks total-fee addition for integer overflow. */
    @Override
    public TransactionQuoteResult quote(QuoteTransactionCommand command) {
        NetworkFeePort.NetworkFeeEstimate feeEstimate = networkFeePort.estimate(
                command.rail(), command.direction(), command.requestedNetworkFeeSats());
        PricingQuote pricingQuote = calculator.quote(
                command.rail(),
                command.direction(),
                SatoshiAmount.positive(command.amountSats()),
                new SatoshiAmount(feeEstimate.selectedNetworkFeeSats()),
                pricingPolicyPort.snapshot(command.rail(), command.direction()));

        long totalFee = Math.addExact(
                pricingQuote.networkFee().value(),
                pricingQuote.keroseneFee().value());
        return new TransactionQuoteResult(
                command.rail(),
                command.direction(),
                pricingQuote.grossAmount().value(),
                pricingQuote.receiverAmount().value(),
                pricingQuote.networkFee().value(),
                pricingQuote.totalDebit().value(),
                pricingQuote.keroseneFee().value(),
                totalFee,
                feeEstimate.selectedFeeRateSatPerVbyte(),
                feeEstimate.estimatedVbytes(),
                feeEstimate.selectedTargetBlocks(),
                feeEstimate.selectedEstimatedSeconds(),
                feeEstimate.selectedSource(),
                feeEstimate.expiresAt(),
                feeEstimate.tiers(),
                pricingQuote.pricingPolicyVersion());
    }
}
