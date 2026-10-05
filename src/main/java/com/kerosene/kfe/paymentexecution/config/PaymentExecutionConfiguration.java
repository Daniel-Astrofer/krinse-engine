package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionRepository;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentEffectsService;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentCancellationHintsService;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.application.usecase.GetPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ListPaymentsService;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentExecutionLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for framework-free payment execution query and cancellation use cases. */
@Configuration
public class PaymentExecutionConfiguration {

    @Bean
    GetPaymentUseCase getPaymentUseCase(PaymentExecutionQueryRepository repository) {
        return new GetPaymentService(repository);
    }

    @Bean
    ListPaymentsUseCase listPaymentsUseCase(PaymentExecutionQueryRepository repository) {
        return new ListPaymentsService(repository);
    }

    @Bean
    CancelPaymentService cancelPaymentService(
            PaymentCancellationQueryPort query, PaymentCancellationHintsUseCase hints,
            PaymentCancellationStatePort state, PaymentRequestCancellationStatePort requests,
            PaymentRequestCancellationLockPort requestLock, RelatedPaymentLookupPort related,
            PaymentCancellationFencePort fence, PaymentInvoiceCancellationPort invoices,
            PaymentRequestCancellationAuditPort requestAudit, CancelPaymentEffectsService effects,
            PaymentCancellationNotificationPort notifications, PaymentExecutionQueryRepository results) {
        return new CancelPaymentService(query, hints, state, requests, requestLock, related,
                fence, invoices, requestAudit, effects, notifications, results);
    }

    @Bean
    PaymentCancellationHintsUseCase paymentCancellationHintsUseCase(PaymentCancellationQueryPort query) {
        return new PaymentCancellationHintsService(query);
    }

    @Bean
    CancelPaymentEffectsService cancelPaymentEffectsService(
            PaymentCancellationStatePort state, PaymentLedgerPort ledger, PaymentLiquidityPort liquidity,
            PaymentStatementPort statement, PaymentCancellationAuditPort audit) {
        return new CancelPaymentEffectsService(state, ledger, liquidity, statement, audit);
    }

    @Bean
    PaymentExecutionLifecycleService paymentExecutionLifecycleService(
            PaymentExecutionRepository repository,
            PaymentExecutionAuditPort auditPort) {
        return new PaymentExecutionLifecycleService(repository, auditPort);
    }
}
