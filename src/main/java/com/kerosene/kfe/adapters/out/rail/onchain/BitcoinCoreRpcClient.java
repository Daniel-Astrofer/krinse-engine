package com.kerosene.kfe.adapters.out.rail.onchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bitcoin Core JSON-RPC adapter for node and wallet operations used by KFE on-chain flows.
 *
 * <p>Wallet-scoped calls use the configured wallet endpoint; chain-wide calls use the node endpoint.
 * The adapter preserves negative confirmation values and distinguishes RPC failures from a genuine
 * not-found result so callers do not accidentally release financial reserves.</p>
 */
@Primary
@Component("kfeBitcoinCoreRpcClient")
@ConditionalOnProperty(prefix = "bitcoin.rpc", name = "enabled", havingValue = "true")
public class BitcoinCoreRpcClient implements BlockchainClient {

    /** Logger for RPC failures and wallet or scan wait diagnostics. */
    private static final Logger log = LoggerFactory.getLogger(BitcoinCoreRpcClient.class);
    /** Exact number of satoshis in one bitcoin, used for unit conversion. */
    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");
    /** Maximum wait for a wallet another concurrent request is loading. */
    private static final long WALLET_LOAD_WAIT_MILLIS = 30_000L;
    /** Delay between checks while waiting for a concurrently loaded wallet. */
    private static final long WALLET_LOAD_POLL_MILLIS = 200L;

    /** HTTP transport configured for Bitcoin Core RPC. */
    private final RestTemplate restTemplate;
    /** JSON encoder, decoder and collection converter for RPC payloads. */
    private final ObjectMapper objectMapper;
    /** Validated node RPC base URL without trailing slashes or credentials. */
    private final String baseUrl;
    /** Bitcoin Core RPC username used for HTTP Basic authentication. */
    private final String username;
    /** Bitcoin Core RPC password used for HTTP Basic authentication. */
    private final String password;
    /** URL-encoded wallet path segment, or empty for node-only calls. */
    private final String walletName;
    private final long scanWaitTimeoutMs;

    /**
     * Creates the RPC client after validating the node URL and wallet path configuration.
     * @param restTemplate Bitcoin Core-specific HTTP transport
     * @param objectMapper JSON request and response mapper
     * @param baseUrl node RPC URL
     * @param username RPC Basic authentication username
     * @param password RPC Basic authentication password
     * @param walletName optional wallet name; blank selects node-scoped calls
     */
    public BitcoinCoreRpcClient(
            @Qualifier("bitcoindRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${bitcoin.rpc.url}") String baseUrl,
            @Value("${bitcoin.rpc.username}") String username,
            @Value("${bitcoin.rpc.password}") String password,
            @Value("${bitcoin.rpc.wallet:}") String walletName) {
        this(restTemplate, objectMapper, baseUrl, username, password, walletName, 90_000L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BitcoinCoreRpcClient(
            @Qualifier("bitcoindRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${bitcoin.rpc.url}") String baseUrl,
            @Value("${bitcoin.rpc.username}") String username,
            @Value("${bitcoin.rpc.password}") String password,
            @Value("${bitcoin.rpc.wallet:}") String walletName,
            @Value("${bitcoin.rpc.scan-wait-timeout-ms:90000}") long scanWaitTimeoutMs) {
        if (scanWaitTimeoutMs <= 0) throw new IllegalArgumentException("scan wait timeout must be positive");
        this.scanWaitTimeoutMs = scanWaitTimeoutMs;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;

        this.baseUrl = sanitizeBaseUrl(baseUrl);

        this.username = username;
        this.password = password;

        this.walletName = sanitizeWalletName(walletName);
    }

    /** Executes a JSON-RPC method against the wallet-scoped endpoint when configured.
     * @param method Bitcoin Core RPC method
     * @param params positional RPC parameters
     * @return full JSON-RPC response
     */
    @Override
    public JsonNode executeRpc(String method, Object... params) {
        return executeRpcAt(resolveEndpoint(), method, params);
    }

    /**
     * Executes a JSON-RPC method against the node endpoint, independent of the selected wallet.
     */
    public JsonNode executeNodeRpc(String method, Object... params) {
        return executeRpcAt(baseUrl, method, params);
    }

    /**
     * Encodes, sends and validates one JSON-RPC request against the selected endpoint.
     * @param endpoint node or wallet endpoint URL
     * @param method Core RPC method name
     * @param params positional JSON-RPC parameters
     * @return complete RPC response node
     * @throws IllegalStateException for transport, HTTP, JSON or Core RPC errors
     */
    private JsonNode executeRpcAt(String endpoint, String method, Object... params) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "1.0");
            request.put("id", UUID.randomUUID().toString());
            request.put("method", method);
            ArrayNode array = request.putArray("params");
            if (params != null) {
                for (Object param : params) {
                    array.add(objectMapper.valueToTree(param));
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Bitcoin Core RPC returned HTTP " + response.getStatusCode());
            }

            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode error = body.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IllegalStateException(
                        "Bitcoin Core RPC " + method + " failed: " + error.path("message").asText("unknown error"));
            }
            return body;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Bitcoin Core RPC request failed for method "
                            + method
                            + ": "
                            + ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Builds a JSON-RPC 1.0 request, sends it to the endpoint and rejects HTTP or RPC errors.
     */
    @Override
    public String sendRawTransaction(String hex) {
        JsonNode result = unwrapResult(executeRpc("sendrawtransaction", hex));
        return result != null && !result.isNull() ? result.asText() : null;
    }

    /**
     * Validates a raw transaction against mempool policy without broadcasting.
     * Returns the mempool acceptance result array from Bitcoin Core.
     *
     * @param rawHex fully-signed raw transaction hex
     * @return the "result" array from {@code testmempoolaccept}; each entry has
     *         {@code txid}, {@code allowed} (bool), and {@code reject-reason} on failure
     */
    public JsonNode testMempoolAccept(String rawHex) {
        if (rawHex == null || rawHex.isBlank()) {
            throw new IllegalArgumentException("rawHex is required for testmempoolaccept");
        }
        return unwrapResult(executeNodeRpc("testmempoolaccept", List.of(rawHex.trim())));
    }

    /** Requires Core's mempool policy to accept every transaction entry.
     * @param rawHex fully signed raw transaction hex
     * @return true when every result entry is allowed
     * @throws IllegalStateException when Core returns no entries or rejects an input transaction
     */
    public boolean requireMempoolAccept(String rawHex) {
        JsonNode result = testMempoolAccept(rawHex);
        if (result == null || !result.isArray() || result.isEmpty()) {
            throw new IllegalStateException(
                    "testmempoolaccept returned empty result for signed transaction.");
        }
        for (JsonNode entry : result) {
            if (!entry.path("allowed").asBoolean(false)) {
                String reason = entry.path("reject-reason").asText("unknown");
                throw new IllegalStateException(
                        "Transaction rejected by mempool policy before broadcast: " + reason);
            }
        }
        return true;
    }

    /**
     * Validates mempool acceptance and throws on rejection.
     * Returns true when all transactions are allowed.
     */
    @Override
    public JsonNode getRawTransaction(String txid, boolean verbose) {
        try {
            return unwrapResult(executeRpc("getrawtransaction", txid, verbose ? 1 : 0));
        } catch (RuntimeException rawTransactionFailure) {
            return walletTransaction(txid, rawTransactionFailure);
        }
    }

    /**
     * Returns the current best-chain height, defaulting to zero for a non-numeric result.
     */
    public long getBlockCount() {
        JsonNode result = unwrapResult(executeRpc("getblockcount"));
        return result != null && result.isNumber() ? result.asLong() : 0L;
    }

    /**
     * Returns the sanitized wallet name used for wallet-scoped RPC calls.
     */
    public String walletName() {
        return walletName;
    }

    /**
     * Returns the chain identifier reported by getblockchaininfo, such as main or test.
     */
    public String chain() {
        return text(blockchainInfo(), "chain");
    }

    /**
     * Returns node-wide blockchain synchronization and chain metadata.
     */
    public JsonNode blockchainInfo() {
        return unwrapResult(executeNodeRpc("getblockchaininfo"));
    }

    /**
     * Checks an address with Core and returns false for blank or invalid addresses.
     */
    public boolean isValidAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        JsonNode result = unwrapResult(executeNodeRpc("validateaddress", address.trim()));
        return result != null && result.path("isvalid").asBoolean(false);
    }

    /**
     * Estimates a conservative fee rate for a positive confirmation target and converts BTC/kvB to sat/vB.
     */
    public long estimateSmartFeeRateSatPerVbyte(int confirmationTarget) {
        if (confirmationTarget <= 0) {
            throw new IllegalArgumentException("confirmationTarget must be positive");
        }
        JsonNode result = unwrapResult(executeNodeRpc("estimatesmartfee", confirmationTarget, "CONSERVATIVE"));
        JsonNode feeRate = result != null ? result.path("feerate") : null;
        if (feeRate == null || feeRate.isMissingNode() || feeRate.isNull() || !feeRate.isNumber()) {
            throw new IllegalStateException("Bitcoin Core did not return a smart fee rate.");
        }
        long satPerVbyte = feeRate.decimalValue()
                .multiply(SATOSHIS_PER_BITCOIN)
                .divide(BigDecimal.valueOf(1000L), 0, RoundingMode.CEILING)
                .longValueExact();
        if (satPerVbyte <= 0L) {
            throw new IllegalStateException("Bitcoin Core returned a non-positive smart fee rate.");
        }
        return satPerVbyte;
    }

    /**
     * Loads an existing wallet or creates it if absent, tolerating concurrent load/create requests.
     */
    public void ensureWalletLoaded(String wallet) {
        String cleanWallet = sanitizeWalletName(wallet);
        if (cleanWallet.isBlank()) {
            return;
        }
        if (listWallets().contains(cleanWallet)) {
            return;
        }
        if (listWalletDir().contains(cleanWallet)) {
            try {
                unwrapResult(executeNodeRpc("loadwallet", cleanWallet));
                return;
            } catch (RuntimeException loadFailure) {
                if (listWallets().contains(cleanWallet)
                        || (walletTransitionInProgress(loadFailure)
                                && waitForWalletLoaded(cleanWallet))) {
                    return;
                }
                throw loadFailure;
            }
        }
        try {
            unwrapResult(executeNodeRpc("createwallet", cleanWallet, false, false, "", false, true));
        } catch (RuntimeException createFailure) {
            if (listWallets().contains(cleanWallet)
                    || (walletTransitionInProgress(createFailure)
                            && waitForWalletLoaded(cleanWallet))) {
                return;
            }
            throw createFailure;
        }
    }

    /**
     * Polls Core until a wallet appears or the bounded load wait expires; preserves thread interruption.
     */
    private boolean waitForWalletLoaded(String wallet) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                WALLET_LOAD_WAIT_MILLIS);
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(WALLET_LOAD_POLL_MILLIS);
                if (listWallets().contains(wallet)) {
                    log.info("Bitcoin Core wallet load completed after a concurrent request wallet={}", wallet);
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException transientPollFailure) {
                log.debug("Bitcoin Core wallet load poll failed wallet={}", wallet);
            }
        }
        return false;
    }

    /**
     * Recognizes Core errors that indicate another request is already loading or creating the wallet.
     */
    private static boolean walletTransitionInProgress(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("wallet already loading")
                        || normalized.contains("wallet already loaded")
                        || normalized.contains("database already exists")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns currently loaded wallet names, or an empty list for an unexpected response shape.
     */
    private List<String> listWallets() {
        JsonNode result = unwrapResult(executeNodeRpc("listwallets"));
        if (result == null || !result.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(
                result,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    /**
     * Returns wallet names known to Core storage, whether loaded or not.
     */
    private List<String> listWalletDir() {
        JsonNode result = unwrapResult(executeNodeRpc("listwalletdir"));
        JsonNode wallets = result != null ? result.path("wallets") : null;
        if (wallets == null || !wallets.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(
                wallets.findValues("name"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    /**
     * Requests a Bech32 receiving address and fails if Core returns no usable address.
     */
    public String getNewAddress(String label) {
        JsonNode result = unwrapResult(executeRpc("getnewaddress", label != null ? label : "", "bech32"));
        String address = result != null && !result.isNull() ? result.asText() : "";
        if (address.isBlank()) {
            throw new IllegalStateException("Bitcoin Core did not return a new receiving address.");
        }
        return address;
    }

    /**
     * Falls back to gettransaction and preserves the original raw-transaction error if both lookups fail.
     */
    private JsonNode walletTransaction(String txid, RuntimeException rawTransactionFailure) {
        try {
            return unwrapResult(executeRpc("gettransaction", txid, true, true));
        } catch (RuntimeException walletFailure) {
            rawTransactionFailure.addSuppressed(walletFailure);
            throw rawTransactionFailure;
        }
    }

    /**
     * Creates a funded wallet PSBT using a fee tier or confirmation target; this overload selects Bech32 change.
     */
    /**
     * Creates a funded wallet PSBT using a fee tier or confirmation target; this overload selects Bech32 change.
     */
    /**
     * Creates a funded wallet PSBT using Core's confirmation target fee estimate and default change type.
     * @param destinationAddress recipient address
     * @param amountSats amount to send in satoshis
     * @param confirmationTarget desired confirmation target, or null to use Core defaults
     * @return PSBT and fee calculated by Core
     */
    public FundedPsbt createFundedPsbt(String destinationAddress, long amountSats, Integer confirmationTarget) {
        return createFundedPsbt(destinationAddress, amountSats, confirmationTarget, null);
    }

    /**
     * Funds a custodial PSBT. Prefer explicit {@code feeRateSatsPerVbyte} (user-selected tier);
     * otherwise fall back to Bitcoin Core {@code conf_target}.
     */
    public FundedPsbt createFundedPsbt(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte) {
        return createFundedPsbt(
                destinationAddress, amountSats, confirmationTarget, feeRateSatsPerVbyte, "bech32");
    }

    /**
     * Funds a custodial PSBT. Prefer explicit {@code feeRateSatsPerVbyte} (user-selected tier);
     * otherwise fall back to Bitcoin Core {@code conf_target}.
     *
     * @param changeType Core {@code change_type} (e.g. {@code bech32} or {@code bech32m} for Taproot)
     */
    public FundedPsbt createFundedPsbt(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeType) {
        return createFundedPsbt(
                destinationAddress,
                amountSats,
                confirmationTarget,
                feeRateSatsPerVbyte,
                changeType,
                false);
    }

    /**
     * Funds a PSBT with caller-selected change type and optional UTXO locking.
     * @param destinationAddress recipient address
     * @param amountSats amount to send in satoshis
     * @param confirmationTarget confirmation target used when no explicit fee rate is supplied
     * @param feeRateSatsPerVbyte explicit fee rate in sat/vB, preferred when positive
     * @param changeType Core change output type; blank defaults to Bech32
     * @param lockUnspents whether selected inputs should remain locked after PSBT creation
     * @return encoded funded PSBT and fee calculated by Core
     */
    public FundedPsbt createFundedPsbt(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeType,
            boolean lockUnspents) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(destinationAddress, satsToBtc(amountSats));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("includeWatching", true);
        options.put(
                "change_type",
                changeType == null || changeType.isBlank() ? "bech32" : changeType.trim());
        options.put("lockUnspents", lockUnspents);
        boolean explicitFeeRate = feeRateSatsPerVbyte != null && feeRateSatsPerVbyte > 0L;
        if (explicitFeeRate) {
            // Bitcoin Core: fee_rate = sat/vB ; feeRate (legacy) = BTC/kvB.
            // Passing BTC/kvB into fee_rate yields RPC -3 "Invalid amount".
            options.put("fee_rate", Math.max(1L, feeRateSatsPerVbyte));
        } else if (confirmationTarget != null && confirmationTarget > 0) {
            options.put("conf_target", confirmationTarget);
        }

        JsonNode result = unwrapResult(executeRpc(
                "walletcreatefundedpsbt",
                List.of(),
                List.of(output),
                0,
                options,
                true));

        String psbt = text(result, "psbt");
        long feeSats = btcNodeToSats(result.path("fee"));
        return new FundedPsbt(psbt, feeSats);
    }

    /**
     * Decodes a PSBT and releases its referenced wallet inputs.
     */
    public boolean unlockPsbtInputs(String psbt) {
        JsonNode decoded = decodePsbt(psbt);
        return unlockDecodedInputs(decoded != null ? decoded.path("tx") : null);
    }

    /**
     * Decodes raw transaction hex and releases its referenced wallet inputs.
     */
    public boolean unlockRawTransactionInputs(String rawTransaction) {
        return unlockDecodedInputs(decodeRawTransaction(rawTransaction));
    }

    /**
     * Attempts to release abandoned PSBT inputs and logs failure without propagating it.
     */
    public void unlockPsbtInputsBestEffort(String psbt) {
        try {
            if (!unlockPsbtInputs(psbt)) {
                log.warn("Bitcoin Core did not confirm release of abandoned PSBT inputs.");
            }
        } catch (RuntimeException exception) {
            log.warn("Bitcoin Core could not release abandoned PSBT inputs: {}", exception.getMessage());
        }
    }

    /**
     * Attempts to release prepared transaction inputs and logs failure without propagating it.
     */
    public void unlockRawTransactionInputsBestEffort(String rawTransaction) {
        try {
            if (!unlockRawTransactionInputs(rawTransaction)) {
                log.warn("Bitcoin Core did not confirm release of prepared transaction inputs.");
            }
        } catch (RuntimeException exception) {
            log.warn("Bitcoin Core could not release prepared transaction inputs: {}", exception.getMessage());
        }
    }

    /**
     * Validates decoded input outpoints and asks Core to unlock all of them.
     */
    private boolean unlockDecodedInputs(JsonNode transaction) {
        JsonNode inputs = transaction != null ? transaction.path("vin") : null;
        if (inputs == null || !inputs.isArray() || inputs.isEmpty()) {
            throw new IllegalArgumentException("Transaction inputs are required for UTXO unlock.");
        }
        List<Map<String, Object>> outputs = new java.util.ArrayList<>();
        for (JsonNode input : inputs) {
            String txid = text(input, "txid");
            JsonNode vout = input.path("vout");
            if (txid == null || !txid.matches("(?i)[0-9a-f]{64}") || !vout.isIntegralNumber()) {
                throw new IllegalArgumentException("Transaction contains an invalid input reference.");
            }
            outputs.add(Map.of("txid", txid.toLowerCase(java.util.Locale.ROOT), "vout", vout.asInt()));
        }
        JsonNode result = unwrapResult(executeRpc("lockunspent", true, outputs));
        return result != null && result.asBoolean(false);
    }

    /**
     * Imports a watch-only output descriptor into the configured Core wallet so
     * {@code listunspent}/{@code listreceivedbyaddress} can see cold funds.
     * Also attempts the matching change branch when the descriptor ends with {@code /0/*}.
     */
    public void importWatchOnlyDescriptor(String descriptor, LocalDateTime timestamp) {
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        String receive = withDescriptorChecksum(descriptor.trim());
        importDescriptorInternal(receive, timestamp);
        String change = toChangeDescriptor(receive);
        if (change != null && !change.equals(receive)) {
            try {
                importDescriptorInternal(withDescriptorChecksum(change), timestamp);
            } catch (RuntimeException exception) {
                // Receive import is enough for funding; change is best-effort for PSBT change detection.
            }
        }
    }

    /**
     * Imports one checksummed watch-only descriptor with a bounded range when ranged.
     */
    private void importDescriptorInternal(String descriptor, LocalDateTime timestamp) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("desc", descriptor);
        if (timestamp != null) {
            request.put("timestamp", timestamp.toEpochSecond(java.time.ZoneOffset.UTC));
        } else {
            request.put("timestamp", "now");
        }
        // Cold watch-only: do NOT mark active (would conflict with hot wallet active
        // receive descriptors) and never set label on ranged descs (Core error -8).
        // Do not send "watchonly" — descriptor wallets reject / ignore it.
        request.put("active", false);
        request.put("internal", descriptor.contains("/1/*"));
        if (descriptor.contains("*")) {
            request.put("range", List.of(0, 1000));
        }
        // importdescriptors takes a single param: array of descriptor request objects.
        JsonNode result = unwrapResult(executeRpc("importdescriptors", List.of(request)));
        requireImportSuccess(result, descriptor);
    }

    /**
     * Requires Core to report at least one successful descriptor import or throws with a shortened descriptor.
     */
    private static void requireImportSuccess(JsonNode result, String descriptor) {
        if (result == null || !result.isArray() || result.isEmpty()) {
            throw new IllegalStateException(
                    "importdescriptors returned empty result for descriptor "
                            + abbreviateDescriptor(descriptor));
        }
        for (JsonNode item : result) {
            if (item != null && item.path("success").asBoolean(false)) {
                return;
            }
        }
        String error = result.get(0).path("error").path("message").asText("unknown error");
        throw new IllegalStateException(
                "importdescriptors failed for "
                        + abbreviateDescriptor(descriptor)
                        + ": "
                        + error);
    }

    /**
     * Shortens descriptor text for diagnostics without exposing an unnecessarily long value.
     */
    private static String abbreviateDescriptor(String descriptor) {
        if (descriptor == null) {
            return "null";
        }
        String bare = descriptor.trim();
        return bare.length() <= 48 ? bare : bare.substring(0, 48) + "…";
    }

    /**
     * Asks node-level getdescriptorinfo to normalize a descriptor and provide its checksum.
     */
    private String withDescriptorChecksum(String descriptor) {
        String bare = descriptor;
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        // Node-level RPC — not wallet-scoped (wallet endpoint rejects / is flaky).
        JsonNode info = unwrapResult(executeNodeRpc("getdescriptorinfo", bare));
        String checksummed = text(info, "descriptor");
        if (checksummed == null || checksummed.isBlank()) {
            throw new IllegalStateException("Bitcoin Core getdescriptorinfo did not return a descriptor.");
        }
        return checksummed;
    }

    /**
     * scantxoutset is a node RPC. Calling it on /wallet/... fails on many Core builds
     * and surfaces as "RPC request failed for method scantxoutset".
     */
    @Override
    public long getConfirmedBalanceForDescriptor(String descriptor, int range) {
        if (descriptor == null || descriptor.isBlank()) {
            return 0L;
        }
        int safeRange = Math.max(1, range);
        Map<String, Object> scanObject = new LinkedHashMap<>();
        scanObject.put("desc", descriptor.trim());
        scanObject.put("range", safeRange);
        JsonNode result = startScantxoutset(scanObject);
        if (result == null || result.isNull() || result.isMissingNode()) {
            return 0L;
        }
        JsonNode totalAmount = result.path("total_amount");
        if (!totalAmount.isNumber()) {
            return 0L;
        }
        return btcNodeToSats(totalAmount);
    }

    /** Scans a descriptor or address for unspent outputs and propagates scan failures.
     * @param descriptorOrAddr descriptor or address to scan
     * @param range derivation range for ranged descriptors
     * @return immutable list of valid UTXOs; empty only when scan completed with no results
     */
    @Override
    public List<AddressUtxo> getUnspentOutputsFromScan(String descriptorOrAddr, int range) {
        if (descriptorOrAddr == null || descriptorOrAddr.isBlank()) {
            return List.of();
        }
        String desc = descriptorOrAddr.trim();
        if (!desc.contains("(")) {
            desc = "addr(" + desc + ")";
        }
        // Propagate failures: empty list must mean "no UTXOs", not "scan aborted".
        // Callers that need soft-fail should catch RuntimeException themselves.
        Map<String, Object> scanObject = new LinkedHashMap<>();
        scanObject.put("desc", desc);
        if (range > 1) {
            scanObject.put("range", Math.max(1, range));
        }
        JsonNode result = startScantxoutset(scanObject);
        return parseNodeScantxoutsetUnspents(result);
    }

    /** Returns the active chain tip height.
     * @return current best-chain height
     */
    @Override
    public long getBlockTipHeight() {
        return getBlockCount();
    }

    /**
     * Converts Core scantxoutset entries to valid UTXO records, skipping malformed or non-positive outputs.
     */
    private List<AddressUtxo> parseNodeScantxoutsetUnspents(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return List.of();
        }
        long tipHeight = result.path("height").isIntegralNumber() ? result.path("height").asLong() : 0L;
        JsonNode unspents = result.path("unspents");
        if (!unspents.isArray()) {
            return List.of();
        }
        java.util.ArrayList<AddressUtxo> results = new java.util.ArrayList<>();
        for (JsonNode utxo : unspents) {
            String txid = text(utxo, "txid");
            JsonNode vout = utxo.path("vout");
            long valueSats = btcNodeToSats(utxo.path("amount"));
            if (utxo.path("value").isIntegralNumber()) {
                valueSats = utxo.path("value").asLong();
            }
            if (txid == null || !vout.isIntegralNumber() || valueSats <= 0L) {
                continue;
            }
            int confs = 0;
            if (utxo.path("confirmations").isIntegralNumber()) {
                confs = utxo.path("confirmations").asInt();
            } else if (utxo.path("height").isIntegralNumber() && tipHeight > 0L) {
                long h = utxo.path("height").asLong();
                if (h > 0L) {
                    confs = (int) Math.max(0L, tipHeight - h + 1L);
                }
            }
            String script = text(utxo, "scriptPubKey");
            results.add(new AddressUtxo(txid, vout.asInt(), valueSats, script, confs, null));
        }
        return List.copyOf(results);
    }

    /**
     * Core allows only one scantxoutset at a time. Serialize all starts in-process and
     * <em>wait</em> for the prior scan to finish. Aborting mid-scan was racing cold balance
     * probes (partial 0 vs full descriptor) and made observed_sats oscillate.
     */
    private JsonNode startScantxoutset(Map<String, Object> scanObject) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(scanWaitTimeoutMs);
        boolean acquired = false;
        try {
            acquired = SCANTXOUTSET_LOCK.tryLock(scanWaitTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) throw new IllegalStateException("Bitcoin UTXO scan queue wait exceeded its budget");
            while (System.nanoTime() < deadline) {
                JsonNode status = unwrapResult(executeNodeRpc("scantxoutset", "status"));
                if (status == null || status.isNull() || status.isMissingNode()) {
                    try {
                        JsonNode result = unwrapResult(executeNodeRpc("scantxoutset", "start", List.of(scanObject)));
                        if (result == null || !result.path("success").asBoolean(false)
                                || !result.path("total_amount").isNumber() || !result.path("unspents").isArray()) {
                            throw new IllegalStateException("Bitcoin UTXO scan did not complete successfully");
                        }
                        return result;
                    } catch (RuntimeException exception) {
                        if (!isScanInProgress(exception)) throw exception;
                        // Another process won the node-wide race. Wait within the original budget.
                    }
                } else if (!status.isObject() || !status.path("progress").isNumber()) {
                    throw new IllegalStateException("Invalid Bitcoin UTXO scan status");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(
                        Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400)));
            }
            // An active scan belongs to Core and may have been started before this process.
            // Never abort it or turn an incomplete observation into an empty balance.
            throw new IllegalStateException("Bitcoin UTXO scan is busy; observation must be retried");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bitcoin UTXO scan wait interrupted", exception);
        } finally {
            if (acquired) SCANTXOUTSET_LOCK.unlock();
        }
    }

    /**
     * Sleeps for the requested interval and restores the interrupt flag if interrupted.
     */
    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Process-wide monitor serializing scantxoutset starts because Core permits one scan at a time. */
    private static final java.util.concurrent.locks.ReentrantLock SCANTXOUTSET_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    /**
     * Recognizes Core RPC errors that indicate a scantxoutset scan is already active.
     */
    private static boolean isScanInProgress(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("scan already in progress");
    }

    /** Returns confirmed funds controlled by an address using a node UTXO scan.
     * @param address address to scan
     * @return confirmed balance in satoshis, or zero for a blank address
     */
    @Override
    public long getConfirmedBalanceForAddress(String address) {
        if (address == null || address.isBlank()) {
            return 0L;
        }
        return getConfirmedBalanceForDescriptor("addr(" + address.trim() + ")", 1);
    }

    /**
     * Derives the change branch descriptor from a receive descriptor ending in /0/*.
     */
    private static String toChangeDescriptor(String receiveDescriptor) {
        if (receiveDescriptor == null) {
            return null;
        }
        // Strip checksum before path rewrite; checksum re-applied by getdescriptorinfo.
        String bare = receiveDescriptor;
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        if (!bare.contains("/0/*")) {
            return null;
        }
        return bare.replace("/0/*", "/1/*");
    }

    /**
     * Builds a PSBT from explicitly selected cold-wallet inputs without adding wallet inputs.
     */
    public FundedPsbt createWatchOnlyPsbt(
            List<PsbtInput> selectedInputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte) {
        return createWatchOnlyPsbt(
                selectedInputs,
                destinationAddress,
                amountSats,
                confirmationTarget,
                feeRateSatsPerVbyte,
                null);
    }

    /**
     * @param changeAddress optional cold-wallet change address. When set, Core must not
     *                      send change to the hot keypool (critical for watch-only spends).
     */
    public FundedPsbt createWatchOnlyPsbt(
            List<PsbtInput> selectedInputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeAddress) {
        if (selectedInputs == null || selectedInputs.isEmpty()) {
            throw new IllegalArgumentException("At least one selected input is required for watch-only PSBT creation.");
        }
        if (destinationAddress == null || destinationAddress.isBlank()) {
            throw new IllegalArgumentException("destinationAddress is required for watch-only PSBT creation.");
        }
        List<Map<String, Object>> inputs = selectedInputs.stream()
                .map(input -> Map.<String, Object>of(
                        "txid", input.txid(),
                        "vout", input.vout()))
                .toList();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put(destinationAddress.trim(), satsToBtc(amountSats));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("includeWatching", true);
        options.put("add_inputs", false);
        if (changeAddress != null && !changeAddress.isBlank()) {
            options.put("changeAddress", changeAddress.trim());
        } else {
            // Fallback only — callers should always pass cold change for WATCH_ONLY.
            options.put("change_type", "bech32");
        }
        boolean explicitFeeRate = feeRateSatsPerVbyte != null && feeRateSatsPerVbyte > 0L;
        if (explicitFeeRate) {
            // fee_rate is sat/vB in modern Bitcoin Core (not BTC/kvB).
            options.put("fee_rate", Math.max(1L, feeRateSatsPerVbyte));
        }
        if (!explicitFeeRate && confirmationTarget != null && confirmationTarget > 0) {
            options.put("conf_target", confirmationTarget);
        }

        JsonNode result = unwrapResult(executeRpc(
                "walletcreatefundedpsbt",
                inputs,
                List.of(output),
                0,
                options,
                true));

        String psbt = text(result, "psbt");
        long feeSats = btcNodeToSats(result.path("fee"));
        return new FundedPsbt(psbt, feeSats);
    }

    /**
     * Returns Core’s decoded PSBT structure.
     */
    public JsonNode decodePsbt(String psbt) {
        return unwrapResult(executeRpc("decodepsbt", psbt));
    }

    /**
     * Combines partial PSBTs and returns the resulting encoded PSBT.
     */
    public String combinePsbt(List<String> partialPsbts) {
        JsonNode result = unwrapResult(executeRpc("combinepsbt", partialPsbts));
        return result != null && !result.isNull() ? result.asText() : null;
    }

    /**
     * Asks Core to finalize a PSBT and returns raw hex plus the completion flag.
     */
    public FinalizedPsbt finalizePsbt(String psbt) {
        JsonNode result = unwrapResult(executeRpc("finalizepsbt", psbt, true));
        return new FinalizedPsbt(
                text(result, "hex"),
                result.path("complete").asBoolean(false));
    }

    /**
     * Signs a PSBT with keys available in the loaded Core wallet ({@code walletprocesspsbt}).
     * Used as a first-class production signer node when custody keys live in Bitcoin Core
     * (or as one contributor in a multi-signer quorum).
     */
    public String walletProcessPsbt(String psbt) {
        if (psbt == null || psbt.isBlank()) {
            throw new IllegalArgumentException("psbt is required");
        }
        // Bitcoin Core 28: walletprocesspsbt "psbt" (sign sighashtype bip32derivs finalize)
        // finalize=false — leave finalization to the quorum assembler after combinepsbt
        JsonNode result = unwrapResult(executeRpc(
                "walletprocesspsbt",
                psbt.trim(),
                true,
                "ALL",
                true,
                false));
        String processed = text(result, "psbt");
        if (processed == null || processed.isBlank()) {
            throw new IllegalStateException("Bitcoin Core walletprocesspsbt did not return a PSBT.");
        }
        return processed;
    }

    /**
     * Full chain status for a transaction — preserves negative confirmations from Core.
     * Never treat RPC unavailability as "transaction not found."
     */
    /**
     * Snapshot of transaction state observed from Core; negative confirmations remain meaningful.
     * @param state classified relationship to the active chain and mempool
     * @param confirmations raw confirmation count returned by Core
     * @param blockHash containing block hash, when known
     * @param blockHeight containing block height, when known
     * @param replacedByTxid replacement transaction ID, when reported
     */
    public record TransactionChainStatus(
            ChainState state,
            int confirmations,
            @Nullable String blockHash,
            @Nullable Integer blockHeight,
            @Nullable String replacedByTxid
    ) {
        /** Classification of the transaction’s observed relationship to the active chain and mempool. */
        public enum ChainState {
            /** Transaction is present without a mined confirmation. */
            MEMPOOL,    // 0 confirmations
            /** Transaction has one or more active-chain confirmations. */
            CONFIRMED,  // > 0
            /** Core reports negative confirmations for a conflicting or replaced transaction. */
            CONFLICTED, // < 0 (negative from Core)
            /** Transaction was not found by wallet or node lookup. */
            NOT_FOUND,  // RPC tx not found in wallet/mempool
            /** Wallet marks the transaction as abandoned. */
            ABANDONED,
            /** RPC failure or ambiguous data prevents a reliable classification. */
            /** RPC failure or ambiguous data prevents a reliable classification. */
            UNKNOWN     // network error / RPC unavailable
        }
    }

    /**
     * Fetches transaction chain status with raw confirmations and block metadata.
     * RPC errors are captured as UNKNOWN — never conflated with NOT_FOUND.
     */
    public TransactionChainStatus fetchTransactionChainStatus(String txid) {
        if (txid == null || txid.isBlank()) {
            return new TransactionChainStatus(TransactionChainStatus.ChainState.UNKNOWN, 0, null, null, null);
        }
        String id = txid.trim();
        RuntimeException walletError = null;
        try {
            JsonNode walletTx = unwrapResult(executeRpc("gettransaction", id));
            if (walletTx != null && !walletTx.isNull() && !walletTx.isMissingNode()) {
                return parseChainStatus(walletTx, id);
            }
        } catch (RuntimeException e) {
            walletError = e;
        }
        try {
            JsonNode raw = getRawTransaction(id, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return new TransactionChainStatus(TransactionChainStatus.ChainState.NOT_FOUND,
                        0, null, null, null);
            }
            return parseChainStatus(raw, id);
        } catch (RuntimeException rawError) {
            if (walletError != null) {
                rawError.addSuppressed(walletError);
            }
            return new TransactionChainStatus(TransactionChainStatus.ChainState.UNKNOWN,
                    0, null, null, null);
        }
    }

    /**
     * Maps Core confirmation and replacement metadata into the adapter chain-status model.
     */
    private TransactionChainStatus parseChainStatus(JsonNode tx, String txid) {
        JsonNode confs = tx.path("confirmations");
        int confirmations = confs.isIntegralNumber() ? confs.asInt() : 0;
        String blockHash = textField(tx, "blockhash");
        Integer blockHeight = tx.path("blockheight").isIntegralNumber()
                ? tx.path("blockheight").asInt() : null;
        String replacedBy = textField(tx, "replaced_by_txid");
        if (replacedBy == null) {
            replacedBy = textField(tx, "replacedbytxid");
        }

        TransactionChainStatus.ChainState state;
        if (confirmations < 0) {
            state = TransactionChainStatus.ChainState.CONFLICTED;
        } else if (confirmations > 0) {
            state = TransactionChainStatus.ChainState.CONFIRMED;
        } else {
            state = TransactionChainStatus.ChainState.MEMPOOL;
        }
        return new TransactionChainStatus(state, confirmations, blockHash, blockHeight, replacedBy);
    }

    /**
     * Reads an optional JSON text field, returning null when absent or explicitly null.
     */
    private static String textField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * Queries an outpoint status via gettxout (include_mempool=true).
     * Returns null when spent/unknown; returns the UTXO JSON when unspent.
     */
    public JsonNode queryOutpoint(String txid, int vout) {
        if (txid == null || txid.isBlank() || vout < 0) {
            return null;
        }
        try {
            JsonNode raw = executeRpc("gettxout", txid.trim(), vout, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return null;
            }
            if (raw.has("result")) {
                JsonNode result = raw.get("result");
                if (result == null || result.isNull() || result.isMissingNode()) {
                    return null;
                }
                return result;
            }
            return raw;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Searches for a replacement transaction by checking each input's spending status.
     * Returns the replacement txid, or null if none found.
     */
    public String findReplacementTxid(String originalTxid) {
        if (originalTxid == null || originalTxid.isBlank()) {
            return null;
        }
        String id = originalTxid.trim();
        try {
            JsonNode raw = getRawTransaction(id, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return null;
            }
            JsonNode vin = raw.path("vin");
            if (!vin.isArray()) {
                return null;
            }
            for (JsonNode input : vin) {
                String inTxid = textField(input, "txid");
                JsonNode inVout = input.path("vout");
                if (inTxid != null && !inTxid.isBlank() && inVout.isIntegralNumber()) {
                    String spending = findSpendingTxid(inTxid, inVout.asInt());
                    if (spending != null && !spending.isBlank() && !spending.equalsIgnoreCase(id)) {
                        return spending.trim();
                    }
                }
            }
        } catch (RuntimeException e) {
            // Best-effort
        }
        return null;
    }

    /**
     * Searches wallet transaction history for a replacement by looking at walletconflicts.
     */
    public String findReplacementInWallet(String originalTxid, long amountSats, String destinationAddress) {
        if (originalTxid == null || originalTxid.isBlank()) {
            return null;
        }
        try {
            JsonNode txs = unwrapResult(executeRpc("listtransactions", "*", 200, 0, true));
            if (txs == null || !txs.isArray()) {
                return null;
            }
            String normalizedOriginal = originalTxid.trim().toLowerCase(java.util.Locale.ROOT);
            for (JsonNode tx : txs) {
                String txid = textField(tx, "txid");
                if (txid == null || txid.equalsIgnoreCase(normalizedOriginal)) {
                    continue;
                }
                JsonNode walletTx = unwrapResult(executeRpc("gettransaction", txid));
                if (walletTx != null && !walletTx.isNull()) {
                    JsonNode walletConflicts = walletTx.path("walletconflicts");
                    if (walletConflicts.isArray()) {
                        for (JsonNode conflict : walletConflicts) {
                            if (normalizedOriginal.equals(conflict.asText().trim().toLowerCase(java.util.Locale.ROOT))) {
                                return txid;
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // Best-effort
        }
        return null;
    }

    /**
     * Confirmation count for a wallet-known or mempool/chain transaction.
     * Empty when the transaction is not found; {@code 0} means in mempool (unconfirmed).
     *
     * <p>Negative values are preserved from Bitcoin Core and carry specific meaning:
     * <ul>
     *   <li>{@code -1}: CONFLICTED — double-spend or conflicting transaction exists</li>
     *   <li>{@code -2}: REMOVED — transaction no longer in mempool (RBF replaced, evicted)</li>
     *   <li>{@code -3} or lower: other Core-specific negative states</li>
     * </ul>
     * Callers must handle negative confirmations explicitly — conflating with zero hides reorgs.
     */
    @Override
    public java.util.OptionalInt findTransactionConfirmations(String txid) {
        if (txid == null || txid.isBlank()) {
            return java.util.OptionalInt.empty();
        }
        String id = txid.trim();
        // Prefer wallet-aware gettransaction when available (always exposes confirmations).
        try {
            JsonNode walletTx = unwrapResult(executeRpc("gettransaction", id));
            if (walletTx != null && !walletTx.isNull() && !walletTx.isMissingNode()) {
                JsonNode confirmations = walletTx.path("confirmations");
                if (confirmations.isIntegralNumber()) {
                    return java.util.OptionalInt.of(confirmations.asInt());
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to getrawtransaction
        }
        try {
            JsonNode raw = getRawTransaction(id, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return java.util.OptionalInt.empty();
            }
            JsonNode confirmations = raw.path("confirmations");
            if (confirmations.isIntegralNumber()) {
                return java.util.OptionalInt.of(confirmations.asInt());
            }
            // Present in mempool/wallet without a confirmations field yet.
            return java.util.OptionalInt.of(0);
        } catch (RuntimeException ignored) {
            return java.util.OptionalInt.empty();
        }
    }

    /** @return confirmation count, or {@code -1} when not found */
    public int getTransactionConfirmations(String txid) {
        return findTransactionConfirmations(txid).orElse(-1);
    }

    /**
     * Decodes raw transaction hex with Bitcoin Core.
     */
    public JsonNode decodeRawTransaction(String rawHex) {
        return unwrapResult(executeRpc("decoderawtransaction", rawHex));
    }

    /**
     * Selects the node endpoint or configured wallet endpoint for wallet-scoped RPC.
     */
    private String resolveEndpoint() {
        if (walletName == null || walletName.isBlank()) {
            return baseUrl;
        }
        return baseUrl + "/wallet/" + walletName;
    }

    /**
     * Builds the HTTP Basic authorization header from configured RPC credentials.
     */
    private String basicAuthHeader() {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extracts the JSON-RPC result member when present; otherwise returns the supplied node.
     */
    private JsonNode unwrapResult(JsonNode response) {
        if (response != null && response.has("result")) {
            return response.get("result");
        }
        return response;
    }

    /**
     * Converts a Core BTC amount to whole satoshis, rounding fractional satoshis down.
     */
    private long btcNodeToSats(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0L;
        }
        BigDecimal btc = value.isNumber()
                ? value.decimalValue()
                : new BigDecimal(value.asText("0"));
        return btc.multiply(SATOSHIS_PER_BITCOIN)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    /**
     * Converts an integer satoshi amount to BTC with eight decimal places.
     */
    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    /**
     * Reads an optional JSON text property, returning null when it is missing or null.
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * Validates the RPC URL scheme and authority, rejects embedded credentials/query/fragment, and normalizes its path.
     */
    private String sanitizeBaseUrl(String url) {
        String trimmed = url != null ? url.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("bitcoin.rpc.url is required");
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("bitcoin.rpc.url must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("bitcoin.rpc.url must include a host");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("bitcoin.rpc.url must not include userinfo credentials");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("bitcoin.rpc.url must not include query or fragment components");
            }
            String normalized = uri.normalize().toASCIIString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("bitcoin.rpc.url must be a valid URI", exception);
        }
    }

    /**
     * Validates and URL-encodes a wallet path name; empty configuration selects node-only RPC.
     */
    private String sanitizeWalletName(String walletName) {
        String cleanWallet = walletName != null ? walletName.trim() : "";
        if (cleanWallet.isEmpty()) {
            return "";
        }
        if (!cleanWallet.matches("^[A-Za-z0-9._-]{1,64}$") || ".".equals(cleanWallet) || "..".equals(cleanWallet)) {
            throw new IllegalArgumentException(
                    "bitcoin.rpc.wallet may only contain letters, numbers, dots, underscores, and hyphens");
        }
        return URLEncoder.encode(cleanWallet, StandardCharsets.UTF_8);
    }

    /**
     * Funded PSBT returned by walletcreatefundedpsbt together with Core's calculated fee.
     * @param psbt encoded partially signed transaction
     * @param feeSats total fee calculated by Core, in satoshis
     */
    public record FundedPsbt(String psbt, long feeSats) {
    }

    /**
     * Finalization output containing raw transaction hex and whether all inputs are complete.
     * @param hex finalized raw transaction, or null when incomplete
     * @param complete whether all inputs were finalized
     */
    public record FinalizedPsbt(String hex, boolean complete) {
    }

    /**
     * Outpoint selected as an input to a watch-only PSBT.
     * @param txid transaction ID containing the output
     * @param vout output index within that transaction
     */
    public record PsbtInput(String txid, int vout) {
    }
}
