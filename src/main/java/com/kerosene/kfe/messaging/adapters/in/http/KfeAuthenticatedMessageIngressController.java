package com.kerosene.kfe.messaging.adapters.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.application.port.in.ReceiveMessageUseCase;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Inbound HTTP adapter for the transition relay. The raw body is authenticated
 * before deserialization and the workload operation is checked by the use case.
 */
@RestController
@RequestMapping("/internal/kfe/messages")
@ConditionalOnProperty(name = "kfe.messaging.ingress.enabled", havingValue = "true")
public final class KfeAuthenticatedMessageIngressController {
    private static final String HMAC = "HmacSHA256";
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final ReceiveMessageUseCase receiveMessage;
    private final byte[] signingKey;

    public KfeAuthenticatedMessageIngressController(
            ObjectMapper objectMapper,
            ReceiveMessageUseCase receiveMessage,
            @Qualifier("messageIngressSigningSecret") String signingSecret) {
        if (objectMapper == null || receiveMessage == null) {
            throw new IllegalArgumentException("message ingress dependencies are required");
        }
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("message ingress signing secret is required");
        }
        this.objectMapper = objectMapper;
        this.receiveMessage = receiveMessage;
        this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> receive(
            @RequestBody String body,
            @RequestHeader("X-KFE-Workload-Id") String workloadId,
            @RequestHeader("X-KFE-Message-Id") String messageId,
            @RequestHeader("X-KFE-Message-Signature") String signature) {
        verifySignature(body, signature);
        MessageEnvelope message = parse(body);
        if (!message.messageId().toString().equals(messageId)) {
            throw badRequest("message id header does not match the envelope");
        }
        try {
            receiveMessage.receive(message, new WorkloadIdentity(workloadId));
        } catch (SecurityException denied) {
            throw unauthorized("workload is not authorized for this message operation");
        } catch (IllegalArgumentException invalid) {
            throw badRequest("invalid message contract");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Message accepted for durable processing.",
                        Map.of("messageId", message.messageId().toString(), "status", "PENDING")));
    }

    private void verifySignature(String body, String signature) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw badRequest("message body is invalid");
        }
        if (signature == null || signature.isBlank()) {
            throw unauthorized("message signature is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(signingKey, HMAC));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = Base64.getDecoder().decode(signature);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw unauthorized("message signature is invalid");
            }
        } catch (IllegalArgumentException invalidBase64) {
            throw unauthorized("message signature is invalid");
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("message signature verification unavailable");
        }
    }

    private MessageEnvelope parse(String body) {
        try {
            return objectMapper.readValue(body, MessageEnvelope.class);
        } catch (Exception invalid) {
            throw badRequest("invalid message envelope");
        }
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
