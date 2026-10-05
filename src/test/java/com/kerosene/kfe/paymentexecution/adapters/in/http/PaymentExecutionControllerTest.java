package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.GetSubmittedPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentExecutionControllerTest {

    @Test
    void recoversOnlyTheAuthenticatedUsersSubmissionWithoutResubmitting() {
        String key = UUID.randomUUID().toString();
        var payment = result(UUID.randomUUID());
        when(getPayment.findSubmitted(any())).thenReturn(Optional.of(payment));
        var response = controller.findSubmitted(key, authentication);
        var query = ArgumentCaptor.forClass(GetSubmittedPaymentQuery.class);
        verify(getPayment).findSubmitted(query.capture());
        assertThat(query.getValue().userId()).isEqualTo(42L);
        assertThat(query.getValue().idempotencyKey().value()).isEqualTo(key);
        assertThat(query.getValue().toString()).doesNotContain(key);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData().id()).isEqualTo(payment.id());
        org.mockito.Mockito.verifyNoInteractions(cancelPayment, listPayments);
    }

    @Test
    void aMissingSubmissionRemainsUncertainRatherThanAuthorizingAnotherPayment() {
        when(getPayment.findSubmitted(any())).thenReturn(Optional.empty());
        var response = controller.findSubmitted(UUID.randomUUID().toString(), authentication);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void submissionRouteDoesNotFallThroughToTheTransactionUuidRoute() throws Exception {
        when(getPayment.findSubmitted(any())).thenReturn(Optional.empty());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/kfe/transactions/submissions")
                .param("idempotencyKey", UUID.randomUUID().toString()).principal(authentication))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        org.mockito.Mockito.verify(getPayment).findSubmitted(any());
    }

    private final GetPaymentUseCase getPayment = mock(GetPaymentUseCase.class);
    private final ListPaymentsUseCase listPayments = mock(ListPaymentsUseCase.class);
    private final CancelPaymentUseCase cancelPayment = mock(CancelPaymentUseCase.class);
    private final PaymentExecutionController controller =
            new PaymentExecutionController(getPayment, listPayments, cancelPayment);
    private final TestingAuthenticationToken authentication =
            new TestingAuthenticationToken("42", "credentials", "ROLE_USER");

    @Test
    void anUntrustedNumericPrincipalCannotQuerySubmissionRecovery() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.findSubmitted("key",
                new TestingAuthenticationToken("42", "unverified")))
                .isInstanceOf(com.kerosene.common.exception.StructuredPlatformException.class);
        org.mockito.Mockito.verifyNoInteractions(getPayment);
    }

    @Test
    void getsParticipantVisiblePaymentThroughInboundPort() {
        UUID id = UUID.randomUUID();
        PaymentExecutionResult result = result(id);
        when(getPayment.get(any())).thenReturn(result);

        var response = controller.get(id, authentication);

        ArgumentCaptor<GetPaymentQuery> query = ArgumentCaptor.forClass(GetPaymentQuery.class);
        verify(getPayment).get(query.capture());
        assertThat(query.getValue().userId()).isEqualTo(42L);
        assertThat(query.getValue().paymentExecutionId().value()).isEqualTo(id);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().id()).isEqualTo(id);
    }

    @Test
    void listsPaymentsWithIncrementalSyncParameters() {
        Instant since = Instant.parse("2026-09-08T12:00:00Z");
        PaymentExecutionResult result = result(UUID.randomUUID());
        when(listPayments.list(any())).thenReturn(List.of(result));

        var response = controller.list(2, 25, since, authentication);

        ArgumentCaptor<ListPaymentsQuery> query = ArgumentCaptor.forClass(ListPaymentsQuery.class);
        verify(listPayments).list(query.capture());
        assertThat(query.getValue()).isEqualTo(new ListPaymentsQuery(42L, 2, 25, since));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).extracting(PaymentExecutionResponse::id)
                .containsExactly(result.id());
    }

    @Test
    void cancelsPaymentThroughInboundPort() {
        UUID id = UUID.randomUUID();
        when(cancelPayment.cancel(any())).thenReturn(result(id));

        controller.cancel(id, authentication);

        ArgumentCaptor<CancelPaymentCommand> command = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPayment).cancel(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(42L);
        assertThat(command.getValue().paymentExecutionId().value()).isEqualTo(id);
    }

    private static PaymentExecutionResult result(UUID id) {
        return new PaymentExecutionResult(
                id, ExecutionStatus.INTENT, "PENDING", "PENDING",
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                null, UUID.randomUUID(), null,
                "wallet", "source", null, "external",
                100_000L, 100_000L, 1_000L, 900L, 101_900L,
                null, null, null, null, null, null,
                null, 0, "BITCOIN_CORE", null, "tb1", "memo",
                null, null, 0, null, null,
                Instant.parse("2026-09-08T12:00:00Z"),
                Instant.parse("2026-09-08T12:00:00Z"),
                false, null, null, null, null, "OPEN", "PENDING", "RESERVED");
    }
}
