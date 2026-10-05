package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentPricingCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplayRates;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreparePaymentPricingServiceTest {

    private final PaymentNetworkFeeFloorPort feeFloor = mock(PaymentNetworkFeeFloorPort.class);
    private final PaymentPricingPort pricing = mock(PaymentPricingPort.class);
    private final PaymentDisplayRatesPort rates = mock(PaymentDisplayRatesPort.class);
    private final PreparePaymentPricingService service = new PreparePaymentPricingService(feeFloor, pricing, rates);

    @ParameterizedTest
    @CsvSource({"100,500,500", "500,500,500", "900,500,900", "0,1,1", "-3,0,0"})
    void onchainOutboundUsesTheHigherOfClientFeeAndAuthoritativeFloor(long clientFee, long floor, long selected) {
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, clientFee);
        var quote = stubQuoteAndUnavailableRates();
        when(feeFloor.minimumReserve(7L, 3)).thenReturn(floor);

        var result = service.prepare(command);

        assertThat(result.reservedNetworkFeeSats()).isEqualTo(selected);
        assertThat(result.quote()).isSameAs(quote);
        var order = inOrder(feeFloor, pricing, rates);
        order.verify(feeFloor).minimumReserve(7L, 3);
        order.verify(pricing).quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, selected);
        order.verify(rates).currentRates();
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @MethodSource("routesWithoutOnchainReserveFloor")
    void doesNotEstimateAFloorForAnyOtherRailDirection(PaymentRail rail, PaymentDirection direction) {
        stubQuoteAndUnavailableRates();

        var result = service.prepare(command(rail, direction, 321L));

        assertThat(result.reservedNetworkFeeSats()).isEqualTo(321L);
        verify(pricing).quote(rail, direction, 100_000L, 321L);
        verifyNoInteractions(feeFloor);
    }

    @Test
    void preservesNullRateAndTargetAsEstimatorDefaults() {
        stubQuoteAndUnavailableRates();
        when(feeFloor.minimumReserve(null, null)).thenReturn(700L);

        var result = service.prepare(new PreparePaymentPricingCommand(
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, 0L, null, null));

        assertThat(result.reservedNetworkFeeSats()).isEqualTo(700L);
        verify(feeFloor).minimumReserve(null, null);
    }

    @Test
    void preservesTheLegacyNonNegativeFeeClamp() {
        stubQuoteAndUnavailableRates();

        var result = service.prepare(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, -5L));

        assertThat(result.reservedNetworkFeeSats()).isZero();
        verify(pricing).quote(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 100_000L, 0L);
        verifyNoInteractions(feeFloor);
    }

    @Test
    void keepsTheGateFeeInputSeparateWhenThePricingPolicyNormalizesTheQuoteFee() {
        var internalQuote = new PaymentPricingQuote(100_000L, 100_000L, 0L, 0L, 100_000L, 7);
        when(pricing.quote(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 100_000L, 5_000L))
                .thenReturn(internalQuote);
        when(rates.currentRates()).thenReturn(new PaymentDisplayRates(null, null, null));

        var result = service.prepare(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 5_000L));

        assertThat(result.reservedNetworkFeeSats()).isEqualTo(5_000L);
        assertThat(result.quote().networkFeeSats()).isZero();
        assertThat(result.quote().pricingPolicyVersion()).isEqualTo(7);
        verifyNoInteractions(feeFloor);
    }

    @Test
    void computesDisplayFromReceiverAmountInsteadOfGrossOrTotalDebit() {
        var quote = new PaymentPricingQuote(200_000_000L, 123_456_789L, 10L, 5L, 200_000_015L, 3);
        when(pricing.quote(any(), any(), anyLong(), anyLong())).thenReturn(quote);
        var usd = new BigDecimal("100");
        var eur = new BigDecimal("1");
        var brl = new BigDecimal("0.00405");
        when(rates.currentRates()).thenReturn(new PaymentDisplayRates(usd, eur, brl));

        var result = service.prepare(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 10L));

        assertThat(result.quote()).isSameAs(quote);
        assertThat(result.display().btcUsd()).isSameAs(usd);
        assertThat(result.display().btcEur()).isSameAs(eur);
        assertThat(result.display().btcBrl()).isSameAs(brl);
        assertThat(result.display().amountUsd()).isEqualTo(new BigDecimal("123.46"));
        assertThat(result.display().amountEur()).isEqualTo(new BigDecimal("1.23"));
        // 1.23456789 * 0.00405 = 0.0049999999545: still below the HALF_UP midpoint.
        assertThat(result.display().amountBrl()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void preservesOneSatoshiPrecisionAndHalfUpCurrencyRounding() {
        when(pricing.quote(any(), any(), anyLong(), anyLong()))
                .thenReturn(new PaymentPricingQuote(1L, 1L, 0L, 0L, 1L, 1));
        when(rates.currentRates()).thenReturn(new PaymentDisplayRates(
                new BigDecimal("500000"), new BigDecimal("499999"), new BigDecimal("1500000")));

        var result = service.prepare(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 0L));

        assertThat(result.display().amountUsd()).isEqualTo(new BigDecimal("0.01"));
        assertThat(result.display().amountEur()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.display().amountBrl()).isEqualTo(new BigDecimal("0.02"));
    }

    @Test
    void retainsRawNullZeroAndNegativeRatesButDoesNotConvertThem() {
        stubQuoteAndUnavailableRates();
        var zero = new BigDecimal("0.000");
        var negative = new BigDecimal("-123.45");
        when(rates.currentRates()).thenReturn(new PaymentDisplayRates(null, zero, negative));

        var display = service.prepare(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 1L)).display();

        assertThat(display.btcUsd()).isNull();
        assertThat(display.btcEur()).isSameAs(zero);
        assertThat(display.btcBrl()).isSameAs(negative);
        assertThat(display.amountUsd()).isNull();
        assertThat(display.amountEur()).isNull();
        assertThat(display.amountBrl()).isNull();
    }

    @Test
    void feeFloorFailurePropagatesBeforePricingOrTickerReads() {
        var failure = new IllegalStateException("estimator unavailable");
        when(feeFloor.minimumReserve(7L, 3)).thenThrow(failure);

        assertThatThrownBy(() -> service.prepare(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 1L)))
                .isSameAs(failure);

        verifyNoInteractions(pricing, rates);
    }

    @Test
    void pricingFailurePropagatesBeforeTickerReads() {
        var failure = new ArithmeticException("amount overflow");
        when(pricing.quote(any(), any(), anyLong(), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.prepare(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 1L)))
                .isSameAs(failure);

        verifyNoInteractions(rates);
    }

    @Test
    void tickerFailureStillAbortsPreparationWithoutInventingAFallback() {
        stubQuoteAndUnavailableRates();
        var failure = new IllegalStateException("ticker unavailable");
        when(rates.currentRates()).thenThrow(failure);

        assertThatThrownBy(() -> service.prepare(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 1L)))
                .isSameAs(failure);
    }

    private PaymentPricingQuote stubQuoteAndUnavailableRates() {
        var quote = new PaymentPricingQuote(100_000L, 99_000L, 10L, 1_000L, 0L, 3);
        when(pricing.quote(any(), any(), anyLong(), anyLong())).thenReturn(quote);
        when(rates.currentRates()).thenReturn(new PaymentDisplayRates(null, null, null));
        return quote;
    }

    private static PreparePaymentPricingCommand command(PaymentRail rail, PaymentDirection direction, long clientFee) {
        return new PreparePaymentPricingCommand(rail, direction, 100_000L, clientFee, 7L, 3);
    }

    private static Stream<Arguments> routesWithoutOnchainReserveFloor() {
        return Arrays.stream(PaymentRail.values()).flatMap(rail -> Arrays.stream(PaymentDirection.values())
                .filter(direction -> rail != PaymentRail.ONCHAIN || direction != PaymentDirection.OUTBOUND)
                .map(direction -> Arguments.of(rail, direction)));
    }
}
