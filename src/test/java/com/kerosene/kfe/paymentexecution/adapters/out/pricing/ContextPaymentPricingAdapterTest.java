package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ContextPaymentPricingAdapterTest {

    @ParameterizedTest
    @MethodSource("pricingParityCases")
    void exactlyMatchesLegacyQuoteForEveryRailDirectionAndPolicy(
            PaymentRail rail, PaymentDirection direction, PricingPolicySnapshot.RailPricing railPricing) {
        PricingPolicyPort policy = (selectedRail, selectedDirection) -> new PricingPolicySnapshot(17, railPricing);
        var calculator = new PricingCalculator();
        var adapter = new ContextPaymentPricingAdapter(policy, calculator);
        var legacy = new KfePricingService(policy, calculator);

        var actual = adapter.quote(rail, direction, 100_001L, 321L);
        var expected = legacy.quote(KfeRail.valueOf(rail.name()), KfeDirection.valueOf(direction.name()), 100_001L, 321L);

        assertThat(actual.grossAmountSats()).isEqualTo(expected.grossAmountSats());
        assertThat(actual.receiverAmountSats()).isEqualTo(expected.receiverAmountSats());
        assertThat(actual.networkFeeSats()).isEqualTo(expected.networkFeeSats());
        assertThat(actual.keroseneFeeSats()).isEqualTo(expected.keroseneFeeSats());
        assertThat(actual.totalDebitSats()).isEqualTo(expected.totalDebitSats());
        assertThat(actual.pricingPolicyVersion()).isEqualTo(expected.pricingPolicyVersion());
    }

    @Test
    void mapsToPricingContextEnumsAndReadsOnePolicySnapshot() {
        var policy = mock(PricingPolicyPort.class);
        var pricingRail = com.kerosene.kfe.pricing.domain.model.PaymentRail.ONCHAIN;
        var pricingDirection = com.kerosene.kfe.pricing.domain.model.PaymentDirection.OUTBOUND;
        when(policy.snapshot(pricingRail, pricingDirection))
                .thenReturn(new PricingPolicySnapshot(3, new PricingPolicySnapshot.RailPricing(90, null, null)));
        var adapter = new ContextPaymentPricingAdapter(policy, new PricingCalculator());

        var quote = adapter.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, 1_000L);

        assertThat(quote.keroseneFeeSats()).isEqualTo(900L);
        assertThat(quote.totalDebitSats()).isEqualTo(101_900L);
        assertThat(quote.pricingPolicyVersion()).isEqualTo(3);
        verify(policy).snapshot(pricingRail, pricingDirection);
        verifyNoMoreInteractions(policy);
    }

    @Test
    void preservesCalculatorValidationAndOverflowFailures() {
        PricingPolicyPort policy = (rail, direction) -> new PricingPolicySnapshot(
                1, new PricingPolicySnapshot.RailPricing(90, null, null));
        var adapter = new ContextPaymentPricingAdapter(policy, new PricingCalculator());

        assertThatThrownBy(() -> adapter.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 1L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, Long.MAX_VALUE, 0L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void preservesInboundRejectionWhenFeeConsumesTheAmount() {
        PricingPolicyPort policy = (rail, direction) -> new PricingPolicySnapshot(
                1, new PricingPolicySnapshot.RailPricing(10_000, null, null));
        var adapter = new ContextPaymentPricingAdapter(policy, new PricingCalculator());

        assertThatThrownBy(() -> adapter.quote(PaymentRail.LIGHTNING, PaymentDirection.INBOUND, 100L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inbound amount is too small after Kerosene fee");
    }

    private static Stream<Arguments> pricingParityCases() {
        return Arrays.stream(PaymentRail.values()).flatMap(rail -> Arrays.stream(PaymentDirection.values())
                .flatMap(direction -> Stream.of(
                                new PricingPolicySnapshot.RailPricing(0, null, null),
                                new PricingPolicySnapshot.RailPricing(90, null, null),
                                new PricingPolicySnapshot.RailPricing(1, 50L, 100L),
                                new PricingPolicySnapshot.RailPricing(90, 50L, 100L))
                        .map(policy -> Arguments.of(rail, direction, policy))));
    }
}
