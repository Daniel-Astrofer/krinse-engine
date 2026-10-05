package com.kerosene.kfe.adapters.out.integration.paymentexecution;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.common.financial.operations.FinancialOperationsAdminPort;
import com.kerosene.common.infra.logging.LogSanitizer;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningClient;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditLogRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.KfeFinancialNotificationOutboxRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Builds sanitized operational health, audit-log, and aggregate metric views for financial administration. */
@Component
public class KfeFinancialOperationsAdminAdapter implements FinancialOperationsAdminPort {

    /** Decimal divisor used to convert integer satoshis to Bitcoin with eight fractional places. */
    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");

    /** Optional Bitcoin Core client used for blockchain and mempool health probes. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    /** Optional Lightning client used for node balance and connectivity health probes. */
    private final ObjectProvider<LightningClient> lightningClient;
    /** Repository supplying ordered audit entries for the administrative log view. */
    private final KfeAuditLogRepository auditLogRepository;
    /** Repository supplying transaction state and volume aggregates. */
    private final KfeTransactionRepository transactionRepository;
    /** Repository supplying execution outbox state counts. */
    private final KfeExecutionOutboxRepository outboxRepository;

    /** Optional repository adding payment-request state counts when that subsystem is present. */
    @Autowired(required = false)
    private KfePaymentRequestRepository paymentRequestRepository;

    /** Optional repository adding notification delivery state counts when messaging is present. */
    @Autowired(required = false)
    private KfeFinancialNotificationOutboxRepository notificationOutboxRepository;

    /**
     * Creates the adapter with mandatory audit, transaction, and execution-outbox stores plus
     * lazy optional providers for external payment rails.
     *
     * @param bitcoinCoreRpcClient optional Bitcoin Core health-probe client
     * @param lightningClient optional Lightning health-probe client
     * @param auditLogRepository ordered audit event store
     * @param transactionRepository financial transaction store
     * @param outboxRepository execution outbox store
     */
    public KfeFinancialOperationsAdminAdapter(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<LightningClient> lightningClient,
            KfeAuditLogRepository auditLogRepository,
            KfeTransactionRepository transactionRepository,
            KfeExecutionOutboxRepository outboxRepository) {
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.lightningClient = lightningClient;
        this.auditLogRepository = auditLogRepository;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Probes Bitcoin Core blockchain, mempool, and fee-estimation RPCs and returns a compact health view.
     * Missing configuration and runtime RPC errors are represented as {@code DOWN}; a successful probe
     * with no blocks is {@code DEGRADED}. The response includes chain sync/pruning and mempool fee data.
     *
     * @return operational status map with source, observation time, selected chain/mempool data, and a safe error summary
     */
    @Override
    public Map<String, Object> blockchain() {
        BitcoinCoreRpcClient client = bitcoinCoreRpcClient.getIfAvailable();
        if (client == null) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "message", "Bitcoin Core RPC is not configured");
        }

        try {
            JsonNode chain = unwrap(client.executeRpc("getblockchaininfo"));
            JsonNode mempool = unwrap(client.executeRpc("getmempoolinfo"));
            BlockchainClient.FeeRates feeRates = client.estimateSmartFee(2, 3, 6);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("height", chain.path("blocks").asLong(0));
            state.put("headers", chain.path("headers").asLong(0));
            state.put("bestBlockHash", chain.path("bestblockhash").asText(""));
            state.put("chain", chain.path("chain").asText(""));
            state.put("initialBlockDownload", chain.path("initialblockdownload").asBoolean(false));
            state.put("pruned", chain.path("pruned").asBoolean(false));

            Map<String, Object> mempoolState = new LinkedHashMap<>();
            mempoolState.put("transactions", mempool.path("size").asLong(0));
            mempoolState.put("bytes", mempool.path("bytes").asLong(0));
            mempoolState.put("feesSatPerVByte", Map.of(
                    "fast", feeRates.fastSatPerVByte(),
                    "halfHour", feeRates.halfHourSatPerVByte(),
                    "hour", feeRates.hourSatPerVByte()));

            return Map.of(
                    "status", chain.path("blocks").asLong(0) > 0 ? "UP" : "DEGRADED",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "chain", state,
                    "mempool", mempoolState,
                    "message", "KFE Bitcoin provider probe completed");
        } catch (RuntimeException exception) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "message", "Bitcoin Core RPC probe failed",
                    "exception", exception.getClass().getSimpleName());
        }
    }

    /**
     * Probes the Lightning provider for local/remote/node balances, uptime, and LSP latency.
     * Missing configuration or provider exceptions map to {@code DOWN}; a configured node with
     * nonpositive uptime maps to {@code DEGRADED}.
     *
     * @return operational status map containing the summarized node health or failure category
     */
    @Override
    public Map<String, Object> lightning() {
        LightningClient client = lightningClient.getIfAvailable();
        if (client == null) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "message", "Lightning provider is not configured");
        }

        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("localBalanceSats", client.getLocalBalance());
            state.put("remoteBalanceSats", client.getRemoteBalance());
            state.put("nodeBalanceSats", client.getLightningNodeBalance());
            state.put("uptime", client.getNodeUptime());
            state.put("lspLatencyMs", client.getLspLatency());
            return Map.of(
                    "status", client.getNodeUptime() > 0 ? "UP" : "DEGRADED",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "node", state,
                    "message", "KFE Lightning provider probe completed");
        } catch (RuntimeException exception) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "message", "Lightning provider probe failed",
                    "exception", exception.getClass().getSimpleName());
        }
    }

    /**
     * Returns the newest audit entries, bounded to between one and one hundred rows, after removing
     * raw transaction and wallet identifiers through stable fingerprints.
     *
     * @param limit requested maximum number of rows; values outside 1..100 are clamped
     * @return newest-first list of safe audit fields and pseudonymous transaction/wallet references
     */
    @Override
    public List<Map<String, Object>> logs(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return auditLogRepository.findAllByOrderBySequenceNumberDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toSafeLog)
                .toList();
    }

    /**
     * Computes aggregate transaction volume, fees, ticket size, state/rail counts, and outbox counts.
     * Optional payment-request and notification outbox counts are included only when their repositories
     * are wired. No per-user timeline, destination, transaction ID, invoice content, or wallet name is emitted.
     *
     * @return aggregate metric map with a sampling timestamp and explicit privacy boundary
     */
    @Override
    public Map<String, Object> metrics() {
        List<KfeTransactionEntity> transactions = transactionRepository.findAll();
        List<KfeExecutionOutboxEntity> outboxItems = outboxRepository.findAll();

        Map<KfeTransactionStatus, Long> byStatus = transactions.stream()
                .collect(Collectors.groupingBy(KfeTransactionEntity::getStatus, () -> new EnumMap<>(KfeTransactionStatus.class), Collectors.counting()));
        Map<KfeRail, Long> byRail = transactions.stream()
                .collect(Collectors.groupingBy(KfeTransactionEntity::getRail, () -> new EnumMap<>(KfeRail.class), Collectors.counting()));
        BigDecimal totalVolume = transactions.stream()
                .map(transaction -> satsToBtc(transaction.getGrossAmountSats()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFees = transactions.stream()
                .map(transaction -> satsToBtc(transaction.getKeroseneFeeSats() + transaction.getNetworkFeeSats()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkedAt", Instant.now());
        payload.put("totalVolumeBtc", totalVolume);
        payload.put("totalFeesBtc", totalFees);
        payload.put("totalTransactions", transactions.size());
        payload.put("avgTicketBtc", transactions.isEmpty()
                ? BigDecimal.ZERO
                : totalVolume.divide(BigDecimal.valueOf(transactions.size()), 8, RoundingMode.HALF_UP));
        payload.put("confirmedTransactions", byStatus.getOrDefault(KfeTransactionStatus.SETTLED, 0L));
        payload.put("pendingTransactions", pendingCount(byStatus));
        payload.put("failedTransactions", byStatus.getOrDefault(KfeTransactionStatus.FAILED, 0L));
        payload.put("transactionsByStatus", stringifyKeys(byStatus));
        payload.put("transactionsByRail", stringifyKeys(byRail));
        payload.put("executionOutboxByStatus", outboxItems.stream()
                .collect(Collectors.groupingBy(KfeExecutionOutboxEntity::getStatus, Collectors.counting())));
        if (paymentRequestRepository != null) {
            payload.put("paymentRequestsByStatus", StreamSupport.stream(
                            paymentRequestRepository.findAll().spliterator(), false)
                    .collect(Collectors.groupingBy(request -> request.getStatus().name(), Collectors.counting())));
        }
        if (notificationOutboxRepository != null) {
            payload.put("notificationOutboxByStatus", StreamSupport.stream(
                            notificationOutboxRepository.findAll().spliterator(), false)
                    .collect(Collectors.groupingBy(notification -> notification.getStatus(), Collectors.counting())));
        }
        payload.put("privacyBoundary",
                "Aggregate KFE metrics only; no user timeline, destination, txid, invoice payload, or wallet name.");
        return payload;
    }

    /** Projects one persisted audit event to approved fields while fingerprinting wallet and transaction IDs. */
    private Map<String, Object> toSafeLog(KfeAuditLogEntity event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequenceNumber", event.getSequenceNumber());
        row.put("id", event.getId());
        row.put("createdAt", event.getCreatedAt());
        row.put("eventType", event.getEventType());
        row.put("transactionRef", LogSanitizer.fingerprint(event.getTransactionId() != null ? event.getTransactionId().toString() : null));
        row.put("walletRef", LogSanitizer.fingerprint(event.getWalletId() != null ? event.getWalletId().toString() : null));
        row.put("payloadHash", event.getPayloadHash());
        row.put("eventHash", event.getEventHash());
        return row;
    }

    /**
     * Adds statuses representing work that has not reached a terminal success or failure state.
     * Reconciliation-required transactions are counted as pending because an operator action may remain.
     *
     * @param byStatus aggregate count indexed by transaction status
     * @return combined count for intent, validation, quorum, lock, execution, and reconciliation states
     */
    private long pendingCount(Map<KfeTransactionStatus, Long> byStatus) {
        return byStatus.getOrDefault(KfeTransactionStatus.INTENT, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.VALIDATING, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.QUORUM_SYNC, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.LOCKED, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.EXECUTING, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.REQUIRES_RECONCILIATION, 0L);
    }

    /** Converts satoshis to BTC using eight decimal places and half-up rounding. */
    private BigDecimal satsToBtc(long sats) {
        return BigDecimal.valueOf(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    /** Returns the nested Bitcoin Core JSON-RPC result when present, otherwise the original response node. */
    private JsonNode unwrap(JsonNode response) {
        if (response != null && response.has("result")) {
            return response.get("result");
        }
        return response;
    }

    /** Converts enum or other aggregate keys to strings while preserving their associated counts and order. */
    private Map<String, Long> stringifyKeys(Map<?, Long> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
