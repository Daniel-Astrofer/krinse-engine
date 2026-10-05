package com.kerosene.kfe.adapters.out.integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.application.WorkloadOperationAuthorizer;
import com.kerosene.kfe.messaging.application.port.out.AuthenticatedMessageTransport;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Small HTTP transport adapter for the transition relay. It authenticates the
 * workload and signs the canonical message bytes; business code sees only the port.
 */
public final class KfeAuthenticatedMessageTransport implements AuthenticatedMessageTransport {
    /** JCA algorithm used to authenticate serialized message-envelope bytes. */
    private static final String HMAC = "HmacSHA256";
    /** Bounded-time HTTP client used to send envelopes to the relay. */
    private final RestTemplate client;
    /** Serializer that defines the exact JSON byte sequence covered by the message signature. */
    private final ObjectMapper mapper;
    /** Relay endpoint after removing trailing slashes. */
    private final String endpoint;
    /** UTF-8 secret bytes retained for HMAC calculation; never emitted as a header or payload value. */
    private final byte[] signingKey;
    /** Policy object that checks whether this workload may publish each message type. */
    private final WorkloadOperationAuthorizer operationAuthorizer;

    /**
     * Creates a relay transport with an empty workload-to-operation allowlist.
     * Because the authorizer denies missing policies, this compatibility overload does not permit
     * publication until its policy source is replaced or a caller uses the explicit policy overload.
     *
     * @param builder Spring HTTP client builder
     * @param mapper JSON serializer for message envelopes
     * @param endpoint absolute relay endpoint URL
     * @param signingSecret shared HMAC secret used to sign serialized message bytes
     * @param connectTimeoutMs maximum connection timeout in milliseconds
     * @param readTimeoutMs maximum response-read timeout in milliseconds
     */
    public KfeAuthenticatedMessageTransport(
            RestTemplateBuilder builder,
            ObjectMapper mapper,
            String endpoint,
            String signingSecret,
            long connectTimeoutMs,
            long readTimeoutMs) {
        this(builder, mapper, endpoint, signingSecret, connectTimeoutMs, readTimeoutMs, Map.of());
    }

    /**
     * Creates a relay transport with explicit per-workload message type authorization.
     * Endpoint and secret must be nonblank; timeout values are clamped to at least one millisecond.
     *
     * @param builder Spring HTTP client builder
     * @param mapper JSON serializer for message envelopes
     * @param endpoint absolute relay endpoint URL
     * @param signingSecret shared secret used for HMAC-SHA256 signatures
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     * @param allowedOperationsByWorkload allowed message type names indexed by workload SPIFFE ID
     * @throws IllegalArgumentException when the endpoint or signing secret is blank
     */
    public KfeAuthenticatedMessageTransport(
            RestTemplateBuilder builder,
            ObjectMapper mapper,
            String endpoint,
            String signingSecret,
            long connectTimeoutMs,
            long readTimeoutMs,
            Map<String, Set<String>> allowedOperationsByWorkload) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("endpoint is required");
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("signing secret is required");
        }
        this.client = builder
                .connectTimeout(Duration.ofMillis(Math.max(1L, connectTimeoutMs)))
                .readTimeout(Duration.ofMillis(Math.max(1L, readTimeoutMs)))
                .build();
        this.mapper = mapper;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.operationAuthorizer = new WorkloadOperationAuthorizer(allowedOperationsByWorkload);
    }

    /**
     * Authorizes the workload, serializes the envelope once, signs those exact JSON bytes, and posts
     * the workload ID, message ID, and signature as request headers.
     *
     * @param message envelope to publish
     * @param workload authenticated workload identity associated with the publication
     * @throws IllegalStateException if authorization, serialization, signing, or HTTP delivery fails
     */
    @Override
    public void publish(MessageEnvelope message, WorkloadIdentity workload) {
        try {
            operationAuthorizer.requireAllowed(workload, message.type());
            String body = mapper.writeValueAsString(message);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-KFE-Workload-Id", workload.spiffeId());
            headers.set("X-KFE-Message-Id", message.messageId().toString());
            headers.set("X-KFE-Message-Signature", sign(body));
            client.postForEntity(endpoint, new HttpEntity<>(body, headers), Void.class);
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Authenticated message signing failed.", ex);
        }
    }

    /** Computes a Base64-encoded HMAC-SHA256 signature over the UTF-8 serialized message body. */
    private String sign(String body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(signingKey, HMAC));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
