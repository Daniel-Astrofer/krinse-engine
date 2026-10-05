package com.kerosene.kfe.adapters.out.rail.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.exception.FinancialProviderUnavailableException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts a configurable custody-provider JSON API to the KFE custody contract.
 * The adapter uses mock mode or missing credentials to make itself non-live, and
 * refuses provider operations rather than silently simulating them.
 */
@Component("kfeConfigurableCustodyGateway")
@ConditionalOnProperty(prefix = "custody", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConfigurableCustodyGateway implements CustodyGateway {

    /** Logger for provider transport failures; messages avoid exposing credentials or payloads. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigurableCustodyGateway.class);

    /** HTTP client bound to the custody provider's transport configuration. */
    private final RestTemplate restTemplate;
    /** JSON codec for provider request and response bodies. */
    private final ObjectMapper objectMapper;
    /** Provider label returned to callers and stored with custody operations. */
    private final String providerName;
    /** Provider base URL with at most one trailing slash removed. */
    private final String baseUrl;
    /** Bearer credential used for all provider requests. */
    private final String apiKey;
    /** When true, live operations are disabled even if credentials are present. */
    private final boolean mockMode;
    /** Provider-relative route for on-chain address creation. */
    private final String onchainAddressPath;
    /** Provider-relative route for Lightning invoice creation. */
    private final String lightningInvoicePath;
    /** Provider-relative route for invoice status lookup. */
    private final String lightningInvoiceStatusPath;
    /** Provider-relative route for invoice cancellation. */
    private final String lightningInvoiceCancelPath;
    /** Provider-relative route for outbound on-chain payments. */
    private final String onchainSendPath;
    /** Provider-relative route for outbound Lightning payments. */
    private final String lightningPayPath;

    /**
     * Builds the adapter from provider settings, routes, HTTP client, and JSON codec.
     *
     * @param providerName stable provider label
     * @param baseUrl provider server base URL
     * @param apiKey bearer credential
     * @param mockMode whether live requests must be disabled
     * @param onchainAddressPath address-generation endpoint
     * @param lightningInvoicePath Lightning invoice-creation endpoint
     * @param lightningInvoiceStatusPath invoice-status endpoint
     * @param lightningInvoiceCancelPath invoice-cancellation endpoint
     * @param onchainSendPath on-chain payment endpoint
     * @param lightningPayPath Lightning payment endpoint
     * @param restTemplate configured custody HTTP client
     * @param objectMapper JSON serializer and parser
     */
    public ConfigurableCustodyGateway(
            @Value("${custody.provider-name:BCX}") String providerName,
            @Value("${custody.base-url:}") String baseUrl,
            @Value("${custody.api-key:}") String apiKey,
            @Value("${custody.mock-mode:false}") boolean mockMode,
            @Value("${custody.onchain-address-path:/api/v1/onchain/address}") String onchainAddressPath,
            @Value("${custody.lightning-invoice-path:/api/v1/lightning/invoice}") String lightningInvoicePath,
            @Value("${custody.lightning-invoice-status-path:/api/v1/lightning/invoice/status}") String lightningInvoiceStatusPath,
            @Value("${custody.lightning-invoice-cancel-path:/api/v1/lightning/invoice/cancel}") String lightningInvoiceCancelPath,
            @Value("${custody.onchain-send-path:/api/v1/onchain/send}") String onchainSendPath,
            @Value("${custody.lightning-pay-path:/api/v1/lightning/pay}") String lightningPayPath,
            @Qualifier("custodyRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.providerName = providerName;
        this.baseUrl = sanitizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.mockMode = mockMode;
        this.onchainAddressPath = onchainAddressPath;
        this.lightningInvoicePath = lightningInvoicePath;
        this.lightningInvoiceStatusPath = lightningInvoiceStatusPath;
        this.lightningInvoiceCancelPath = lightningInvoiceCancelPath;
        this.onchainSendPath = onchainSendPath;
        this.lightningPayPath = lightningPayPath;
    }

    /** @return whether live mode is enabled and both endpoint URL and API key are configured */
    @Override
    public boolean isLive() {
        return !mockMode && baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** @return configured provider label */
    @Override
    public String providerName() {
        return providerName;
    }

    /**
     * Requests a provider-managed on-chain address and rejects responses without an address.
     *
     * @param command user and wallet context plus requested address label
     * @return generated address, provider wallet reference, and operation reference
     * @throws FinancialProviderUnavailableException when integration is disabled or address absent
     */
    @Override
    public GeneratedOnchainAddress createOnchainAddress(OnchainAddressCommand command) {
        ensureLiveForAddressIssuance();
        JsonNode response = post(onchainAddressPath, Map.of(
                "userId", command.userId(),
                "walletId", command.walletId(),
                "walletName", command.walletName(),
                "label", command.label()));

        String address = text(response, "address", "depositAddress");
        if (address == null || address.isBlank()) {
            throw new FinancialProviderUnavailableException(
                    "The custody provider did not return a valid on-chain address.");
        }

        return new GeneratedOnchainAddress(
                address,
                text(response, "walletReference", "walletId", "accountReference"),
                text(response, "reference", "id"));
    }

    /**
     * Creates a Lightning invoice and maps the provider's invoice identifiers and expiry.
     *
     * @param command wallet context, amount, memo, and requested expiry
     * @return payment request, payment hash, Lightning address, reference, and expiry
     */
    @Override
    public GeneratedLightningInvoice createLightningInvoice(LightningInvoiceCommand command) {
        ensureLive("Lightning invoice issuance");

        JsonNode response = post(lightningInvoicePath, Map.of(
                "userId", command.userId(),
                "walletId", command.walletId(),
                "walletName", command.walletName(),
                "amountSats", command.amountSats(),
                "memo", safeText(command.memo()),
                "expiresInSeconds", command.expiresInSeconds()));

        return new GeneratedLightningInvoice(
                text(response, "paymentRequest", "bolt11", "invoice"),
                text(response, "paymentHash", "hash"),
                text(response, "lightningAddress", "lnAddress"),
                text(response, "reference", "id"),
                parseDateTime(response, "expiresAt"));
    }

    /**
     * Queries invoice status and maps provider amount, settlement timestamp, and raw response.
     *
     * @param command invoice identity and wallet context for provider lookup
     * @return mapped status, optional received amount, optional settlement time, and raw payload
     */
    @Override
    public IncomingLightningInvoiceStatus getLightningInvoiceStatus(LightningInvoiceStatusCommand command) {
        ensureLive("Lightning invoice status lookup");

        JsonNode response = post(lightningInvoiceStatusPath, Map.of(
                "userId", command.userId(),
                "walletId", command.walletId(),
                "walletName", command.walletName(),
                "paymentHash", safeText(command.paymentHash()),
                "reference", safeText(command.providerReference()),
                "paymentRequest", safeText(command.paymentRequest())));

        return new IncomingLightningInvoiceStatus(
                textOrDefault(response, "status", "PENDING"),
                nullableLongValue(response, "receivedSats", "settledAmountSats", "amountSats"),
                parseOptionalDateTime(response, "settledAt", "paidAt", "confirmedAt"),
                response.toString());
    }

    /**
     * Sends an invoice-cancellation request and interprets either a boolean or status response.
     *
     * @param command invoice identity and wallet context
     * @return provider cancellation result, or false when its response is null
     */
    @Override
    public boolean cancelLightningInvoice(LightningInvoiceCancellationCommand command) {
        ensureLive("Lightning invoice cancellation");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", command.userId());
        payload.put("walletId", command.walletId());
        payload.put("walletName", command.walletName());
        payload.put("paymentHash", safeText(command.paymentHash()));
        payload.put("reference", safeText(command.providerReference()));
        payload.put("paymentRequest", safeText(command.paymentRequest()));
        JsonNode response = post(lightningInvoiceCancelPath, payload);

        if (response == null) {
            return false;
        }
        JsonNode cancelled = response.path("cancelled");
        if (cancelled.isBoolean()) {
            return cancelled.asBoolean();
        }
        return "CANCELLED".equalsIgnoreCase(text(response, "status"));
    }

    /**
     * Submits an authorized on-chain payment request to the configured provider route.
     *
     * @param command destination, amount, idempotency data, and authorization proof
     * @return provider reference, transaction identifier, status, fee, and raw payload
     */
    @Override
    public PaymentResult sendOnchain(OnchainPaymentCommand command) {
        ensureLive("On-chain payment");

        JsonNode response = post(onchainSendPath, Map.of(
                "userId", command.userId(),
                "walletId", command.walletId(),
                "walletName", command.walletName(),
                "destinationAddress", command.destinationAddress(),
                "amountSats", command.amountSats(),
                "description", safeText(command.description()),
                "idempotencyKey", safeText(command.idempotencyKey()),
                "authorizationProof", safeText(command.authorizationProof())));

        return new PaymentResult(
                text(response, "reference", "id"),
                text(response, "txid", "transactionId"),
                null,
                textOrDefault(response, "status", "PENDING"),
                longValue(response, "feeSats", "fee"),
                response.toString());
    }

    /**
     * Submits an authorized Lightning payment and maps provider payment details.
     *
     * @param command invoice, amount/fee limits, idempotency data, and authorization proof
     * @return provider reference, payment hash, status, fee, and raw payload
     */
    @Override
    public PaymentResult payLightning(LightningPaymentCommand command) {
        ensureLive("Lightning payment");

        JsonNode response = post(lightningPayPath, Map.of(
                "userId", command.userId(),
                "walletId", command.walletId(),
                "walletName", command.walletName(),
                "paymentRequest", command.paymentRequest(),
                "amountSats", command.amountSats(),
                "maxFeeSats", command.maxFeeSats(),
                "description", safeText(command.description()),
                "idempotencyKey", safeText(command.idempotencyKey()),
                "authorizationProof", safeText(command.authorizationProof())));

        return new PaymentResult(
                text(response, "reference", "id"),
                text(response, "txid", "transactionId"),
                text(response, "paymentHash", "hash"),
                textOrDefault(response, "status", "SETTLED"),
                longValue(response, "feeSats", "fee"),
                response.toString());
    }

    /**
     * Rejects address issuance when mock mode or incomplete live configuration disables the provider.
     *
     * @throws FinancialProviderUnavailableException when live integration is unavailable
     */
    private void ensureLiveForAddressIssuance() {
        if (!isLive()) {
            throw new FinancialProviderUnavailableException(
                    "Live custody integration is not configured for on-chain address issuance.");
        }
    }

    /**
     * Requires live provider configuration before performing the named operation.
     *
     * @param operation human-readable operation name included in the unavailable error
     * @throws FinancialProviderUnavailableException when live integration is unavailable
     */
    private void ensureLive(String operation) {
        if (!isLive()) {
            throw new FinancialProviderUnavailableException(
                    operation + " requires a configured live custody provider.");
        }
    }

    /**
     * Sends an authenticated JSON POST and converts transport or response failures to the
     * domain-level provider-unavailable exception. The original payload is not included in logs.
     *
     * @param path provider-relative endpoint path
     * @param payload body fields to serialize
     * @return decoded provider JSON response
     * @throws FinancialProviderUnavailableException when the request fails or response is invalid
     */
    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl + path, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new FinancialProviderUnavailableException(
                        "The custody provider returned an invalid response for " + path + ".");
            }
            return objectMapper.readTree(response.getBody());
        } catch (FinancialProviderUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[CustodyGateway] Provider call failed on {}: {}", path, ex.getMessage(), ex);
            throw new FinancialProviderUnavailableException(
                    "Unable to complete the custody provider request at the moment.");
        }
    }

    /**
     * Returns the first nonblank textual value among the named direct response fields.
     *
     * @param node provider response object
     * @param fieldNames candidate fields in priority order
     * @return first nonblank text or null
     */
    private String text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Reads a textual response field and substitutes the specified fallback when absent.
     *
     * @param node provider response object
     * @param fieldName field to read
     * @param fallback value used when the field is absent or blank
     * @return field value or fallback
     */
    private String textOrDefault(JsonNode node, String fieldName, String fallback) {
        String value = text(node, fieldName);
        return value != null ? value : fallback;
    }

    /**
     * Reads the first integer-valued or parseable textual field, defaulting to zero.
     *
     * @param node provider response object
     * @param fieldNames candidate fields in priority order
     * @return parsed long value, or zero when none can be parsed
     */
    private long longValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    /**
     * Reads an optional integer-valued or parseable textual field without inventing a zero value.
     *
     * @param node provider response object
     * @param fieldNames candidate fields in priority order
     * @return parsed long value, or null when none can be parsed
     */
    private Long nullableLongValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Parses a local timestamp or supplies a 15-minute UTC fallback when the provider omits it.
     *
     * @param node provider response object
     * @param fieldName expiry field to parse
     * @return parsed timestamp or current UTC time plus fifteen minutes
     */
    private LocalDateTime parseDateTime(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isTextual()) {
            try {
                return LocalDateTime.parse(value.asText());
            } catch (Exception ignored) {
            }
        }
        return LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(15);
    }

    /**
     * Parses the first present local timestamp without applying an assumed default.
     *
     * @param node provider response object
     * @param fieldNames candidate fields in priority order
     * @return parsed timestamp or null when absent or invalid
     */
    private LocalDateTime parseOptionalDateTime(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual()) {
                try {
                    return LocalDateTime.parse(value.asText());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** @param value nullable text field @return empty text for null, otherwise the original value */
    private String safeText(String value) {
        return value != null ? value : "";
    }

    /**
     * Normalizes the configured base URL for direct concatenation with endpoint paths.
     *
     * @param raw configured provider URL
     * @return empty string for null, otherwise the URL without one trailing slash
     */
    private String sanitizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

}
