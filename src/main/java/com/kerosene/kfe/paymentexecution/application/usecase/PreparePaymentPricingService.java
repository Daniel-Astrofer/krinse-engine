package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentPricingCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentPricingUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplaySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Coordinates submission pricing without depending on Spring, persistence, or another context's domain. */
public final class PreparePaymentPricingService implements PreparePaymentPricingUseCase {

    /** Conversion constant used to format satoshi amounts as BTC display amounts. */
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");

    /** Supplies a minimum network-fee reserve for outbound on-chain payments. */
    private final PaymentNetworkFeeFloorPort networkFeeFloor;
    /** Produces the authoritative principal, fee, and total-debit quote. */
    private final PaymentPricingPort pricing;
    /** Supplies current fiat display rates without affecting settlement values. */
    private final PaymentDisplayRatesPort displayRates;

    /** Creates pricing coordination with fee-floor, quote, and display-rate ports. */
    /** @param networkFeeFloor outbound on-chain fee reserve calculator @param pricing authoritative payment quote provider @param displayRates current fiat display-rate provider */
    public PreparePaymentPricingService(
            PaymentNetworkFeeFloorPort networkFeeFloor,
            PaymentPricingPort pricing,
            PaymentDisplayRatesPort displayRates) {
        this.networkFeeFloor = networkFeeFloor;
        this.pricing = pricing;
        this.displayRates = displayRates;
    }

    /**
     * Calculates a bounded network reserve, obtains the authoritative quote, and attaches
     * BTC-to-fiat display snapshots. Display-rate conversion is informational and never
     * changes the satoshi quote used for reservation or settlement.
     * @param command rail, direction, amount, and client fee inputs
     * @return reserved fee, authoritative quote, and optional display conversions
     */
    @Override
    public PaymentSubmissionPricing prepare(PreparePaymentPricingCommand command) {
        long reservedNetworkFee = Math.max(0L, command.requestedNetworkFeeSats());
        if (command.rail() == PaymentRail.ONCHAIN && command.direction() == PaymentDirection.OUTBOUND) {
            reservedNetworkFee = Math.max(reservedNetworkFee,
                    networkFeeFloor.minimumReserve(command.feeRateSatPerVbyte(), command.feeTargetBlocks()));
        }
        var quote = pricing.quote(command.rail(), command.direction(), command.amountSats(), reservedNetworkFee);
        BigDecimal receiverBtc = BigDecimal.valueOf(quote.receiverAmountSats())
                .divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP);
        var rates = displayRates.currentRates();
        var display = new PaymentDisplaySnapshot(
                rates.btcUsd(), rates.btcEur(), rates.btcBrl(),
                convertSnapshot(receiverBtc, rates.btcUsd()),
                convertSnapshot(receiverBtc, rates.btcEur()),
                convertSnapshot(receiverBtc, rates.btcBrl()));
        return new PaymentSubmissionPricing(reservedNetworkFee, quote, display);
    }

    /** Converts a BTC display amount into a two-decimal fiat snapshot when the rate is positive. */
    /** @param amountBtc receiver amount expressed in BTC @param btcPrice fiat value per BTC @return fiat amount rounded half-up to two decimals, or null for an unavailable/nonpositive rate */
    private static BigDecimal convertSnapshot(BigDecimal amountBtc, BigDecimal btcPrice) {
        if (btcPrice == null || btcPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return amountBtc.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP);
    }
}
