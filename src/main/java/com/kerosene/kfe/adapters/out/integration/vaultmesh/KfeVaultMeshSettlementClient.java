package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import com.kerosene.kfe.adapters.out.integration.directory.KfeKeroseneNodeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.governance.VaultMeshReshareResult;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.net.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production HTTP adapter from {@code kfe-service} to the Vault mesh.
 * Signing is available only through Intent-bound PSBT requests.
 *
 * <p>Optional client mTLS via {@code kfe.vaultmesh.tls.*} (PEM or keystore/truststore).
 * Every request uses the configured mTLS operator or workload identity.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class KfeVaultMeshSettlementClient implements VaultMeshSettlementPort {

    /** Logger for retry and terminal failure information from noncritical Vault mesh reads. */
    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshSettlementClient.class);

    /** Recognizes an explicit day-epoch stale response and captures the reported and required UTC dates. */
    private static final Pattern DAY_STALE =
            Pattern.compile("day_epoch stale:\\s*have\\s+(\\d{4}-\\d{2}-\\d{2}),\\s*need\\s+(\\d{4}-\\d{2}-\\d{2})",
                    Pattern.CASE_INSENSITIVE);

    /** HTTP client configured with the selected proxy, TLS identity, and finite timeouts. */
    private final RestTemplate restTemplate;
    /** JSON codec for mesh phase, deposit, day, and PSBT payloads. */
    private final ObjectMapper objectMapper;
    /** Primary mesh coordinator URL with trailing slashes removed. */
    private final String baseUrl;
    /** Fixed, normalized Vault member URLs used for availability and agreement queries. */
    private final List<String> vaultUrls;
    /** Whether mutual TLS is active for requests made by this client. */
    private final boolean tlsEnabled;
    /** Expected constitution hash required to accept signed PSBT receipts. */
    private final String constitutionHash;
    /** Maximum number of member IDs accepted in a constitution-bound PSBT receipt. */
    private final int constitutionMemberCount;
    /** Minimum participant count and configured quorum threshold for accepted PSBT receipts. */
    private final int constitutionThreshold;
    /** Minimum matching member responses required for multi-vault read agreement. */
    private final int minimumAgreement;
    /** Optional verified node directory that authorizes Vault URLs before requests are sent. */
    private KfeKeroseneNodeDirectory nodeDirectory;

    /**
     * Creates the production mesh transport, validates TLS and Tor/direct transport settings, and
     * fixes the Vault roster and constitution thresholds for the lifetime of this adapter. Legacy
     * API-token authentication is rejected; Tor transport cannot run without mTLS. Thresholds are
     * clamped to positive values and bounded by the configured member count.
     *
     * @param restTemplateBuilder base Spring HTTP client builder
     * @param objectMapper serializer/parser for Vault mesh protocol messages
     * @param baseUrl required HTTPS coordinator URL
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     * @param apiToken removed bearer-token setting; any nonblank value causes startup failure
     * @param tlsEnabled whether client mTLS is requested
     * @param tlsCertPath PEM client certificate path
     * @param tlsKeyPath PEM client private key path
     * @param tlsCaPath PEM CA certificate path
     * @param tlsKeystorePath optional client keystore path
     * @param tlsKeystorePassword client keystore password
     * @param tlsKeystoreType client keystore format
     * @param tlsTruststorePath optional truststore path
     * @param tlsTruststorePassword truststore password
     * @param tlsTruststoreType truststore format
     * @param tlsHostnameVerification whether TLS hostname identity is checked
     * @param constitutionHash expected constitution digest used for receipt validation
     * @param constitutionMemberCount expected maximum constitution member count
     * @param constitutionThreshold minimum constitution signing threshold
     * @param vaultUrlsCsv optional comma-separated Vault member URLs
     * @param transport selected mesh network transport
     * @param socksHost SOCKS proxy host when Tor routing is selected
     * @param socksPort SOCKS proxy port
     * @throws IllegalStateException for removed token usage or insecure Tor without mTLS
     * @throws IllegalArgumentException for malformed or non-HTTPS Vault URLs
     */
    @Autowired
    public KfeVaultMeshSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kfe.vaultmesh.base-url}") String baseUrl,
            @Value("${kfe.vaultmesh.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${kfe.vaultmesh.read-timeout-ms:20000}") long readTimeoutMs,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.tls.enabled:true}") boolean tlsEnabled,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.keystore-password:}") String tlsKeystorePassword,
            @Value("${kfe.vaultmesh.tls.keystore-type:PKCS12}") String tlsKeystoreType,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath,
            @Value("${kfe.vaultmesh.tls.truststore-password:}") String tlsTruststorePassword,
            @Value("${kfe.vaultmesh.tls.truststore-type:PKCS12}") String tlsTruststoreType,
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean tlsHostnameVerification,
            @Value("${kfe.vaultmesh.constitution.hash:}") String constitutionHash,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int constitutionMemberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int constitutionThreshold,
            @Value("${kfe.vaultmesh.urls:}") String vaultUrlsCsv,
            @Value("${kfe.vaultmesh.transport:tor}") String transport,
            @Value("${kfe.vaultmesh.proxy.socks-host:}") String socksHost,
            @Value("${kfe.vaultmesh.proxy.socks-port:9050}") int socksPort) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.vaultUrls = parseVaultUrls(vaultUrlsCsv, this.baseUrl);
        if (apiToken != null && !apiToken.isBlank()) {
            throw new IllegalStateException("kfe.vaultmesh.api-token was removed; use mTLS identity");
        }
        this.tlsEnabled = KfeVaultMeshTlsSupport.tlsConfigured(
                tlsEnabled, tlsCertPath, tlsKeyPath, tlsCaPath, tlsKeystorePath, tlsTruststorePath);
        this.constitutionHash = blankToEmpty(constitutionHash);
        this.constitutionMemberCount = Math.max(1, constitutionMemberCount);
        this.constitutionThreshold = Math.max(1, Math.min(constitutionThreshold, constitutionMemberCount));
        this.minimumAgreement = this.constitutionThreshold;
        Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                transport, socksHost, socksPort, this.tlsEnabled, this.vaultUrls);

        RestTemplateBuilder builder = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs));
        if (this.tlsEnabled) {
            SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                    tlsCertPath,
                    tlsKeyPath,
                    tlsCaPath,
                    tlsKeystorePath,
                    tlsKeystorePassword,
                    tlsKeystoreType,
                    tlsTruststorePath,
                    tlsTruststorePassword,
                    tlsTruststoreType);
            ClientHttpRequestFactory factory = KfeVaultMeshTlsSupport.requestFactory(
                    sslContext,
                    tlsHostnameVerification,
                    (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE),
                    (int) Math.min(readTimeoutMs, Integer.MAX_VALUE),
                    proxy);
            this.restTemplate = builder.requestFactory(() -> factory).build();
        } else if (proxy != null) {
            throw new IllegalStateException("Tor vault-mesh transport requires mTLS");
        } else {
            this.restTemplate = builder.build();
        }
    }

    /**
     * Creates a direct-transport, no-mTLS instance for isolated HTTP contract tests.
     * Production wiring uses the full constructor so TLS and transport policy cannot be skipped.
     *
     * @param restTemplateBuilder builder for the test HTTP client
     * @param objectMapper serializer/parser used in assertions
     * @param baseUrl mock server URL
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response timeout in milliseconds
     * @param apiToken legacy token argument retained for constructor compatibility
     */
    KfeVaultMeshSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            long connectTimeoutMs,
            long readTimeoutMs,
            String apiToken) {
        this(
                restTemplateBuilder,
                objectMapper,
                baseUrl,
                connectTimeoutMs,
                readTimeoutMs,
                apiToken,
                false,
                "",
                "",
                "",
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12",
                true,
                "",
                3,
                2,
                baseUrl,
                "direct",
                "",
                9050);
    }

    /** @return whether production TLS credentials were configured and activated */
    boolean tlsEnabled() {
        return tlsEnabled;
    }

    /** Attaches the optional verified-node directory used to authorize configured Vault member URLs. */
    @Autowired(required = false)
    void setNodeDirectory(KfeKeroseneNodeDirectory nodeDirectory) {
        this.nodeDirectory = nodeDirectory;
    }

    /**
     * Rejects generic settlement intents because signing must use the separate intent-bound PSBT contract.
     * This prevents callers from requesting an unscoped or legacy mesh signing operation.
     *
     * @param intent legacy intent, used only to retain its ID in the rejection receipt when available
     * @return rejected receipt identifying the intent-bound PSBT requirement
     */
    @Override
    public VaultMeshReceipt submitIntent(VaultMeshIntent intent) {
        String intentId = intent == null ? null : intent.intentId();
        return rejected("INTENT_BOUND_PSBT_REQUIRED", intentId);
    }

    /** Reserves funds for a valid mesh intent through the coordinator's reserve phase endpoint.
     * @param intent intent carrying the stable ID, bucket, destination, amount, and policy context
     * @return accepted, rejected, or fail-stop receipt describing the remote phase result
     */
    @Override
    public VaultMeshReceipt reserveIntent(VaultMeshIntent intent) {
        if (intent == null || intent.intentId() == null || intent.intentId().isBlank()) {
            return rejected("INVALID_INTENT", null);
        }
        return postIntentPhase("/v1/intent/reserve", intentPayload(intent), intent.intentId());
    }

    /** Releases the requested amount from an existing intent and normalizes an absent bucket to USERS.
     * @param intentId stable intent ID to release
     * @param bucket account bucket; blank values use USERS
     * @param amountSats amount to release in satoshis
     * @return receipt for the coordinator's release result, or a local rejection for invalid input
     */
    @Override
    public VaultMeshReceipt releaseIntent(String intentId, String bucket, long amountSats) {
        if (intentId == null || intentId.isBlank()) {
            return rejected("INVALID_INTENT", null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent_id", intentId.trim());
        payload.put(
                "bucket",
                bucket == null || bucket.isBlank() ? "USERS" : bucket.trim().toUpperCase(Locale.ROOT));
        payload.put("amount_sats", amountSats);
        return postIntentPhase("/v1/intent/release", payload, intentId.trim());
    }

    /** Commits a previously reserved intent after validating that its ID is present.
     * @param intentId stable ID of the reservation to commit
     * @return receipt representing the coordinator's commit response
     */
    @Override
    public VaultMeshReceipt commitIntent(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return rejected("INVALID_INTENT", null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent_id", intentId.trim());
        return postIntentPhase("/v1/intent/commit", payload, intentId.trim());
    }

    /** Serializes intent identity and settlement data using the Vault API's snake-case field names. */
    private Map<String, Object> intentPayload(VaultMeshIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent_id", intent.intentId().trim());
        payload.put(
                "bucket",
                intent.bucket() == null || intent.bucket().isBlank()
                        ? "USERS"
                        : intent.bucket().trim().toUpperCase(Locale.ROOT));
        payload.put("destination", intent.destination() == null ? "" : intent.destination());
        payload.put("amount_sats", intent.amountSats());
        return payload;
    }

    /** Posts one intent lifecycle phase without retrying it and translates HTTP errors to mesh receipts. */
    private VaultMeshReceipt postIntentPhase(String pathSuffix, Map<String, Object> payload, String intentId) {
        String coordinator = coordinatorForPost();
        if (coordinator == null) {
            return rejected("MESH_COORDINATOR_UNAVAILABLE", intentId);
        }
        String path = coordinator + pathSuffix;
        try {
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            return toIntentPhaseReceipt(intentId, response.getBody());
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return meshError(intentId, String.valueOf(body.get("error")));
            }
            return rejected("MESH_HTTP_" + ex.getStatusCode().value(), intentId);
        } catch (Exception ex) {
            return rejected("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName(), intentId);
        }
    }

    /** Maps successful phase status spellings and mesh error bodies to the shared receipt contract. */
    private VaultMeshReceipt toIntentPhaseReceipt(String intentId, Map<?, ?> body) {
        if (body == null) {
            return rejected("EMPTY_RESPONSE", intentId);
        }
        if (body.get("error") != null) {
            return meshError(intentId, String.valueOf(body.get("error")));
        }
        String status = body.get("status") == null ? "" : String.valueOf(body.get("status"));
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("reserved")
                || lower.contains("accepted")
                || lower.contains("released")
                || lower.contains("committed")
                || lower.isBlank()) {
            String id = body.get("intent_id") == null ? intentId : String.valueOf(body.get("intent_id"));
            return new VaultMeshReceipt(
                    id,
                    VaultMeshReceipt.Status.ACCEPTED,
                    status.isBlank() ? null : status.toUpperCase(Locale.ROOT),
                    null,
                    Instant.now());
        }
        return rejected("UNEXPECTED_INTENT_STATUS:" + status, intentId);
    }

    /** @return verified deposit details for the shared USERS bucket, or null when quorum is unavailable */
    @Override
    public VaultMeshDepositInfo getUsersDepositAddress() {
        return fetchDeposit("USERS");
    }

    /** @return verified deposit details for the dedicated CHANNELS bucket, or null when quorum is unavailable */
    @Override
    public VaultMeshDepositInfo getChannelsDepositAddress() {
        return fetchDeposit("CHANNELS");
    }

    /** Selects single-node or multi-vault deposit lookup based on the configured roster size. */
    private VaultMeshDepositInfo fetchDeposit(String bucket) {
        if (vaultUrls.size() > 1) {
            return fetchDepositMultiVault(bucket);
        }
        return fetchDepositFromUrl(baseUrl, bucket);
    }

    /** Queries one member at most twice and accepts only a response with a valid Bech32m Taproot address. */
    private VaultMeshDepositInfo fetchDepositFromUrl(String url, String bucket) {
        String path = url + "/v1/bitcoin/deposit?bucket=" + bucket;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                @SuppressWarnings("rawtypes")
                ResponseEntity<Map> response =
                        restTemplate.exchange(
                                path, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), Map.class);
                Map<?, ?> body = response.getBody();
                if (body == null) {
                    return null;
                }
                return parseDepositInfo(body);
            } catch (Exception ex) {
                if (attempt == 1) {
                    log.debug("Vault mesh deposit query attempt 1 failed for url={} bucket={}, retrying: {}", url, bucket, rootCauseSummary(ex));
                    continue;
                }
                log.warn("Vault mesh deposit query failed for url={} bucket={}: {}", url, bucket, rootCauseSummary(ex));
                return null;
            }
        }
        return null;
    }

    /**
     * Queries every configured vault concurrently and returns an address only when enough members
     * respond and every responding member reports the same address. Failed members are tolerated
     * only up to the configured agreement threshold.
     *
     * @param bucket logical deposit bucket queried from each vault
     * @return consistent deposit info, or null when interrupted, inconsistent, or below quorum
     */
    private VaultMeshDepositInfo fetchDepositMultiVault(String bucket) {
        List<VaultMeshDepositInfo> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VaultMeshDepositInfo>> probes = new ArrayList<>(vaultUrls.size());
            for (String url : vaultUrls) {
                probes.add(executor.submit(() -> fetchDepositFromUrl(url, bucket)));
            }
            for (Future<VaultMeshDepositInfo> probe : probes) {
                try {
                    VaultMeshDepositInfo info = probe.get();
                    if (info != null) {
                        results.add(info);
                    }
                } catch (ExecutionException ignored) {
                    // A failed member is tolerated as long as the configured agreement is reached.
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (results.size() < minimumAgreement) {
            return null;
        }
        // Require consistent address across all responding vaults
        String expectedAddress = results.get(0).address();
        for (int i = 1; i < results.size(); i++) {
            if (!expectedAddress.equals(results.get(i).address())) {
                return null;
            }
        }
        return results.get(0);
    }

    /** Validates required address presence and Bech32m checksum before projecting optional descriptor metadata. */
    private VaultMeshDepositInfo parseDepositInfo(Map<?, ?> body) {
        String address = body.get("address") == null ? null : String.valueOf(body.get("address"));
        if (address == null || address.isBlank()) {
            return null;
        }
        String lower = address.trim().toLowerCase(Locale.ROOT);
        if (!isValidBech32mAddress(lower)) {
            return null;
        }
        return new VaultMeshDepositInfo(
                address.trim(),
                body.get("descriptor") == null ? null : String.valueOf(body.get("descriptor")),
                body.get("scheme") == null ? null : String.valueOf(body.get("scheme")),
                body.get("output_pubkey") == null ? null : String.valueOf(body.get("output_pubkey")),
                body.get("xonly_pubkey") == null ? null : String.valueOf(body.get("xonly_pubkey")),
                body.get("network") == null ? null : String.valueOf(body.get("network")));
    }

    /**
     * Requests intent-bound PSBT signing for USERS or CHANNELS, then verifies constitution hash,
     * threshold, unique participant count, session binding, and receipt proof before accepting the
     * returned signed PSBT. Other buckets and any malformed or unverified response fail closed.
     *
     * @param request intent/session identity, bucket, destination, amount, and funded PSBT
     * @return accepted signed-PSBT receipt, fail-stop receipt, or explicit rejection
     */
    @Override
    public VaultMeshPsbtReceipt signPsbt(VaultMeshPsbtRequest request) {
        if (request == null || request.intentId() == null || request.intentId().isBlank()) {
            return psbtRejected("INVALID_INTENT", null);
        }
        if (request.psbtBase64() == null || request.psbtBase64().isBlank()) {
            return psbtRejected("EMPTY_PSBT", request.intentId());
        }
        // USERS → shared Taproot; CHANNELS → dedicated CHANNELS Taproot (≠ USERS omnibus).
        // Other buckets stay fail-closed on shared key escape.
        String bucket = request.bucket() == null || request.bucket().isBlank()
                ? "USERS"
                : request.bucket().trim().toUpperCase(Locale.ROOT);
        if (!"USERS".equals(bucket) && !"CHANNELS".equals(bucket)) {
            return psbtRejected("MESH_BUCKET_NOT_SHARED_TAPROOT:" + bucket, request.intentId());
        }
        String coordinator = coordinatorForPost();
        if (coordinator == null) {
            return psbtRejected("MESH_COORDINATOR_UNAVAILABLE", request.intentId());
        }
        String path = coordinator + "/v1/bitcoin/sign-psbt";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session_id", firstNonBlank(request.sessionId(), request.intentId()));
            payload.put("psbt", request.psbtBase64());
            payload.put("intent_id", request.intentId());
            payload.put("bucket", bucket);
            payload.put("destination", request.destination() == null ? "" : request.destination());
            payload.put("amount_sats", request.amountSats());
            payload.put("commit_intent", request.shouldCommitIntent());
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            return toPsbtReceipt(
                    request.intentId(), firstNonBlank(request.sessionId(), request.intentId()), response.getBody());
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return psbtMeshError(request.intentId(), String.valueOf(body.get("error")));
            }
            return psbtRejected("MESH_HTTP_" + ex.getStatusCode().value(), request.intentId());
        } catch (Exception ex) {
            return psbtRejected("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName(), request.intentId());
        }
    }

    /**
     * Returns the UTC day epoch agreed by the configured Vault members, requiring the configured
     * minimum agreement when multiple members are present. An interrupted or insufficient quorum
     * produces a failed status rather than selecting one member's value.
     *
     * @return agreed current day status or a failure status when the roster cannot establish quorum
     */
    @Override
    public VaultMeshDayStatus getDayStatus() {
        String utcToday = LocalDate.now(ZoneOffset.UTC).toString();
        if (vaultUrls.size() == 1) {
            return getDayStatusFromUrl(baseUrl, utcToday);
        }
        Map<String, Integer> agreement = new LinkedHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VaultMeshDayStatus>> probes = new ArrayList<>(vaultUrls.size());
            for (String url : vaultUrls) {
                probes.add(executor.submit(() -> getDayStatusFromUrl(url, utcToday)));
            }
            for (Future<VaultMeshDayStatus> probe : probes) {
                try {
                    VaultMeshDayStatus status = probe.get();
                    if (status != null && status.error() == null && status.dayEpoch() != null) {
                        agreement.merge(status.dayEpoch(), 1, Integer::sum);
                    }
                } catch (ExecutionException ignored) {
                    // A failed member is tolerated if another fixed 2-of-3 quorum agrees.
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return VaultMeshDayStatus.failed("MESH_DAY_QUERY_INTERRUPTED");
        }
        return agreement.entrySet().stream()
                .filter(entry -> entry.getValue() >= minimumAgreement)
                .max(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().compareTo(utcToday) >= 0
                        ? VaultMeshDayStatus.upToDate(entry.getKey())
                        : VaultMeshDayStatus.stale(entry.getKey(), utcToday))
                .orElseGet(() -> VaultMeshDayStatus.failed("MESH_DAY_QUORUM_UNAVAILABLE"));
    }

    /** Reads one member's UTC day epoch with one retry and translates stale-epoch error text into status. */
    private VaultMeshDayStatus getDayStatusFromUrl(String url, String utcToday) {
        String path = url + "/v1/day/current";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                @SuppressWarnings("rawtypes")
                ResponseEntity<Map> response = restTemplate.exchange(
                        path, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), Map.class);
                Map<?, ?> body = response.getBody();
                if (body == null || body.get("day_epoch") == null) {
                    return VaultMeshDayStatus.failed("EMPTY_DAY_RESPONSE");
                }
                String day = String.valueOf(body.get("day_epoch")).trim();
                if (day.compareTo(utcToday) >= 0) {
                    return VaultMeshDayStatus.upToDate(day);
                }
                return VaultMeshDayStatus.stale(day, utcToday);
            } catch (RestClientResponseException ex) {
                Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
                String err = body != null && body.get("error") != null
                        ? String.valueOf(body.get("error"))
                        : "MESH_HTTP_" + ex.getStatusCode().value();
                Matcher stale = DAY_STALE.matcher(err);
                if (stale.find()) {
                    return VaultMeshDayStatus.stale(stale.group(1), stale.group(2));
                }
                return VaultMeshDayStatus.failed(err);
            } catch (Exception ex) {
                if (attempt == 1) {
                    log.debug("Vault mesh day query attempt 1 failed via configured transport, retrying: {}", rootCauseSummary(ex));
                    continue;
                }
                log.warn("Vault mesh day query failed via configured transport: {}", rootCauseSummary(ex));
                return VaultMeshDayStatus.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
            }
        }
        return VaultMeshDayStatus.failed("MESH_HTTP_ERROR");
    }

    /** Produces one-line diagnostics from the deepest cause without retaining multiline log injection text. */
    private static String rootCauseSummary(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message.replaceAll("[\\r\\n]+", " "));
    }

    /**
     * Submits a member vote for the requested day through the live coordinator. The supplied voter
     * label is optional; the Vault derives identity from authenticated mTLS rather than trusting it.
     *
     * @param voter optional display/reference label included in the request body
     * @param dayEpoch UTC day epoch being voted on
     * @return vote success or a failed result describing coordinator/HTTP rejection
     */
    @Override
    public VaultMeshDayAdvanceResult voteDay(String voter, String dayEpoch) {
        if (dayEpoch == null || dayEpoch.isBlank()) {
            return VaultMeshDayAdvanceResult.failed("INVALID_VOTE");
        }
        String coordinator = coordinatorForPost();
        if (coordinator == null) {
            return VaultMeshDayAdvanceResult.failed("MESH_COORDINATOR_UNAVAILABLE");
        }
        String path = coordinator + "/v1/day/vote";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            // Voter is derived server-side from authenticated vault identity.
            // Do not spoof peer vault ids under shared lab token / mTLS binding.
            if (voter != null && !voter.isBlank()) {
                payload.put("voter", voter.trim());
            }
            payload.put("day_epoch", dayEpoch.trim());
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body != null && body.get("error") != null) {
                return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
            }
            return VaultMeshDayAdvanceResult.ok(dayEpoch.trim(), false);
        } catch (RestClientResponseException ex) {
            return dayHttpFailure(ex);
        } catch (Exception ex) {
            return VaultMeshDayAdvanceResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    /** Requests quorum-controlled advancement on every configured member without replaying a POST.
     * Each coordinator persists its own calendar; advancing only one member cannot establish the
     * calendar agreement required by {@link #getDayStatus()}. Multi-member success is confirmed by
     * reading that agreement after the requests, including when a POST response was lost.
     * @return agreed current day and whether a member acknowledged advancement, or a failure result
     */
    @Override
    public VaultMeshDayAdvanceResult advanceDay() {
        if (vaultUrls.size() == 1) {
            return advanceDayAt(baseUrl);
        }
        boolean advanced = false;
        for (String member : vaultUrls) {
            VaultMeshDayAdvanceResult result = advanceDayAt(member);
            if (result.ok()) {
                advanced |= result.advanced();
            } else {
                log.warn("Vault mesh member day advance failed: {}", result.error());
            }
        }
        VaultMeshDayStatus agreement = getDayStatus();
        if (!agreement.upToDate()) {
            return VaultMeshDayAdvanceResult.failed(agreement.error() != null
                    ? agreement.error() : "MESH_DAY_ADVANCE_QUORUM_NOT_CURRENT");
        }
        return VaultMeshDayAdvanceResult.ok(agreement.dayEpoch(), advanced);
    }

    /** Sends exactly one advancement request to one fixed constitution member. */
    private VaultMeshDayAdvanceResult advanceDayAt(String member) {
        String path = member + "/v1/day/advance";
        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(authHeaders(false)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return VaultMeshDayAdvanceResult.failed("EMPTY_ADVANCE_RESPONSE");
            }
            if (body.get("error") != null) {
                return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
            }
            String day = body.get("day_epoch") == null ? null : String.valueOf(body.get("day_epoch"));
            boolean advanced = body.get("advanced") == null || Boolean.parseBoolean(String.valueOf(body.get("advanced")));
            return VaultMeshDayAdvanceResult.ok(day, advanced);
        } catch (RestClientResponseException ex) {
            return dayHttpFailure(ex);
        } catch (Exception ex) {
            return VaultMeshDayAdvanceResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    /** Triggers threshold reshare with a default rotation reason when the supplied reason is blank.
     * @param reason operator or workflow explanation for the reshare
     * @return reshare policy and effective reason, or a failed result
     */
    @Override
    public VaultMeshReshareResult triggerReshare(String reason) {
        String effectiveReason = reason == null || reason.isBlank() ? "kfe-day-rotation" : reason.trim();
        String coordinator = coordinatorForPost();
        if (coordinator == null) {
            return VaultMeshReshareResult.failed("MESH_COORDINATOR_UNAVAILABLE");
        }
        String path = coordinator + "/v1/reshare/trigger";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", effectiveReason);
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return VaultMeshReshareResult.failed("EMPTY_RESHARE_RESPONSE");
            }
            if (body.get("error") != null) {
                return VaultMeshReshareResult.failed(String.valueOf(body.get("error")));
            }
            String policy = body.get("policy") == null ? null : String.valueOf(body.get("policy"));
            String respReason = body.get("reason") == null ? effectiveReason : String.valueOf(body.get("reason"));
            return VaultMeshReshareResult.ok(policy, respReason);
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return VaultMeshReshareResult.failed(String.valueOf(body.get("error")));
            }
            return VaultMeshReshareResult.failed("MESH_HTTP_" + ex.getStatusCode().value());
        } catch (Exception ex) {
            return VaultMeshReshareResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    /** Authorizes the configured roster before constructing request headers and optionally sets JSON content type. */
    private HttpHeaders authHeaders(boolean json) {
        if (nodeDirectory != null) {
            nodeDirectory.requireAuthorized(vaultUrls);
        }
        HttpHeaders headers = new HttpHeaders();
        if (json) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    /**
     * Selects a live coordinator before a non-idempotent request. The POST itself is never retried:
     * an ambiguous response must fail closed instead of risking a financial replay.
     */
    /** Selects the first member answering the liveness probe; non-idempotent POSTs are never retried. */
    private String coordinatorForPost() {
        if (vaultUrls.size() == 1) {
            return baseUrl;
        }
        for (String url : vaultUrls) {
            try {
                restTemplate.exchange(
                        url + "/v1/live",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(false)),
                        String.class);
                return url;
            } catch (Exception ignored) {
                // Continue to the next fixed constitution member.
            }
        }
        return null;
    }

    /** Extracts a mesh error body or returns the HTTP status as a day-operation failure code. */
    private VaultMeshDayAdvanceResult dayHttpFailure(RestClientResponseException ex) {
        Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
        if (body != null && body.get("error") != null) {
            return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
        }
        return VaultMeshDayAdvanceResult.failed("MESH_HTTP_" + ex.getStatusCode().value());
    }

    /**
     * Validates that a signing response contains constitution-bound evidence for the expected intent
     * session and that its participant list is unique and within the configured threshold bounds.
     *
     * @param intentId local intent identity that must be retained in the receipt
     * @param expectedSessionId session identity sent with the request
     * @param body decoded response body returned by the signing coordinator
     * @return accepted receipt only after every constitution and session check succeeds
     */
    private VaultMeshPsbtReceipt toPsbtReceipt(String intentId, String expectedSessionId, Map<?, ?> body) {
        if (body == null) {
            return psbtRejected("EMPTY_RESPONSE", intentId);
        }
        if (body.get("error") != null) {
            return psbtMeshError(intentId, String.valueOf(body.get("error")));
        }
        Object signed = body.get("signed_psbt");
        if (signed == null || String.valueOf(signed).isBlank()) {
            return psbtRejected("MISSING_SIGNED_PSBT", intentId);
        }

        // Constitution-bound receipt verification
        String respConstitutionHash = body.get("constitution_hash") == null
                ? null : String.valueOf(body.get("constitution_hash"));
        String respSessionId = body.get("session_id") == null
                ? null : String.valueOf(body.get("session_id"));
        String respTranscriptHash = body.get("transcript_hash") == null
                ? null : String.valueOf(body.get("transcript_hash"));
        String respSignature = body.get("signature") == null
                ? null : String.valueOf(body.get("signature"));

        if (constitutionHash.isEmpty()) {
            return psbtRejected("CONSTITUTION_HASH_NOT_CONFIGURED", intentId);
        }
        if (respConstitutionHash == null || !constitutionHash.equals(respConstitutionHash.trim())) {
            return psbtRejected(
                    "CONSTITUTION_HASH_MISMATCH: expected="
                            + constitutionHash + " got=" + respConstitutionHash, intentId);
        }

        Object respThreshold = body.get("threshold");
        if (respThreshold == null || Integer.parseInt(String.valueOf(respThreshold)) != constitutionThreshold) {
            return psbtRejected("THRESHOLD_MISMATCH", intentId);
        }

        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) body.get("participant_ids");
        if (participants == null) {
            return psbtRejected("MISSING_PARTICIPANT_IDS", intentId);
        }
        long distinctCount = participants.stream().distinct().count();
        if (distinctCount != participants.size()) {
            return psbtRejected("DUPLICATE_PARTICIPANT_IDS", intentId);
        }
        if (participants.size() < constitutionThreshold || participants.size() > constitutionMemberCount) {
            return psbtRejected(
                    "PARTICIPANT_COUNT_MISMATCH: provided=" + participants.size()
                            + " allowed=" + constitutionThreshold + ".." + constitutionMemberCount, intentId);
        }

        if (respSessionId == null || !respSessionId.equals(expectedSessionId)) {
            return psbtRejected("SESSION_ID_MISMATCH: expected=" + expectedSessionId
                    + " got=" + respSessionId, intentId);
        }

        String proof = respSignature;
        if (proof == null) {
            proof = respTranscriptHash;
        }
        if (proof == null || proof.isBlank()) {
            return psbtRejected("MISSING_SIGNATURE_PROOF", intentId);
        }
        return new VaultMeshPsbtReceipt(
                intentId,
                VaultMeshReceipt.Status.ACCEPTED,
                null,
                String.valueOf(signed),
                proof,
                Instant.now());
    }

    /** Maps recognized threshold fail-stop messages to FAIL_STOP and all other mesh errors to rejection. */
    private static VaultMeshPsbtReceipt psbtMeshError(String intentId, String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("fail-stop") || lower.contains("fail_stop") || lower.contains("online <")) {
            return new VaultMeshPsbtReceipt(
                    intentId,
                    VaultMeshReceipt.Status.FAIL_STOP,
                    reason,
                    null,
                    null,
                    Instant.now());
        }
        return psbtRejected(reason, intentId);
    }

    /** Creates a timestamped rejected PSBT receipt with no signed payload or proof. */
    private static VaultMeshPsbtReceipt psbtRejected(String reason, String intentId) {
        return new VaultMeshPsbtReceipt(
                intentId,
                VaultMeshReceipt.Status.REJECTED,
                reason,
                null,
                null,
                Instant.now());
    }

    /** Uses a trimmed primary identifier when present, otherwise a trimmed fallback or empty string. */
    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    /** Parses a JSON object response body; blank, malformed, or non-object data is treated as absent. */
    private Map<?, ?> parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    raw,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Maps generic intent-phase fail-stop text to FAIL_STOP and other mesh error text to rejection. */
    private static VaultMeshReceipt meshError(String intentId, String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("fail-stop") || lower.contains("fail_stop") || lower.contains("online <")) {
            return new VaultMeshReceipt(
                    intentId,
                    VaultMeshReceipt.Status.FAIL_STOP,
                    reason,
                    null,
                    Instant.now());
        }
        return rejected(reason, intentId);
    }

    /**
     * Computes a stable SHA-256 digest over the canonical pipe-separated identity, bucket,
     * destination, amount, policy hash, and creation timestamp fields of a mesh intent.
     *
     * @param intent intent whose immutable identifying fields are included in the digest
     * @return lowercase hexadecimal SHA-256 digest
     * @throws IllegalStateException if SHA-256 is unavailable in the runtime
     */
    static String messageHash(VaultMeshIntent intent) {
        String material = String.join(
                "|",
                nullToEmpty(intent.intentId()),
                nullToEmpty(intent.bucket()),
                nullToEmpty(intent.destination()),
                Long.toString(intent.amountSats()),
                nullToEmpty(intent.policyHash()),
                Long.toString(intent.createdAt().toEpochMilli()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Creates a timestamped generic rejection receipt without a remote proof. */
    private static VaultMeshReceipt rejected(String reason, String intentId) {
        return new VaultMeshReceipt(
                intentId,
                VaultMeshReceipt.Status.REJECTED,
                reason,
                null,
                Instant.now());
    }

    /** Requires an HTTPS Vault URL and removes trailing slashes before protocol paths are appended. */
    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Vault mesh URL is required");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("Vault mesh URL must use https:// mTLS");
        }
        return trimmed;
    }

    /** Normalizes null to an empty string and canonicalizes non-null digest input with trim/lowercase. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Trims a configuration value and maps null to empty without otherwise changing its case. */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Parses and deduplicates configured HTTPS vault URLs, or returns the required primary URL as fallback. */
    private static List<String> parseVaultUrls(String csv, String fallback) {
        if (csv == null || csv.isBlank()) {
            return Collections.singletonList(fallback);
        }
        return Arrays.stream(csv.split(","))
                .map(s -> trimTrailingSlash(s.trim()))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** Full Bech32m validation including checksum, not just prefix match. */
    /**
     * Validates supported Bitcoin Taproot address HRPs, character/length shape, and Bech32m checksum.
     * This is structural validation only; network-to-wallet policy is enforced by the caller/configuration.
     *
     * @param address candidate address returned by a Vault member
     * @return true only when the candidate passes supported-prefix and checksum validation
     */
    private static boolean isValidBech32mAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String lower = address.trim().toLowerCase(Locale.ROOT);
        // Taproot addresses: bc1p (mainnet), tb1p (testnet), bcrt1p (regtest)
        if (!(lower.startsWith("bc1p") || lower.startsWith("tb1p") || lower.startsWith("bcrt1p"))) {
            return false;
        }
        // Bech32m character set + length check
        if (!lower.matches("^(bc|tb|bcrt)1p[a-z0-9]{58,}$")) {
            return false;
        }
        // Verify Bech32m checksum
        int sep = lower.lastIndexOf('1');
        if (sep < 2) {
            return false;
        }
        String hrp = lower.substring(0, sep);
        String data = lower.substring(sep + 1);
        if (!data.matches("[a-z0-9]+")) {
            return false;
        }
        byte[] values = new byte[data.length()];
        for (int i = 0; i < data.length(); i++) {
            values[i] = bech32CharValue(data.charAt(i));
        }
        return verifyBech32mChecksum(hrp, values);
    }

    /** Bech32 alphabet used to map printable data characters to their five-bit values. */
    private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    /** ASCII lookup table populated by the static initializer; invalid characters retain the -1 sentinel. */
    private static final byte[] BECH32_CHAR_VALUES = new byte[128];

    /** Initializes the constant-time-indexed ASCII-to-Bech32-value lookup table. */
    static {
        Arrays.fill(BECH32_CHAR_VALUES, (byte) -1);
        for (int i = 0; i < BECH32_CHARSET.length(); i++) {
            BECH32_CHAR_VALUES[BECH32_CHARSET.charAt(i)] = (byte) i;
        }
    }

    /** @return five-bit Bech32 value for an ASCII character, or -1 when it is outside the alphabet */
    private static byte bech32CharValue(char c) {
        if (c < 128) {
            return BECH32_CHAR_VALUES[c];
        }
        return -1;
    }

    /** Generator constants from the Bech32/Bech32m polymod checksum recurrence. */
    private static final int[] BECH32M_GEN = {
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    /** Checks the polymod result against the Bech32m constant after expanding the HRP and data values. */
    private static boolean verifyBech32mChecksum(String hrp, byte[] data) {
        int chk = 1;
        for (int i = 0; i < hrp.length(); i++) {
            int val = hrp.charAt(i) >> 5;
            chk = polymodStep(chk) ^ val;
        }
        chk = polymodStep(chk);
        for (int i = 0; i < hrp.length(); i++) {
            int val = hrp.charAt(i) & 0x1f;
            chk = polymodStep(chk) ^ val;
        }
        for (byte b : data) {
            chk = polymodStep(chk) ^ (b & 0xff);
        }
        return chk == 0x2bc830a3;
    }

    /** Applies one five-bit Bech32 polymod recurrence step using the configured generator constants. */
    private static int polymodStep(int chk) {
        int b = chk >> 25;
        return ((chk & 0x1ffffff) << 5)
                ^ (-((b >> 0) & 1) & BECH32M_GEN[0])
                ^ (-((b >> 1) & 1) & BECH32M_GEN[1])
                ^ (-((b >> 2) & 1) & BECH32M_GEN[2])
                ^ (-((b >> 3) & 1) & BECH32M_GEN[3])
                ^ (-((b >> 4) & 1) & BECH32M_GEN[4]);
    }
}
