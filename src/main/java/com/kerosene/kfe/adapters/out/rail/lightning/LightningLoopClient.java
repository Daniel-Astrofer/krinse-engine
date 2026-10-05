package com.kerosene.kfe.adapters.out.rail.lightning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Lightning Loop (loopd) REST client for submarine swaps when circular self-pay is insufficient.
 *
 * <p>Enable with {@code lightning.loop.enabled=true} and {@code lightning.loop.base-url}.
 */
@Component
@ConditionalOnProperty(prefix = "lightning.loop", name = "enabled", havingValue = "true")
public class LightningLoopClient {

    /** HTTP client configured for the LND/loopd transport and TLS settings. */
    private final RestTemplate restTemplate;
    /** Serializer and parser for loopd JSON request and response bodies. */
    private final ObjectMapper objectMapper;
    /** Normalized loopd base URL without one trailing slash. */
    private final String baseUrl;
    /** Optional hex macaroon sent using loopd's gRPC metadata header. */
    private final String macaroonHex;
    /** Maximum swap service fee included in every loop request, clamped to zero or greater. */
    private final long maxSwapFeeSats;
    /** Maximum miner fee included in every loop request, clamped to zero or greater. */
    private final long maxMinerFeeSats;

    /**
     * Creates the optional loopd client and normalizes endpoint and fee policy settings.
     *
     * @param restTemplate HTTP client shared with the LND REST integration
     * @param objectMapper JSON serializer and parser
     * @param baseUrl loopd REST base URL
     * @param macaroonHex optional loopd macaroon in hexadecimal form
     * @param maxSwapFeeSats swap-service fee ceiling in satoshis
     * @param maxMinerFeeSats on-chain miner-fee ceiling in satoshis
     */
    public LightningLoopClient(
            @Qualifier("lndRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lightning.loop.base-url}") String baseUrl,
            @Value("${lightning.loop.macaroon:}") String macaroonHex,
            @Value("${lightning.loop.max-swap-fee-sats:10000}") long maxSwapFeeSats,
            @Value("${lightning.loop.max-miner-fee-sats:5000}") long maxMinerFeeSats) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = sanitize(baseUrl);
        this.macaroonHex = macaroonHex != null ? macaroonHex.trim() : "";
        this.maxSwapFeeSats = Math.max(0L, maxSwapFeeSats);
        this.maxMinerFeeSats = Math.max(0L, maxMinerFeeSats);
    }

    /** @return true when a nonblank loopd base URL has been configured */
    public boolean isLive() {
        return !baseUrl.isBlank();
    }

    /** @return stable provider identifier for operation metadata */
    public String providerName() {
        return "LIGHTNING_LOOP";
    }

    /**
     * Starts a Loop In swap that converts on-chain funds into inbound Lightning liquidity.
     *
     * @param amountSats requested swap amount in satoshis
     * @return operation result containing loopd swap identifier or failure detail
     */
    public LoopResult loopIn(long amountSats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amt", String.valueOf(amountSats));
        payload.put("max_swap_fee", String.valueOf(maxSwapFeeSats));
        payload.put("max_miner_fee", String.valueOf(maxMinerFeeSats));
        return postLoop("/v1/loop/in", payload, "LOOP_IN");
    }

    /**
     * Starts a Loop Out swap that converts Lightning funds to an on-chain destination.
     * Optional channel identifiers are sent as numeric values when parseable, otherwise as text.
     *
     * @param amountSats requested swap amount in satoshis
     * @param outgoingChanIds optional channel IDs to constrain the outgoing route
     * @return operation result containing loopd swap identifier or failure detail
     */
    public LoopResult loopOut(long amountSats, List<String> outgoingChanIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amt", String.valueOf(amountSats));
        payload.put("max_swap_fee", String.valueOf(maxSwapFeeSats));
        payload.put("max_miner_fee", String.valueOf(maxMinerFeeSats));
        if (outgoingChanIds != null && !outgoingChanIds.isEmpty()) {
            payload.put(
                    "outgoing_chan_set",
                    outgoingChanIds.stream()
                            .map(id -> {
                                try {
                                    return Long.parseLong(id);
                                } catch (NumberFormatException ex) {
                                    return id;
                                }
                            })
                            .toList());
        }
        return postLoop("/v1/loop/out", payload, "LOOP_OUT");
    }

    /**
     * Sends a loopd operation request, attaches the optional macaroon, and maps HTTP/JSON outcomes.
     * Failures are represented in {@link LoopResult} so callers can decide retry or recovery policy.
     *
     * @param path loopd operation path
     * @param payload request body fields
     * @param kind stable operation label, such as LOOP_IN or LOOP_OUT
     * @return success or failure result with available provider response data
     */
    private LoopResult postLoop(String path, Map<String, ?> payload, String kind) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!macaroonHex.isBlank()) {
                headers.set("Grpc-Metadata-macaroon", macaroonHex);
            }
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return LoopResult.failed(kind, "HTTP " + response.getStatusCode(), null);
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            String id = text(body, "id", "swap_hash");
            return LoopResult.ok(kind, id, body.toString());
        } catch (Exception ex) {
            return LoopResult.failed(
                    kind, ex.getMessage() != null ? ex.getMessage() : "loop request failed", null);
        }
    }

    /**
     * Reads the first present textual field from a loopd response object.
     *
     * @param node response JSON node
     * @param fields candidate field names in priority order
     * @return field text or null when none is present as text
     */
    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Trims the endpoint URL and removes one trailing slash for path concatenation.
     *
     * @param value configured endpoint URL
     * @return normalized URL, or an empty string when null
     */
    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Result of one Loop In or Loop Out REST operation.
     *
     * @param succeeded whether the request received a successful HTTP response
     * @param kind operation label supplied by the caller
     * @param swapId swap identifier extracted from the provider response, if present
     * @param message {@code OK} on success or failure detail on rejection/error
     * @param rawPayload provider JSON response when available
     */
    public record LoopResult(
            boolean succeeded,
            String kind,
            String swapId,
            String message,
            String rawPayload) {

        /**
         * Creates a successful result with the provider swap ID and raw response body.
         *
         * @param kind operation label
         * @param swapId provider swap identifier, possibly null if omitted by the response
         * @param raw provider response JSON
         * @return successful loop result with message {@code OK}
         */
        public static LoopResult ok(String kind, String swapId, String raw) {
            return new LoopResult(true, kind, swapId, "OK", raw);
        }

        /**
         * Creates a failed result without assigning a swap ID.
         *
         * @param kind operation label
         * @param message failure reason
         * @param raw provider response when available
         * @return failed loop result
         */
        public static LoopResult failed(String kind, String message, String raw) {
            return new LoopResult(false, kind, null, message, raw);
        }
    }
}
