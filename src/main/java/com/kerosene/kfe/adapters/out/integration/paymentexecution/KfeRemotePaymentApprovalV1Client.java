package com.kerosene.kfe.adapters.out.integration.paymentexecution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalPort;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Strict versioned transport; never falls back to legacy approval endpoints or empty acknowledgements. */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.transaction-approval.enabled", havingValue = "true", matchIfMissing = true)
public final class KfeRemotePaymentApprovalV1Client implements FinancialPaymentApprovalPort {
    /** Only these authentication errors may expose the small sanitized PIN-status projection. */
    private static final Set<String> PIN_CODES = Set.of(ErrorCodes.AUTH_APP_PIN_INVALID, ErrorCodes.AUTH_APP_PIN_LOCKED,
            ErrorCodes.AUTH_APP_PIN_NOT_CONFIGURED, ErrorCodes.AUTH_APP_PIN_DEVICE_REQUIRED);
    /** Bounded-time HTTP client used for the versioned approval endpoint. */
    private final RestTemplate restTemplate;
    /** Dedicated strict mapper that rejects ambiguous or coercible request/response JSON. */
    private final ObjectMapper json;
    /** Normalized core-service URL used to build the versioned approval route. */
    private final String baseUrl;
    /** Shared secret required to call the core's internal approval API. */
    private final String internalSecret;

    /**
     * Configures strict versioned approval transport with bounded timeouts and a private mapper copy.
     * The mapper emits lower-camel fields, includes nulls, detects duplicate keys, and rejects unknown
     * properties, trailing tokens, missing/null primitives, floating-point integer coercion, and scalar coercion.
     *
     * @param builder Spring builder used to create the bounded-time HTTP client
     * @param mapper application mapper copied before applying this protocol's strict settings
     * @param baseUrl optional authentication-service base URL; blank values use the service DNS default
     * @param internalSecret shared credential accepted by the internal approval route
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     */
    public KfeRemotePaymentApprovalV1Client(RestTemplateBuilder builder, ObjectMapper mapper,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${kfe.internal.shared-secret:}") String internalSecret,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        this.restTemplate = builder.connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs)).build();
        this.json = mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        String configuredUrl = baseUrl == null || baseUrl.isBlank() ? "http://server:8080" : baseUrl;
        this.baseUrl = configuredUrl.replaceAll("/+$", "");
        this.internalSecret = internalSecret;
    }

    /**
     * Validates and sends one v1 financial-approval request, then strictly parses the acknowledgement.
     * The serialized request is capped at 32 KiB and response at 16 KiB; malformed or oversized data
     * is rejected. Remote error bodies are reduced to an allowlisted code and safe PIN-status fields.
     *
     * @param request versioned approval proof and transaction context
     * @return validated versioned approval acknowledgement
     * @throws StructuredPlatformException with 400 for invalid local requests, 502 for malformed responses,
     *         503 for unavailable service, or the sanitized remote rejection status/code
     */
    @Override
    public FinancialPaymentApprovalV1.Response approve(FinancialPaymentApprovalV1.Request request) {
        if (internalSecret == null || internalSecret.isBlank()) { throw unavailable(); }
        final String body;
        try {
            if (request == null) { throw new IllegalArgumentException(); }
            body = json.writeValueAsString(request);
            if (body.length() > 32768) { throw new IllegalArgumentException(); }
        } catch (Exception failure) {
            throw new StructuredPlatformException("Solicitacao de autorizacao financeira invalida.",
                    HttpStatus.BAD_REQUEST, "KFE_PAYMENT_PROOF_INVALID", null);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-KFE-Internal-Secret", internalSecret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        final String response;
        try {
            var entity = restTemplate.postForEntity(baseUrl + FinancialPaymentApprovalV1.PATH,
                    new HttpEntity<>(body, headers), String.class);
            if (!entity.getStatusCode().is2xxSuccessful()) { throw invalidResponse(); }
            response = entity.getBody();
        } catch (RestClientResponseException rejected) {
            throw remoteFailure(rejected);
        } catch (RestClientException unavailable) {
            throw unavailable();
        }
        try {
            if (response == null || response.isBlank() || response.length() > 16384) { throw new IllegalArgumentException(); }
            var result = json.readValue(response, FinancialPaymentApprovalV1.Response.class);
            if (result == null) { throw new IllegalArgumentException(); }
            return result;
        } catch (Exception failure) { throw invalidResponse(); }
    }

    /**
     * Converts a remote HTTP rejection to a stable structured error without forwarding arbitrary
     * remote messages or response data. Only known PIN-related error codes retain a filtered status map.
     *
     * @param failure HTTP response exception returned by the approval service
     * @return structured rejection with a valid error status and sanitized code/data
     */
    private StructuredPlatformException remoteFailure(RestClientResponseException failure) {
        HttpStatus status = HttpStatus.resolve(failure.getStatusCode().value());
        if (status == null || !status.isError()) { status = HttpStatus.BAD_GATEWAY; }
        String code = "KFE_PAYMENT_APPROVAL_REJECTED";
        Object data = null;
        try {
            String body = failure.getResponseBodyAsString();
            if (body.length() <= 16384) {
                JsonNode tree = json.readTree(body);
                String candidate = tree == null ? null : tree.path("errorCode").textValue();
                if (candidate != null && PIN_CODES.contains(candidate)) {
                    code = candidate;
                    data = safePinStatus(tree.path("data"));
                }
            }
        } catch (Exception ignored) { /* Remote messages, parser failures and arbitrary data are never forwarded. */ }
        return new StructuredPlatformException("Autorizacao financeira rejeitada pelo servidor de autenticacao.",
                status, code, data);
    }

    /**
     * Copies only recognized booleans, nonnegative integer counters, and a parseable ISO local lock time.
     * Unknown properties and arbitrary strings are omitted; the result is immutable.
     *
     * @param source remote {@code data} node from an allowlisted PIN error response
     * @return immutable projection containing only fields approved for client disclosure
     */
    private static Map<String, Object> safePinStatus(JsonNode source) {
        if (!source.isObject()) { return Map.of(); }
        Map<String, Object> result = new HashMap<>();
        for (String field : Set.of("enabled", "configured", "locked", "resettableWithTotp", "deviceScoped")) {
            if (source.path(field).isBoolean()) { result.put(field, source.path(field).booleanValue()); }
        }
        for (String field : Set.of("failedAttempts", "remainingAttempts", "maxAttempts", "minPinLength", "maxPinLength")) {
            JsonNode value = source.path(field);
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
                result.put(field, value.intValue());
            }
        }
        JsonNode lockedUntil = source.path("lockedUntil");
        if (lockedUntil.isTextual() && lockedUntil.textValue().length() <= 40) {
            try { result.put("lockedUntil", LocalDateTime.parse(lockedUntil.textValue()).toString()); }
            catch (java.time.format.DateTimeParseException ignored) { /* Not an arbitrary string channel. */ }
        }
        return Map.copyOf(result);
    }

    /** @return stable 502 error used when the remote service's acknowledgement cannot be trusted */
    private static StructuredPlatformException invalidResponse() {
        return new StructuredPlatformException("Resposta de autorizacao financeira invalida.", HttpStatus.BAD_GATEWAY,
                "KFE_PAYMENT_APPROVAL_INVALID", null);
    }
    /** @return stable 503 error used for missing credentials or transport-level service failures */
    private static StructuredPlatformException unavailable() {
        return new StructuredPlatformException("Servico de autorizacao financeira indisponivel.", HttpStatus.SERVICE_UNAVAILABLE,
                "KFE_PAYMENT_APPROVAL_UNAVAILABLE", null);
    }
}
