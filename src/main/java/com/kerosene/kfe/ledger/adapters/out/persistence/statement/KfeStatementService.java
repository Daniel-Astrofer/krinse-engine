package com.kerosene.kfe.ledger.adapters.out.persistence.statement;

import com.kerosene.kfe.messaging.adapters.out.websocket.TransactionEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeUserStatementEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 24h operational statement buffer with <b>one row per (user, transaction)</b>.
 *
 * <p>Cohesion rules:
 * <ul>
 *   <li>Upsert on every status/conf update — never insert a second row for the same tx.
 *   <li>{@code created_at} is fixed (ledger time) so order never jumps.
 *   <li>{@code updated_at} + payload change so the client merges in place.
 * </ul>
 *
 * <p>Uses PostgreSQL {@code ON CONFLICT} for concurrent writers. Joins the <b>caller
 * transaction</b> ({@link Propagation#REQUIRED}) so a statement written during submit
 * can see the still-uncommitted {@code transactions_master} row (FK). {@code REQUIRES_NEW}
 * was tried and rejected: it cannot see the parent insert and blew up submit with
 * {@code UnexpectedRollbackException} after device-key approval already returned 200.
 */
@Service
public class KfeStatementService {

    /** Logger for statement persistence and best-effort projection failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeStatementService.class);

    /** Repository used by the fallback JPA path and uniqueness-race recovery. */
    private final KfeUserStatementRepository statementRepository;
    /** Serializer for the participant-visible payload stored as JSON. */
    private final ObjectMapper objectMapper;
    /** Provides the native PostgreSQL upsert and flush needed for the transaction-row foreign key. */
    private final EntityManager entityManager;
    /** Optional publisher that emits the statement projection after the surrounding transaction commits. */
    private final TransactionEventPublisher transactionEventPublisher;
    /** Self proxy used so internal calls pass through Spring transaction interception. */
    private final KfeStatementService self;

    /**
     * Creates the statement writer with native SQL when an entity manager is available and JPA as
     * its persistence fallback. The lazy self-reference preserves Spring transaction interception
     * for methods that delegate to the required-transaction writer.
     *
     * @param statementRepository statement lookup and JPA persistence adapter
     * @param objectMapper JSON encoder for display payloads
     * @param entityManager persistence context used by the PostgreSQL upsert, when available
     * @param transactionEventPublisher optional after-commit event publisher provider
     * @param self lazy proxied reference used for transactional internal delegation
     */
    public KfeStatementService(
            KfeUserStatementRepository statementRepository,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            ObjectProvider<TransactionEventPublisher> transactionEventPublisher,
            @Lazy KfeStatementService self) {
        this.statementRepository = statementRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.transactionEventPublisher = transactionEventPublisher.getIfAvailable();
        this.self = self != null ? self : this;
    }

    /**
     * Upsert statement for the user/tx pair. Safe on every lifecycle stage.
     * Must not throw into the financial path when possible — callers also wrap.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordUserStatement(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        // Flush parent tx row so native INSERT FK sees it in this connection.
        if (entityManager != null) {
            try {
                entityManager.flush();
            } catch (RuntimeException ignored) {
                // Entity may already be clean; native path still tries.
            }
        }
        try {
            if (entityManager != null) {
                nativeUpsert(userId, walletId, transaction, payload);
            } else {
                jpaUpsert(userId, walletId, transaction, payload);
            }
            publishTransactionEvent(userId, payload);
        } catch (RuntimeException first) {
            // Unique race only: re-load and update. Do NOT swallow FK / other errors that
            // leave the JDBC connection aborted — rethrow so caller can fail cleanly
            // rather than UnexpectedRollbackException later.
            if (!isUniqueViolation(first)) {
                log.error(
                        "KFE statement upsert failed userId={} txId={}: {}",
                        userId,
                        transaction.getId(),
                        first.getMessage());
                throw first;
            }
            log.debug(
                    "KFE statement unique race userId={} txId={} — JPA update",
                    userId,
                    transaction.getId());
            try {
                jpaUpsert(userId, walletId, transaction, payload);
                publishTransactionEvent(userId, payload);
            } catch (DataIntegrityViolationException race) {
                jpaUpsert(userId, walletId, transaction, payload);
                publishTransactionEvent(userId, payload);
            }
        }
    }

    /**
     * Schedules a participant transaction event after commit when both publisher and payload exist.
     * A missing optional publisher or payload intentionally suppresses this display-only side effect.
     *
     * @param userId statement owner to notify
     * @param payload display projection sent to connected clients
     */
    private void publishTransactionEvent(Long userId, Map<String, ?> payload) {
        if (transactionEventPublisher == null || payload == null) {
            return;
        }
        transactionEventPublisher.publishAfterCommit(userId, payload);
    }

    /**
     * Best-effort statement write that never poisons the caller transaction.
     * Used from outbox/monitor paths where display cache is secondary.
     */
    public void recordUserStatementBestEffort(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        try {
            self.recordUserStatement(userId, walletId, transaction, payload);
        } catch (RuntimeException exception) {
            log.error(
                    "KFE statement best-effort abandoned userId={} txId={}: {}",
                    userId,
                    transaction != null ? transaction.getId() : null,
                    exception.getMessage());
        }
    }

    /**
     * Persists a statement only when the user/transaction key is absent, then delegates through the
     * Spring proxy so the normal upsert and event rules remain centralized.
     *
     * @param userId statement owner
     * @param walletId optional wallet association
     * @param transaction persisted transaction represented by the statement
     * @param payload participant-facing display fields
     */
    @Transactional
    public void recordUserStatementIfAbsent(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        if (statementRepository.existsByUserIdAndTransactionId(userId, transaction.getId())) {
            return;
        }
        self.recordUserStatement(userId, walletId, transaction, payload);
    }

    /**
     * Refreshes the statement display data from a transaction whose owner is present. Invalid or
     * incomplete projections are ignored because this method updates a secondary display cache.
     *
     * @param transaction latest transaction state used for identity and owner
     * @param payload refreshed participant-facing fields
     */
    @Transactional
    public void refreshTransactionDisplayPayload(KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (transaction == null || transaction.getId() == null || payload == null) {
            return;
        }
        Long userId = transaction.getUserId();
        if (userId == null) {
            return;
        }
        self.recordUserStatement(userId, null, transaction, payload);
    }

    /**
     * Inserts or updates the 24-hour projection with PostgreSQL conflict handling. The unique key
     * preserves one row per user/transaction, and conflict updates intentionally retain the first
     * {@code created_at} ordering timestamp while refreshing payload, expiry and update time.
     *
     * @param userId owning participant
     * @param walletId optional wallet to associate; null does not clear an existing wallet on conflict
     * @param transaction source entity supplying transaction identity and original ordering time
     * @param payload fields serialized for participant display
     */
    private void nativeUpsert(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        UUID transactionId = transaction.getId();
        String json = toJson(payload);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(24);
        LocalDateTime orderAt = transaction.getCreatedAt() != null ? transaction.getCreatedAt() : now;
        UUID rowId = UUID.randomUUID();

        entityManager.createNativeQuery("""
                INSERT INTO financial.user_statement_24h
                    (id, user_id, transaction_id, wallet_id, display_payload_json, expires_at, created_at, updated_at)
                VALUES
                    (CAST(:id AS uuid), :userId, CAST(:transactionId AS uuid), CAST(:walletId AS uuid),
                     CAST(:payload AS text), CAST(:expiresAt AS timestamp), CAST(:createdAt AS timestamp),
                     CAST(:updatedAt AS timestamp))
                ON CONFLICT (user_id, transaction_id) DO UPDATE SET
                    wallet_id = COALESCE(EXCLUDED.wallet_id, financial.user_statement_24h.wallet_id),
                    display_payload_json = EXCLUDED.display_payload_json,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at
                """)
                .setParameter("id", rowId.toString())
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId.toString())
                .setParameter("walletId", walletId != null ? walletId.toString() : null)
                .setParameter("payload", json)
                .setParameter("expiresAt", expiresAt)
                .setParameter("createdAt", orderAt)
                .setParameter("updatedAt", now)
                .executeUpdate();
    }

    /**
     * Implements the same one-row-per-user/transaction behavior through repository operations for
     * environments without an {@link EntityManager}. Existing rows keep their original creation
     * ordering; new rows use the transaction creation time when present.
     *
     * @param userId owning participant
     * @param walletId optional wallet association
     * @param transaction source entity supplying identity and ordering time
     * @param payload participant display fields to serialize
     */
    private void jpaUpsert(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        UUID transactionId = transaction.getId();
        String json = toJson(payload);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(24);

        var existing = statementRepository.findByUserIdAndTransactionId(userId, transactionId);
        if (existing.isPresent()) {
            KfeUserStatementEntity statement = existing.get();
            if (walletId != null) {
                statement.setWalletId(walletId);
            }
            statement.setDisplayPayloadJson(json);
            statement.setExpiresAt(expiresAt);
            statement.setUpdatedAt(now);
            statementRepository.save(statement);
            return;
        }

        KfeUserStatementEntity statement = new KfeUserStatementEntity();
        statement.markNew();
        statement.setUserId(userId);
        statement.setWalletId(walletId);
        statement.setTransactionId(transactionId);
        statement.setDisplayPayloadJson(json);
        statement.setExpiresAt(expiresAt);
        LocalDateTime orderAt = transaction.getCreatedAt() != null
                ? transaction.getCreatedAt()
                : now;
        statement.setCreatedAt(orderAt);
        statement.setUpdatedAt(now);
        statementRepository.save(statement);
    }

    /**
     * Recognizes the supported database/driver representations of a unique-key collision so the
     * caller can reload and update the existing projection. Other integrity failures must propagate.
     *
     * @param exception persistence exception or wrapper to inspect through its cause chain
     * @return whether the cause chain indicates a duplicate user/transaction key
     */
    private static boolean isUniqueViolation(Throwable exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null
                    && (message.contains("uq_user_statement_24h_user_tx")
                            || message.contains("duplicate key")
                            || message.contains("Unique index")
                            || message.contains("23505"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return exception instanceof DataIntegrityViolationException
                && exception.getMessage() != null
                && exception.getMessage().contains("duplicate");
    }

    /**
     * Serializes a display payload, representing null as an empty JSON object. Serialization failure
     * falls back to an empty object so malformed display metadata cannot break the financial path.
     *
     * @param payload participant-facing fields, or null for an empty object
     * @return serialized JSON object or {@code {}} if serialization fails
     */
    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }
}
