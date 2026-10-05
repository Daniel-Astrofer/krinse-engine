package com.kerosene.kfe.paymentexecution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalPort;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.kfe.adapters.out.integration.paymentexecution.KfeRemoteFinancialTransactionApprovalClient;
import com.kerosene.kfe.adapters.out.integration.paymentexecution.KfeRemotePaymentApprovalV1Client;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentAuthorizationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.remote.BoundFinancialPaymentApprovalAdapter;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.application.usecase.AuthorizePaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real approval bean composition, without database or outbound HTTP requests. */
class PaymentApprovalRemoteWiringTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withAllowCircularReferences(false)
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("kfe"))
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(InternalServiceRestTemplateFactory.class, () -> WorkloadIdentityTestClients.legacy("test-secret"))
            .withUserConfiguration(Graph.class)
            .withPropertyValues("kfe.internal.shared-secret=test-secret", "auth.remote.base-url=http://server.test");

    @Test
    void paymentSubmitHasExactlyOneBoundApprovalAndOneVersionedRemotePort() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PaymentApprovalPort.class).hasSingleBean(FinancialPaymentApprovalPort.class)
                    .hasSingleBean(AuthorizePaymentUseCase.class).hasSingleBean(FinancialTransactionApprovalPort.class);
            assertThat(ctx.getBean(PaymentApprovalPort.class)).isExactlyInstanceOf(BoundFinancialPaymentApprovalAdapter.class);
            assertThat(ctx.getBean(FinancialPaymentApprovalPort.class)).isExactlyInstanceOf(KfeRemotePaymentApprovalV1Client.class);
            assertThat(ctx.getBean(AuthorizePaymentUseCase.class)).isExactlyInstanceOf(PaymentAuthorizationAdapter.class);
            assertThat(ctx.getBean(FinancialTransactionApprovalPort.class)).isExactlyInstanceOf(KfeRemoteFinancialTransactionApprovalClient.class);
            assertThat(ctx.getBean(FinancialTransactionApprovalPort.class)).isNotInstanceOf(FinancialPaymentApprovalPort.class);
            assertThat(ctx).doesNotHaveBean("financialTransactionApprovalAdapter");
        });
    }

    @Test
    void explicitRemoteEnablementRetainsTheSameUniqueComposition() {
        context.withPropertyValues("kfe.remote.transaction-approval.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PaymentApprovalPort.class).hasSingleBean(FinancialPaymentApprovalPort.class);
        });
    }

    @Test
    void disablingRemoteApprovalFailsClosedInsteadOfInstallingPermissiveFallback() {
        context.withPropertyValues("kfe.remote.transaction-approval.enabled=false").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining(FinancialPaymentApprovalPort.class.getName());
        });
    }

    @Test
    void nonKfeProfileCannotAccidentallyResolveTheVersionedRemotePort() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("auth")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining(FinancialPaymentApprovalPort.class.getName());
        });
    }

    @Test
    void legacyOnlyApprovalBeanCannotSatisfyTheNewBoundContract() {
        var legacy = mock(FinancialTransactionApprovalPort.class);
        context.withPropertyValues("kfe.remote.transaction-approval.enabled=false")
                .withBean(FinancialTransactionApprovalPort.class, () -> legacy).run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining(FinancialPaymentApprovalPort.class.getName());
                    verifyNoInteractions(legacy);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BoundFinancialPaymentApprovalAdapter.class, KfeRemotePaymentApprovalV1Client.class,
            KfeRemoteFinancialTransactionApprovalClient.class, PaymentAuthorizationAdapter.class})
    static class Graph {
        @Bean AuthorizePaymentService authorizePaymentService(PaymentApprovalPort port) { return new AuthorizePaymentService(port); }
    }
}
