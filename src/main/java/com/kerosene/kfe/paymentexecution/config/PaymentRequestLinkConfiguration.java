package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentRequestLinkService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class PaymentRequestLinkConfiguration {
    @Bean
    PaymentRequestLinkService paymentRequestLinkService(PaymentRequestLinkStatePort state, PaymentWalletLookupPort wallets) {
        return new PaymentRequestLinkService(state, wallets, Clock.systemUTC());
    }
}
