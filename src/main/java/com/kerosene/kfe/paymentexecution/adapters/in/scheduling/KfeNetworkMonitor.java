package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.paymentexecution.adapters.out.settlement.KfeInboundSettlementService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reconciles inbound Bitcoin and Lightning executions against authoritative provider evidence. */
@Component
@ConditionalOnProperty(name = "kfe.network-monitor.enabled", havingValue = "true")
public class KfeNetworkMonitor {

    /** Logger for per-command errors that should not stop the reconciliation batch. */
    private static final Logger log = LoggerFactory.getLogger(KfeNetworkMonitor.class);
    /** Outbox operations whose inbound provider evidence can be reconciled by this monitor. */
    private static final List<String> INBOUND_OPERATIONS = List.of("ONCHAIN_INBOUND", "LIGHTNING_INBOUND");
    /** Strict pattern used to distinguish a transaction id from address or invoice references. */
    private static final Pattern TXID = Pattern.compile("^[0-9a-fA-F]{64}$");
    /** Exact unit scale used to convert positive BTC values to integer satoshis. */
    private static final BigDecimal SATOSHIS_PER_BTC = new BigDecimal("100000000");

    /** Repository that pages durable inbound reconciliation candidates. */
    private final KfeExecutionOutboxRepository outboxRepository;
    /** Repository used to load current transaction state for candidate outbox rows. */
    private final KfeTransactionRepository transactionRepository;
    /** Settlement boundary that validates and applies observed inbound evidence. */
    private final KfeInboundSettlementService settlementService;
    /** Optional Bitcoin RPC client used to verify inbound chain evidence. */
    private final ObjectProvider<BlockchainClient> blockchainClient;
    /** Optional external Lightning gateway used to verify invoice settlement. */
    private final ObjectProvider<LightningInvoiceGateway> lightningInvoiceGateway;
    /** Parser for persisted outbox payloads. */
    private final ObjectMapper objectMapper;
    /** Maximum candidate count inspected in one scheduled pass. */
    private final int batchSize;
    /** Confirmation threshold required before on-chain inbound credit. */
    private final int minOnchainConfirmations;

    /** Wires repositories, optional provider clients, parsing, and the credit finality threshold. */
    public KfeNetworkMonitor(
            KfeExecutionOutboxRepository outboxRepository,
            KfeTransactionRepository transactionRepository,
            KfeInboundSettlementService settlementService,
            ObjectProvider<BlockchainClient> blockchainClient,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            ObjectProvider<LightningInvoiceGateway> lightningInvoiceGateway,
            ObjectMapper objectMapper,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            KfeBitcoinFinalityPolicy finalityPolicy) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.settlementService = settlementService;
        this.blockchainClient = blockchainClient;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, batchSize);
        this.minOnchainConfirmations = finalityPolicy.getCreditConfirmations();
    }

    /** Loads eligible inbound commands in bounded batches and isolates failures per item. */
    @Scheduled(
            fixedDelayString = "${kfe.network-monitor.fixed-delay-ms:30000}",
            initialDelayString = "${kfe.network-monitor.initial-delay-ms:20000}")
    public void reconcileInbound() {
        List<KfeExecutionOutboxEntity> candidates = outboxRepository.findInboundReconciliationCandidates(
                INBOUND_OPERATIONS,
                PageRequest.of(0, batchSize));
        for (KfeExecutionOutboxEntity outbox : candidates) {
            try {
                inspect(outbox);
            } catch (RuntimeException exception) {
                log.warn("[KFE Monitor] Inbound reconciliation failed outboxId={}: {}",
                        outbox.getId(), exception.getMessage());
            }
        }
    }

    /** Loads the transaction and dispatches reconciliation only while its state remains executing or uncertain. */
    private void inspect(KfeExecutionOutboxEntity outbox) {
        Optional<KfeTransactionEntity> optionalTx = transactionRepository.findById(outbox.getTransactionId());
        if (optionalTx.isEmpty()) {
            return;
        }
        KfeTransactionEntity tx = optionalTx.get();
        if (tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION
                && tx.getStatus() != KfeTransactionStatus.EXECUTING) {
            return;
        }

        JsonNode payload = payload(outbox);
        if (tx.getRail() == KfeRail.ONCHAIN) {
            inspectOnchain(outbox, tx, payload);
        } else if (tx.getRail() == KfeRail.LIGHTNING) {
            inspectLightning(outbox, tx, payload);
        }
    }

    /** Credits on-chain evidence only after the configured confirmation threshold is met. */
    private void inspectOnchain(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        Optional<OnchainProof> proof = findOnchainProof(client, outbox, tx, payload);
        if (proof.isEmpty() || proof.get().confirmations() < minOnchainConfirmations) {
            return;
        }

        OnchainProof value = proof.get();
        settlementService.settle(new KfeInboundSettlementService.InboundSettlementProof(
                tx.getId(),
                outbox.getId(),
                "BITCOIN_CORE_MONITOR",
                value.txid(),
                value.txid(),
                value.observedAmountSats(),
                value.confirmations(),
                value.rawPayload()));
    }

    /** Finds a matching transaction by known txid or scans address receipts for sufficient value and finality. */
    private Optional<OnchainProof> findOnchainProof(
            BlockchainClient client,
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            JsonNode payload) {
        String targetAddress = targetAddress(payload);
        String txid = txid(outbox, tx, payload);
        if (txid != null) {
            return loadOnchainTx(client, txid, targetAddress);
        }
        if (targetAddress == null) {
            return Optional.empty();
        }

        JsonNode received = client.getAddressTransactions(targetAddress);
        if (received == null || !received.isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : received) {
            long observed = amountSats(entry);
            int confirmations = confirmations(entry);
            String observedTxid = txidFromReceivedEntry(entry);
            if (observedTxid != null
                    && confirmations >= minOnchainConfirmations
                    && observed >= tx.getGrossAmountSats()) {
                return loadOnchainTx(client, observedTxid, targetAddress)
                        .or(() -> Optional.of(new OnchainProof(
                                observedTxid,
                                observed,
                                confirmations,
                                entry.toString())));
            }
        }
        return Optional.empty();
    }

    /** Loads raw chain evidence and returns empty when RPC cannot provide a usable transaction. */
    private Optional<OnchainProof> loadOnchainTx(
            BlockchainClient client,
            String txid,
            String targetAddress) {
        try {
            JsonNode raw = client.getRawTransaction(txid, true);
            int confirmations = confirmations(raw);
            long observed = amountSats(raw, targetAddress);
            if (observed <= 0L) {
                observed = amountSats(raw);
            }
            return Optional.of(new OnchainProof(txid, observed, confirmations, raw.toString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Queries the external invoice status and settles only provider-confirmed received value. */
    private void inspectLightning(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        LightningInvoiceGateway gateway = lightningInvoiceGateway.getIfAvailable();
        if (gateway == null || !gateway.isLive()) {
            return;
        }

        String paymentRequest = paymentRequest(payload);
        String paymentHash = firstNonBlank(
                tx.getPaymentHash(),
                text(payload, "paymentHash", "payment_hash"),
                looksLikeLightningInvoice(text(payload, "externalReference")) ? null : text(payload, "externalReference"));
        String providerReference = firstNonBlank(
                tx.getProviderReference(),
                outbox.getProviderReference(),
                text(payload, "providerReference", "provider_reference", "invoiceId", "invoice_id"));

        CustodyGateway.IncomingLightningInvoiceStatus status = gateway.getLightningInvoiceStatus(
                new CustodyGateway.LightningInvoiceStatusCommand(
                        tx.getUserId(),
                        null,
                        null,
                        paymentHash,
                        providerReference,
                        paymentRequest));
        if (!isSettled(status.status()) || status.receivedSats() == null) {
            return;
        }
        settlementService.settle(new KfeInboundSettlementService.InboundSettlementProof(
                tx.getId(),
                outbox.getId(),
                gateway.providerName(),
                firstNonBlank(providerReference, paymentHash),
                firstNonBlank(paymentHash, providerReference),
                status.receivedSats(),
                1,
                status.rawPayload()));
    }

    /** Parses persisted command JSON, treating absent or malformed payload as an empty object. */
    private JsonNode payload(KfeExecutionOutboxEntity outbox) {
        if (outbox.getPayloadJson() == null || outbox.getPayloadJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(outbox.getPayloadJson());
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    /** Selects the first valid transaction reference from persisted execution and provider fields. */
    private String txid(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                tx.getBlockchainTxid(),
                tx.getProviderReference(),
                outbox.getProviderReference(),
                text(payload, "txid", "blockchainTxid", "blockchain_txid", "providerReference", "provider_reference"),
                looksLikeTxid(externalReference) ? externalReference : null);
    }

    /** Extracts a receive address without mistaking a txid or Lightning invoice for an address. */
    private String targetAddress(JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                text(payload, "address", "receiveAddress", "receive_address", "destinationAddress", "destination_address"),
                !looksLikeTxid(externalReference) && !looksLikeLightningInvoice(externalReference) ? externalReference : null);
    }

    /** Extracts a Lightning payment request from recognized payload fields or the external reference. */
    private String paymentRequest(JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                text(payload, "paymentRequest", "payment_request", "bolt11", "invoice"),
                looksLikeLightningInvoice(externalReference) ? externalReference : null);
    }

    /** Validates direct or array-form transaction identifiers from an address-history entry. */
    private String txidFromReceivedEntry(JsonNode entry) {
        String direct = text(entry, "txid");
        if (looksLikeTxid(direct)) {
            return direct;
        }
        JsonNode txids = entry.path("txids");
        if (txids.isArray() && txids.size() > 0) {
            String txid = txids.get(0).asText();
            return looksLikeTxid(txid) ? txid : null;
        }
        return null;
    }

    /** Reads nonnegative satoshi fields first, then converts BTC-denominated values. */
    private long amountSats(JsonNode node) {
        long sats = satsField(node, "sats", "satoshis", "amountSats", "amount_sats", "valueSats", "value_sats");
        if (sats > 0L) {
            return sats;
        }
        long amount = amountFromBtcField(node, "amount");
        if (amount > 0L) {
            return amount;
        }
        return amountFromBtcField(node, "value");
    }

    /** Totals only receive details and outputs paying the target address, with whole-transaction value as a fallback. */
    private long amountSats(JsonNode node, String targetAddress) {
        if (targetAddress == null || targetAddress.isBlank()) {
            return amountSats(node);
        }

        long total = 0L;
        JsonNode details = node.path("details");
        if (details.isArray()) {
            for (JsonNode item : details) {
                if ("receive".equalsIgnoreCase(text(item, "category"))
                        && targetAddress.equals(text(item, "address"))) {
                    total += amountSats(item);
                }
            }
        }

        JsonNode vout = node.path("vout");
        if (vout.isArray()) {
            for (JsonNode output : vout) {
                if (scriptPaysAddress(output.path("scriptPubKey"), targetAddress)) {
                    total += amountSats(output);
                }
            }
        }
        return total > 0L ? total : amountSats(node);
    }

    /** Checks direct and legacy array-form script addresses for an exact destination match. */
    private boolean scriptPaysAddress(JsonNode scriptPubKey, String targetAddress) {
        if (targetAddress.equals(text(scriptPubKey, "address"))) {
            return true;
        }
        JsonNode addresses = scriptPubKey.path("addresses");
        if (addresses.isArray()) {
            for (JsonNode address : addresses) {
                if (targetAddress.equals(address.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns a nonnegative integral confirmation count, defaulting malformed values to zero. */
    private int confirmations(JsonNode node) {
        JsonNode confirmations = node.path("confirmations");
        return confirmations.isIntegralNumber() ? Math.max(0, confirmations.asInt()) : 0;
    }

    /** Reads the first usable integral or numeric-string satoshi property without allowing negative values. */
    private long satsField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Math.max(0L, value.asLong());
            }
            if (value.isTextual()) {
                try {
                    return Math.max(0L, Long.parseLong(value.asText()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    /** Converts a positive numeric BTC value to satoshis, rounding fractional satoshis down. */
    private long amountFromBtcField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return 0L;
        }
        BigDecimal btc = value.decimalValue();
        if (btc.signum() <= 0) {
            return 0L;
        }
        return btc.multiply(SATOSHIS_PER_BTC)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    /** Recognizes provider terminal-success labels after case and whitespace normalization. */
    private boolean isSettled(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "SETTLED", "CONFIRMED", "PAID", "SUCCEEDED", "COMPLETE", "COMPLETED" -> true;
            default -> false;
        };
    }

    /** Validates a 64-character hexadecimal transaction identifier. */
    private boolean looksLikeTxid(String value) {
        return value != null && TXID.matcher(value.trim()).matches();
    }

    /** Recognizes mainnet, testnet, and regtest BOLT11 invoice prefixes. */
    private boolean looksLikeLightningInvoice(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt");
    }

    /** Returns the first nonblank textual property among candidate JSON field names. */
    private String text(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    /** Returns the first nonblank candidate after trimming, or null when none is available. */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Immutable verified Bitcoin receipt evidence used to authorize inbound settlement.
     * @param txid canonical Bitcoin transaction identifier
     * @param observedAmountSats amount observed for the candidate receipt in satoshis
     * @param confirmations confirmation count reported for the transaction
     * @param rawPayload provider evidence retained for audit and reconciliation
     */
    private record OnchainProof(
            String txid,
            long observedAmountSats,
            int confirmations,
            String rawPayload) {
    }
}
