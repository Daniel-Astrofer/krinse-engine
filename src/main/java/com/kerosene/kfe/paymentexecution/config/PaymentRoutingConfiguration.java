package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.SettleInternalPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.usecase.RouteLockedPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the service that routes a locked payment to internal settlement or external execution. */
@Configuration
public class PaymentRoutingConfiguration {
    /** Assembles route state, settlement, execution command, lifecycle, statement, notification, and Vault ports. */
    @Bean
    RouteLockedPaymentService routeLockedPaymentService(PaymentRoutingStatePort state, SettleInternalPaymentUseCase internal,
            ExecutionCommandStore commands, PaymentExecutionLifecycleUseCase lifecycle, PaymentStatementPort statements,
            PaymentInitiatedNotificationPort notifications, PaymentVaultIntentPort vault) {
        return new RouteLockedPaymentService(state, internal, commands, lifecycle, statements, notifications, vault);
    }
}
