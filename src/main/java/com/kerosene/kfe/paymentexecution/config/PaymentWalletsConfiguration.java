package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentWalletsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentWalletsConfiguration {
    @Bean
    PaymentWalletsService paymentWalletsService(PaymentWalletLookupPort wallets, PaymentRecipientDirectoryPort recipients) {
        return new PaymentWalletsService(wallets, recipients);
    }
}
