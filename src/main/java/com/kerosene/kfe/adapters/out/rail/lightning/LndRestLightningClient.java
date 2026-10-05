package com.kerosene.kfe.adapters.out.rail.lightning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway.*;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lnurl.LnurlPayResolver;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LND REST adapter: balance probe, pay invoice, create/lookup invoices, channels.
 * Enabled only when {@code lightning.lnd.rest.enabled=true}.
 * Marked {@link Primary} so channel lifecycle prefers this bean when invoice/payment
 * gateway aliases also expose the same implementation type.
 */
@Component("kfeLndRestLightningClient")
@Primary
@ConditionalOnProperty(prefix = "lightning.lnd.rest", name = "enabled", havingValue = "true")
public class LndRestLightningClient
        implements LightningClient, LightningPaymentGateway, LightningInvoiceGateway, LightningChannelGateway {

    /** TLV type for keysend preimage (LND / BOLT). */
    private static final String KEYSEND_PREIMAGE_RECORD = "5482373484";
    /** Logger for LND REST diagnostics; request credentials are never included explicitly. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LndRestLightningClient.class);

    /** HTTP transport configured for the LND REST endpoint and TLS policy. */
    private final RestTemplate restTemplate;
    /** JSON serializer/parser shared by all LND REST operations. */
    private final ObjectMapper objectMapper;
    /** LND REST base URL normalized for endpoint concatenation. */
    private final String baseUrl;
    /** Hex-encoded macaroon included in gRPC metadata on authenticated requests. */
    private final String macaroonHex;
    /** Deadline supplied to outbound router payment requests. */
    private final int paymentTimeoutSeconds;
    /** Fallback invoice lifetime, clamped to at least one minute. */
    private final int defaultInvoiceExpirySeconds;
    /** Optional resolver that turns LNURL/Lightning Address destinations into BOLT11 invoices. */
    private final LnurlPayResolver lnurlPayResolver;
    /** Cryptographically secure source for one-time keysend preimages. */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the LND adapter and normalizes endpoint, credential, and invoice-expiry settings.
     *
     * @param restTemplate LND-specific HTTP client
     * @param objectMapper JSON serializer and parser
     * @param baseUrl LND REST base URL
     * @param macaroonHex LND macaroon in hexadecimal form
     * @param paymentTimeoutSeconds maximum router payment request duration
     * @param defaultInvoiceExpirySeconds fallback lifetime for invoices without an explicit expiry
     * @param lnurlPayResolver optional LNURL/Lightning Address resolver
     */
    public LndRestLightningClient(
            @Qualifier("lndRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lightning.lnd.base-url}") String baseUrl,
            @Value("${lightning.lnd.macaroon}") String macaroonHex,
            @Value("${lightning.lnd.payment-timeout-seconds:30}") int paymentTimeoutSeconds,
            @Value("${lightning.lnd.invoice-expiry-seconds:3600}") int defaultInvoiceExpirySeconds,
            org.springframework.beans.factory.ObjectProvider<LnurlPayResolver> lnurlPayResolver) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = sanitize(baseUrl);
        this.macaroonHex = macaroonHex != null ? macaroonHex.trim() : "";
        this.paymentTimeoutSeconds = paymentTimeoutSeconds;
        this.defaultInvoiceExpirySeconds = Math.max(60, defaultInvoiceExpirySeconds);
        this.lnurlPayResolver = lnurlPayResolver.getIfAvailable();
    }

    /** @return aggregate local channel balance in satoshis using LND's compatible response fields */
    @Override
    public long getLocalBalance() {
        JsonNode response = get("/v1/balance/channels");
        return nestedLong(response, "local_balance", "sat", "value")
                + longField(response, "local_balance_sat");
    }

    /** @return aggregate remote channel balance in satoshis using LND's compatible response fields */
    @Override
    public long getRemoteBalance() {
        JsonNode response = get("/v1/balance/channels");
        return nestedLong(response, "remote_balance", "sat", "value")
                + longField(response, "remote_balance_sat");
    }

    /**
     * Returns LND's explicit node balance when present, otherwise sums local and remote channel
     * balances from the same channel-balance endpoint.
     *
     * @return node channel balance in satoshis
     */
    @Override
    public long getLightningNodeBalance() {
        JsonNode response = get("/v1/balance/channels");
        long explicit = longField(response, "balance", "balance_sat");
        if (explicit > 0L) {
            return explicit;
        }
        return getLocalBalance() + getRemoteBalance();
    }

    /**
     * Exposes chain synchronization as a coarse health score.
     *
     * @return 1.0 when LND reports synchronized to chain, otherwise 0.0
     */
    @Override
    public double getNodeUptime() {
        JsonNode response = get("/v1/getinfo");
        return response.path("synced_to_chain").asBoolean(false) ? 1.0d : 0.0d;
    }

    /**
     * Returns no measured LSP latency because this adapter does not collect that metric.
     *
     * @return zero milliseconds (unavailable measurement sentinel)
     */
    @Override
    public long getLspLatency() {
        return 0L;
    }

    /**
     * Counts pending HTLC entries across channels.
     *
     * @return total pending HTLC count, or -1 when LND's channel collection is unavailable
     */
    @Override
    public int pendingHtlcCount() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return -1;
        }
        int total = 0;
        for (JsonNode channel : channels) {
            JsonNode pending = channel.path("pending_htlcs");
            if (pending.isArray()) {
                total += pending.size();
            }
        }
        return total;
    }

    /**
     * Collects unique remote peer keys for channels that currently contain pending HTLCs.
     *
     * @return immutable lowercased peer-key set; empty when channel data is unavailable
     */
    @Override
    public Set<String> peersWithPendingHtlcs() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return Set.of();
        }
        Set<String> peers = new HashSet<>();
        for (JsonNode channel : channels) {
            JsonNode pending = channel.path("pending_htlcs");
            if (!pending.isArray() || pending.isEmpty()) {
                continue;
            }
            String peer = text(channel, "remote_pubkey");
            if (peer != null && !peer.isBlank()) {
                peers.add(peer.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(peers);
    }

    /**
     * Loads the LND channel list and returns its array only when the response has the expected shape.
     *
     * @return channel JSON array or null when the endpoint response is malformed
     */
    private JsonNode channelsArray() {
        JsonNode response = get("/v1/channels");
        JsonNode channels = response.path("channels");
        if (!channels.isArray()) {
            return null;
        }
        return channels;
    }

    /** @return true when both normalized LND endpoint and macaroon are configured */
    @Override
    public boolean isLive() {
        return !baseUrl.isBlank() && !macaroonHex.isBlank();
    }

    /** @return stable provider identifier for payment and invoice metadata */
    @Override
    public String providerName() {
        return "LND_REST";
    }

    /**
     * Prepares and submits a Lightning payment through the persisted-payment safeguards.
     *
     * @param command payment request, amount/fee policy, and authorization context
     * @return normalized provider payment result
     */
    @Override
    public CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command) {
        return payPreparedLightning(prepareLightning(command));
    }

    /**
     * Resolves supported destination forms into a persisted payment identity before sending funds.
     * BOLT11 hashes are decoded by LND; keysend preimages are generated once and returned encoded
     * so retries can use the exact same payment identity.
     *
     * @param command requested payment and wallet context
     * @return immutable prepared payment with normalized destination kind and payment hash
     * @throws IllegalArgumentException when the command or destination syntax is invalid
     * @throws IllegalStateException when required LNURL resolution or hash decoding fails
     */
    @Override
    public PreparedLightningPayment prepareLightning(CustodyGateway.LightningPaymentCommand command) {
        requireLive();
        if (command == null) {
            throw new IllegalArgumentException("Lightning payment command is required.");
        }
        if (command.paymentRequest() == null || command.paymentRequest().isBlank()) {
            throw new IllegalArgumentException("Lightning destination (invoice / LNURL / address / pubkey) is required.");
        }
        LightningDestinationClassifier.Classified destination =
                LightningDestinationClassifier.classify(command.paymentRequest());
        if (destination == null) {
            throw new IllegalArgumentException(
                    "Invalid Lightning destination. Use BOLT11 (ln…), LNURL1…, user@domain, or 66-char node pubkey.");
        }

        String paymentRequest = null;
        String nodePubkey = null;
        String preimageBase64 = null;
        String paymentHash;
        String kind;
        switch (destination.kind()) {
            case BOLT11 -> {
                kind = "BOLT11";
                paymentRequest = destination.value();
                paymentHash = decodeInvoice(paymentRequest).paymentHash();
            }
            case KEYSEND -> {
                kind = "KEYSEND";
                nodePubkey = destination.value().toLowerCase(Locale.ROOT);
                byte[] preimage = new byte[32];
                secureRandom.nextBytes(preimage);
                preimageBase64 = Base64.getEncoder().encodeToString(preimage);
                paymentHash = sha256Hex(preimage);
            }
            case LNURL, LIGHTNING_ADDRESS -> {
                if (lnurlPayResolver == null) {
                    throw new IllegalStateException("LNURL / Lightning Address resolver is not available.");
                }
                kind = "BOLT11";
                paymentRequest = lnurlPayResolver.resolveBolt11(destination, command.amountSats());
                paymentHash = decodeInvoice(paymentRequest).paymentHash();
            }
            default -> throw new IllegalArgumentException("Unsupported Lightning destination kind.");
        }
        if (paymentHash == null || !paymentHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("LND did not decode a valid Lightning payment hash.");
        }
        return new PreparedLightningPayment(
                kind,
                command.userId(),
                command.walletId(),
                command.walletName(),
                paymentRequest,
                nodePubkey,
                preimageBase64,
                paymentHash.toLowerCase(Locale.ROOT),
                paymentHash.toLowerCase(Locale.ROOT),
                command.amountSats(),
                command.maxFeeSats(),
                command.description(),
                command.idempotencyKey(),
                command.authorizationProof());
    }

    /**
     * Reconciles the persisted payment hash before submitting, then returns only a terminal success.
     * Existing terminal failures are rejected; pending/unknown states and hash mismatches remain
     * in-flight so the outbox can reconcile instead of releasing the user's reserve prematurely.
     *
     * @param prepared previously persisted payment identity and execution parameters
     * @return normalized terminal-success payment result
     * @throws IllegalArgumentException for invalid prepared data or a terminal provider failure
     * @throws LightningPaymentInFlightException when provider state is nonterminal or mismatched
     */
    @Override
    public CustodyGateway.PaymentResult payPreparedLightning(PreparedLightningPayment prepared) {
        requireLive();
        validatePreparedPayment(prepared);
        LightningPaymentResult prior = lookupPayment(prepared.paymentHash());
        if (prior != null) {
            LightningPaymentOutcome priorOutcome = LightningPaymentOutcome.fromProviderStatus(prior.status());
            if (priorOutcome == LightningPaymentOutcome.SUCCEEDED) {
                return successfulPayment(prior, prepared.paymentHash());
            }
            if (priorOutcome == LightningPaymentOutcome.FAILED) {
                throw new IllegalArgumentException(
                        "Lightning payment is already terminal FAILED for the persisted payment hash.");
            }
            throw new LightningPaymentInFlightException(
                    "Lightning payment is already in flight for the persisted payment hash.",
                    prepared.paymentHash(),
                    prior.rawPayload());
        }

        LightningPaymentResult result = switch (prepared.destinationKind()) {
            case "BOLT11" -> payInvoice(
                    prepared.paymentRequest(), prepared.amountSats(), prepared.maxFeeSats());
            case "KEYSEND" -> payKeysendPrepared(
                    prepared.nodePubkey(),
                    prepared.amountSats(),
                    prepared.maxFeeSats(),
                    prepared.keysendPreimageBase64(),
                    prepared.paymentHash());
            default -> throw new IllegalArgumentException("Unsupported prepared Lightning destination kind.");
        };

        if (result.paymentHash() != null
                && !prepared.paymentHash().equalsIgnoreCase(result.paymentHash())) {
            throw new LightningPaymentInFlightException(
                    "LND returned a payment hash different from the persisted payment identity.",
                    prepared.paymentHash(),
                    result.rawPayload());
        }

        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(result.status());
        return switch (outcome) {
            case SUCCEEDED -> successfulPayment(result, prepared.paymentHash());
            case FAILED -> throw new IllegalArgumentException(
                    "Lightning payment failed: " + nullToEmpty(result.status()));
            case IN_FLIGHT, UNKNOWN -> throw new LightningPaymentInFlightException(
                    "Lightning payment is not terminal (status=" + nullToEmpty(result.status()) + ").",
                    prepared.paymentHash(),
                    result.rawPayload());
        };
    }

    /**
     * Maps a terminal LND success into the custody result while retaining the expected identity.
     *
     * @param result raw LND payment result
     * @param expectedPaymentHash persisted hash used as the operation and payment reference
     * @return custody payment result marked SUCCEEDED
     */
    private CustodyGateway.PaymentResult successfulPayment(
            LightningPaymentResult result,
            String expectedPaymentHash) {
        return new CustodyGateway.PaymentResult(
                expectedPaymentHash,
                null,
                expectedPaymentHash,
                LightningPaymentOutcome.SUCCEEDED.name(),
                result.feeSats(),
                result.rawPayload());
    }

    /**
     * Creates a private LND invoice, supporting zero-value invoices and configured expiry fallback.
     *
     * @param command requested amount, memo, and optional expiry
     * @return invoice request, payment hash, provider reference, and calculated expiry timestamp
     * @throws IllegalArgumentException when amount is negative
     */
    @Override
    public CustodyGateway.GeneratedLightningInvoice createLightningInvoice(
            CustodyGateway.LightningInvoiceCommand command) {
        requireLive();
        if (command.amountSats() < 0L) {
            throw new IllegalArgumentException("Lightning invoice amountSats must be non-negative.");
        }
        // Allow amountless invoices (0 sats) — payer supplies amount at payment time
        int expiry = command.expiresInSeconds() > 0
                ? command.expiresInSeconds()
                : defaultInvoiceExpirySeconds;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value", String.valueOf(command.amountSats()));
        payload.put("memo", command.memo() != null ? command.memo() : "KFE receive");
        payload.put("expiry", String.valueOf(expiry));
        payload.put("private", true);

        JsonNode response = post("/v1/invoices", payload);
        String paymentRequest = text(response, "payment_request");
        String rHashHex = paymentHashHex(response);
        LocalDateTime expiresAt = LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(expiry);
        return new CustodyGateway.GeneratedLightningInvoice(
                paymentRequest,
                rHashHex,
                null,
                rHashHex,
                expiresAt);
    }

    /**
     * Queries an invoice by payment hash/provider reference and normalizes LND settlement fields.
     * A non-required GET failure returns an UNKNOWN result rather than asserting non-payment.
     *
     * @param command payment hash or provider reference and wallet context
     * @return mapped invoice state, received satoshis, settlement observation, and raw response
     * @throws IllegalArgumentException when no lookup identity is supplied
     */
    @Override
    public CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(
            CustodyGateway.LightningInvoiceStatusCommand command) {
        requireLive();
        String lookup = firstNonBlank(command.paymentHash(), command.providerReference());
        if (lookup == null) {
            throw new IllegalArgumentException("paymentHash is required for Lightning invoice status.");
        }
        String path = "/v1/invoice/" + encodePaymentHashPath(lookup);
        JsonNode response = get(path);
        if (response == null || response.isEmpty()) {
            return new CustodyGateway.IncomingLightningInvoiceStatus(
                    "UNKNOWN", null, null, "{}");
        }
        boolean settled = response.path("settled").asBoolean(false)
                || "SETTLED".equalsIgnoreCase(text(response, "state"));
        long received = longField(response, "amt_paid_sat", "value");
        if (received <= 0L) {
            received = nestedLong(response, "amt_paid", "sat", "msat") / 1000L;
        }
        String status = settled ? "SETTLED" : text(response, "state");
        if (status == null || status.isBlank()) {
            status = settled ? "SETTLED" : "OPEN";
        }
        return new CustodyGateway.IncomingLightningInvoiceStatus(
                status.toUpperCase(Locale.ROOT),
                received > 0L ? received : null,
                settled ? LocalDateTime.now(java.time.ZoneOffset.UTC) : null,
                response.toString());
    }

    /**
     * Reports cancellation as unsupported because LND REST versions do not consistently expose it.
     *
     * @param command invoice identity requested for cancellation
     * @return always false; the invoice remains subject to its normal expiry/settlement lifecycle
     */
    @Override
    public boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command) {
        // LND does not support true cancel of open invoices via a simple REST call in all versions.
        return false;
    }

    /**
     * Maps the active LND channel list into immutable channel lifecycle snapshots.
     *
     * @return snapshots for channels returned by LND, or an empty list when response shape is invalid
     */
    @Override
    public List<ChannelSnapshot> listChannels() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return List.of();
        }
        return channelSnapshots(channels);
    }

    private List<ChannelSnapshot> channelSnapshots(JsonNode channels) {
        List<ChannelSnapshot> snapshots = new ArrayList<>();
        for (JsonNode channel : channels) {
            String chanId = text(channel, "chan_id");
            if (chanId == null && channel.path("chan_id").isIntegralNumber()) {
                chanId = String.valueOf(channel.path("chan_id").asLong());
            }
            snapshots.add(new ChannelSnapshot(
                    text(channel, "channel_point"),
                    text(channel, "remote_pubkey"),
                    channel.path("active").asBoolean(false),
                    longField(channel, "capacity"),
                    longField(channel, "local_balance"),
                    longField(channel, "remote_balance"),
                    channel.path("pending_htlcs").isArray() ? channel.path("pending_htlcs").size() : 0,
                    channel.path("initiator").asBoolean(false),
                    longField(channel, "commit_fee"),
                    chanId));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public ChannelInventory observeChannels() {
        requireLive();
        JsonNode channels = getRequired("/v1/channels").path("channels");
        if (!channels.isArray()) throw new IllegalStateException("Channel inventory is incomplete");
        for (JsonNode channel : channels) {
            if (text(channel, "channel_point") == null || text(channel, "remote_pubkey") == null
                    || !channel.path("active").isBoolean() || !channel.path("pending_htlcs").isArray())
                throw new IllegalStateException("Channel observation is incomplete");
            for (String amount : List.of("capacity", "local_balance", "remote_balance")) {
                JsonNode value = channel.get(amount);
                if (value == null || !value.asText().matches("[0-9]+"))
                    throw new IllegalStateException("Channel amount is missing or invalid");
                Long.parseLong(value.asText());
            }
        }
        JsonNode pending = getRequired("/v1/channels/pending");
        List<PendingChannelSnapshot> out = new ArrayList<>();
        String[][] states = {{"pending_open_channels", "PENDING_OPEN"},
                {"pending_force_closing_channels", "PENDING_FORCE_CLOSE"},
                {"waiting_close_channels", "WAITING_CLOSE"},
                {"pending_closing_channels", "PENDING_CLOSING"}};
        for (String[] state : states) {
            if (!pending.path(state[0]).isArray())
                throw new IllegalStateException("Pending channel inventory is incomplete");
            appendPending(out, pending.path(state[0]), state[1]);
        }
        return new ChannelInventory(channelSnapshots(channels), out);
    }

    /**
     * Queries LND's pending-channel endpoint and combines its open/closing state collections.
     *
     * @return immutable snapshots labeled with the originating pending state
     */
    @Override
    public List<PendingChannelSnapshot> listPendingChannels() {
        requireLive();
        JsonNode response = get("/v1/channels/pending");
        List<PendingChannelSnapshot> out = new ArrayList<>();
        appendPending(out, response.path("pending_open_channels"), "PENDING_OPEN");
        appendPending(out, response.path("pending_force_closing_channels"), "PENDING_FORCE_CLOSE");
        appendPending(out, response.path("waiting_close_channels"), "WAITING_CLOSE");
        appendPending(out, response.path("pending_closing_channels"), "PENDING_CLOSING");
        return List.copyOf(out);
    }

    /**
     * Appends entries from one pending-channel response array, extracting peer, point, and capacity.
     *
     * @param out accumulator receiving normalized pending snapshots
     * @param arr provider array for one pending lifecycle state
     * @param status normalized lifecycle label assigned to each entry
     */
    private void appendPending(
            List<PendingChannelSnapshot> out, JsonNode arr, String status) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            JsonNode channel = item.path("channel");
            if (channel.isMissingNode() || channel.isNull()) {
                channel = item;
            }
            String remote = text(channel, "remote_node_pub");
            if (remote == null || remote.isBlank()) {
                remote = text(channel, "remote_pubkey");
            }
            String point = text(channel, "channel_point");
            long capacity = longField(channel, "capacity");
            out.add(new PendingChannelSnapshot(remote, point, status, capacity));
        }
    }

    /**
     * Requests a new standard native-SegWit wallet receive address from LND.
     * The label is accepted by the gateway contract but LND's newaddress request needs no label.
     *
     * @param label caller label retained by the contract but not sent to LND
     * @return trimmed address string
     * @throws IllegalStateException when LND returns no address
     */
    @Override
    public String newOnchainAddress(String label) {
        requireLive();
        Map<String, Object> payload = new LinkedHashMap<>();
        // WITNESS_PUBKEY_HASH (p2wkh) — standard LND wallet receive for funding.
        payload.put("type", 0);
        JsonNode response = post("/v1/newaddress", payload);
        String address = text(response, "address");
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("LND newaddress returned empty address.");
        }
        return address.trim();
    }

    /**
     * Reads the confirmed on-chain wallet balance, preferring nested satoshi fields.
     *
     * @return confirmed chain balance in satoshis
     */
    @Override
    public long confirmedOnchainBalanceSats() {
        requireLive();
        JsonNode response = get("/v1/balance/blockchain");
        long confirmed = nestedLong(response, "confirmed_balance", "sat", "value");
        if (confirmed > 0L) {
            return confirmed;
        }
        return longField(response, "confirmed_balance");
    }

    /**
     * Opens a channel after validating peer identity and positive local funding amount.
     *
     * @param command peer, capacity, privacy, and unconfirmed-input policy
     * @return funding transaction, output index, channel point, and raw LND response
     * @throws IllegalArgumentException when peer key or capacity is invalid
     */
    @Override
    public OpenChannelResult openChannel(OpenChannelCommand command) {
        requireLive();
        if (command.peerPubkey() == null || command.peerPubkey().isBlank()) {
            throw new IllegalArgumentException("peerPubkey is required to open a channel.");
        }
        if (command.localAmountSats() <= 0L) {
            throw new IllegalArgumentException("localAmountSats must be positive.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node_pubkey_string", command.peerPubkey().trim());
        payload.put("local_funding_amount", String.valueOf(command.localAmountSats()));
        payload.put("private", command.privateChannel());
        payload.put("min_confs", command.minConfsZero() ? 0 : 1);
        payload.put("spend_unconfirmed", command.minConfsZero());
        JsonNode response = post("/v1/channels", payload);
        String fundingTxid = text(response, "funding_txid_str");
        if (fundingTxid == null) {
            fundingTxid = text(response.path("funding_txid_bytes"), "funding_txid_str");
        }
        String outputIndex = text(response, "output_index");
        if (outputIndex == null && response.path("output_index").isIntegralNumber()) {
            outputIndex = String.valueOf(response.path("output_index").asLong());
        }
        String channelPoint = fundingTxid != null && outputIndex != null
                ? fundingTxid + ":" + outputIndex
                : fundingTxid;
        return new OpenChannelResult(fundingTxid, outputIndex, channelPoint, response.toString());
    }

    /**
     * Closes a channel identified as {@code fundingTxid:outputIndex}, optionally force-closing it.
     *
     * @param command channel point and force-close choice
     * @return closing transaction ID when supplied by LND and raw response
     * @throws IllegalArgumentException when channel point is missing or malformed
     */
    @Override
    public CloseChannelResult closeChannel(CloseChannelCommand command) {
        requireLive();
        if (command.channelPoint() == null || command.channelPoint().isBlank()) {
            throw new IllegalArgumentException("channelPoint is required to close a channel.");
        }
        String[] parts = command.channelPoint().trim().split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("channelPoint must be fundingTxid:outputIndex.");
        }
        String path = "/v1/channels/" + parts[0] + "/" + parts[1]
                + "?force=" + command.force();
        JsonNode response = delete(path);
        String closingTxid = text(response, "closing_txid");
        if (closingTxid == null) {
            closingTxid = text(response.path("close_pending"), "txid");
        }
        return new CloseChannelResult(closingTxid, response.toString());
    }

    /**
     * Attempts an internal self-payment rebalance from richer active channels into a target channel.
     * The target is excluded from first-hop candidates and the operation remains nonterminal when
     * LND reports pending or unknown payment state.
     *
     * @param command target channel, amount, fee limit, and optional memo
     * @return structured success/failure result; operational errors are represented as failures
     */
    @Override
    public PreparedCircularRebalance prepareCircularRebalance(CircularRebalanceCommand command) {
        requireLive();
        if (command.amountSats() <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive");
        }
        List<ChannelSnapshot> channels = observeChannels().channels();
        ChannelSnapshot target = channels.stream()
                .filter(c -> command.targetChannelPoint() != null
                        && command.targetChannelPoint().equals(c.channelPoint()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new IllegalStateException("Target channel not found");
        }
        if (!target.active() || target.remotePubkey() == null || target.pendingHtlcs() != 0
                || target.remoteBalanceSats() < command.amountSats()
                || channels.stream().filter(c -> target.remotePubkey().equalsIgnoreCase(c.remotePubkey())).count() != 1)
            throw new IllegalStateException("Target channel cannot be unambiguously routed");
        long requiredOutgoing = Math.addExact(command.amountSats(), Math.max(0L, command.maxFeeSats()));
        // Prefer outgoing hop = richest local channel that is not the drained target.
        List<String> outgoing = channels.stream()
                .filter(ChannelSnapshot::active)
                .filter(c -> c.chanId() != null && !c.chanId().isBlank())
                .filter(c -> !target.remotePubkey().equalsIgnoreCase(c.remotePubkey()))
                .filter(c -> c.pendingHtlcs() == 0)
                .filter(c -> c.localBalanceSats() >= requiredOutgoing)
                .sorted((a, b) -> Long.compare(b.localBalanceSats(), a.localBalanceSats()))
                .map(ChannelSnapshot::chanId)
                .limit(3)
                .toList();
        if (outgoing.isEmpty()) {
            throw new IllegalStateException("No alternate channel with enough local balance");
        }

        CustodyGateway.GeneratedLightningInvoice invoice = createLightningInvoice(
                new CustodyGateway.LightningInvoiceCommand(
                        0L,
                        null,
                        "rebalance",
                        command.amountSats(),
                        command.memo() != null ? command.memo() : "KFE circular rebalance",
                        600));
        if (invoice.paymentRequest() == null || invoice.paymentRequest().isBlank()) {
            throw new IllegalStateException("Could not create self-invoice");
        }

        return new PreparedCircularRebalance(invoice.paymentHash(), invoice.paymentRequest(),
                command.maxFeeSats(), outgoing, target.remotePubkey());
    }

    @Override
    public CircularRebalanceResult attemptCircularRebalance(CircularRebalanceCommand command) {
        return submitCircularRebalance(prepareCircularRebalance(command));
    }

    @Override
    public CircularRebalanceResult submitCircularRebalance(PreparedCircularRebalance prepared) {
        requireLive();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request", prepared.paymentRequest());
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, prepared.maxFeeSats())));
        payload.put("allow_self_payment", true);
        payload.put("no_inflight_updates", true);
        payload.put("outgoing_chan_ids", prepared.outgoingChannelIds());
        payload.put("last_hop_pubkey", Base64.getEncoder().encodeToString(
                HexFormat.of().parseHex(prepared.lastHopPubkey())));

        try {
            JsonNode response = post("/v2/router/send", payload);
            String status = text(response, "status");
            LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
            if (outcome == LightningPaymentOutcome.SUCCEEDED) {
                String reportedHash = paymentHashHex(response);
                if (reportedHash == null || !prepared.paymentHash().equalsIgnoreCase(reportedHash))
                    return CircularRebalanceResult.failed("UNKNOWN", response.toString(), "Payment identity mismatch");
                return CircularRebalanceResult.ok(
                        prepared.paymentHash(),
                        verifiedCircularFee(response),
                        response.toString());
            }
            if (outcome == LightningPaymentOutcome.IN_FLIGHT || outcome == LightningPaymentOutcome.UNKNOWN) {
                return CircularRebalanceResult.failed(
                        status != null ? status : "IN_FLIGHT",
                        response.toString(),
                        "Circular payment not terminal");
            }
            return CircularRebalanceResult.failed(
                    status != null ? status : "FAILED",
                    response.toString(),
                    "Circular payment failed");
        } catch (RuntimeException ex) {
            return CircularRebalanceResult.failed(
                    "ERROR", null, ex.getMessage() != null ? ex.getMessage() : "send failed");
        }
    }


    private static long verifiedCircularFee(JsonNode payment) {
        JsonNode fee = payment.get("fee_sat");
        if (fee == null || !fee.asText().matches("[0-9]+")) return -1L;
        try { return Long.parseLong(fee.asText()); }
        catch (NumberFormatException ex) { return -1L; }
    }

    @Override
    public CircularRebalanceResult lookupCircularRebalance(String paymentHash) {
        requireLive();
        LightningPaymentResult result = lookupPayment(paymentHash);
        if (result == null) return CircularRebalanceResult.failed("UNKNOWN", null, "Payment not found");
        if (LightningPaymentOutcome.fromProviderStatus(result.status()) == LightningPaymentOutcome.SUCCEEDED) {
            JsonNode payload;
            try { payload = objectMapper.readTree(result.rawPayload()); }
            catch (Exception ex) { throw new IllegalStateException("Invalid payment evidence", ex); }
            long fee = verifiedCircularFee(payload);
            return CircularRebalanceResult.ok(paymentHash, fee, result.rawPayload());
        }
        return CircularRebalanceResult.failed(result.status(), result.rawPayload(), "Payment reconciliation");
    }

    /**
     * Updates forwarding fee policy, clamping negative fees and enforcing LND's minimum timelock.
     * When a valid channel point is supplied the request targets that channel; otherwise it is global.
     *
     * @param command base fee, fee rate, timelock delta, and optional channel point
     * @return success flag derived from LND's failed_updates collection and the raw response
     */
    @Override
    public UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command) {
        requireLive();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("base_fee_msat", String.valueOf(Math.max(0L, command.baseFeeMsat())));
        payload.put("fee_rate_ppm", String.valueOf(Math.max(0L, command.feeRatePpm())));
        payload.put("time_lock_delta", Math.max(18, command.timeLockDelta()));
        if (command.channelPoint() != null && !command.channelPoint().isBlank()) {
            String[] parts = command.channelPoint().trim().split(":");
            if (parts.length == 2) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("funding_txid_str", parts[0]);
                point.put("output_index", Integer.parseInt(parts[1]));
                payload.put("chan_point", point);
            }
        }
        JsonNode response = post("/v1/chanpolicy", payload);
        boolean failed = response.path("failed_updates").isArray()
                && !response.path("failed_updates").isEmpty();
        return new UpdatePolicyResult(!failed, response.toString());
    }

    /**
     * Sends a spontaneous keysend payment using a newly generated cryptographic preimage.
     *
     * @param nodePubkeyHex compressed destination node key as 66 hexadecimal characters
     * @param amountSats payment amount in satoshis
     * @param maxFeeSats maximum routing fee in satoshis
     * @return LND payment hash, fee, status, and raw response
     */
    public LightningPaymentResult payKeysend(String nodePubkeyHex, long amountSats, long maxFeeSats) {
        byte[] preimage = new byte[32];
        secureRandom.nextBytes(preimage);
        String paymentHashHex = sha256Hex(preimage);
        return payKeysendPrepared(
                nodePubkeyHex,
                amountSats,
                maxFeeSats,
                Base64.getEncoder().encodeToString(preimage),
                paymentHashHex);
    }

    /**
     * Sends keysend using the persisted preimage/hash pair so retries preserve payment identity.
     * Validates that the preimage is 32 bytes and hashes to the expected payment hash before sending.
     *
     * @param nodePubkeyHex destination compressed node key
     * @param amountSats positive amount in satoshis
     * @param maxFeeSats maximum routing fee in satoshis
     * @param preimageBase64 persisted 32-byte preimage encoded with Base64
     * @param expectedPaymentHash expected 32-byte SHA-256 hash in hexadecimal
     * @return provider payment details, retaining expected hash if LND omits its own hash
     * @throws IllegalArgumentException when key, amount, preimage, or preimage/hash pair is invalid
     */
    private LightningPaymentResult payKeysendPrepared(
            String nodePubkeyHex,
            long amountSats,
            long maxFeeSats,
            String preimageBase64,
            String expectedPaymentHash) {
        if (nodePubkeyHex == null || !nodePubkeyHex.matches("(?i)[0-9a-f]{66}")) {
            throw new IllegalArgumentException("Keysend destination must be a 66-char hex node pubkey.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive for keysend.");
        }
        byte[] preimage;
        try {
            preimage = Base64.getDecoder().decode(preimageBase64);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Prepared keysend preimage is invalid.", exception);
        }
        if (preimage.length != 32 || !sha256Hex(preimage).equalsIgnoreCase(expectedPaymentHash)) {
            throw new IllegalArgumentException("Prepared keysend preimage does not match its payment hash.");
        }
        byte[] paymentHash = HexFormat.of().parseHex(expectedPaymentHash);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dest", nodePubkeyHex.toLowerCase(Locale.ROOT));
        payload.put("amt", String.valueOf(amountSats));
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, maxFeeSats)));
        payload.put("no_inflight_updates", true);
        payload.put("payment_hash", Base64.getEncoder().encodeToString(paymentHash));
        Map<String, String> customRecords = new LinkedHashMap<>();
        customRecords.put(KEYSEND_PREIMAGE_RECORD, preimageBase64);
        payload.put("dest_custom_records", customRecords);

        JsonNode response = post("/v2/router/send", payload);
        String status = text(response, "status");
        if (status == null && response.has("failure")) {
            status = "FAILED";
        }
        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
        if (outcome == LightningPaymentOutcome.FAILED) {
            String failureReason = firstNonBlank(
                    text(response.path("failure"), "failure_reason"),
                    text(response, "failure_reason"),
                    text(response, "payment_error"),
                    status);
            throw new IllegalArgumentException(
                    "Lightning keysend failed: " + nullToEmpty(failureReason));
        }
        String hash = paymentHashHex(response);
        if (hash == null || hash.isBlank()) {
            hash = expectedPaymentHash;
        }
        return new LightningPaymentResult(hash, longField(response, "fee_sat", "fee"), status, response.toString());
    }

    /**
     * Pays a BOLT11 invoice through LND while enforcing fixed/amountless invoice amount rules.
     * Fixed invoices reject a conflicting requested amount; amountless invoices require a positive
     * explicit amount. Permanent provider failures are surfaced as non-retryable exceptions.
     *
     * @param paymentRequest BOLT11 invoice text
     * @param amountSats explicit amount for amountless invoices, or matching amount for fixed invoices
     * @param maxFeeSats maximum routing fee in satoshis
     * @return payment hash, fee, provider status, and raw response
     * @throws IllegalArgumentException for amount mismatch, missing amount, or permanent payment failure
     */
    public LightningPaymentResult payInvoice(String paymentRequest, long amountSats, long maxFeeSats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request", paymentRequest);
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, maxFeeSats)));
        payload.put("no_inflight_updates", true);
        // Local / same-node invoices (payment links paid on our LND) require this.
        payload.put("allow_self_payment", true);
        // LND rejects amt on fixed-amount invoices:
        // "amount must not be specified when paying a non-zero amount invoice".
        // Only send amt for zero-amount (amountless) invoices.
        long invoiceSats = decodeInvoice(paymentRequest).amountSats();
        if (invoiceSats <= 0L) {
            if (amountSats <= 0L) {
                throw new IllegalArgumentException(
                        "Lightning amountSats is required when paying a zero-amount invoice.");
            }
            payload.put("amt", String.valueOf(amountSats));
        } else if (amountSats > 0L && amountSats != invoiceSats) {
            throw new IllegalArgumentException(
                    "Lightning amountSats (" + amountSats + ") does not match invoice amount (" + invoiceSats + ").");
        }

        JsonNode response = post("/v2/router/send", payload);
        String status = text(response, "status");
        if (status == null && response.has("failure")) {
            status = "FAILED";
        }
        // Surface permanent payment failures as non-retryable so the outbox does not
        // leave the user-facing tx stuck in EXECUTING/pending forever.
        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
        if (outcome == LightningPaymentOutcome.FAILED) {
            String failureReason = firstNonBlank(
                    text(response.path("failure"), "failure_reason"),
                    text(response, "failure_reason"),
                    text(response, "payment_error"),
                    status);
            throw new IllegalArgumentException(
                    "Lightning payment failed: " + nullToEmpty(failureReason));
        }
        return new LightningPaymentResult(
                paymentHashHex(response),
                longField(response, "fee_sat", "fee"),
                status,
                response.toString());
    }

    /**
     * Invoice amount in sats: LND decode first, bolt11 HRP fallback.
     * Returns 0 only for true amountless invoices (or unparseable bech32 with no HRP amount).
     *
     * @param paymentRequest BOLT11 invoice to decode
     * @return invoice amount and normalized payment hash
     * @throws IllegalArgumentException when request is null or blank
     * @throws IllegalStateException when LND cannot decode a valid payment hash
     */
    private DecodedInvoice decodeInvoice(String paymentRequest) {
        if (paymentRequest == null || paymentRequest.isBlank()) {
            throw new IllegalArgumentException("Lightning payment request is required.");
        }
        try {
            String encoded = java.net.URLEncoder
                    .encode(paymentRequest.trim(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode decoded = getRequired("/v1/payreq/" + encoded);
            String paymentHash = text(decoded, "payment_hash");
            if (paymentHash == null || !paymentHash.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalStateException("LND payreq decode did not return a valid payment_hash.");
            }
            long sats = longField(decoded, "num_satoshis", "num_satoshi");
            if (sats > 0L) {
                return new DecodedInvoice(sats, paymentHash.toLowerCase(Locale.ROOT));
            }
            long msat = longField(decoded, "num_msat", "num_msats");
            if (msat > 0L) {
                return new DecodedInvoice(msat / 1000L, paymentHash.toLowerCase(Locale.ROOT));
            }
            return new DecodedInvoice(0L, paymentHash.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("LND could not decode the Lightning invoice.", exception);
        }
    }

    /**
     * Minimal decoded BOLT11 identity used for amount enforcement and payment reconciliation.
     *
     * @param amountSats fixed invoice amount, or zero for amountless invoice
     * @param paymentHash normalized 32-byte payment hash in lowercase hexadecimal
     */
    private record DecodedInvoice(long amountSats, String paymentHash) {
    }

    /**
     * Offline BOLT11 amount from human-readable part, e.g. {@code lntb1m1...} → 100_000 sats,
     * {@code lntb800u1...} → 80_000, {@code lnbc1p...} (no amount) → 0.
     */
    public static long parseBolt11AmountSatsFromHrp(String paymentRequest) {
        if (paymentRequest == null || paymentRequest.isBlank()) {
            return 0L;
        }
        String lower = paymentRequest.trim().toLowerCase(Locale.ROOT);
        // Bech32/BOLT11 separator is the *last* '1' before the data part.
        // Using indexOf would break amounts that contain '1' (e.g. lntb1m1...).
        int sep = lower.lastIndexOf('1');
        if (sep <= 0) {
            return 0L;
        }
        String hrp = lower.substring(0, sep);
        // strip network prefix: ln + (bc|tb|tbs|bcrt|sb)
        String rest = hrp;
        if (rest.startsWith("ln")) {
            rest = rest.substring(2);
        }
        for (String net : List.of("bcrt", "tbs", "bc", "tb", "sb")) {
            if (rest.startsWith(net)) {
                rest = rest.substring(net.length());
                break;
            }
        }
        if (rest.isEmpty()) {
            return 0L; // amountless
        }
        char multiplier = rest.charAt(rest.length() - 1);
        String numberPart;
        double multSats;
        if (multiplier >= '0' && multiplier <= '9') {
            numberPart = rest;
            multSats = 100_000_000d; // whole BTC
        } else {
            numberPart = rest.substring(0, rest.length() - 1);
            multSats = switch (multiplier) {
                case 'm' -> 100_000d;          // milli-btc
                case 'u' -> 100d;              // micro-btc
                case 'n' -> 0.1d;              // nano-btc
                case 'p' -> 0.0001d;           // pico-btc
                default -> -1d;
            };
            if (multSats < 0d) {
                return 0L;
            }
        }
        if (numberPart.isEmpty()) {
            return 0L;
        }
        try {
            double btcUnits = Double.parseDouble(numberPart);
            long sats = Math.round(btcUnits * multSats);
            return Math.max(0L, sats);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Enforces that this adapter has both a usable endpoint and macaroon before protected calls.
     *
     * @throws IllegalStateException when LND REST is not configured
     */
    private void requireLive() {
        if (!isLive()) {
            throw new IllegalStateException("LND REST is not configured (base-url/macaroon).");
        }
    }

    /**
     * Checks persisted payment identity, bounds, destination kind, invoice hash, and fixed amount.
     * This prevents a retry from changing the payment represented by its durable hash.
     *
     * @param prepared prepared payment restored from the execution record
     * @throws IllegalArgumentException when identity or destination fields are inconsistent
     */
    private void validatePreparedPayment(PreparedLightningPayment prepared) {
        if (prepared == null
                || prepared.paymentHash() == null
                || !prepared.paymentHash().matches("(?i)[0-9a-f]{64}")
                || prepared.executionReference() == null
                || !prepared.paymentHash().equalsIgnoreCase(prepared.executionReference())
                || prepared.amountSats() <= 0L
                || prepared.maxFeeSats() < 0L) {
            throw new IllegalArgumentException("Persisted Lightning payment identity is invalid.");
        }
        if ("BOLT11".equals(prepared.destinationKind())) {
            DecodedInvoice decoded = decodeInvoice(prepared.paymentRequest());
            if (!prepared.paymentHash().equalsIgnoreCase(decoded.paymentHash())) {
                throw new IllegalArgumentException("Persisted invoice does not match its payment hash.");
            }
            if (decoded.amountSats() > 0L && decoded.amountSats() != prepared.amountSats()) {
                throw new IllegalArgumentException("Persisted invoice amount does not match the payment amount.");
            }
        } else if (!"KEYSEND".equals(prepared.destinationKind())) {
            throw new IllegalArgumentException("Persisted Lightning destination kind is invalid.");
        }
    }

    /**
     * Searches recent LND payment history, including incomplete entries, for an existing hash.
     * Pagination is bounded to twenty pages of one hundred entries to avoid unbounded recovery work.
     *
     * @param expectedPaymentHash payment hash to locate
     * @return matching payment details or null when not found within available history
     * @throws IllegalStateException when the bounded history scan cannot finish
     */
    private LightningPaymentResult lookupPayment(String expectedPaymentHash) {
        long indexOffset = 0L;
        for (int page = 0; page < 20; page++) {
            String path = "/v1/payments?include_incomplete=true&max_payments=100&reversed=true&index_offset="
                    + indexOffset;
            JsonNode response = getRequired(path);
            JsonNode payments = response.path("payments");
            if (!payments.isArray() || payments.isEmpty()) {
                return null;
            }
            for (JsonNode payment : payments) {
                String paymentHash = paymentHashHex(payment);
                if (paymentHash != null && expectedPaymentHash.equalsIgnoreCase(paymentHash)) {
                    long feeSats = longField(payment, "fee_sat", "fee");
                    if (feeSats <= 0L) {
                        feeSats = Math.max(0L, longField(payment, "fee_msat") / 1000L);
                    }
                    return new LightningPaymentResult(
                            expectedPaymentHash,
                            feeSats,
                            text(payment, "status"),
                            payment.toString());
                }
            }
            long nextOffset = longField(response, "first_index_offset");
            if (nextOffset <= 0L || nextOffset == indexOffset) {
                return null;
            }
            indexOffset = nextOffset;
        }
        throw new IllegalStateException(
                "LND payment history pagination limit was reached before reconciliation completed.");
    }

    /**
     * Computes lowercase hexadecimal SHA-256 for cryptographic payment material.
     *
     * @param value byte sequence to hash
     * @return 64-character lowercase hexadecimal digest
     * @throws IllegalStateException when SHA-256 is unavailable
     */
    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash Lightning payment identity.", exception);
        }
    }

    /**
     * Performs a best-effort GET; failures are logged and represented by an empty JSON object.
     * Use {@link #getRequired(String)} when the caller must distinguish transport failure from
     * an empty provider result.
     *
     * @param path LND-relative endpoint path
     * @return parsed response, or an empty object when the request fails
     */
    private JsonNode get(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            // Callers such as invoice status treat empty JSON as UNKNOWN; log so silent
            // lookup failures (wrong path encoding, TLS, 4xx/5xx) are visible in ops.
            org.slf4j.LoggerFactory.getLogger(LndRestLightningClient.class)
                    .warn("[LND REST] GET {} failed: {}", safeEndpoint(path), ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Performs a GET whose transport or parsing failure must abort the operation.
     *
     * @param path LND-relative endpoint path
     * @return parsed response
     * @throws IllegalStateException when the request or response parsing fails
     */
    private JsonNode getRequired(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "LND REST request failed on " + safeEndpoint(path), exception);
        }
    }

    /**
     * Sends an authenticated JSON POST and classifies permanent client/provider errors as
     * non-retryable {@link IllegalArgumentException}; other failures remain operational errors.
     *
     * @param path LND-relative endpoint path
     * @param payload request body fields
     * @return parsed provider response
     * @throws IllegalArgumentException for permanent request/payment errors
     * @throws IllegalStateException for transient transport or response failures
     */
    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            return parse(response);
        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            String detail = body != null && !body.isBlank() ? body : httpEx.getStatusText();
            String message = "LND REST request failed on " + path + ": " + detail;
            // Permanent payment/invoice errors must not be retried as EXECUTING forever.
            if (isPermanentLightningClientError(detail)) {
                throw new IllegalArgumentException(message, httpEx);
            }
            throw new IllegalStateException(message, httpEx);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            String message = "LND REST request failed on " + path
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            if (isPermanentLightningClientError(message)) {
                throw new IllegalArgumentException(message, ex);
            }
            throw new IllegalStateException(message, ex);
        }
    }

    /**
     * Recognizes known permanent payment/request/auth errors that cannot succeed by retrying unchanged.
     *
     * @param detail provider error body or formatted error message
     * @return true when the error belongs to the non-retryable set
     */
    public static boolean isPermanentLightningClientError(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("invoice expired")
                || lower.contains("invoice is already paid")
                || lower.contains("invoice already paid")
                || lower.contains("amount must not be specified")
                || lower.contains("amount must be specified")
                || lower.contains("invalid payment request")
                || lower.contains("checksum failed")
                || lower.contains("payment is in terminal state")
                || lower.contains("payment hash already exists")
                || lower.contains("destination unknown")
                || lower.contains("unable to find a path")
                || lower.contains("no route")
                || lower.contains("incorrect_or_unknown_payment_details")
                || lower.contains("incorrect payment details")
                || lower.contains("self-payments not allowed")
                || lower.contains("self payment not allowed")
                // Authentication/secrets errors — no retry possible without redeploy.
                || lower.contains("encoding/hex: invalid byte")
                || lower.contains("cannot set both feerate and feerateppm")
                || lower.contains("permission denied")
                || lower.contains("invalid macaroon");
    }

    /**
     * Sends an authenticated DELETE request and requires a successful parseable response.
     *
     * @param path LND-relative endpoint path
     * @return parsed provider response
     * @throws IllegalStateException when the HTTP exchange or response parsing fails
     */
    private JsonNode delete(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.DELETE, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("LND REST request failed on " + path, ex);
        }
    }

    /**
     * Rejects unsuccessful HTTP responses and decodes a successful response body as JSON.
     *
     * @param response HTTP response returned by the REST client
     * @return parsed JSON response tree
     * @throws Exception when status is unsuccessful, body is absent, or JSON is malformed
     */
    private JsonNode parse(ResponseEntity<String> response) throws Exception {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("LND returned HTTP " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    /** @return JSON headers with the LND macaroon authentication metadata */
    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Grpc-Metadata-macaroon", macaroonHex);
        return headers;
    }

    /**
     * Reads a nested integer-like balance value and clamps missing/negative values to zero.
     *
     * @param root response root object
     * @param field outer balance object
     * @param nestedField preferred nested satoshi field
     * @param fallbackField alternate nested value field
     * @return nonnegative parsed balance
     */
    private long nestedLong(JsonNode root, String field, String nestedField, String fallbackField) {
        JsonNode nested = root.path(field);
        long direct = longField(nested, nestedField, fallbackField);
        return Math.max(0L, direct);
    }

    /**
     * Returns the first integer or parseable decimal-text field from a response node.
     *
     * @param node response object
     * @param fields field candidates in priority order
     * @return parsed value or zero if all candidates are absent/invalid
     */
    private long longField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
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
     * Reads one response field as text while preserving null for missing/null JSON values.
     *
     * @param node response object
     * @param field field name
     * @return textual representation or null when missing/null
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * Extracts a payment hash from LND's hex, Base64, or binary response encodings.
     *
     * @param response provider response object
     * @return lowercase hex when decoding succeeds, original textual value as a final fallback,
     *         or null when no hash is available
     */
    private String paymentHashHex(JsonNode response) {
        String direct = text(response, "payment_hash");
        if (direct != null && !direct.isBlank()) {
            if (looksLikeHex(direct)) {
                return direct.toLowerCase(Locale.ROOT);
            }
            try {
                return HexFormat.of().formatHex(Base64.getDecoder().decode(direct)).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return direct;
            }
        }
        JsonNode rHash = response.path("r_hash");
        if (rHash.isTextual()) {
            String value = rHash.asText();
            if (looksLikeHex(value)) {
                return value.toLowerCase(Locale.ROOT);
            }
            try {
                return HexFormat.of().formatHex(Base64.getDecoder().decode(value)).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }
        if (rHash.isBinary()) {
            try {
                return HexFormat.of().formatHex(rHash.binaryValue()).toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * LND REST {@code GET /v1/invoice/{r_hash_str}} expects the payment hash as a
     * <strong>hex</strong> string (not base64). Sending base64url makes LND return
     * {@code encoding/hex: invalid byte} / HTTP 500, and the silent GET fallback
     * then treats the invoice as UNKNOWN — so settled invoices never credit.
     */
    /**
     * Converts a hex or Base64 payment hash into the hexadecimal path form required by LND.
     *
     * @param paymentHash provider hash in hex, standard Base64, or URL-safe Base64 form
     * @return normalized hex path component, or trimmed original text if decoding is impossible
     */
    private String encodePaymentHashPath(String paymentHash) {
        String trimmed = paymentHash.trim();
        if (looksLikeHex(trimmed)) {
            String normalized = trimmed.length() % 2 == 0 ? trimmed : "0" + trimmed;
            return normalized.toLowerCase(Locale.ROOT);
        }
        // Accept base64 / base64url inputs and normalize to hex for the path.
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(paddedBase64(trimmed));
            return HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(paddedBase64(trimmed));
            return HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException ignored) {
            return trimmed;
        }
    }

    /**
     * Converts URL-safe or unpadded Base64 text into a standard padded representation.
     *
     * @param value Base64 text
     * @return standard alphabet text padded to a four-character boundary
     */
    private static String paddedBase64(String value) {
        String s = value.replace('-', '+').replace('_', '/');
        int mod = s.length() % 4;
        if (mod == 0) {
            return s;
        }
        return s + "====".substring(mod);
    }

    /**
     * Removes query data and redacts invoice/payment-request path identifiers before logging.
     *
     * @param path raw relative endpoint path
     * @return safe path label, with sensitive invoice identifiers replaced
     */
    private static String safeEndpoint(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        String withoutQuery = path.split("\\?", 2)[0];
        if (withoutQuery.startsWith("/v1/payreq/")) {
            return "/v1/payreq/{redacted}";
        }
        if (withoutQuery.startsWith("/v1/invoice/")) {
            return "/v1/invoice/{hash}";
        }
        return withoutQuery;
    }

    /**
     * Checks whether a nonblank string contains only hexadecimal digits.
     *
     * @param value candidate text
     * @return true when every character is in the hexadecimal alphabet
     */
    private boolean looksLikeHex(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trims the endpoint URL and removes one trailing slash for REST path concatenation.
     *
     * @param value raw configured URL
     * @return normalized URL or empty string when null
     */
    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Selects the first nonnull, nonblank candidate from provider error fields.
     *
     * @param values candidate messages in preference order
     * @return first usable value or null
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Converts an optional status/message into a safe concatenation value.
     *
     * @param value nullable text
     * @return empty string for null, otherwise the original value
     */
    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    // ------------------------------------------------------------------ //
    //  SSE streaming subscription (ITEM 47 — Lightning monitor)           //
    // ------------------------------------------------------------------ //

    /**
     * Opens an LND invoice server-sent-event stream on a daemon thread and forwards parsed updates.
     * The returned subscription stops delivery by clearing its active flag and interrupting the
     * reader thread; provider reconnect behavior is intentionally left to the caller/monitor.
     *
     * @param handler callback receiving each parsed invoice update
     * @return subscription handle that stops this stream when unsubscribed
     */
    @Override
    public LightningInvoiceGateway.InvoiceSubscription subscribeInvoices(
            java.util.function.Consumer<CustodyGateway.IncomingLightningInvoiceStatus> handler) {
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread subscriber = new Thread(() -> {
            try {
                String url = baseUrl + "/v1/invoices/subscribe";
                java.net.URL target = java.net.URI.create(url).toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) target.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Grpc-Metadata-macaroon", macaroonHex);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(0); // stream indefinitely
                int status = conn.getResponseCode();
                if (status != 200) {
                    log.warn("[LND SSE] subscribeInvoices failed httpStatus={}", status);
                    return;
                }
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (active.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode result = node.path("result");
                        if (result.isMissingNode()) continue;
                        String paymentHash = text(result, "r_hash");
                        long addIndex = result.path("add_index").asLong(0L);
                        long settleIndex = result.path("settle_index").asLong(0L);
                        String state = result.path("state").asText("UNKNOWN");
                        long amtPaid = result.path("amt_paid_sat").asLong(
                                result.path("amt_paid_msat").asLong(0L) / 1000);
                        handler.accept(new CustodyGateway.IncomingLightningInvoiceStatus(
                                state, amtPaid > 0 ? amtPaid : null, null,
                                node.toString(), paymentHash, addIndex, settleIndex));
                    } catch (RuntimeException e) {
                        log.debug("[LND SSE] parse error: {}", e.getMessage());
                    }
                }
            } catch (java.io.IOException e) {
                if (active.get()) {
                    log.warn("[LND SSE] stream error: {}", e.getMessage());
                }
            }
        }, "kfe-lnd-sse-invoices");
        subscriber.setDaemon(true);
        subscriber.start();
        return () -> { active.set(false); subscriber.interrupt(); };
    }

    /**
     * Provider response snapshot returned by LND invoice, keysend, and reconciliation operations.
     *
     * @param paymentHash normalized payment identity when returned by LND
     * @param feeSats routing fee charged or reported in satoshis
     * @param status provider lifecycle status used to distinguish terminal and in-flight outcomes
     * @param rawPayload serialized provider response retained for reconciliation/diagnostics
     */
    public record LightningPaymentResult(
            String paymentHash,
            long feeSats,
            String status,
            String rawPayload) {
    }
}
