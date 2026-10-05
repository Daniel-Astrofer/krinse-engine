package com.kerosene.kfe.adapters.out.integration.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.integration.vaultmesh.KfeVaultMeshTlsSupport;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Fail-closed authorization bridge from KFE to a Vault-plane Kerosene Node.
 *
 * <p>The Node manifest signs the member onion host at the discovery port. KFE
 * keeps using its existing Tor+mTLS Vault transport, but refuses every
 * configured Vault URL whose onion host is absent from the verified manifest.
 */
@Component
@ConditionalOnProperty(name = "kfe.kerosene-node.enabled", havingValue = "true")
public final class KfeKeroseneNodeDirectory {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KfeKeroseneNodeDirectory.class);

    /** Fresh node challenges prevent replay; only authorized roster hosts receive a checkpoint. */
    public void publishLedgerCheckpoint(Function<String, ?> signedCheckpoint) {
        var hosts = currentAuthorizedHosts();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(Math.min(3, hosts.size()))) {
            var deliveries = hosts.stream().map(host -> java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> deliverLedgerCheckpoint(host, signedCheckpoint), workers)).toList();
            if (deliveries.stream().map(java.util.concurrent.CompletableFuture::join)
                    .filter(Boolean::booleanValue).count() == 0) {
                throw new IllegalStateException("No Node accepted the authoritative KFE ledger checkpoint");
            }
        }
    }

    private boolean deliverLedgerCheckpoint(String host, Function<String, ?> signedCheckpoint) {
        String base = "https://" + host + ":8800";
        try {
            var response = client.getForObject(base + "/v1/ledger/challenge", Map.class);
            if (response == null || !(response.get("challenge") instanceof String challenge)) {
                throw new IllegalStateException("Node returned no ledger challenge");
            }
            var reply = client.exchange(base + "/v1/ledger/checkpoint", HttpMethod.POST,
                    new HttpEntity<>(signedCheckpoint.apply(challenge)), Map.class);
            if (!reply.getStatusCode().is2xxSuccessful() || reply.getBody() == null
                    || !Boolean.TRUE.equals(reply.getBody().get("accepted"))) {
                throw new IllegalStateException("Node did not acknowledge ledger checkpoint");
            }
            return true;
        } catch (RuntimeException exception) {
            // No automatic POST replay. A later round obtains a new challenge.
            log.warn("Ledger checkpoint delivery failed for Node {} ({})", host,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    /** Mutual-TLS HTTP client used for quorum readiness and signed membership retrieval. */
    private final RestTemplate client;
    /** JSON mapper that deserializes the node's current membership manifest. */
    private final ObjectMapper objectMapper;
    /** Explicitly configured TLS-authenticated seeds; no unverified endpoint discovery. */
    private final List<String> seedUrls;
    /** Last successful seed tried first on the next roster refresh. */
    private volatile String preferredSeed;
    private volatile Instant refreshRetryAfter = Instant.EPOCH;
    /** Network identifier that every returned manifest must match. */
    private final String expectedNetwork;
    /** Maximum age of a successfully verified roster before it must be fetched again. */
    private final Duration cacheTtl;
    /** Last successfully verified set of Vault onion hosts and its refresh instant. */
    private volatile Snapshot snapshot = new Snapshot(Set.of(), Instant.EPOCH);

    KfeKeroseneNodeDirectory(RestTemplate client, ObjectMapper mapper, String baseUrl, String network) {
        this(client, mapper, List.of(baseUrl), network);
    }

    KfeKeroseneNodeDirectory(RestTemplate client, ObjectMapper mapper, List<String> seeds, String network) {
        this(client, mapper, seeds, network, Duration.ofSeconds(30));
    }

    KfeKeroseneNodeDirectory(RestTemplate client, ObjectMapper mapper, List<String> seeds, String network,
            Duration cacheTtl) {
        this.client = client;
        this.objectMapper = mapper;
        this.expectedNetwork = requireText(network, "network-id");
        this.cacheTtl = cacheTtl;
        this.seedUrls = validateSeeds(seeds);
        this.preferredSeed = seedUrls.getFirst();
    }

    /**
     * Builds the authenticated node client, validating TLS material, transport, base URL and cache policy.
     * The roster cache is clamped to at least one second; each refresh checks quorum before fetching
     * a manifest and fails closed if its network, plane, or member list is invalid.
     *
     * @param builder Spring client builder used to apply timeout and request-factory settings
     * @param objectMapper JSON parser for the membership response
     * @param baseUrl HTTPS onion base URL of the Kerosene Node
     * @param fallbackUrls explicitly configured alternative HTTPS onion seeds
     * @param expectedNetwork network ID that must match the manifest
     * @param certPath client certificate path for mutual TLS
     * @param keyPath client private key path for mutual TLS
     * @param caPath trusted certificate authority path
     * @param transport configured onion transport mode
     * @param socksHost SOCKS proxy hostname when required by the selected transport
     * @param socksPort SOCKS proxy port
     * @param cacheTtlMs roster cache lifetime in milliseconds, clamped to at least 1,000
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KfeKeroseneNodeDirectory(
            RestTemplateBuilder builder,
            ObjectMapper objectMapper,
            @Value("${kfe.kerosene-node.base-url}") String baseUrl,
            @Value("${kfe.kerosene-node.fallback-urls:}") String fallbackUrls,
            @Value("${kfe.kerosene-node.network-id}") String expectedNetwork,
            @Value("${kfe.kerosene-node.tls.cert-path}") String certPath,
            @Value("${kfe.kerosene-node.tls.key-path}") String keyPath,
            @Value("${kfe.kerosene-node.tls.ca-path}") String caPath,
            @Value("${kfe.kerosene-node.transport:tor}") String transport,
            @Value("${kfe.kerosene-node.proxy.socks-host:}") String socksHost,
            @Value("${kfe.kerosene-node.proxy.socks-port:9050}") int socksPort,
            @Value("${kfe.kerosene-node.cache-ttl-ms:15000}") long cacheTtlMs,
            @Value("${kfe.kerosene-node.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${kfe.kerosene-node.read-timeout-ms:20000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.expectedNetwork = requireText(expectedNetwork, "network-id");
        this.cacheTtl = Duration.ofMillis(Math.max(1000, cacheTtlMs));
        String normalized = trimTrailingSlash(requireText(baseUrl, "base-url"));
        var seeds = new java.util.ArrayList<String>();
        seeds.add(normalized);
        if (fallbackUrls != null && !fallbackUrls.isBlank()) {
            seeds.addAll(List.of(fallbackUrls.split(",")));
        }
        this.seedUrls = validateSeeds(seeds);
        this.preferredSeed = seedUrls.getFirst();

        SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                certPath, keyPath, caPath, "", "", "PKCS12", "", "", "PKCS12");
        Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                transport, socksHost, socksPort, true, seedUrls);
        ClientHttpRequestFactory factory =
                KfeVaultMeshTlsSupport.requestFactory(sslContext, true, connectTimeoutMs, readTimeoutMs, proxy);
        this.client = builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .requestFactory(() -> factory)
                .build();
    }

    /**
     * Rejects any configured Vault endpoint absent from the latest verified node membership roster.
     * A fresh roster is fetched only after the cache expires.
     *
     * @param vaultUrls Vault transport URLs whose onion hosts must be authorized
     * @throws IllegalStateException if membership cannot be verified or any host is not authorized
     */
    public void requireAuthorized(Collection<String> vaultUrls) {
        requireAuthorized(vaultUrls, currentAuthorizedHosts());
    }

    /** Checks URL hosts against an already-authorized host set; package visibility supports focused tests. */
    static void requireAuthorized(Collection<String> vaultUrls, Set<String> authorized) {
        for (String vaultUrl : vaultUrls) {
            String host = onionHost(vaultUrl);
            if (!authorized.contains(host)) {
                throw new IllegalStateException(
                        "Vault endpoint is absent from verified Kerosene Node membership: " + host);
            }
        }
    }

    /**
     * Returns the cached authorized hosts or refreshes them from a healthy, network-matching node.
     * Synchronization with a second cache check prevents concurrent callers from duplicating a refresh.
     *
     * @return immutable lowercase host set from the last verified manifest
     * @throws IllegalStateException when quorum, transport, JSON, network, plane, or roster validation fails
     */
    private Set<String> currentAuthorizedHosts() {
        Snapshot current = snapshot;
        if (current.refreshedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return current.hosts();
        }
        synchronized (this) {
            current = snapshot;
            if (current.refreshedAt().plus(cacheTtl).isAfter(Instant.now())) {
                return current.hosts();
            }
            if (refreshRetryAfter.isAfter(Instant.now())) {
                throw new IllegalStateException("Node directory refresh deferred after transport failure");
            }
            RuntimeException unavailable = null;
            var candidates = new java.util.LinkedHashSet<String>();
            candidates.add(preferredSeed);
            candidates.addAll(seedUrls);
            for (String seed : candidates) {
                try {
                    Set<String> hosts = fetchAuthorizedHosts(seed);
                    snapshot = new Snapshot(hosts, Instant.now());
                    preferredSeed = seed;
                    refreshRetryAfter = Instant.EPOCH;
                    return hosts;
                } catch (org.springframework.web.client.ResourceAccessException
                        | org.springframework.web.client.HttpServerErrorException exception) {
                    // Only retry reads against explicitly configured, TLS-authenticated seeds.
                    // Identity/manifest validation and 4xx refusals never become a successful fallback.
                    if (certificateValidationFailed(exception)) throw exception;
                    unavailable = exception;
                    log.warn("Node directory seed unavailable host={} failure={}",
                            onionHost(seed), exception.getClass().getSimpleName());
                }
            }
            // Coalesce concurrent callers after an outage without serving an expired roster.
            refreshRetryAfter = Instant.now().plusSeconds(5);
            throw new IllegalStateException("No configured Node seed supplied a fresh authorized roster", unavailable);
        }
    }

    private Set<String> fetchAuthorizedHosts(String seed) {
        var readiness = client.exchange(seed + "/ready-quorum", HttpMethod.GET, null, String.class);
        if (!readiness.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Kerosene Node quorum is unavailable");
        }
        ResponseEntity<String> response =
                client.exchange(seed + "/v1/membership/current", HttpMethod.GET, null, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Kerosene Node membership is unavailable");
        }
        try {
            Manifest manifest = objectMapper.readValue(response.getBody(), Manifest.class);
            if (!expectedNetwork.equals(manifest.networkId()) || !"vault".equals(manifest.plane())
                    || manifest.members() == null || manifest.members().isEmpty()) {
                throw new IllegalStateException("Kerosene Node network, plane or membership is invalid");
            }
            Set<String> hosts = manifest.members().stream()
                    .map(Member::endpoint).map(KfeKeroseneNodeDirectory::onionHost)
                    .collect(Collectors.toUnmodifiableSet());
            if (!hosts.contains(onionHost(seed))) {
                throw new IllegalStateException("Node seed is absent from its returned membership");
            }
            return hosts;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid Kerosene Node membership response", exception);
        }
    }

    private static boolean certificateValidationFailed(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.security.cert.CertificateException
                    || cause instanceof java.security.cert.CertPathValidatorException
                    || cause instanceof java.security.cert.CertPathBuilderException
                    || cause instanceof javax.net.ssl.SSLPeerUnverifiedException) return true;
        }
        return false;
    }

    private static List<String> validateSeeds(List<String> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalStateException("At least one Node seed is required");
        }
        return seeds.stream().map(value -> trimTrailingSlash(requireText(value, "seed-url")))
                .peek(KfeKeroseneNodeDirectory::onionHost).distinct().toList();
    }

    /**
     * Validates a URL as HTTPS with a v3 onion hostname and extracts the canonical lowercase host.
     * User-info, query strings, and fragments are rejected to avoid ambiguous endpoint identities.
     *
     * @param rawUrl configured or manifest-provided URL to validate
     * @return lowercase hostname including the {@code .onion} suffix
     * @throws IllegalStateException when the URL is not an allowed HTTPS onion endpoint
     */
    static String onionHost(String rawUrl) {
        URI uri = URI.create(requireText(rawUrl, "Vault URL"));
        String host = uri.getHost();
        String onionLabel = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("\\.onion$", "");
        boolean isV3Onion = onionLabel.length() == 56
                && onionLabel.chars().allMatch(character ->
                        (character >= 'a' && character <= 'z') || (character >= '2' && character <= '7'));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !isV3Onion
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("Kerosene Node integration accepts only HTTPS onion endpoints");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** Requires a nonblank configuration value and returns it with surrounding whitespace removed. */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("kfe.kerosene-node." + name + " is required");
        }
        return value.trim();
    }

    /** Removes all terminal slash characters so endpoint paths can be appended exactly once. */
    private static String trimTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** Immutable cache value containing a verified host roster and its successful refresh time.
     * @param hosts canonical lowercase onion hosts authorized by the manifest
     * @param refreshedAt instant when readiness and membership validation both succeeded
     */
    private record Snapshot(
            /** Verified authorized hostnames. */
            Set<String> hosts,
            /** Successful verification time used to calculate cache expiry. */
            Instant refreshedAt) {}

    /** JSON model for a node membership manifest; unknown wire fields are intentionally ignored.
     * @param networkId network identifier signed into the manifest
     * @param plane deployment plane; this adapter accepts {@code vault}
     * @param members roster entries whose endpoints are validated as v3 onion URLs
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Manifest(
            /** Network identifier from the wire-format {@code network_id} property. */
            @com.fasterxml.jackson.annotation.JsonProperty("network_id") String networkId,
            /** Node plane associated with this roster. */
            String plane,
            /** Membership entries advertised by the node. */
            List<Member> members) {}

    /** One advertised node membership endpoint.
     * @param endpoint HTTPS v3 onion URL for the member
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Member(
            /** URL whose onion host is included in the verified authorization roster. */
            String endpoint) {}
}
