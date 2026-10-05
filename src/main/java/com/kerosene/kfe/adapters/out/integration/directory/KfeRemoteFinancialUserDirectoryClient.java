package com.kerosene.kfe.adapters.out.integration.directory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.financial.operations.FinancialUserDirectoryLookupRequest;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;

import java.util.Locale;
import java.util.Optional;

/** Calls the core service's internal user directory and translates transport failures to service-unavailable errors. */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.user-directory.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialUserDirectoryClient implements FinancialUserDirectoryPort {

    private static final String DEFAULT_BASE_URL = "http://server:8080";
    /** Core endpoint that accepts username- or ID-based directory lookup requests. */
    private static final String LOOKUP_PATH = "/internal/kfe/user-directory/lookup";
    /** Generic response type used to deserialize the shared API envelope and user handle. */
    private static final ParameterizedTypeReference<ApiResponse<FinancialUserHandle>> RESPONSE_TYPE =
            ParameterizedTypeReference.forType(
                    ResolvableType.forClassWithGenerics(ApiResponse.class, FinancialUserHandle.class).getType());

    /** HTTP client configured with the directory service's connection and read timeouts. */
    private final RestTemplate restTemplate;
    /** Normalized core-service base URL with no trailing slash. */
    private final String baseUrl;

    /**
     * Configures a bounded-time HTTP client for user-directory lookups.
     *
     * @param restTemplateFactory factory producing mTLS/SPIFFE or legacy client
     * @param baseUrl optional core-service URL, defaulting to the service DNS address
     * @param connectTimeoutMs maximum connection-establishment time in milliseconds
     * @param readTimeoutMs maximum response-read time in milliseconds
     */
    public KfeRemoteFinancialUserDirectoryClient(
            InternalServiceRestTemplateFactory restTemplateFactory,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        InternalServiceRestTemplateFactory.ConfiguredClient client = restTemplateFactory.create(
                baseUrl, DEFAULT_BASE_URL, connectTimeoutMs, readTimeoutMs);
        this.restTemplate = client.restTemplate();
        this.baseUrl = client.baseUrl();
    }

    /**
     * Looks up a user by case-insensitive username after trimming surrounding whitespace.
     * Blank input is treated as no lookup and returns empty without calling the remote service.
     *
     * @param username requested account name
     * @return matching user handle, or empty when input is blank or the core service reports not found
     * @throws ResponseStatusException with 503 when the remote directory cannot be trusted or reached
     */
    @Override
    public Optional<FinancialUserHandle> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return lookup(FinancialUserDirectoryLookupRequest.byUsername(normalized));
    }

    /**
     * Looks up a user by positive numeric database identifier.
     * Null or nonpositive IDs return empty without making a remote request.
     *
     * @param userId core user identifier
     * @return matching user handle, or empty when the identifier is invalid or not found
     * @throws ResponseStatusException with 503 when the remote directory fails
     */
    @Override
    public Optional<FinancialUserHandle> findById(Long userId) {
        if (userId == null || userId <= 0L) {
            return Optional.empty();
        }
        return lookup(FinancialUserDirectoryLookupRequest.byUserId(userId));
    }

    /**
     * Sends one authenticated lookup and validates the response envelope and returned handle.
     * A remote 404 is a normal absence; malformed envelopes and other HTTP/network failures are
     * surfaced as service unavailable so callers do not mistake an outage for an unknown user.
     *
     * @param request lookup discriminator and value to send to the core service
     * @return validated user handle, or empty for remote not-found
     */
    private Optional<FinancialUserHandle> lookup(FinancialUserDirectoryLookupRequest request) {
        try {
            ResponseEntity<ApiResponse<FinancialUserHandle>> response = restTemplate.exchange(
                    baseUrl + LOOKUP_PATH,
                    HttpMethod.POST,
                    internalJsonEntity(request),
                    RESPONSE_TYPE);
            ApiResponse<FinancialUserHandle> body = response.getBody();
            if (body == null || !body.isSuccess() || !isValidHandle(body.getData())) {
                throw unavailable("Core user directory returned an invalid response.", null);
            }
            return Optional.of(body.getData());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw unavailable("Core user directory is unavailable.", exception);
        } catch (RestClientException exception) {
            throw unavailable("Core user directory is unavailable.", exception);
        }
    }

    /** Builds a JSON request entity. */
    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** Creates a consistent HTTP 503 error for directory outages or unusable responses. */
    private ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    /** Requires a positive user ID and nonblank username before accepting remote directory data. */
    private boolean isValidHandle(FinancialUserHandle handle) {
        return handle != null
                && handle.id() != null
                && handle.id() > 0L
                && handle.username() != null
                && !handle.username().isBlank();
    }
}
