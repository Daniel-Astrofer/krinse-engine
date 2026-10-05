package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.CreatePaymentIntentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.application.usecase.CreatePaymentIntentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentIntentConfiguration {
    @Bean
    CreatePaymentIntentUseCase createPaymentIntentUseCase(PaymentIntentStore store) {
        return new CreatePaymentIntentService(store);
    }
}
