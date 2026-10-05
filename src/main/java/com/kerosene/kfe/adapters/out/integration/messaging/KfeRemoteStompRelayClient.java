package com.kerosene.kfe.adapters.out.integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.financial.stomp.StompUserPublishRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort HTTP bridge to Core STOMP when KFE runs standalone (no in-process broker).
 */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.stomp-relay.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteStompRelayClient {

    /** Logger used when the downstream relay is unavailable without failing the originating operation. */
    private static final Logger log = LoggerFactory.getLogger(KfeRemoteStompRelayClient.class);
    private static final String DEFAULT_BASE_URL = "http://server:8080";
    /** Internal endpoint that fans an event out to a user's STOMP destinations. */
    private static final String PATH = "/internal/kfe/stomp/publish";

    /** Bounded-time HTTP client for relay calls. */
    private final RestTemplate restTemplate;
    /** JSON converter used when arbitrary payload objects need to become relay maps. */
    private final ObjectMapper objectMapper;
    /** Normalized core-service URL used to build the publish endpoint. */
    private final String baseUrl;

    /**
     * Configures the STOMP relay client with bounded connection and read timeouts.
     *
     * @param restTemplateFactory factory producing mTLS/SPIFFE or legacy client
     * @param objectMapper converter for non-map payloads
     * @param baseUrl optional core service base URL
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     */
    public KfeRemoteStompRelayClient(
            InternalServiceRestTemplateFactory restTemplateFactory,
            ObjectMapper objectMapper,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        InternalServiceRestTemplateFactory.ConfiguredClient client = restTemplateFactory.create(
                baseUrl, DEFAULT_BASE_URL, connectTimeoutMs, readTimeoutMs);
        this.restTemplate = client.restTemplate();
        this.objectMapper = objectMapper;
        this.baseUrl = client.baseUrl();
    }

    /**
     * Publishes a nonempty payload to one user's relay destination.
     * Missing identifiers, destination, or payload, and maps with no usable keys are ignored.
     * Relay failures after request construction are logged rather than propagated to business callers.
     *
     * @param userId recipient user ID used by the core relay to resolve subscriptions
     * @param destination STOMP destination name to publish to
     * @param payload map or object converted into a JSON-compatible map
     */
    public void publishToUser(Long userId, String destination, Object payload) {
        if (userId == null || destination == null || destination.isBlank() || payload == null) {
            return;
        }
        Map<String, Object> body = toMap(payload);
        if (body.isEmpty()) {
            return;
        }
        post(new StompUserPublishRequest(userId, destination, body));
    }

    /**
     * Converts a payload into a string-keyed map while preserving its values.
     * Map keys that are null are discarded; non-map objects use Jackson's bean conversion.
     *
     * @param payload source event object
     * @return map representation suitable for the shared relay request
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return copy;
        }
        return objectMapper.convertValue(payload, Map.class);
    }

    /** Posts a typed relay request and logs HTTP/network failures without interrupting the caller. */
    private void post(StompUserPublishRequest request) {
        HttpEntity<StompUserPublishRequest> entity = internalJsonEntity(request);
        try {
            restTemplate.postForEntity(baseUrl + PATH, entity, Void.class);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "[KFE STOMP] auth server rejected {} for user {} dest {} with HTTP {} — continuing",
                    PATH,
                    request.userId(),
                    request.destination(),
                    exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE STOMP] failed to POST {} for user {} dest {} ({}): {} — continuing",
                    PATH,
                    request.userId(),
                    request.destination(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
        }
    }

    /** Builds a JSON request entity. */
    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
