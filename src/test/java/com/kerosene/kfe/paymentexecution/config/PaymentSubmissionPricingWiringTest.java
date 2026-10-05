package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.common.financial.operations.FinancialTickerPort;
import com.kerosene.kfe.paymentexecution.adapters.out.pricing.BitcoinPaymentNetworkFeeFloorAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.pricing.ContextPaymentPricingAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.pricing.FinancialPaymentDisplayRatesAdapter;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentPricingCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentPricingUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentSubmissionPricingWiringTest {
    @Test
    void resolvesSingleInputPortAndUsesRealAdaptersAndCalculatorWithoutCycles() {
        var fee = mock(KfeNetworkFeeEstimateService.class);
        var ticker = mock(FinancialTickerPort.class);
        when(fee.reservedFeeFloorSats(8L, 2)).thenReturn(1_500L);
        when(ticker.getPrice("usd")).thenReturn(new BigDecimal("60000"));
        PricingPolicyPort policy = (rail, direction) -> new PricingPolicySnapshot(
                7, new PricingPolicySnapshot.RailPricing(90, null, null));

        new ApplicationContextRunner().withAllowCircularReferences(false)
                .withUserConfiguration(PaymentSubmissionPricingConfiguration.class,
                        BitcoinPaymentNetworkFeeFloorAdapter.class, ContextPaymentPricingAdapter.class,
                        FinancialPaymentDisplayRatesAdapter.class)
                .withBean(KfeNetworkFeeEstimateService.class, () -> fee)
                .withBean(FinancialTickerPort.class, () -> ticker)
                .withBean(PricingPolicyPort.class, () -> policy)
                .withBean(PricingCalculator.class, PricingCalculator::new)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(PreparePaymentPricingUseCase.class)
                            .hasSingleBean(PaymentNetworkFeeFloorPort.class)
                            .hasSingleBean(PaymentPricingPort.class)
                            .hasSingleBean(PaymentDisplayRatesPort.class);
                    var result = ctx.getBean(PreparePaymentPricingUseCase.class).prepare(
                            new PreparePaymentPricingCommand(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                                    100_000L, 1_000L, 8L, 2));
                    assertThat(result.reservedNetworkFeeSats()).isEqualTo(1_500L);
                    assertThat(result.quote().networkFeeSats()).isEqualTo(1_500L);
                    assertThat(result.quote().keroseneFeeSats()).isEqualTo(900L);
                    assertThat(result.quote().totalDebitSats()).isEqualTo(102_400L);
                    assertThat(result.quote().pricingPolicyVersion()).isEqualTo(7);
                    assertThat(result.display().amountUsd()).isEqualByComparingTo("60.00");
                    verify(fee).reservedFeeFloorSats(8L, 2);
                });
    }
}
