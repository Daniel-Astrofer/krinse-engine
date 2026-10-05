package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentSettlementStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.application.usecase.SettleInternalPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentInternalSettlementConfiguration {
    @Bean
    SettleInternalPaymentService settleInternalPaymentService(
            InternalPaymentSettlementStatePort state, PaymentLedgerPort ledger,
            PaymentExecutionLifecycleUseCase lifecycle, PaymentFeeSettlementPort fees,
            PaymentStatementPort statements, InternalPaymentNotificationPort notifications) {
        return new SettleInternalPaymentService(state, ledger, lifecycle, fees, statements, notifications);
    }
}
