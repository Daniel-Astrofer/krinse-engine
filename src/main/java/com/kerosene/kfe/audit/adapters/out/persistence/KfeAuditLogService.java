package com.kerosene.kfe.audit.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.audit.AuditEventPayloadSanitizer;
import com.kerosene.common.audit.AuditEventType;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditLogRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Persists append-only, hash-chained financial audit events and emits sanitized structured records.
 * Transaction propagation is deliberately controlled by separate caller-transaction and forensic APIs.
 */
@Service
public class KfeAuditLogService {

    /** Previous-hash value used by the first event in the append-only audit chain. */
    private static final String GENESIS_HASH = "0".repeat(64);

    /** Repository enforcing sequence allocation and serialization of the global audit appender. */
    private final KfeAuditLogRepository repository;
    /** SHA-256 service used for payload and chained event digests. */
    private final KfeHashService hashService;
    /** JSON serializer used to canonicalize sanitized payload maps before hashing. */
    private final ObjectMapper objectMapper;
    /** Structured audit sink notified after the database entity has been persisted. */
    private final StructuredAuditLogger auditLogger;

    /**
     * Creates the append-only audit service with persistence, hashing, serialization, and logging ports.
     *
     * @param repository event store and global appender lock
     * @param hashService payload/event hash implementation
     * @param objectMapper serializer for sanitized payload content
     * @param auditLogger structured logger for persisted event metadata
     */
    public KfeAuditLogService(
            KfeAuditLogRepository repository,
            KfeHashService hashService,
            ObjectMapper objectMapper,
            StructuredAuditLogger auditLogger) {
        this.repository = repository;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
        this.auditLogger = auditLogger;
    }

    /**
     * Append-only audit event in the caller's transaction.
     *
     * <p>Must join the outer submit TX when {@code transactionId} points at a row that is not
     * committed yet — {@link Propagation#REQUIRES_NEW} would violate
     * {@code financial_audit_log_transaction_id_fkey} and surface as a generic client error
     * ("não conseguimos concluir essa solicitação").
     *
     * <p>The global audit appender lock is xact-scoped; callers must not call
     * {@link #recordInNewTransaction} while this lock is held (see settlement-gate path).
     */
    @Transactional
    public KfeAuditLogEntity record(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        return persist(eventType, transactionId, walletId, fromStatus, toStatus, redactedPayload);
    }

    /**
     * Forensic audit in a <strong>new</strong> transaction (survives outer rollback).
     *
     * <p>Only safe when the outer transaction does <em>not</em> already hold
     * {@code GLOBAL_AUDIT_APPENDER}. Prefer {@link #record} from inside submit, or schedule this
     * after the outer TX has released its locks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KfeAuditLogEntity recordInNewTransaction(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        return persist(eventType, transactionId, walletId, fromStatus, toStatus, redactedPayload);
    }

    /**
     * Sanitizes and hashes payload, serializes the global chain append, persists the event, then logs it.
     * The caller's transaction boundary is retained so event references can point to uncommitted rows.
     *
     * @param eventType known audit event type
     * @param transactionId associated transaction, when applicable
     * @param walletId associated wallet, when applicable
     * @param fromStatus previous transaction status
     * @param toStatus resulting transaction status
     * @param redactedPayload payload to sanitize before storage/hash/logging
     * @return saved entity with sequence number and chained hashes
     */
    private KfeAuditLogEntity persist(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        AuditEventType auditEventType = AuditEventType.requireKnown(eventType);
        Map<String, Object> sanitizedPayload = AuditEventPayloadSanitizer.sanitize(redactedPayload);
        String payloadHash = hashService.sha256(toJson(sanitizedPayload));
        repository.lockAuditAppender();
        String previousHash = repository.findTopByOrderBySequenceNumberDesc()
                .map(KfeAuditLogEntity::getEventHash)
                .orElse(GENESIS_HASH);

        KfeAuditLogEntity event = new KfeAuditLogEntity();
        event.setEventType(auditEventType.name());
        event.setTransactionId(transactionId);
        event.setWalletId(walletId);
        event.setFromStatus(fromStatus != null ? fromStatus.name() : null);
        event.setToStatus(toStatus != null ? toStatus.name() : null);
        event.setPayloadHash(payloadHash);
        event.setPreviousHash(previousHash);
        event.setEventHash(hashService.sha256(previousHash + "|" + payloadHash + "|" + auditEventType.name()
                + "|" + transactionId + "|" + walletId + "|" + toStatus));
        KfeAuditLogEntity saved = repository.save(event);
        auditLogger.persisted(
                auditEventType,
                saved.getSequenceNumber(),
                saved.getId(),
                saved.getTransactionId(),
                saved.getWalletId(),
                saved.getFromStatus(),
                saved.getToStatus(),
                saved.getPayloadHash(),
                saved.getEventHash(),
                sanitizedPayload);
        return saved;
    }

    /**
     * Serializes a sanitized payload map; null or serialization failure produces an empty JSON object.
     *
     * @param payload safe payload fields to serialize
     * @return JSON text used as input to the payload digest
     */
    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }

}
