package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.SubmitPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitPaymentControllerTest {

    @Test
    void mapsAuthenticatedHttpInputToTheInboundPort() {
        SubmitPaymentUseCase useCase = mock(SubmitPaymentUseCase.class);
        var controller = new SubmitPaymentController(useCase);
        UUID sourceWalletId = UUID.randomUUID();
        var request = new SubmitPaymentRequest(
                "checkout-42",
                PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND,
                sourceWalletId,
                null,
                100_000L,
                1_000L,
                "tb1-destination",
                "memo",
                "totp",
                "passkey-secret",
                "passphrase",
                "1234",
                null,
                12L,
                3,
                "quote-1");
        PaymentExecutionResult result = result();
        when(useCase.submit(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        var response = controller.submit(
                request,
                "device-hash",
                new TestingAuthenticationToken("42", "credentials", "ROLE_USER"));

        ArgumentCaptor<SubmitPaymentCommand> command = ArgumentCaptor.forClass(SubmitPaymentCommand.class);
        verify(useCase).submit(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(42L);
        assertThat(command.getValue().idempotencyKey().value()).isEqualTo("checkout-42");
        assertThat(command.getValue().sourceWalletId()).isEqualTo(sourceWalletId);
        assertThat(command.getValue().deviceHash()).isEqualTo("device-hash");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().id()).isEqualTo(result.id());
        assertThat(response.getBody().getData().status()).isEqualTo(ExecutionStatus.INTENT);
    }

    @Test
    void sensitiveFactorsAreRedactedFromRequestAndCommandDiagnostics() {
        var request = new SubmitPaymentRequest(
                "key", PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                UUID.randomUUID(), UUID.randomUUID(), 1L, 0L, null, null,
                "totp-secret", "passkey-secret", "passphrase-secret", "pin-secret",
                null, null, null, null);
        SubmitPaymentCommand command = PaymentExecutionHttpMapper.toSubmitCommand(
                42L, request, "device-hash-secret");

        assertThat(request.toString()).doesNotContain(
                "totp-secret", "passkey-secret", "passphrase-secret", "pin-secret");
        assertThat(command.toString()).doesNotContain(
                "totp-secret", "passkey-secret", "passphrase-secret", "pin-secret", "device-hash-secret");
    }

    private static PaymentExecutionResult result() {
        return new PaymentExecutionResult(
                UUID.randomUUID(), ExecutionStatus.INTENT, "PENDING", "PENDING",
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
