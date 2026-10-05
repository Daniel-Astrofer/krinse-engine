package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentPricingUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentProposalHashPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionTelemetryPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentSubmissionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentSubmissionPreparationConfiguration {
    @Bean
    PreparePaymentSubmissionService preparePaymentSubmissionService(PaymentSubmissionStatePort state,
            PaymentWalletsUseCase wallets, PreparePaymentPricingUseCase pricing, PaymentProposalHashPort hashes,
            PaymentSettlementGateUseCase gate, PaymentExecutionLifecycleUseCase lifecycle,
            PaymentSubmissionTelemetryPort telemetry) {
        return new PreparePaymentSubmissionService(state, wallets, pricing, hashes, gate, lifecycle, telemetry);
    }
}
