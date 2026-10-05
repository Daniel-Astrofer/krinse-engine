package com.kerosene.kfe.messaging.adapters.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.application.port.in.ReceiveMessageUseCase;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KfeAuthenticatedMessageIngressControllerTest {
    private static final String SECRET = "ingress-test-secret";
    private static final String WORKLOAD = "spiffe://kerosene.test/node-1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ReceiveMessageUseCase receiveMessage = mock(ReceiveMessageUseCase.class);
    private final KfeAuthenticatedMessageIngressController controller =
            new KfeAuthenticatedMessageIngressController(mapper, receiveMessage, SECRET);

    @Test
    void verifiesRawBodyBeforeAcceptingTheEnvelope() throws Exception {
        MessageEnvelope message = message();
        String body = mapper.writeValueAsString(message);

        var response = controller.receive(body, WORKLOAD, message.messageId().toString(), sign(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(receiveMessage).receive(
                argThat(received -> received.messageId().equals(message.messageId())),
                argThat(identity -> identity.equals(new WorkloadIdentity(WORKLOAD))));
    }

    @Test
    void tamperingCannotReachTheApplicationBoundary() throws Exception {
        MessageEnvelope message = message();
        String body = mapper.writeValueAsString(message);

        assertStatus(HttpStatus.UNAUTHORIZED,
                () -> controller.receive(body + " ", WORKLOAD, message.messageId().toString(), sign(body)));
        verifyNoInteractions(receiveMessage);
    }

    @Test
    void envelopeIdHeaderMustMatchTheSignedEnvelope() throws Exception {
        MessageEnvelope message = message();
        String body = mapper.writeValueAsString(message);

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> controller.receive(body, WORKLOAD, UUID.randomUUID().toString(), sign(body)));
        verifyNoInteractions(receiveMessage);
    }

    @Test
    void unauthorizedWorkloadIsNotStoredInTheInbox() throws Exception {
        MessageEnvelope message = message();
        String body = mapper.writeValueAsString(message);
        org.mockito.Mockito.doThrow(new SecurityException("denied"))
                .when(receiveMessage).receive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertStatus(HttpStatus.UNAUTHORIZED,
                () -> controller.receive(body, WORKLOAD, message.messageId().toString(), sign(body)));
    }

    @Test
    void malformedEnvelopeIsRejectedAfterSignatureVerification() throws Exception {
        String body = "{}";

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> controller.receive(body, WORKLOAD, UUID.randomUUID().toString(), sign(body)));
        verifyNoInteractions(receiveMessage);
    }

    private MessageEnvelope message() {
        UUID id = UUID.randomUUID();
        return new MessageEnvelope(
                id,
                "PAYMENT_REQUEST",
                "KFE_PAYMENT_REQUEST_ONCHAIN_OBSERVATION",
                1,
                Instant.parse("2026-09-26T12:00:00Z"),
                id,
                null,
                "request-1",
                1,
                "message-key-1",
                "{\"paymentRequestId\":\"00000000-0000-0000-0000-000000000001\"}");
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertStatus(HttpStatus expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(expected);
    }
}
