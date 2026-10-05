package com.kerosene.kfe.messaging.adapters.out.notification;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.common.financial.notification.FinancialNotificationEventRequest;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;

import java.util.UUID;

/**
 * Converts durable financial-notification outbox rows into typed notification-port calls.
 * Delivery outcomes are persisted with claim-aware transitions, retried up to the configured code
 * ceiling, and quarantined on repeated failure; payload and provider exception details are not logged.
 */
@Service
public class KfeNotificationOutboxProcessor {

    /** Logger deliberately avoids printing payload content or user-specific notification details. */
    private static final Logger log = LoggerFactory.getLogger(KfeNotificationOutboxProcessor.class);
    /** Number of prior attempts after which another delivery failure is quarantined as dead letter. */
    private static final int MAX_RETRIES = 5;
    /** Dedicated payload parser whose configuration is independent of application serialization settings. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** Owns claim-aware delivered, retryable, and dead-letter state transitions. */
    private final KfeFinancialNotificationOutboxService outboxService;
    /** Dispatches typed notification events to the configured financial notification adapter. */
    private final FinancialNotificationPort notificationPort;
    /** Records delivery-success and dead-letter metrics by event type. */
    private final KfeFinancialMetrics financialMetrics;

    /**
     * Creates a notification outbox dispatcher with persistence and observability collaborators.
     *
     * @param outboxService outbox state transition service
     * @param notificationPort notification delivery port
     * @param financialMetrics delivery outcome metrics
     */
    public KfeNotificationOutboxProcessor(
            KfeFinancialNotificationOutboxService outboxService,
            FinancialNotificationPort notificationPort,
            KfeFinancialMetrics financialMetrics) {
        this.outboxService = outboxService;
        this.notificationPort = notificationPort;
        this.financialMetrics = financialMetrics;
    }

    /**
     * Parses and dispatches one claimed or legacy unclaimed outbox entry, then records delivery,
     * retryable failure, or dead-letter state. A claim token is passed back to persistence when present
     * so a stale worker cannot complete another worker's lease; compatibility rows without a claim use
     * the identifier-only repository methods.
     *
     * @param entity outbox snapshot selected for delivery
     */
    public void processDeliverable(KfeFinancialNotificationOutboxEntity entity) {
        UUID outboxId = entity.getId();
        String eventType = entity.getEventType();
        int attempts = entity.getAttempts();
        KfeFinancialNotificationOutboxService.NotificationClaim claim = entity.getClaimToken() == null
                ? null
                : new KfeFinancialNotificationOutboxService.NotificationClaim(outboxId, entity.getClaimToken());

        try {
            Deliverable d = Deliverable.fromJson(entity);
            String rail = d.eventType.startsWith("INTERNAL_TRANSFER_") ? "INTERNAL" : d.rail;
            var event = new FinancialNotificationEventRequest(entity.getEventId(), entity.getSchemaVersion(),
                    FinancialNotificationEventRequest.Type.valueOf(d.eventType), d.userId, d.transactionId,
                    d.walletId, rail, d.amountSats, d.confirmations, d.txid, d.failureCode,
                    d.paymentRequestId, d.publicId, entity.getCreatedAt(), entity.getEventSequence());
            var receipt = notificationPort.deliverEvent(event);
            if (receipt == null || !receipt.accepted() || !event.eventId().equals(receipt.eventId())) {
                throw new IllegalStateException("Financial event was not accepted durably.");
            }
            if (claim == null) {
                outboxService.markDelivered(outboxId);
            } else {
                outboxService.markDelivered(claim);
            }
            financialMetrics.recordNotificationDelivered(eventType);
            log.debug("[KFE Notif Outbox] delivered eventType={} eventId={}", eventType, entity.getEventId());
        } catch (RuntimeException exception) {
            log.warn("[KFE Notif Outbox] delivery failed eventType={} attempts={}", eventType, attempts);
            if ((exception instanceof IllegalArgumentException
                    || exception instanceof org.springframework.web.client.RestClientResponseException response
                       && response.getStatusCode().value() == 409) && attempts >= MAX_RETRIES) {
                if (claim == null) {
                    outboxService.markDeadLetter(outboxId, safeMessage(exception));
                } else {
                    outboxService.markDeadLetter(claim, safeMessage(exception));
                }
                financialMetrics.recordNotificationDeadLetter(eventType);
            } else {
                if (claim == null) {
                    outboxService.markRetryableFailure(outboxId, attempts + 1, safeMessage(exception));
                } else {
                    outboxService.markRetryableFailure(claim, attempts, safeMessage(exception));
                }
            }
        }
    }

    /**
     * Worker-facing alias for {@link #processDeliverable(KfeFinancialNotificationOutboxEntity)}.
     *
     * @param entity deliverable outbox snapshot
     */
    public void process(KfeFinancialNotificationOutboxEntity entity) {
        processDeliverable(entity);
    }

    /**
     * Returns a fixed persistence-safe failure message without leaking provider exception text or payloads.
     *
     * @param exception delivery or parse failure; details intentionally are not exposed
     * @return generic message suitable for retry/dead-letter metadata
     */
    private String safeMessage(Throwable exception) {
        return "Notification delivery failed; retry or quarantine required.";
    }

    /** Normalized typed view of optional fields from one notification outbox payload. */
    private static final class Deliverable {
        /** Event discriminator selecting one notification port operation. */
        final String eventType;
        /** Recipient user identifier copied from the outbox envelope. */
        final Long userId;
        /** Related financial transaction identifier, when the notification represents one. */
        final UUID transactionId;
        /** Wallet identifier decoded from the event payload, when present. */
        final UUID walletId;
        /** Rail label, defaulting to ONCHAIN for legacy payloads without a rail field. */
        final String rail;
        /** Event amount in satoshis, defaulting to zero when omitted. */
        final long amountSats;
        /** Observed confirmation count, defaulting to zero when omitted. */
        final int confirmations;
        /** On-chain transaction ID associated with payment broadcast, when present. */
        final String txid;
        /** Machine-readable payment failure code, when present. */
        final String failureCode;
        /** Human-readable failure detail from payload, falling back to outbox last-error metadata. */
        final String failureMessage;
        /** Payment-request identifier for request-specific deposit notifications. */
        final UUID paymentRequestId;
        /** Public payment-request identifier suitable for client-facing notification context. */
        final String publicId;

        /**
         * Creates the normalized internal delivery payload.
         *
         * @param eventType event discriminator
         * @param userId recipient user identifier
         * @param transactionId related transaction identifier
         * @param walletId related wallet identifier
         * @param rail payment rail label
         * @param amountSats event amount in satoshis
         * @param confirmations observed confirmation count
         * @param txid on-chain transaction ID
         * @param failureCode machine-readable failure code
         * @param failureMessage safe failure explanation
         * @param paymentRequestId related payment request identifier
         * @param publicId public payment request identifier
         */
        Deliverable(String eventType, Long userId, UUID transactionId, UUID walletId,
                   String rail, long amountSats, int confirmations,
                   String txid, String failureCode, String failureMessage,
                   UUID paymentRequestId, String publicId) {
            this.eventType = eventType;
            this.userId = userId;
            this.transactionId = transactionId;
            this.walletId = walletId;
            this.rail = rail;
            this.amountSats = amountSats;
            this.confirmations = confirmations;
            this.txid = txid;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
            this.paymentRequestId = paymentRequestId;
            this.publicId = publicId;
        }

        /**
         * Parses the persisted JSON object into notification fields, applying backward-compatible
         * defaults for optional fields and rejecting missing, malformed, or non-object payloads.
         * Invalid optional UUID strings are treated as absent; required event type/user identity remain
         * sourced from the outbox envelope.
         *
         * @param entity outbox row containing envelope and serialized payload
         * @return normalized event for typed dispatch
         * @throws IllegalArgumentException when the payload is missing or invalid JSON/object data
         */
        static Deliverable fromJson(KfeFinancialNotificationOutboxEntity entity) {
            String eventType = entity.getEventType();
            Long userId = entity.getUserId();
            UUID transactionId = entity.getTransactionId();
            UUID walletId = null;
            String rail = "ONCHAIN";
            long amountSats = 0L;
            int confirmations = 0;
            String txid = null;
            String failureCode = null;
            String failureMessage = null;
            UUID paymentRequestId = null;
            String publicId = null;

            String json = entity.getPayloadJson();
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("Notification payload is missing.");
            }
            if (json != null && !json.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(json);
                    if (node == null || !node.isObject()) {
                        throw new IllegalArgumentException("Notification payload is invalid.");
                    }
                    walletId = uuidOrNull(node, "walletId");
                    if (node.has("rail") && !node.get("rail").isNull()) {
                        rail = node.get("rail").asText();
                    }
                    amountSats = node.has("amountSats") ? node.get("amountSats").asLong() : 0L;
                    confirmations = node.has("confirmations") ? node.get("confirmations").asInt() : 0;
                    txid = textOrNull(node, "txid");
                    failureCode = textOrNull(node, "failureCode");
                    paymentRequestId = uuidOrNull(node, "paymentRequestId");
                    publicId = textOrNull(node, "publicId");
                    if (node.has("failureMessage") && !node.get("failureMessage").isNull()) {
                        failureMessage = node.get("failureMessage").asText();
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("Notification payload is invalid.", e);
                }
            }

            return new Deliverable(eventType, userId, transactionId, walletId,
                    rail, amountSats, confirmations, txid, failureCode, failureMessage,
                    paymentRequestId, publicId);
        }

        /**
         * Reads an optional UUID-valued JSON property without failing the entire event for malformed text.
         *
         * @param node parsed JSON object
         * @param field property name to read
         * @return parsed UUID, or null when absent, null-valued, or malformed
         */
        private static UUID uuidOrNull(JsonNode node, String field) {
            if (node.has(field) && !node.get(field).isNull()) {
                try {
                    return UUID.fromString(node.get(field).asText());
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        /**
         * Reads an optional scalar JSON property as text.
         *
         * @param node parsed JSON object
         * @param field property name to read
         * @return textual value, or null when absent or explicitly null
         */
        private static String textOrNull(JsonNode node, String field) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText();
            }
            return null;
        }
    }
}
