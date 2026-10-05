package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentFundsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentFundsReservationConfiguration {
    @Bean
    ReservePaymentFundsService reservePaymentFundsService(
            PaymentFundsReservationStatePort state, PaymentWalletLookupPort wallets, PaymentLedgerPort ledger,
            PaymentLiquidityPort liquidity, PaymentExecutionLifecycleUseCase lifecycle) {
        return new ReservePaymentFundsService(state, wallets, ledger, liquidity, lifecycle);
    }
}
