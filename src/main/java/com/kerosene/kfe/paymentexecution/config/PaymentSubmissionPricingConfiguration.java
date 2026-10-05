package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentPricingUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentPricingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires payment pricing preparation from fee floors, platform pricing, and display-rate ports. */
@Configuration
public class PaymentSubmissionPricingConfiguration {
    /** Registers the pricing preparation use case used before a payment proposal is persisted. */
    @Bean
    PreparePaymentPricingUseCase preparePaymentPricingUseCase(
            PaymentNetworkFeeFloorPort floor, PaymentPricingPort pricing, PaymentDisplayRatesPort rates) {
        return new PreparePaymentPricingService(floor, pricing, rates);
    }
}
