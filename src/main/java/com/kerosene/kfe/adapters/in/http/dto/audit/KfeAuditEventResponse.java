package com.kerosene.kfe.adapters.in.http.dto.audit;

import java.time.LocalDateTime;
import java.util.UUID;

/** One serialized entry from the append-only KFE audit chain.
 * @param sequenceNumber monotonically increasing position within the chain
 * @param id stable identifier of this audit event
 * @param transactionId transaction associated with the event, when applicable
 * @param walletId wallet associated with the event, when applicable
 * @param eventType domain event name recorded by the audit writer
 * @param fromStatus state before the event, when the event represents a transition
 * @param toStatus state after the event, when the event represents a transition
 * @param payloadHash digest of the canonical event payload
 * @param previousHash digest of the preceding event in the chain
 * @param eventHash digest binding this event to its sequence and predecessor
 * @param createdAt timestamp when the audit event was persisted
 */
public record KfeAuditEventResponse(
        Long sequenceNumber,
        UUID id,
        UUID transactionId,
        UUID walletId,
        String eventType,
        String fromStatus,
        String toStatus,
        String payloadHash,
        String previousHash,
        String eventHash,
        LocalDateTime createdAt) {
}
