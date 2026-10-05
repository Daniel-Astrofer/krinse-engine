package com.kerosene.kfe.adapters.out.integration.paymentexecution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.FinancialColdWalletPsbtApprovalRequest;
import com.kerosene.common.financial.approval.FinancialCustodyTransferApprovalRequest;
import com.kerosene.common.financial.approval.FinancialLocalFactorApprovalRequest;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.approval.FinancialWalletOutboundApprovalRequest;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.PasskeyAssertion;
import com.kerosene.common.financial.approval.RecoveryApproval;

import java.util.Map;

/** Sends typed transaction-approval proofs to the core authentication service. */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.transaction-approval.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialTransactionApprovalClient implements FinancialTransactionApprovalPort {

    private static final String DEFAULT_BASE_URL = "http://server:8080";

    /** HTTP client configured with finite connect and read timeouts. */
    private final RestTemplate restTemplate;
    /** Parser used to preserve structured error details returned by the authentication service. */
    private final ObjectMapper objectMapper;
    /** Normalized authentication service base URL. */
    private final String baseUrl;

    /**
     * Configures the remote approval client using the shared URL and timeout settings.
     *
     * @param restTemplateFactory factory producing mTLS/SPIFFE or legacy client
     * @param objectMapper JSON parser for remote error envelopes
     * @param baseUrl optional authentication service base URL
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     */
    public KfeRemoteFinancialTransactionApprovalClient(
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

    /** Sends a device-bound local-factor approval request to the core service.
     * @param userId user being authenticated
     * @param deviceRef registered device reference
     * @param factor typed proof produced by the device authentication flow
     */
    @Override
    public void approveLocalFactor(Long userId, String deviceRef, DeviceProof factor) {
        post("/internal/kfe/transaction-approval/local-factor",
                new FinancialLocalFactorApprovalRequest(userId, deviceRef, factor));
    }

    /** Sends a WebAuthn assertion authorizing a custody transfer.
     * @param userId custody account owner
     * @param assertion verified-form passkey assertion to validate remotely
     */
    @Override
    public void approveCustodyTransfer(Long userId, PasskeyAssertion assertion) {
        post("/internal/kfe/transaction-approval/custody-transfer",
                new FinancialCustodyTransferApprovalRequest(userId, assertion));
    }

    /** Sends all supplied approval proofs for an outbound wallet transfer.
     * @param actorUserId authenticated actor submitting the operation
     * @param ownerUserId owner of the wallet whose funds will be spent
     * @param passkeyAssertion optional WebAuthn assertion
     * @param recoveryApproval optional recovery-based approval proof
     * @param deviceProof optional registered-device proof
     */
    @Override
    public void approveWalletOutbound(
            Long actorUserId,
            Long ownerUserId,
            PasskeyAssertion passkeyAssertion,
            RecoveryApproval recoveryApproval,
            DeviceProof deviceProof) {
        post("/internal/kfe/transaction-approval/wallet-outbound",
                new FinancialWalletOutboundApprovalRequest(
                        actorUserId, ownerUserId, passkeyAssertion, recoveryApproval, deviceProof));
    }

    /** Sends a device proof authorizing a cold-wallet PSBT operation.
     * @param userId cold-wallet owner
     * @param factor typed device proof for the approval
     */
    @Override
    public void approveColdWalletPsbt(Long userId, DeviceProof factor) {
        post("/internal/kfe/transaction-approval/cold-wallet-psbt",
                new FinancialColdWalletPsbtApprovalRequest(userId, factor));
    }

    /** Posts a typed approval request and converts HTTP rejections to structured platform errors. */
    private void post(String path, Object request) {
        try {
            restTemplate.postForEntity(baseUrl + path, internalJsonEntity(request), Void.class);
        } catch (RestClientResponseException exception) {
            throw mapRemoteAuthFailure(exception);
        }
    }

    /**
     * Preserves status, message, error code, and optional data from the remote API error body.
     * If the body is malformed, a stable approval error code is used and nonblank raw text becomes
     * the message so the rejection is not silently discarded.
     *
     * @param exception HTTP failure returned by the authentication service
     * @return structured exception carrying the remote rejection details
     */
    private StructuredPlatformException mapRemoteAuthFailure(RestClientResponseException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = "Autorizacao transacional rejeitada pelo servidor de autenticacao.";
        String errorCode = ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED;
        Object data = null;

        try {
            JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
            if (hasText(body.path("message").asText(null))) {
                message = body.path("message").asText();
            }
            if (hasText(body.path("errorCode").asText(null))) {
                errorCode = body.path("errorCode").asText();
            }
            JsonNode dataNode = body.path("data");
            if (!dataNode.isMissingNode() && !dataNode.isNull()) {
                data = objectMapper.convertValue(dataNode, Map.class);
            }
        } catch (Exception ignored) {
            if (hasText(exception.getResponseBodyAsString())) {
                message = exception.getResponseBodyAsString();
            }
        }

        return new StructuredPlatformException(message, status, errorCode, data);
    }

    /** Builds an authenticated JSON entity and fails fast when the shared secret is missing. */
    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
