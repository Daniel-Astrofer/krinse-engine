package com.kerosene.kfe.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.kerosene.common.infra.logging.StructuredLogEvent;
import com.kerosene.kfe.audit.domain.KfeAuditEvent;

import java.util.UUID;

/**
 * Emits lightweight structured JSON audit events to the "kerosene.audit.financial" log stream.
 *
 * <p>Complements {@code KfeAuditLogService} (chain-hashed DB audit). This logger records
 * only sanitized metadata — never bearer tokens, macaroons, full invoices, preimages,
 * PSBT, raw transactions, or PII.
 *
 * <p>Usage:
 * <pre>{@code
 * auditEventLogger.logStateTransition(txId, walletId, "LOCKED", "EXECUTING", amountSats, "ONCHAIN");
 * auditEventLogger.logSettlement(txId, walletId, amountSats, feeSats, "ONCHAIN");
 * auditEventLogger.logConflict(txId, walletId, "replacementTxidHash", "CONFLICTED_DOUBLE_SPEND", "ONCHAIN");
 * }</pre>
 */
@Component
public class KfeAuditEventLogger {

    /** Dedicated structured-log category for sanitized financial audit metadata. */
    private static final Logger log = LoggerFactory.getLogger("kerosene.audit.financial");

    // --- State machine transitions ---

    /**
     * Emits an audit event describing a transaction state transition and its amount/rail context.
     *
     * @param eventType stable audit event type
     * @param transactionId affected transaction identifier
     * @param walletId associated wallet identifier
     * @param previousStatus state before transition
     * @param newStatus state after transition
     * @param amountSats transaction amount in satoshis
     * @param rail payment rail label
     */
    public void logStateTransition(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String previousStatus,
            String newStatus,
            long amountSats,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .amountSats(amountSats)
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Settlement operations ---

    /**
     * Emits settlement metadata without recording invoice, transaction, or signing payloads.
     *
     * @param eventType stable audit event type
     * @param transactionId settled transaction identifier
     * @param walletId associated wallet identifier
     * @param amountSats settled amount in satoshis
     * @param feeSats network/provider fee in satoshis
     * @param network blockchain/network label
     * @param rail payment rail label
     * @param referenceHash sanitized hash of the external settlement reference
     */
    public void logSettlement(
            String eventType,
            UUID transactionId,
            UUID walletId,
            long amountSats,
            long feeSats,
            String network,
            String rail,
            String referenceHash) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .amountSats(amountSats)
                .feeSats(feeSats)
                .network(network)
                .rail(rail)
                .referenceHash(referenceHash)
                .build();
        emit(event);
    }

    // --- Conflict / reorg events ---

    /**
     * Emits a conflict event with the sanitized reason and external reference hash.
     *
     * @param eventType stable audit event type
     * @param transactionId affected transaction identifier
     * @param walletId associated wallet identifier
     * @param referenceHash hash of the conflicting external reference
     * @param reason conflict classification stored as the previous-status field
     * @param rail payment rail label
     */
    public void logConflict(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String referenceHash,
            String reason,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .referenceHash(referenceHash)
                .rail(rail)
                .previousStatus(reason)
                .build();
        emit(event);
    }

    /**
     * Emits a chain reorganization event with the previous and newly observed confirmations.
     *
     * @param transactionId affected transaction identifier
     * @param walletId associated wallet identifier
     * @param previousConfirmations last recorded confirmation count
     * @param currentConfirmations newly observed confirmation count
     * @param rail payment rail label
     */
    public void logReorg(
            UUID transactionId,
            UUID walletId,
            int previousConfirmations,
            int currentConfirmations,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType("KFE_REORG_DETECTED")
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(String.valueOf(previousConfirmations))
                .newStatus(String.valueOf(currentConfirmations))
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Reconciliation operations ---

    /**
     * Emits a reconciliation event with the reason and rail context.
     *
     * @param eventType stable audit event type
     * @param transactionId transaction being reconciled
     * @param walletId associated wallet identifier
     * @param reason reconciliation trigger or outcome classification
     * @param rail payment rail label
     */
    public void logReconciliation(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String reason,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(reason)
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Generic ---

    /**
     * Emits an already-assembled audit event through the same sanitized structured-log projection.
     *
     * @param event event carrying financial audit metadata
     */
    public void log(KfeAuditEvent event) {
        emit(event);
    }

    /**
     * Projects allowed event fields into structured logs and omits null/zero optional values.
     * Sensitive secrets and payment payloads are intentionally excluded from this projection.
     *
     * @param event financial audit event to serialize
     */
    private void emit(KfeAuditEvent event) {
        StructuredLogEvent structured = StructuredLogEvent.of(
                        event.eventType(),
                        "financial-audit",
                        "audit",
                        "Financial audit event")
                .field("audit.eventId", event.eventId())
                .field("audit.eventType", event.eventType())
                .field("audit.transactionId", event.transactionId())
                .field("audit.walletId", event.walletId());

        if (event.principalId() != null) {
            structured.field("audit.principalId", event.principalId());
        }
        if (event.previousStatus() != null) {
            structured.field("audit.previousStatus", event.previousStatus());
        }
        if (event.newStatus() != null) {
            structured.field("audit.newStatus", event.newStatus());
        }
        if (event.amountSats() > 0L) {
            structured.field("audit.amountSats", event.amountSats());
        }
        if (event.feeSats() > 0L) {
            structured.field("audit.feeSats", event.feeSats());
        }
        if (event.network() != null) {
            structured.field("audit.network", event.network());
        }
        if (event.rail() != null) {
            structured.field("audit.rail", event.rail());
        }
        if (event.referenceHash() != null) {
            structured.field("audit.referenceHash", event.referenceHash());
        }
        if (event.requestId() != null) {
            structured.field("audit.requestId", event.requestId());
        }
        if (event.correlationId() != null) {
            structured.field("audit.correlationId", event.correlationId());
        }
        structured.field("audit.occurredAt", event.occurredAt().toString());

        log.info("audit.event", structured.arguments());
    }
}
