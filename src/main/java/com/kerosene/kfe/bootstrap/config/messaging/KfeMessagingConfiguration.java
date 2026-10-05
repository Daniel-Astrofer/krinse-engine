package com.kerosene.kfe.bootstrap.config.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.integration.messaging.KfeAuthenticatedMessageTransport;
import com.kerosene.kfe.messaging.application.WorkloadOperationAuthorizer;
import com.kerosene.kfe.messaging.application.port.out.AuthenticatedMessageTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates the message-ingress authorization and transport beans from deployment properties.
 * Each capability is conditional so deployments can enable ingress and outbound transport
 * independently. The outbound transport is restricted to HTTPS by this configuration.
 */
@Configuration
public class KfeMessagingConfiguration {

    /**
     * Builds the allowlist authorizer for inbound workload messages.
     *
     * @param workloadId identity allowed to submit messages through this ingress
     * @param allowedOperations comma-separated operation names; blank entries are ignored
     * @return immutable authorization policy for the configured workload
     */
    @Bean
    @ConditionalOnProperty(name = "kfe.messaging.ingress.enabled", havingValue = "true")
    WorkloadOperationAuthorizer messageIngressOperationAuthorizer(
            @Value("${kfe.messaging.ingress.workload-id}") String workloadId,
            @Value("${kfe.messaging.ingress.allowed-operations:}") String allowedOperations) {
        Set<String> operations = Arrays.stream(allowedOperations == null ? new String[0] : allowedOperations.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new WorkloadOperationAuthorizer(Map.of(workloadId, operations));
    }

    /**
     * Publishes the secret used to authenticate inbound message signatures.
     *
     * @param signingSecret configured signing secret
     * @return nonblank secret consumed by the ingress verifier
     * @throws IllegalStateException if the secret is missing or blank
     */
    @Bean
    @ConditionalOnProperty(name = "kfe.messaging.ingress.enabled", havingValue = "true")
    String messageIngressSigningSecret(
            @Value("${kfe.messaging.ingress.signing-secret}") String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalStateException("message ingress signing secret is required");
        }
        return signingSecret;
    }

    /**
     * Creates the signed outbound message transport after validating its endpoint and policy.
     *
     * @param builder Spring HTTP client builder used by the adapter
     * @param objectMapper serializer shared with the rest of the application
     * @param endpoint HTTPS endpoint receiving authenticated messages
     * @param signingSecret secret used by the transport to sign outgoing messages
     * @param workloadId workload identity attached to the authorization policy
     * @param allowedOperations comma-separated operation allowlist; blank entries are ignored
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response timeout in milliseconds
     * @return configured authenticated transport
     * @throws IllegalStateException if the endpoint is absent or does not use HTTPS
     */
    @Bean
    @ConditionalOnProperty(name = "kfe.messaging.transport.enabled", havingValue = "true")
    AuthenticatedMessageTransport authenticatedMessageTransport(
            RestTemplateBuilder builder,
            ObjectMapper objectMapper,
            @Value("${kfe.messaging.transport.endpoint}") String endpoint,
            @Value("${kfe.messaging.transport.signing-secret}") String signingSecret,
            @Value("${kfe.messaging.transport.workload-id}") String workloadId,
            @Value("${kfe.messaging.transport.allowed-operations:}") String allowedOperations,
            @Value("${kfe.messaging.transport.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${kfe.messaging.transport.read-timeout-ms:10000}") long readTimeoutMs) {
        if (endpoint == null || !endpoint.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("authenticated message transport requires an HTTPS endpoint");
        }
        Set<String> operations = Arrays.stream(allowedOperations == null ? new String[0] : allowedOperations.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new KfeAuthenticatedMessageTransport(
                builder,
                objectMapper,
                endpoint,
                signingSecret,
                connectTimeoutMs,
                readTimeoutMs,
                Map.of(workloadId, operations));
    }
}
