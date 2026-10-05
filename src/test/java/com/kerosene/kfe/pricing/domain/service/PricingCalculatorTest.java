package com.kerosene.kfe.pricing.domain.service;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @Test
    void calculatesInboundFeeWithoutFrameworkConfiguration() {
        var policy = new PricingPolicySnapshot(
                7,
                new PricingPolicySnapshot.RailPricing(100, 90L, null));

        var quote = calculator.quote(
                PaymentRail.ONCHAIN,
                PaymentDirection.INBOUND,
                SatoshiAmount.positive(10_000L),
                new SatoshiAmount(25L),
                policy);

        assertThat(quote.keroseneFee().value()).isEqualTo(100L);
        assertThat(quote.receiverAmount().value()).isEqualTo(9_900L);
        assertThat(quote.pricingPolicyVersion()).isEqualTo(7);
    }

    @Test
    void keepsInternalTransfersFreeAndIgnoresNetworkFee() {
        var quote = calculator.quote(
                PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL,
                SatoshiAmount.positive(500L),
                new SatoshiAmount(999L),
                new PricingPolicySnapshot(2, null));

        assertThat(quote.totalDebit().value()).isEqualTo(500L);
        assertThat(quote.networkFee().value()).isZero();
        assertThat(quote.keroseneFee().value()).isZero();
    }

    @Test
    void appliesMinimumAndMaximumFeeBoundaries() {
        var minimum = calculator.quote(
                PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND,
                SatoshiAmount.positive(1_000L),
                new SatoshiAmount(10L),
                new PricingPolicySnapshot(1,
                        new PricingPolicySnapshot.RailPricing(1, 50L, 75L)));
        var maximum = calculator.quote(
                PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND,
                SatoshiAmount.positive(1_000_000L),
                new SatoshiAmount(10L),
                new PricingPolicySnapshot(1,
                        new PricingPolicySnapshot.RailPricing(100, 50L, 75L)));

        assertThat(minimum.keroseneFee().value()).isEqualTo(50L);
        assertThat(maximum.keroseneFee().value()).isEqualTo(75L);
    }

    @Test
    void rejectsOverflowInsteadOfCorruptingAmounts() {
        assertThatThrownBy(() -> calculator.quote(
                PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND,
                SatoshiAmount.positive(Long.MAX_VALUE),
                new SatoshiAmount(1L),
                new PricingPolicySnapshot(1, null)))
                .isInstanceOf(ArithmeticException.class);
    }
}
