package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.operations.FinancialQuorumPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.net.Proxy;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/** Collects threshold-pinned Vault keys and verifies signed financial-quorum decisions from the mesh. */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public final class VaultMeshFinancialQuorumAdapter implements FinancialQuorumPort {

    /** Hex codec used to validate keys, aggregate proofs, and proposal digest fields. */
    private static final HexFormat HEX = HexFormat.of();
    /** Domain-separation prefix included in every canonical financial-quorum digest. */
    private static final String DOMAIN = "kerosene-financial-quorum-v1";
    /** Logger that records safe phase-level transport and validation diagnostics. */
    private static final Logger log = LoggerFactory.getLogger(VaultMeshFinancialQuorumAdapter.class);
    /** Stable public-facing failure message that does not reveal transport causes or internal URLs. */
    private static final String UNAVAILABLE = "Vault financial quorum is temporarily unavailable";
    /** Default long-read budget used by the compatibility constructor for aggregate proof requests. */
    private static final long DEFAULT_PROOF_READ_TIMEOUT_MS = 100_000;
    /** Default lifetime of proposals synthesized for the deprecated hash-only API. */
    private static final long DEFAULT_PROPOSAL_TTL_MS = 120_000;

    /** HTTP client for ordinary keyset/context reads under the general read budget. */
    private final RestTemplate restTemplate;
    /** Dedicated client allowing enough time for threshold aggregate proof generation. */
    private final RestTemplate proofRestTemplate;
    /** JSON mapper used to parse and validate Vault protocol objects. */
    private final ObjectMapper objectMapper;
    /** Primary coordinator URL used for financial quorum decision endpoints. */
    private final String coordinatorUrl;
    /** Deduplicated member URLs queried for threshold agreement on the USERS group key. */
    private final List<String> vaultUrls;
    /** Optional legacy Vault token included only when configured; mTLS can provide transport identity. */
    private final String apiToken;
    /** Constitution roster size against which response metadata is checked. */
    private final int memberCount;
    /** Minimum participant count required to pin a group key and accept an aggregate decision. */
    private final int threshold;
    /** UTC clock seam used for proposal expiry, submission, and remote decision-time validation. */
    private final Clock clock;
    /** Lifetime assigned to proposals synthesized by the deprecated hash-only operation. */
    private final long proposalTtlMs;
    /** Maximum connection-plus-read budget for collecting enough independent group-key responses. */
    private final long keysetWaitMs;

    /**
     * Compatibility constructor using default proof-read and synthesized-proposal budgets.
     * The Spring-configured constructor exposes these budgets separately for production tuning.
     *
     * @param restTemplateBuilder base HTTP client builder
     * @param objectMapper response parser
     * @param coordinatorUrl financial quorum coordinator URL
     * @param vaultUrls comma-separated Vault member URLs
     * @param apiToken optional legacy token
     * @param connectTimeoutMs connection budget
     * @param readTimeoutMs ordinary read budget
     * @param tlsEnabled whether mTLS is enabled
     * @param tlsCertPath PEM client certificate
     * @param tlsKeyPath PEM client key
     * @param tlsCaPath trusted CA bundle
     * @param tlsKeystorePath optional client keystore
     * @param tlsKeystorePassword client keystore password
     * @param tlsKeystoreType client keystore format
     * @param tlsTruststorePath optional server truststore
     * @param tlsTruststorePassword truststore password
     * @param tlsTruststoreType truststore format
     * @param hostnameVerification whether HTTPS host identity is checked
     * @param memberCount constitution roster size
     * @param threshold required quorum size
     * @param transport direct or Tor transport mode
     * @param socksHost Tor SOCKS proxy host
     * @param socksPort Tor SOCKS proxy port
     */
    public VaultMeshFinancialQuorumAdapter(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            String coordinatorUrl, String vaultUrls, String apiToken,
            long connectTimeoutMs, long readTimeoutMs, boolean tlsEnabled,
            String tlsCertPath, String tlsKeyPath, String tlsCaPath,
            String tlsKeystorePath, String tlsKeystorePassword, String tlsKeystoreType,
            String tlsTruststorePath, String tlsTruststorePassword, String tlsTruststoreType,
            boolean hostnameVerification, int memberCount, int threshold,
            String transport, String socksHost, int socksPort) {
        this(restTemplateBuilder, objectMapper, coordinatorUrl, vaultUrls, apiToken,
                connectTimeoutMs, readTimeoutMs, tlsEnabled, tlsCertPath, tlsKeyPath, tlsCaPath,
                tlsKeystorePath, tlsKeystorePassword, tlsKeystoreType,
                tlsTruststorePath, tlsTruststorePassword, tlsTruststoreType,
                hostnameVerification, memberCount, threshold, transport, socksHost, socksPort,
                DEFAULT_PROOF_READ_TIMEOUT_MS, DEFAULT_PROPOSAL_TTL_MS);
    }

    /**
     * Configures production quorum transport with separate ordinary/proof read budgets, validated
     * roster thresholds, and optional mutual TLS. Invalid timeout budgets, roster sizing, URL, or
     * incomplete TLS material fail during startup rather than weakening decision verification.
     *
     * @param restTemplateBuilder base Spring HTTP client builder
     * @param objectMapper JSON parser for Vault protocol responses
     * @param coordinatorUrl required coordinator URL
     * @param vaultUrls comma-separated configured Vault members
     * @param apiToken optional legacy Vault token
     * @param connectTimeoutMs connection budget in milliseconds
     * @param readTimeoutMs ordinary request read budget in milliseconds
     * @param tlsEnabled whether mTLS should be enabled
     * @param tlsCertPath PEM client certificate path
     * @param tlsKeyPath PEM private key path
     * @param tlsCaPath PEM trusted CA bundle
     * @param tlsKeystorePath client keystore path
     * @param tlsKeystorePassword client keystore password
     * @param tlsKeystoreType client keystore format
     * @param tlsTruststorePath truststore path
     * @param tlsTruststorePassword truststore password
     * @param tlsTruststoreType truststore format
     * @param hostnameVerification whether to verify peer hostnames
     * @param memberCount constitution member count
     * @param threshold required threshold of agreeing Vault members
     * @param transport direct or Tor mode
     * @param socksHost Tor SOCKS host
     * @param socksPort Tor SOCKS port
     * @param proofReadTimeoutMs read budget for aggregate proof generation
     * @param proposalTtlMs lifetime for synthesized legacy proposals
     */
    @Autowired
    public VaultMeshFinancialQuorumAdapter(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kfe.vaultmesh.base-url}") String coordinatorUrl,
            @Value("${kfe.vaultmesh.urls}") String vaultUrls,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${kfe.vaultmesh.read-timeout-ms:10000}") long readTimeoutMs,
            @Value("${kfe.vaultmesh.tls.enabled:false}") boolean tlsEnabled,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.keystore-password:}") String tlsKeystorePassword,
            @Value("${kfe.vaultmesh.tls.keystore-type:PKCS12}") String tlsKeystoreType,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath,
            @Value("${kfe.vaultmesh.tls.truststore-password:}") String tlsTruststorePassword,
            @Value("${kfe.vaultmesh.tls.truststore-type:PKCS12}") String tlsTruststoreType,
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean hostnameVerification,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold,
            @Value("${kfe.vaultmesh.transport:direct}") String transport,
            @Value("${kfe.vaultmesh.proxy.socks-host:}") String socksHost,
            @Value("${kfe.vaultmesh.proxy.socks-port:9050}") int socksPort,
            @Value("${kfe.vaultmesh.financial-quorum.read-timeout-ms:100000}") long proofReadTimeoutMs,
            @Value("${kfe.vaultmesh.financial-quorum.proposal-ttl-ms:120000}") long proposalTtlMs) {
        validateBudget(connectTimeoutMs);
        validateBudget(readTimeoutMs);
        validateBudget(proofReadTimeoutMs);
        validateBudget(proposalTtlMs);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Clock.systemUTC();
        this.proposalTtlMs = proposalTtlMs;
        this.keysetWaitMs = connectTimeoutMs + readTimeoutMs;
        this.coordinatorUrl = trimUrl(coordinatorUrl);
        this.vaultUrls = parseUrls(vaultUrls, this.coordinatorUrl);
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.memberCount = memberCount;
        this.threshold = threshold;
        validateRoster();
        boolean configuredTls = KfeVaultMeshTlsSupport.tlsConfigured(
                tlsEnabled, tlsCertPath, tlsKeyPath, tlsCaPath, tlsKeystorePath, tlsTruststorePath);
        Set<String> transportUrls = new HashSet<>(this.vaultUrls);
        transportUrls.add(this.coordinatorUrl);
        Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                transport, socksHost, socksPort, configuredTls, transportUrls);
        RestTemplateBuilder builder = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs));
        RestTemplateBuilder proofBuilder = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(proofReadTimeoutMs));
        if (configuredTls) {
            SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                    tlsCertPath, tlsKeyPath, tlsCaPath,
                    tlsKeystorePath, tlsKeystorePassword, tlsKeystoreType,
                    tlsTruststorePath, tlsTruststorePassword, tlsTruststoreType);
            ClientHttpRequestFactory factory =
                    KfeVaultMeshTlsSupport.requestFactory(
                            sslContext,
                            hostnameVerification,
                            (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE),
                            (int) Math.min(readTimeoutMs, Integer.MAX_VALUE),
                            proxy);
            builder = builder.requestFactory(() -> factory);
            ClientHttpRequestFactory proofFactory = KfeVaultMeshTlsSupport.requestFactory(
                    sslContext, hostnameVerification, (int) connectTimeoutMs, (int) proofReadTimeoutMs, proxy);
            proofBuilder = proofBuilder.requestFactory(() -> proofFactory);
        } else if (tlsEnabled) {
            throw new IllegalStateException("Vault financial quorum requires complete mTLS material");
        }
        this.restTemplate = builder.build();
        this.proofRestTemplate = proofBuilder.build();
    }

    /**
     * Isolated transport/clock seam for deterministic tests; production always uses the constructors
     * that build bounded HTTP clients and validate TLS/transport configuration.
     *
     * @param restTemplate ordinary member and context HTTP client
     * @param proofRestTemplate long-budget aggregate proof client
     * @param objectMapper protocol JSON mapper
     * @param coordinatorUrl quorum coordinator URL
     * @param vaultUrls comma-separated simulated member URLs
     * @param memberCount configured constitution size
     * @param threshold number of member responses required to agree
     * @param clock controllable UTC clock for expiry and skew checks
     * @param proposalTtlMs lifetime for legacy generated proposals
     * @param keysetWaitMs maximum group-key vote collection window
     */
    VaultMeshFinancialQuorumAdapter(RestTemplate restTemplate, RestTemplate proofRestTemplate,
            ObjectMapper objectMapper, String coordinatorUrl, String vaultUrls,
            int memberCount, int threshold, Clock clock, long proposalTtlMs, long keysetWaitMs) {
        validateBudget(proposalTtlMs);
        validateBudget(keysetWaitMs);
        this.restTemplate = Objects.requireNonNull(restTemplate);
        this.proofRestTemplate = Objects.requireNonNull(proofRestTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.coordinatorUrl = trimUrl(coordinatorUrl);
        this.vaultUrls = parseUrls(vaultUrls, this.coordinatorUrl);
        this.memberCount = memberCount;
        this.threshold = threshold;
        this.clock = Objects.requireNonNull(clock);
        this.proposalTtlMs = proposalTtlMs;
        this.keysetWaitMs = keysetWaitMs;
        this.apiToken = "";
        validateRoster();
    }

    /**
     * Requires a live proposal, pins the USERS group key by member threshold, and accepts only a
     * coordinator decision whose constitution/proposal metadata and BIP-340 aggregate proof verify.
     *
     * @param proposal immutable proposal fields whose canonical digest must be approved
     * @return verified threshold decision and the accepted/rejected/unavailable member sets
     * @throws IllegalArgumentException when the proposal is absent or expired
     * @throws IllegalStateException when key agreement, transport, metadata, or signature verification fails
     */
    @Override
    public QuorumDecision requireThresholdConsensus(Proposal proposal) {
        if (proposal == null || !proposal.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Live financial quorum proposal required");
        }
        Set<String> pinnedGroupKeys = requireThresholdGroupKeyAgreement(proposal.expiresAt());
        return requestProof(proposal, pinnedGroupKeys);
    }

    /** Requests aggregate proof for a proposal and verifies all response commitments against the pinned key. */
    private QuorumDecision requestProof(Proposal proposal, Set<String> pinnedGroupKeys) {
        requireLiveDeadline(proposal);
        byte[] digest = canonicalDigest(proposal);
        Map<String, Object> request = Map.of(
                "proposal_hash", proposal.proposalHash(),
                "constitution_hash", proposal.constitutionHash(),
                "constitution_epoch", proposal.constitutionEpoch(),
                "submitted_at_epoch_ms", proposal.submittedAt().toEpochMilli(),
                "expires_at_epoch_ms", proposal.expiresAt().toEpochMilli());
        JsonNode response = postJson(coordinatorUrl + "/v1/financial-quorum", request);
        requireLiveDeadline(proposal);
        try {
        requireText(response, "decision", "ACCEPTED");
        requireText(response, "proposal_hash", proposal.proposalHash());
        requireText(response, "constitution_hash", proposal.constitutionHash());
        requireLong(response, "constitution_epoch", proposal.constitutionEpoch());
        requireLong(response, "configured_members", memberCount);
        requireLong(response, "required_threshold", threshold);
        requireText(response, "signed_digest", HEX.formatHex(digest));

        byte[] responseKey = decodeHex(response.path("verifying_key").asText(), "verifying_key");
        String responseKeyHex = xOnly(responseKey).toLowerCase();
        if (!pinnedGroupKeys.contains(responseKeyHex)) {
            throw new IllegalStateException("Financial quorum proof key differs from threshold-pinned USERS key");
        }
        byte[] signature = decodeHex(response.path("aggregate_proof").asText(), "aggregate_proof");
        if (!Bip340Verifier.verify(digest, responseKey, signature)) {
            throw new IllegalStateException("Financial quorum FROST/BIP340 proof verification failed");
        }

        Set<String> accepted = textSet(response.path("accepted_members"));
        Set<String> rejected = textSet(response.path("rejected_members"));
        Set<String> unavailable = textSet(response.path("unavailable_members"));
        JsonNode decidedAtNode = response.path("decided_at_epoch_ms");
        if (!decidedAtNode.isIntegralNumber() || !decidedAtNode.canConvertToLong()) {
            throw new IllegalStateException("Invalid decision timestamp");
        }
        Instant decidedAt = Instant.ofEpochMilli(decidedAtNode.longValue());
        // Match Vault's allowed 30 s clock skew without accepting stale/future decisions.
        if (decidedAt.isBefore(proposal.submittedAt().minusSeconds(30))
                || !decidedAt.isBefore(proposal.expiresAt())
                || decidedAt.isAfter(clock.instant().plusSeconds(30))) {
            throw new IllegalStateException("Incoherent decision timestamp");
        }
        return new QuorumDecision(
                Decision.ACCEPTED,
                proposal.proposalHash(),
                proposal.constitutionHash(),
                proposal.constitutionEpoch(),
                memberCount,
                threshold,
                accepted,
                rejected,
                unavailable,
                response.path("aggregate_proof").asText(),
                decidedAt);
        } catch (RuntimeException exception) {
            log.warn("[KFE Quorum] phase=proof_validation outcome=rejected exceptionClass={}",
                    exception.getClass().getSimpleName());
            throw unavailable();
        }
    }

    /**
     * Deprecated compatibility path that retrieves the current constitution context and synthesizes
     * a short-lived proposal around a hash. It delegates to the full proof verification path.
     *
     * @param proposalHash canonical 64-character SHA-256 hex proposal hash
     * @return compatibility result with accepted and configured member counts
     * @throws IllegalArgumentException when the hash is malformed
     * @throws IllegalStateException when context, key agreement, or proof validation is unavailable
     */
    @Override
    @Deprecated(forRemoval = true)
    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        if (proposalHash == null || !proposalHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proposalHash must be a canonical SHA-256 hex digest");
        }
        Set<String> pinnedGroupKeys = requireThresholdGroupKeyAgreement(null);
        JsonNode epoch = getJson(coordinatorUrl + "/v1/financial-quorum/context", "context", -1);
        JsonNode epochNumber = epoch.path("constitution_epoch");
        if (!epochNumber.isIntegralNumber() || !epochNumber.canConvertToLong() || epochNumber.longValue() < 0
                || !epoch.path("constitution_hash").isTextual() || epoch.path("constitution_hash").asText().isBlank()) {
            log.warn("[KFE Quorum] phase=context_validation outcome=rejected");
            throw unavailable();
        }
        Instant submittedAt = clock.instant();
        QuorumDecision decision = requestProof(new Proposal(
                proposalHash,
                epoch.path("constitution_hash").asText(),
                epoch.path("constitution_epoch").asLong(),
                submittedAt,
                submittedAt.plusMillis(proposalTtlMs)), pinnedGroupKeys);
        return new Result(decision.acceptedMembers().size(), decision.configuredMembers());
    }

    /**
     * Polls member deposit metadata concurrently and returns the x-only USERS group key(s) that
     * received at least {@link #threshold} independent votes before the deadline.
     */
    private Set<String> requireThresholdGroupKeyAgreement(Instant proposalDeadline) {
        Map<String, Integer> counts = new HashMap<>();
        long waitMs = keysetWaitMs;
        if (proposalDeadline != null) {
            waitMs = Math.min(waitMs, Math.max(0, Duration.between(clock.instant(), proposalDeadline).toMillis()));
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var completed = new ExecutorCompletionService<Set<String>>(executor);
        List<Future<Set<String>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < vaultUrls.size(); index++) {
                final int member = index;
                final String url = vaultUrls.get(index);
                futures.add(completed.submit(() -> depositKeys(getJson(
                        url + "/v1/bitcoin/deposit?bucket=USERS", "keyset", member))));
            }
            for (int received = 0; received < vaultUrls.size(); received++) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) break;
                Future<Set<String>> next = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                try {
                    // One completed task per distinct configured member, one vote per key.
                    for (String key : next.get()) counts.merge(key, 1, Integer::sum);
                } catch (ExecutionException exception) {
                    continue;
                }
                Set<String> agreed = counts.entrySet().stream()
                        .filter(entry -> entry.getValue() >= threshold)
                        .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
                if (!agreed.isEmpty()) return agreed;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            futures.forEach(future -> future.cancel(true));
            // Executor.close() waits for slow network tasks and would defeat threshold progress.
            executor.shutdownNow();
        }
        log.warn("[KFE Quorum] phase=keyset_agreement outcome=unavailable requiredVotes={}", threshold);
        throw unavailable();
    }

    /** Extracts unique, well-shaped 32-byte x-only key fields from one Vault deposit response. */
    private static Set<String> depositKeys(JsonNode deposit) {
        Set<String> keys = new HashSet<>();
        for (String field : List.of("output_pubkey", "xonly_pubkey")) {
            String key = deposit.path(field).asText("");
            if (key.matches("(?i)[0-9a-f]{64}")) keys.add(key.toLowerCase(Locale.ROOT));
        }
        if (keys.isEmpty()) throw unavailable();
        return Set.copyOf(keys);
    }

    /** Retrieves and parses one JSON object, logging a sanitized phase failure before mapping it to unavailable. */
    private JsonNode getJson(String url, String phase, int member) {
        long started = System.nanoTime();
        try {
            return requireObject(objectMapper.readTree(restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers()), String.class).getBody()));
        } catch (Exception exception) {
            logTransportFailure(phase, member, started, exception);
            throw unavailable();
        }
    }

    /** Posts a proof request through its dedicated timeout client and requires a JSON object response. */
    private JsonNode postJson(String url, Object body) {
        long started = System.nanoTime();
        try {
            return requireObject(objectMapper.readTree(proofRestTemplate.postForObject(
                    url, new HttpEntity<>(body, headers()), String.class)));
        } catch (Exception exception) {
            logTransportFailure("proof", -1, started, exception);
            throw unavailable();
        }
    }

    /** Records elapsed time, HTTP status, and exception class without logging remote bodies or credentials. */
    private static void logTransportFailure(String phase, int member, long started, Exception exception) {
        int status = exception instanceof HttpStatusCodeException http ? http.getStatusCode().value() : 0;
        log.warn("[KFE Quorum] phase={} member={} durationMs={} httpStatus={} exceptionClass={}",
                phase, member, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), status,
                exception.getClass().getSimpleName());
    }

    /** Requires an object-shaped JSON response and maps any other shape to the safe unavailable error. */
    private static JsonNode requireObject(JsonNode node) {
        if (node == null || !node.isObject()) throw unavailable();
        return node;
    }

    /** Rechecks proposal expiry against the injected clock immediately before and after proof retrieval. */
    private void requireLiveDeadline(Proposal proposal) {
        if (!proposal.expiresAt().isAfter(clock.instant())) {
            log.warn("[KFE Quorum] phase=deadline outcome=expired");
            throw unavailable();
        }
    }

    /** Creates the stable unavailable exception without attaching secret-bearing transport causes. */
    private static IllegalStateException unavailable() {
        // Never attach a transport cause: its message/body can contain secrets or internal URLs.
        return new IllegalStateException(UNAVAILABLE);
    }

    /** Requires a positive millisecond budget representable by the HTTP request factory. */
    private static void validateBudget(long value) {
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Financial quorum timeout budgets must be positive bounded milliseconds");
        }
    }

    /** Enforces positive constitution dimensions and a configured URL roster within threshold/member bounds. */
    private void validateRoster() {
        if (memberCount < 1 || threshold < 1 || threshold > memberCount
                || vaultUrls.size() < threshold || vaultUrls.size() > memberCount) {
            throw new IllegalStateException("Vault financial quorum roster/threshold is invalid");
        }
    }

    /** Builds JSON headers and adds the optional legacy Vault token when configured. */
    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiToken.isEmpty()) {
            headers.set("X-Vault-Token", apiToken);
        }
        return headers;
    }

    /** Computes the domain-separated SHA-256 digest of proposal and constitution commitments. */
    private static byte[] canonicalDigest(Proposal proposal) {
        String value = DOMAIN + "|" + proposal.proposalHash() + "|" + proposal.constitutionHash()
                + "|" + proposal.constitutionEpoch() + "|" + proposal.submittedAt().toEpochMilli()
                + "|" + proposal.expiresAt().toEpochMilli();
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Parses an array of unique, nonblank textual member IDs into an immutable set. */
    private static Set<String> textSet(JsonNode node) {
        if (!node.isArray()) throw unavailable();
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || !values.add(value.asText())) throw unavailable();
        }
        return Set.copyOf(values);
    }

    /** Requires an exact textual response field match and identifies mismatched field names in diagnostics. */
    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) {
            throw new IllegalStateException("Financial quorum response mismatch: " + field);
        }
    }

    /** Requires an integral, long-convertible response field equal to the configured expectation. */
    private static void requireLong(JsonNode node, String field, long expected) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong()
                || node.path(field).longValue() != expected) {
            throw new IllegalStateException("Financial quorum response mismatch: " + field);
        }
    }

    /** Parses hexadecimal proof/key bytes and rejects malformed protocol values. */
    private static byte[] decodeHex(String value, String field) {
        try {
            return HEX.parseHex(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid hex in financial quorum " + field, exception);
        }
    }

    /** Normalizes a 32-byte x-only or 33-byte compressed secp256k1 public key to lowercase X hex. */
    private static String xOnly(byte[] key) {
        if (key.length != 32 && (key.length != 33 || (key[0] != 2 && key[0] != 3))) throw unavailable();
        byte[] normalized = key.length == 33 ? Arrays.copyOfRange(key, 1, 33) : key;
        return HEX.formatHex(normalized);
    }

    /** Parses, normalizes, and deduplicates the configured member URLs, using the coordinator when empty. */
    private static List<String> parseUrls(String csv, String fallback) {
        List<String> values = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(VaultMeshFinancialQuorumAdapter::trimUrl)
                .distinct().toList();
        return values.isEmpty() ? List.of(fallback) : values;
    }

    /** Trims a required URL and removes trailing slashes for deterministic endpoint construction. */
    private static String trimUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Vault financial quorum URL is required");
        }
        return result;
    }
}
