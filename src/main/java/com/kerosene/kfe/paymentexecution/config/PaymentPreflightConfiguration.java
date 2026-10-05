package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.application.usecase.AuthorizePaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.PreflightPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ValidatePaymentRequestService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentPreflightConfiguration {
    @Bean
    AuthorizePaymentService authorizePaymentService(PaymentApprovalPort approvals) {
        return new AuthorizePaymentService(approvals);
    }

    @Bean
    ValidatePaymentRequestService validatePaymentRequestService(PaymentDestinationValidationPort destinations) {
        return new ValidatePaymentRequestService(destinations);
    }

    @Bean
    PreflightPaymentService preflightPaymentService(PaymentWalletsUseCase wallets,
            PaymentCanonicalDestinationPort destinations, ValidatePaymentRequestService validation,
            PaymentRequestFingerprintPort fingerprints, GetIdempotentPaymentUseCase replay,
            AuthorizePaymentUseCase authorization) {
        return new PreflightPaymentService(wallets, destinations, validation, fingerprints, replay, authorization);
    }
}
