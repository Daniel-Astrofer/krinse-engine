package com.kerosene.kfe.pricing.config;

import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import com.kerosene.kfe.pricing.application.port.out.NetworkFeePort;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.application.usecase.QuoteTransactionService;
import com.kerosene.kfe.pricing.adapters.out.configuration.PricingProperties;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the pricing bounded context. */
@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingConfiguration {

    /** Registers the stateless pure pricing calculator. */
    @Bean
    PricingCalculator pricingCalculator() {
        return new PricingCalculator();
    }

    /** Composes the inbound quote use case from fee, policy, and calculation collaborators. */
    @Bean
    QuoteTransactionUseCase quoteTransactionUseCase(
            NetworkFeePort networkFeePort,
            PricingPolicyPort pricingPolicyPort,
            PricingCalculator pricingCalculator) {
        return new QuoteTransactionService(networkFeePort, pricingPolicyPort, pricingCalculator);
    }
}
