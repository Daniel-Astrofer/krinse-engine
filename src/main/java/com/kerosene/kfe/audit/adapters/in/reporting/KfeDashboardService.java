package com.kerosene.kfe.audit.adapters.in.reporting;

import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeDashboardResponse;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeDashboardWallet;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeStatementItem;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeUserStatementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeDashboardWalletRow;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeUserStatementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.bootstrap.time.Utc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the wallet dashboard from persisted wallet aggregates and recent user-visible activity.
 * Live transaction rows take precedence over the expiring statement projection when available.
 */
@Service
public class KfeDashboardService {

    /** Repository projecting wallet balances and metadata for one user. */
    private final KfeWalletRepository walletRepository;
    /** Expiring statement snapshot repository used when live activity is empty. */
    private final KfeUserStatementRepository statementRepository;
    /** Live transaction source used to rebuild current recent activity. */
    private final KfeTransactionRepository transactionRepository;
    /** Shared mapper for privacy-filtered transaction and wallet labels. */
    private final KfeResponseMapper responseMapper;
    /** JSON codec for statement payload parsing and serialization. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the dashboard service with wallet, ledger, projection, and mapping dependencies.
     *
     * @param walletRepository source of user-scoped wallet balance rows
     * @param statementRepository fallback source of unexpired statement snapshots
     * @param transactionRepository live participant-visible activity source
     * @param responseMapper display-safe labels and transaction payload mapper
     * @param objectMapper JSON codec for statement payloads
     */
    public KfeDashboardService(
            KfeWalletRepository walletRepository,
            KfeUserStatementRepository statementRepository,
            KfeTransactionRepository transactionRepository,
            KfeResponseMapper responseMapper,
            ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns user wallets, balance totals, and up to 25 recent activity entries.
     * Spendable totals include available, pending, and locked buckets of spendable wallets;
     * observed-only balances are aggregated separately.
     *
     * @param userId owner whose dashboard is requested
     * @return dashboard wallet rows, recent statement, and spendable/observed/combined totals
     */
    @Transactional(readOnly = true)
    public KfeDashboardResponse dashboard(Long userId) {
        List<KfeDashboardWallet> wallets = walletRepository.findDashboardRows(userId).stream()
                .map(this::toWallet)
                .toList();
        long spendable = wallets.stream()
                .filter(KfeDashboardWallet::spendable)
                .mapToLong(wallet -> wallet.availableSats() + wallet.pendingSats() + wallet.lockedSats())
                .sum();
        long observed = wallets.stream()
                .filter(wallet -> !wallet.spendable())
                .mapToLong(KfeDashboardWallet::observedSats)
                .sum();
        // Prefer live transactions_master (full labels, stable createdAt/order).
        List<KfeStatementItem> statement = buildLiveStatement(userId);
        if (statement.isEmpty()) {
            statement = statementRepository
                    .findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
                            userId, LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .stream()
                    .map(this::toStatementItem)
                    .toList();
        }
        return new KfeDashboardResponse(wallets, statement, spendable, observed, spendable + observed);
    }

    /**
     * Rebuilds recent activity from participant-visible live ledger rows.
     * Each transaction is represented by its transaction ID; the repository's created-time ordering
     * remains stable when later status updates change {@code updatedAt}.
     *
     * @param userId participant whose activity should be visible
     * @return mapped ledger items, limited by the repository request to forty rows
     */
    private List<KfeStatementItem> buildLiveStatement(Long userId) {
        List<KfeTransactionEntity> rows = transactionRepository.findParticipantVisibleByUserId(
                userId,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                PageRequest.of(0, 40));
        List<KfeStatementItem> out = new ArrayList<>(rows.size());
        for (KfeTransactionEntity tx : rows) {
            Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, userId));
            UUID walletId = tx.getDirection() == KfeDirection.INBOUND
                    ? tx.getDestinationWalletId()
                    : (tx.getSourceWalletId() != null ? tx.getSourceWalletId() : tx.getDestinationWalletId());
            String status = tx.getStatus() != null ? tx.getStatus().name() : null;
            Instant createdAt = Utc.toInstant(tx.getCreatedAt());
            Instant updatedAt = Utc.toInstant(tx.getUpdatedAt());
            out.add(new KfeStatementItem(
                    tx.getId(),
                    tx.getId(),
                    walletId,
                    status,
                    KfeTransactionStatus.displayStatusOf(tx.getStatus()),
                    toJson(payload),
                    createdAt,
                    updatedAt,
                    null));
        }
        return out;
    }

    /**
     * Converts an expiring statement projection into the dashboard response shape.
     * Payload timestamps take precedence; persistence timestamps provide UTC fallbacks.
     *
     * @param item persisted user statement row
     * @return normalized response item with optional projection expiry
     */
    private KfeStatementItem toStatementItem(KfeUserStatementEntity item) {
        JsonNode root = readJson(item.getDisplayPayloadJson());
        String status = text(root, "status");
        Instant payloadCreated = instant(root, "createdAt");
        Instant payloadUpdated = instant(root, "updatedAt");
        // Prefer ledger time from payload; fall back to row created_at (UTC wall).
        Instant createdAt = payloadCreated != null ? payloadCreated : Utc.toInstant(item.getCreatedAt());
        Instant updatedAt = payloadUpdated != null
                ? payloadUpdated
                : Utc.toInstant(item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt());
        return new KfeStatementItem(
                item.getTransactionId(),
                item.getTransactionId(),
                item.getWalletId(),
                status,
                KfeTransactionStatus.displayStatusOf(status),
                item.getDisplayPayloadJson(),
                createdAt,
                updatedAt,
                Utc.toInstant(item.getExpiresAt()));
    }

    /**
     * Parses statement JSON and returns an empty object instead of failing dashboard rendering.
     *
     * @param json serialized display payload
     * @return parsed JSON tree or an empty JSON object for absent/invalid content
     */
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Reads a nonblank textual value from a JSON field.
     *
     * @param root JSON object to inspect
     * @param field property name
     * @return field text or null when absent, null-valued, or blank
     */
    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        String value = root.get(field).asText(null);
        return value != null && !value.isBlank() ? value : null;
    }

    /**
     * Parses a textual ISO-8601 instant without letting malformed display metadata fail the request.
     *
     * @param root JSON payload
     * @param field timestamp property
     * @return parsed instant or null when absent/invalid
     */
    private static Instant instant(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Serializes a display payload, substituting an empty object for null or serialization failure.
     *
     * @param payload fields produced by the response mapper
     * @return JSON text, or {@code {}} when unavailable
     */
    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }

    /**
     * Maps a wallet projection into dashboard buckets according to wallet custody kind.
     * Watch-only balances remain observed and nonspendable; internal wallets do not contribute
     * observed balances, while other kinds preserve their observed amount.
     *
     * @param row repository projection for one wallet
     * @return API wallet summary with null balances normalized to zero
     */
    private KfeDashboardWallet toWallet(KfeDashboardWalletRow row) {
        KfeWalletKind kind = walletKind(row.getKind());
        if (kind == KfeWalletKind.WATCH_ONLY) {
            return new KfeDashboardWallet(
                    row.getWalletId(),
                    row.getKind(),
                    row.getStatus(),
                    row.getLabel(),
                    row.getLabel(),
                    responseMapper.walletTypeDescription(kind),
                    row.getAsset(),
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    value(row.getObservedSats()),
                    row.getActiveAddress(),
                    Utc.toInstant(row.getCreatedAt()),
                    Utc.toInstant(row.getUpdatedAt()));
        }
        long observed = kind == KfeWalletKind.INTERNAL ? 0L : value(row.getObservedSats());
        return new KfeDashboardWallet(
                row.getWalletId(),
                row.getKind(),
                row.getStatus(),
                row.getLabel(),
                row.getLabel(),
                responseMapper.walletTypeDescription(kind),
                row.getAsset(),
                Boolean.TRUE.equals(row.getSpendable()),
                value(row.getAvailableSats()),
                value(row.getPendingSats()),
                value(row.getLockedSats()),
                value(row.getAutoHoldSats()),
                observed,
                row.getActiveAddress(),
                Utc.toInstant(row.getCreatedAt()),
                Utc.toInstant(row.getUpdatedAt()));
    }

    /**
     * Normalizes a nullable aggregate amount returned by a grouped database projection.
     *
     * @param value nullable balance or transaction aggregate
     * @return stored amount, or zero when the SQL projection is null
     */
    private long value(Long value) {
        return value != null ? value : 0L;
    }

    /**
     * Parses a persisted wallet kind and uses INTERNAL as the conservative unknown-value fallback.
     *
     * @param value stored enum text
     * @return recognized wallet kind, or INTERNAL when missing or invalid
     */
    private KfeWalletKind walletKind(String value) {
        if (value == null || value.isBlank()) {
            return KfeWalletKind.INTERNAL;
        }
        try {
            return KfeWalletKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return KfeWalletKind.INTERNAL;
        }
    }
}
