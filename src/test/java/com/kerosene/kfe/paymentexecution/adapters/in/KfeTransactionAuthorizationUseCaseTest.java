package com.kerosene.kfe.paymentexecution.adapters.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.AuthorizePaymentService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentAuthorizationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class KfeTransactionAuthorizationUseCaseTest {

    @Test
    void testJacksonDeserializesTransactionAuthorizationAndPaymentRequestReference() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        String json = """
                {
                  "idempotencyKey": "idemp-key",
                  "rail": "INTERNAL",
                  "direction": "INTERNAL",
                  "sourceWalletId": "61a8bb23-e18e-4f32-8414-9844e7300c14",
                  "destinationWalletId": "34b5cc23-e18e-4f32-8414-9844e7300c25",
                  "amountSats": 10000,
                  "networkFeeSats": 0,
                  "memo": "test",
                  "appPin": "1234",
                  "paymentRequestPublicId": "public-internal-id"
                }
                """;
        KfeSubmitTransactionRequest request = mapper.readValue(json, KfeSubmitTransactionRequest.class);
        assertEquals("1234", request.appPin());
        assertEquals("public-internal-id", request.paymentRequestPublicId());
    }

    private final PaymentApprovalPort transactionApprovalPort = mock(PaymentApprovalPort.class);
    private final KfeTransactionAuthorizationUseCase useCase = new KfeTransactionAuthorizationUseCase(
            new PaymentAuthorizationAdapter(new AuthorizePaymentService(transactionApprovalPort)));

    @Test
    void internalTransferWithoutTransactionalAuthorizationMaterialIsRejectedAsUnauthorized() {
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                UUID.randomUUID(),
                UUID.randomUUID(),
                10_000L,
                0L,
                null,
                "memo",
                null,
                null,
                null);

        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> useCase.authorize(123L, request, "device-hash"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(transactionApprovalPort);
    }

    @Test
    void bridgePreservesTheCompleteRequestAndDeviceIdentityForTheTypedInput() {
        var input = mock(AuthorizePaymentUseCase.class);
        var bridge = new KfeTransactionAuthorizationUseCase(input);
        var request = new KfeSubmitTransactionRequest(" raw-key ", KfeRail.ONCHAIN, KfeDirection.OUTBOUND,
                UUID.randomUUID(), UUID.randomUUID(), 25_000L, 300L, " raw-address ", " raw memo ",
                " totp ", " assertion ", " passphrase ", " app-pin ", " public-id ", 17L, 3, " quote ");

        bridge.authorize(123L, request, " device-hash ");

        verify(input).authorize(LegacyPaymentSubmissionMapper.toCommand(123L, request, " device-hash "));
    }

    @Test
    void bridgeBindsApprovalToTheCompleteCommandWithoutTrimmingAuthorizationMaterial() {
        var request = new KfeSubmitTransactionRequest("internal-key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                UUID.randomUUID(), UUID.randomUUID(), 10_000L, 0L, null, "memo", null, " assertion ",
                null, " app-pin ", null);

        useCase.authorize(123L, request, " device-hash ");

        verify(transactionApprovalPort).approve(LegacyPaymentSubmissionMapper.toCommand(123L, request, " device-hash "));
        org.mockito.Mockito.verifyNoMoreInteractions(transactionApprovalPort);
    }
}
