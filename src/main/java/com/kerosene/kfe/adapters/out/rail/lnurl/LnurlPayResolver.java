package com.kerosene.kfe.adapters.out.rail.lnurl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningDestinationClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Resolves LNURL-pay (LUD-06) and Lightning Address (LUD-16) to a BOLT11 invoice.
 *
 * <p>SSRF-hardened: validates scheme, blocks private/reserved IPs,
 * prevents cross-host redirects, limits response size.
 * Uses a dedicated non-redirecting RestTemplate with strict timeouts.
 */
@Component
@ConditionalOnProperty(prefix = "lightning.lnd.rest", name = "enabled", havingValue = "true")
public class LnurlPayResolver {

    /** Logger for endpoint-resolution failures without exposing callback query secrets. */
    private static final Logger log = LoggerFactory.getLogger(LnurlPayResolver.class);
    /** Maximum number of manually validated redirect hops after the original request. */
    private static final int MAX_REDIRECTS = 3;
    /** Maximum response body size accepted from an LNURL endpoint. */
    private static final int MAX_RESPONSE_BYTES = 100_000;
    /** Connection timeout for metadata and invoice callback requests. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Read timeout for metadata and invoice callback responses. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Dedicated HTTP client with automatic redirects disabled for explicit SSRF checks. */
    private final RestTemplate lnurlRestTemplate;
    /** JSON parser for LNURL payRequest and callback responses. */
    private final ObjectMapper objectMapper;
    /** Whether validated Tor onion destinations are permitted by deployment policy. */
    private final boolean allowTor;

    /**
     * Creates the LNURL resolver with bounded, non-redirecting HTTP behavior.
     *
     * @param objectMapper JSON parser for remote protocol responses
     * @param allowTor whether onion hosts may be used after address validation
     */
    public LnurlPayResolver(
            ObjectMapper objectMapper,
            @Value("${kfe.lightning.lnurl.allow-tor:false}") boolean allowTor) {
        this.lnurlRestTemplate = createLnurlRestTemplate();
        this.objectMapper = objectMapper;
        this.allowTor = allowTor;
    }

    /**
     * Creates a RestTemplate with fixed connect/read timeouts and automatic redirects disabled.
     * Disabling automatic redirects allows every redirect target to be validated before access.
     *
     * @return configured isolated HTTP client for LNURL requests
     */
    private static RestTemplate createLnurlRestTemplate() {
        SimpleClientHttpRequestFactory factory = new NonRedirectingRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return new RestTemplate(factory);
    }

    /** Request factory that leaves redirect handling to the resolver's validation loop. */
    private static final class NonRedirectingRequestFactory extends SimpleClientHttpRequestFactory {

        /**
         * Configures each connection not to follow redirects automatically.
         *
         * @param connection connection about to be prepared
         * @param httpMethod request method selected by Spring
         * @throws java.io.IOException when the base factory cannot prepare the connection
         */
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    /**
     * @param classified LNURL or LIGHTNING_ADDRESS
     * @param amountSats amount the user intends to pay
     * @return bolt11 payment request
     * @throws IllegalArgumentException when destination, amount, payRequest, callback, or amount
     *         limits are invalid
     * @throws ArithmeticException when satoshi-to-millisatoshi conversion overflows
     */
    public String resolveBolt11(LightningDestinationClassifier.Classified classified, long amountSats) {
        if (classified == null) {
            throw new IllegalArgumentException("Lightning destination is required.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive for LNURL / Lightning Address.");
        }
        String endpoint = switch (classified.kind()) {
            case LNURL -> LnurlBech32.decodeToUrl(classified.value());
            case LIGHTNING_ADDRESS -> lightningAddressToLnurlpUrl(classified.value());
            default -> throw new IllegalArgumentException(
                    "LNURL resolver does not support kind " + classified.kind());
        };
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Could not decode LNURL / Lightning Address to a URL.");
        }

        // SSRF: validate scheme and host BEFORE any network call
        LnurlSslGuard.validateScheme(endpoint, allowTor);
        LnurlSslGuard.validateRemoteHost(endpoint);

        JsonNode payRequest = httpGetJson(endpoint);
        String tag = text(payRequest, "tag");
        if (tag != null && !tag.isBlank() && !"payRequest".equalsIgnoreCase(tag)) {
            throw new IllegalArgumentException("LNURL tag is not payRequest: " + tag);
        }
        String callback = text(payRequest, "callback");
        if (callback == null || callback.isBlank()) {
            throw new IllegalArgumentException("LNURL payRequest missing callback.");
        }
        long minSendable = longField(payRequest, "minSendable");
        long maxSendable = longField(payRequest, "maxSendable");
        long amountMsat = Math.multiplyExact(amountSats, 1000L);
        if (minSendable > 0L && amountMsat < minSendable) {
            throw new IllegalArgumentException(
                    "Amount below LNURL minSendable (" + (minSendable / 1000L) + " sats).");
        }
        if (maxSendable > 0L && amountMsat > maxSendable) {
            throw new IllegalArgumentException(
                    "Amount above LNURL maxSendable (" + (maxSendable / 1000L) + " sats).");
        }

        // SSRF: validate callback URL before fetching invoice
        LnurlSslGuard.validateScheme(callback, allowTor);
        LnurlSslGuard.validateRemoteHost(callback);

        String invoiceUrl = UriComponentsBuilder.fromUriString(callback)
                .replaceQueryParam("amount", amountMsat)
                .build(true)
                .toUriString();
        JsonNode invoiceResponse = httpGetJson(invoiceUrl);
        String pr = text(invoiceResponse, "pr");
        if (pr == null || pr.isBlank()) {
            // Some servers use "payment_request"
            pr = text(invoiceResponse, "payment_request");
        }
        if (pr == null || pr.isBlank()) {
            String reason = text(invoiceResponse, "reason", "status");
            throw new IllegalArgumentException(
                    "LNURL callback did not return a BOLT11 invoice"
                            + (reason != null ? ": " + reason : "."));
        }
        String lower = pr.trim().toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt")
                || lower.startsWith("lnsb") || lower.startsWith("lntbs"))) {
            throw new IllegalArgumentException("LNURL callback returned a non-BOLT11 pr.");
        }
        log.info("[LNURL] resolved {} → bolt11 ({} sats)", classified.kind(), amountSats);
        return pr.trim();
    }

    /**
     * Converts a LUD-16 {@code user@domain} address into its well-known LNURL-pay metadata URL.
     * Onion domains use HTTP because Tor provides transport privacy; other domains use HTTPS.
     *
     * @param address Lightning Address text
     * @return encoded well-known URL, or null when user/domain parts are missing
     */
    public static String lightningAddressToLnurlpUrl(String address) {
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return null;
        }
        String name = address.substring(0, at).trim();
        String domain = address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || domain.isEmpty()) {
            return null;
        }
        // LUD-16: https://<domain>/.well-known/lnurlp/<name> (http only for .onion)
        boolean onion = domain.endsWith(".onion");
        String scheme = onion ? "http" : "https";
        String encodedName = java.net.URLEncoder
                .encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return scheme + "://" + domain + "/.well-known/lnurlp/" + encodedName;
    }

    /**
     * Fetches and parses JSON while validating every URL, redirect, port, response status, and size.
     * Redirects may not change host, and the response body is capped before JSON parsing.
     *
     * @param url initially validated metadata or callback URL
     * @return parsed JSON response
     * @throws IllegalArgumentException for unsafe URLs, redirects, HTTP errors, oversized/empty bodies,
     *         or invalid JSON
     */
    private JsonNode httpGetJson(String url) {
        URI originalUri = URI.create(url);
        LnurlSslGuard.validatePort(originalUri);
        URI currentUri = originalUri;

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            try {
                ResponseEntity<byte[]> response = lnurlRestTemplate.exchange(
                        currentUri, HttpMethod.GET, null, byte[].class);

                HttpStatus statusCode = (HttpStatus) response.getStatusCode();

                // Handle redirects manually
                if (statusCode.is3xxRedirection()) {
                    URI location = response.getHeaders().getLocation();
                    if (location == null) {
                        throw new IllegalArgumentException(
                                "LNURL redirect without Location header from " + safeHost(currentUri.toString()));
                    }
                    String redirectUrl = location.toString();

                    // SSRF checks on redirect target
                    LnurlSslGuard.validateScheme(redirectUrl, allowTor);
                    LnurlSslGuard.validateRemoteHost(redirectUrl);
                    LnurlSslGuard.validateNoHostChange(originalUri, location);
                    LnurlSslGuard.validatePort(location);

                    currentUri = location;
                    continue;
                }

                if (!statusCode.is2xxSuccessful()) {
                    throw new IllegalArgumentException(
                            "LNURL HTTP " + statusCode.value() + " for " + safeHost(url));
                }

                byte[] body = response.getBody();
                if (body == null) {
                    throw new IllegalArgumentException(
                            "LNURL empty response body from " + safeHost(url));
                }

                // Limit response size
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalArgumentException(
                            "LNURL response too large (" + body.length + " bytes) from " + safeHost(url));
                }

                return objectMapper.readTree(body);
            } catch (RestClientException | IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "LNURL fetch failed for " + safeHost(url) + ": " + ex.getMessage(), ex);
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "LNURL parse failed for " + safeHost(url) + ": " + ex.getMessage(), ex);
            }
        }

        throw new IllegalArgumentException(
                "LNURL: too many redirects for " + safeHost(url));
    }

    /**
     * Extracts a host label for diagnostics without including path, query, or callback tokens.
     *
     * @param url endpoint URL
     * @return parsed host or generic {@code endpoint} label when parsing fails
     */
    private static String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return "endpoint";
        }
    }

    /**
     * Reads the first nonblank textual value from the candidate JSON fields.
     *
     * @param node provider response object
     * @param fields field names in priority order
     * @return trimmed text or null if no candidate is present
     */
    private static String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.isTextual()) {
                String t = value.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    /**
     * Reads a nonnegative integral or decimal-text field, treating absent/invalid values as zero.
     *
     * @param node provider response object
     * @param field field name
     * @return parsed nonnegative long, or zero when absent/invalid
     */
    private static long longField(JsonNode node, String field) {
        if (node == null) {
            return 0L;
        }
        JsonNode value = node.path(field);
        if (value.isIntegralNumber()) {
            return Math.max(0L, value.asLong());
        }
        if (value.isTextual()) {
            try {
                return Math.max(0L, Long.parseLong(value.asText().trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
