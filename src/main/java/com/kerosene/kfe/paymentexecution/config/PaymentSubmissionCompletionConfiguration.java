package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionCompletionPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionDashboardPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.usecase.CompletePaymentSubmissionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentSubmissionCompletionConfiguration {
    @Bean
    CompletePaymentSubmissionService completePaymentSubmissionService(PaymentSubmissionCompletionPort state,
            IdempotencyReservationStore idempotency, PaymentWalletLookupPort wallets, PaymentSubmissionDashboardPort dashboards) {
        return new CompletePaymentSubmissionService(state, idempotency, wallets, dashboards);
    }
}
