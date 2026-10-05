package com.kerosene.kfe.adapters.out.rail.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.lightning.LndRestLightningClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the custody operations delegated to BTCPay Server and LND REST.
 * BTCPay creates on-chain deposit addresses and Lightning invoices; outbound on-chain
 * spending remains delegated to the Bitcoin Core quorum workflow.
 */
@Primary
@Component("kfeBtcpayCustodyGateway")
@ConditionalOnProperty(prefix = "btcpay", name = "enabled", havingValue = "true")
public class BtcPayServerCustodyGateway implements CustodyGateway {

    /** Exact decimal conversion factor between BTC and satoshis. */
    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");

    /** HTTP client configured for the BTCPay endpoint and its transport policy. */
    private final RestTemplate restTemplate;
    /** JSON codec used to encode requests, decode responses, and preserve provider payloads. */
    private final ObjectMapper objectMapper;
    /** Optional LND client used only for outbound Lightning payments. */
    private final LndRestLightningClient lndRestLightningClient;
    /** Normalized BTCPay server base URL without a trailing slash. */
    private final String baseUrl;
    /** BTCPay API token sent in the Authorization header. */
    private final String apiKey;
    /** Store identifier embedded in store-scoped BTCPay API paths. */
    private final String storeId;
    /** Provider payment-method identifier used to create on-chain addresses. */
    private final String onchainPaymentMethodId;
    /** Preferred provider payment-method identifier for Lightning invoice details. */
    private final String lightningPaymentMethodId;
    /** Optional cancellation endpoint template; {@code {invoiceId}} is replaced at call time. */
    private final String invoiceCancelPath;

    /**
     * Creates the BTCPay gateway and normalizes injected settings for subsequent requests.
     *
     * @param restTemplate BTCPay-specific HTTP client
     * @param objectMapper JSON serializer and parser
     * @param lndRestLightningClient provider for optional outbound Lightning support
     * @param baseUrl BTCPay server base URL
     * @param apiKey BTCPay API token
     * @param storeId target BTCPay store identifier
     * @param onchainPaymentMethodId payment method used for on-chain address generation
     * @param lightningPaymentMethodId preferred Lightning payment method identifier
     * @param invoiceCancelPath optional cancellation route template
     */
    public BtcPayServerCustodyGateway(
            @Qualifier("btcpayRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<LndRestLightningClient> lndRestLightningClient,
            @Value("${btcpay.base-url}") String baseUrl,
            @Value("${btcpay.api-key}") String apiKey,
            @Value("${btcpay.store-id}") String storeId,
            @Value("${btcpay.onchain-payment-method-id:BTC-CHAIN}") String onchainPaymentMethodId,
            @Value("${btcpay.lightning-payment-method-id:BTC-LN}") String lightningPaymentMethodId,
            @Value("${btcpay.invoice-cancel-path:}") String invoiceCancelPath) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.lndRestLightningClient = lndRestLightningClient.getIfAvailable();
        this.baseUrl = sanitize(baseUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.storeId = storeId != null ? storeId.trim() : "";
        this.onchainPaymentMethodId = onchainPaymentMethodId;
        this.lightningPaymentMethodId = lightningPaymentMethodId;
        this.invoiceCancelPath = invoiceCancelPath != null ? invoiceCancelPath.trim() : "";
    }

    /** @return whether the base URL, API token, and store identifier are configured */
    @Override
    public boolean isLive() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !storeId.isBlank();
    }

    /** @return stable provider identifier used in custody records */
    @Override
    public String providerName() {
        return "BTCPAY";
    }

    /**
     * Requests a fresh on-chain deposit address from the configured store wallet.
     *
     * @param command address-generation request (provider API currently needs no body fields)
     * @return address plus any provider wallet reference and the returned destination
     */
    @Override
    public GeneratedOnchainAddress createOnchainAddress(OnchainAddressCommand command) {
        JsonNode response = post(
                "/api/v1/stores/" + storeId + "/payment-methods/" + onchainPaymentMethodId + "/wallet/generate",
                Map.of());
        String address = text(response, "address", "depositAddress", "destination");
        return new GeneratedOnchainAddress(
                address,
                text(response, "walletId", "walletReference", "accountReference"),
                text(response, "address", "depositAddress", "destination"));
    }

    /**
     * Creates a BTC-denominated BTCPay invoice and resolves its Lightning payment details.
     * The requested expiry is rounded up to minutes and clamped to at least one minute.
     *
     * @param command invoice amount, expiry, and internal wallet/user metadata
     * @return Lightning payment request, provider hash/address, invoice reference, and expiry
     */
    @Override
    public GeneratedLightningInvoice createLightningInvoice(LightningInvoiceCommand command) {
        int expirationMinutes = Math.max(1, (int) Math.ceil(command.expiresInSeconds() / 60d));
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("expirationMinutes", expirationMinutes);
        checkout.put("redirectAutomatically", false);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", "kerosene-ln-" + UUID.randomUUID());
        metadata.put("internalUserId", String.valueOf(command.userId()));
        metadata.put("internalWalletId", String.valueOf(command.walletId()));
        metadata.put("walletName", command.walletName());
        metadata.put("memo", safeText(command.memo()));
        metadata.put("source", "KEROSENE");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", satsToBtc(command.amountSats()).toPlainString());
        payload.put("currency", "BTC");
        payload.put("checkout", checkout);
        payload.put("metadata", metadata);

        JsonNode invoice = post("/api/v1/stores/" + storeId + "/invoices", payload);
        String invoiceId = text(invoice, "id", "invoiceId");
        JsonNode paymentMethod = invoiceId != null ? findPaymentMethod(invoiceId, lightningPaymentMethodId) : null;

        return new GeneratedLightningInvoice(
                text(paymentMethod, "paymentRequest", "bolt11", "invoice", "paymentLink"),
                text(paymentMethod, "paymentHash", "hash"),
                text(paymentMethod, "lightningAddress", "lnAddress"),
                invoiceId,
                parseDateTime(invoice, "expirationTime", "expiresAt"));
    }

    /**
     * Loads the provider invoice and its Lightning method, then maps provider state and amount.
     *
     * @param command invoice lookup identifiers, preferring provider reference to payment hash
     * @return normalized incoming-payment status, received satoshis, expiry, and merged raw data
     */
    @Override
    public IncomingLightningInvoiceStatus getLightningInvoiceStatus(LightningInvoiceStatusCommand command) {
        String invoiceId = firstNonBlank(command.providerReference(), command.paymentHash());
        JsonNode invoice = get("/api/v1/stores/" + storeId + "/invoices/" + invoiceId);
        JsonNode paymentMethod = invoiceId != null ? findPaymentMethod(invoiceId, lightningPaymentMethodId) : null;

        String status = normalizeInvoiceStatus(invoice);
        long receivedSats = btcNodeToSats(invoice.path("amount"));
        if (receivedSats <= 0L) {
            receivedSats = btcNodeToSats(paymentMethod == null ? null : paymentMethod.path("amount"));
        }

        return new IncomingLightningInvoiceStatus(
                status,
                receivedSats > 0L ? receivedSats : null,
                parseDateTime(invoice, "monitoringExpiration", "statusTime", "expiresAt"),
                mergePayload(invoice, paymentMethod));
    }

    /**
     * Cancels an invoice only when both its identifier and cancellation route are configured.
     *
     * @param command invoice identifiers used to locate the BTCPay invoice
     * @return true when a cancellation request was sent; false when cancellation is unavailable
     */
    @Override
    public boolean cancelLightningInvoice(LightningInvoiceCancellationCommand command) {
        String invoiceId = firstNonBlank(command.providerReference(), command.paymentHash());
        if (invoiceId == null || invoiceId.isBlank() || invoiceCancelPath.isBlank()) {
            return false;
        }
        post(invoiceCancelPath.replace("{invoiceId}", invoiceId), Map.of("invoiceId", invoiceId));
        return true;
    }

    /**
     * Refuses provider-side on-chain spending because this system requires quorum signing.
     *
     * @param command requested on-chain payment
     * @return never returns; callers receive an exception explaining the required signing path
     * @throws IllegalStateException always, because on-chain spending is handled by Bitcoin Core
     */
    @Override
    public PaymentResult sendOnchain(OnchainPaymentCommand command) {
        throw new IllegalStateException("On-chain payments are handled by Bitcoin Core quorum signing.");
    }

    /**
     * Pays a Lightning invoice through LND and maps its result to the custody contract.
     *
     * @param command payment request, optional amount, and maximum fee
     * @return LND payment hash, status, fee, and provider payload
     * @throws IllegalStateException when no LND REST client is configured
     */
    @Override
    public PaymentResult payLightning(LightningPaymentCommand command) {
        if (lndRestLightningClient == null) {
            throw new IllegalStateException("LND REST is required for outbound Lightning payments.");
        }
        LndRestLightningClient.LightningPaymentResult payment = lndRestLightningClient.payInvoice(
                command.paymentRequest(),
                command.amountSats(),
                command.maxFeeSats());
        return new PaymentResult(
                null,
                null,
                payment.paymentHash(),
                payment.status(),
                payment.feeSats(),
                payment.rawPayload());
    }

    /**
     * Fetches the raw BTCPay invoice representation for callers that need provider-specific fields.
     *
     * @param invoiceId BTCPay invoice identifier
     * @return parsed provider JSON document
     */
    public JsonNode loadInvoice(String invoiceId) {
        return get("/api/v1/stores/" + storeId + "/invoices/" + invoiceId);
    }

    /**
     * Selects the requested payment method, then a Lightning method, then the first available item.
     *
     * @param invoiceId BTCPay invoice identifier
     * @param preferredPaymentMethodId configured method identifier to prefer
     * @return selected method object or an empty JSON object when none is available
     */
    private JsonNode findPaymentMethod(String invoiceId, String preferredPaymentMethodId) {
        JsonNode response = get(
                "/api/v1/stores/" + storeId + "/invoices/" + invoiceId + "/payment-methods?includeSensitive=true");
        if (response == null || !response.isArray()) {
            return objectMapper.createObjectNode();
        }
        for (JsonNode item : response) {
            String paymentMethodId = text(item, "paymentMethodId", "paymentMethod", "id");
            if (paymentMethodId != null && paymentMethodId.equalsIgnoreCase(preferredPaymentMethodId)) {
                return item;
            }
        }
        for (JsonNode item : response) {
            String paymentMethodId = text(item, "paymentMethodId", "paymentMethod", "id");
            if (paymentMethodId != null && paymentMethodId.toUpperCase().endsWith("-LN")) {
                return item;
            }
        }
        return response.size() > 0 ? response.get(0) : objectMapper.createObjectNode();
    }

    /**
     * Executes an authenticated GET and converts transport or parsing failures to an operation error.
     *
     * @param path provider-relative API path
     * @return parsed JSON response
     * @throws IllegalStateException when the HTTP exchange or response parsing fails
     */
    private JsonNode get(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("BTCPay request failed on " + path, ex);
        }
    }

    /**
     * Serializes and executes an authenticated JSON POST.
     *
     * @param path provider-relative API path
     * @param payload request fields to serialize
     * @return parsed JSON response
     * @throws IllegalStateException when serialization, transport, or response parsing fails
     */
    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("BTCPay request failed on " + path, ex);
        }
    }

    /**
     * Enforces a successful HTTP status and a response body before JSON decoding.
     *
     * @param response response received from BTCPay
     * @return parsed JSON tree
     * @throws Exception when the HTTP response is unsuccessful, empty, or invalid JSON
     */
    private JsonNode parse(ResponseEntity<String> response) throws Exception {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("BTCPay returned HTTP " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    /** @return JSON headers carrying the configured BTCPay token */
    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "token " + apiKey);
        return headers;
    }

    /**
     * Maps BTCPay invoice status strings onto the custody gateway's small status vocabulary.
     * Unknown and absent provider values remain pending to avoid claiming settlement prematurely.
     *
     * @param invoice provider invoice response
     * @return one of SETTLED, EXPIRED, FAILED, or PENDING
     */
    private String normalizeInvoiceStatus(JsonNode invoice) {
        String status = text(invoice, "status");
        if (status == null) {
            return "PENDING";
        }
        return switch (status.trim().toUpperCase()) {
            case "SETTLED" -> "SETTLED";
            case "EXPIRED" -> "EXPIRED";
            case "INVALID" -> "FAILED";
            case "PROCESSING" -> "PENDING";
            default -> "PENDING";
        };
    }

    /**
     * Combines the invoice and chosen payment-method payload for downstream reconciliation.
     *
     * @param invoice provider invoice JSON
     * @param paymentMethod selected Lightning payment-method JSON
     * @return serialized combined payload, falling back to invoice JSON if serialization fails
     */
    private String mergePayload(JsonNode invoice, JsonNode paymentMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice", invoice);
        payload.put("paymentMethod", paymentMethod);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return invoice.toString();
        }
    }

    /**
     * Reads the first nonblank named value from a JSON object or its additionalData object.
     *
     * @param node provider JSON node to inspect
     * @param fields candidate field names in priority order
     * @return first nonblank textual value, or null when no candidate exists
     */
    private String text(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            JsonNode additionalData = node.path("additionalData");
            if (additionalData.isObject()) {
                JsonNode nested = additionalData.path(field);
                if (!nested.isMissingNode() && !nested.isNull()) {
                    String text = nested.asText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parses the first usable date field as UTC LocalDateTime or as a local timestamp.
     *
     * @param node provider JSON object containing date fields
     * @param fields candidate date field names in priority order
     * @return parsed timestamp, or null when values are absent or unparseable
     */
    private LocalDateTime parseDateTime(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value == null) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
            } catch (Exception ignored) {
            }
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Converts a provider BTC amount to whole satoshis, truncating sub-satoshi precision.
     * Invalid or absent values are treated as zero.
     *
     * @param node JSON number or decimal text containing BTC
     * @return amount in satoshis, or zero when it cannot be interpreted
     */
    private long btcNodeToSats(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0L;
        }
        try {
            BigDecimal btc = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText("0"));
            return btc.multiply(SATOSHIS_PER_BITCOIN)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (Exception ex) {
            return 0L;
        }
    }

    /**
     * Converts an integer satoshi amount to a BTC decimal with eight fractional places.
     *
     * @param sats amount in satoshis
     * @return BTC amount at the protocol's maximum decimal precision
     */
    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    /** @param values candidate strings in priority order @return first nonblank value or null */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** @param value nullable metadata text @return empty string for null, otherwise the input */
    private String safeText(String value) {
        return value != null ? value : "";
    }

    /**
     * Trims the configured server URL and removes one trailing slash for path concatenation.
     *
     * @param value raw configured base URL
     * @return normalized URL, or an empty string for null
     */
    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
