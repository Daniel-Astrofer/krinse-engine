package com.kerosene.kfe.messaging.adapters.out.persistence;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;

import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.KfeFinancialNotificationOutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists financial notification events and coordinates idempotent enqueue, fenced worker claims,
 * retry scheduling, dead-letter transitions, and delivery acknowledgement.
 */
@Service
public class KfeFinancialNotificationOutboxService {

    /** Persisted statuses eligible for a due-event claim. */
    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    /** Claim lease duration; abandoned events become claimable after this interval. */
    private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);
    /** Serializer dedicated to the persisted notification payload schema. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Fencing token binding worker completion to the exact outbox row claim.
     *
     * @param outboxId row being processed
     * @param claimToken unguessable token assigned to this lease
     */
    public record NotificationClaim(UUID outboxId, UUID claimToken) {
        /**
         * Rejects incomplete claim identity before any claim-aware state transition is attempted.
         *
         * @param outboxId row being processed
         * @param claimToken current lease token
         */
        public NotificationClaim {
            java.util.Objects.requireNonNull(outboxId, "outboxId is required");
            java.util.Objects.requireNonNull(claimToken, "claimToken is required");
        }
    }

    /** Executes deduplicated insert, fenced claim, and status-transition database operations. */
    private final KfeFinancialNotificationOutboxRepository repository;

    /** Optional metrics adapter; persistence does not depend on metrics wiring. */
    @Autowired(required = false)
    private KfeFinancialMetrics metrics;

    /** @param repository notification outbox repository */
    public KfeFinancialNotificationOutboxService(KfeFinancialNotificationOutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * Enqueues a versioned message envelope once per message ID, returning the existing row on replay.
     * A unique-key race is resolved by reloading the winner after flush.
     *
     * @param message validated message envelope carrying event and trace identity
     * @param userId positive notification recipient
     * @param transactionId associated financial transaction, if any
     * @return existing or newly persisted outbox record
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public KfeFinancialNotificationOutboxEntity enqueue(
            MessageEnvelope message, long userId, UUID transactionId) {
        java.util.Objects.requireNonNull(message, "message is required");
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return insertEvent(message.messageId(), userId, transactionId, message.type(), message.payload(),
                message.schemaVersion(), message.correlationId(), message.causationId());
    }

    /**
     * Claims at most 100 due rows using a five-minute lease and per-row fencing token.
     * Candidate selection and update are fenced in persistence so concurrent workers cannot own the
     * same live claim; expired leases become eligible for later recovery.
     *
     * @param workerId diagnostic owner label, normalized and length-limited
     * @return immutable list of rows successfully claimed by this invocation
     */
    @Transactional
    public List<KfeFinancialNotificationOutboxEntity> claimDue(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        Instant now = Instant.now();
        Instant claimedUntil = now.plus(CLAIM_DURATION);
        return repository.findClaimCandidatesFenced(DUE_STATUSES, now, org.springframework.data.domain.PageRequest.of(0, 100))
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now, claimedUntil))
                .flatMap(Optional::stream)
                .toList();
    }

    /** Enqueues a financial event in the caller's transaction without a uniqueness-error rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public KfeFinancialNotificationOutboxEntity enqueue(UUID eventId, Long userId, UUID transactionId,
                                                        String eventType, Map<String, ?> payload) {
        if (eventId == null || userId == null || userId <= 0 || eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("Notification identity, positive recipient and type are required.");
        return insertEvent(eventId, userId, transactionId, eventType.trim().toUpperCase(Locale.ROOT),
                toJson(payload), 1, null, null);
    }

    private KfeFinancialNotificationOutboxEntity insertEvent(UUID eventId, long userId, UUID transactionId,
                                                             String type, String payload, int version,
                                                             UUID correlationId, UUID causationId) {
        java.util.Objects.requireNonNull(transactionId, "Financial transaction identity is required.");
        Optional<KfeFinancialNotificationOutboxEntity> existing = repository.findByEventId(eventId);
        int inserted = 0;
        if (existing.isEmpty()) {
            repository.flush();
            long sequence = repository.nextEventSequence(transactionId);
            inserted = repository.insertEventIfAbsent(UUID.randomUUID(), eventId, userId, transactionId,
                    type, payload, version, correlationId, causationId, Instant.now(), sequence);
        }
        if (inserted != 0 && inserted != 1) throw new IllegalStateException("Unexpected outbox insert result.");
        KfeFinancialNotificationOutboxEntity stored = repository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("Outbox event was not persisted."));
        if (!java.util.Objects.equals(stored.getUserId(), userId)
                || !java.util.Objects.equals(stored.getTransactionId(), transactionId)
                || !java.util.Objects.equals(stored.getEventType(), type)
                || stored.getSchemaVersion() != version
                || !samePayload(stored.getPayloadJson(), payload)) {
            throw new IllegalStateException("Outbox event identity was reused with different content.");
        }
        if (inserted == 1 && metrics != null) metrics.recordNotificationEnqueued(type);
        return stored;
    }

    private boolean samePayload(String first, String second) {
        try {
            return MAPPER.readTree(first).equals(MAPPER.readTree(second));
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Notification payload is invalid.", invalid);
        }
    }

    /**
     * Derives a deterministic name-based UUID so repeated producers converge on one outbox event.
     *
     * @param eventType event category
     * @param transactionId transaction or aggregate identifier
     * @param discriminator event-specific stable suffix
     * @return deterministic UUID from the UTF-8 composite key
     */
    public UUID stableEventId(String eventType, UUID transactionId, String discriminator) {
        String key = String.valueOf(eventType) + ":" + transactionId + ":" + discriminator;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Serializes the payload, substituting an empty object for null input.
     *
     * @param payload event-specific map
     * @return JSON object text
     * @throws IllegalArgumentException when values cannot be serialized
     */
    private String toJson(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification payload is not serializable.", exception);
        }
    }

    /**
     * Attempts one atomic fenced claim and returns the reloaded row only when its token matches.
     *
     * @param outboxId candidate row
     * @param workerId normalized worker owner label
     * @param now current claim timestamp
     * @param claimedUntil lease expiration instant
     * @return claimed row, or empty when another worker/state transition won the race
     */
    private Optional<KfeFinancialNotificationOutboxEntity> claim(
            UUID outboxId,
            String workerId,
            Instant now,
            Instant claimedUntil) {
        UUID token = UUID.randomUUID();
        int updated = repository.claimFenced(outboxId, DUE_STATUSES, now, workerId, token, claimedUntil);
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findById(outboxId).filter(entity -> token.equals(entity.getClaimToken()));
    }

    /**
     * Schedules an unclaimed compatibility row for retry using exponential delay.
     *
     * @param outboxId row to retry
     * @param attempts number of attempts after this failure
     * @param lastError persistence detail supplied by the legacy caller
     */
    @Transactional
    public void markRetryableFailure(
            UUID outboxId,
            int attempts,
            String lastError) {
        Instant next = nextBackoff(attempts);
        repository.markRetryableFailure(outboxId, "FAILED_RETRYABLE", next, lastError);
    }

    /**
     * Fenced retry transition that stores a fixed safe error and rejects stale lease tokens.
     *
     * @param claim current row/token ownership
     * @param attempts number of attempts after this failure
     * @param ignoredError caller detail intentionally ignored to avoid persisting sensitive provider data
     * @return true when the row was transitioned by this claim
     */
    @Transactional
    public boolean markRetryableFailure(NotificationClaim claim, int attempts, String ignoredError) {
        return repository.markRetryableFailureFenced(
                claim.outboxId(), claim.claimToken(), nextBackoff(attempts), safeFailure()) == 1;
    }

    /** @param outboxId row to move to DEAD_LETTER with caller-provided compatibility detail */
    @Transactional
    public void markDeadLetter(UUID outboxId, String lastError) {
        repository.markFinalFailure(outboxId, "DEAD_LETTER", lastError);
    }

    /**
     * Fenced dead-letter transition using a fixed safe error message.
     *
     * @param claim current row/token ownership
     * @param ignoredError caller detail intentionally ignored
     * @return true when this claim moved the row to DEAD_LETTER
     */
    @Transactional
    public boolean markDeadLetter(NotificationClaim claim, String ignoredError) {
        return repository.markFinalFailureFenced(claim.outboxId(), claim.claimToken(), safeFailure()) == 1;
    }

    /** @param outboxId row to mark delivered */
    @Transactional
    public void markDelivered(UUID outboxId) {
        repository.markDelivered(outboxId, Instant.now());
    }

    /**
     * Fenced delivery acknowledgement using the current time as deliveredAt.
     *
     * @param claim current row/token ownership
     * @return true when this claim acknowledged delivery
     */
    @Transactional
    public boolean markDelivered(NotificationClaim claim) {
        return repository.markDeliveredFenced(claim.outboxId(), claim.claimToken(), Instant.now()) == 1;
    }

    /**
     * Computes capped exponential retry delay: one second times 2^min(attempts, 5).
     *
     * @param attempts failed delivery count
     * @return next eligible attempt instant
     */
    private Instant nextBackoff(int attempts) {
        long delayMillis = (long) Math.pow(2, Math.min(attempts, 5)) * 1_000L;
        return Instant.now().plus(Duration.ofMillis(delayMillis));
    }

    /**
     * Normalizes worker labels to lowercase, substitutes a stable default, and caps storage at 128 chars.
     *
     * @param workerId worker-provided diagnostic identifier
     * @return normalized nonblank owner label
     */
    private String normalizeWorkerId(String workerId) {
        String value = workerId == null ? "" : workerId.trim();
        if (value.isBlank()) {
            return "kfe-notification-worker";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, Math.min(128, lower.length()));
    }

    /** @return fixed generic retry/dead-letter error with no provider or payload details */
    private static String safeFailure() {
        return "Notification delivery failed; retry or quarantine required.";
    }
}
