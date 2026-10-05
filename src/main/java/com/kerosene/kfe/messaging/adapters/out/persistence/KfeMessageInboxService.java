package com.kerosene.kfe.messaging.adapters.out.persistence;

import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeMessageInboxEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.KfeMessageInboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Durable consumer inbox: message-id deduplication, fenced claims and quarantine/replay. */
@Service
public class KfeMessageInboxService {
    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");

    private final KfeMessageInboxRepository repository;

    public KfeMessageInboxService(KfeMessageInboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public KfeMessageInboxEntity accept(MessageEnvelope message) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        Optional<KfeMessageInboxEntity> existing = repository.findByMessageId(message.messageId());
        if (existing.isPresent()) {
            KfeMessageInboxEntity item = existing.get();
            if (!item.getMessageType().equals(message.type().trim())
                    || item.getSchemaVersion() != message.schemaVersion()
                    || !item.getPayloadJson().equals(message.payload())) {
                throw new IllegalArgumentException("message id was reused with a different contract");
            }
            return item;
        }
        KfeMessageInboxEntity entity = new KfeMessageInboxEntity();
        entity.setMessageId(message.messageId());
        entity.setMessageKind(message.messageKind().trim().toUpperCase(Locale.ROOT));
        entity.setMessageType(message.type().trim());
        entity.setSchemaVersion(message.schemaVersion());
        entity.setOccurredAt(message.occurredAt());
        entity.setCorrelationId(message.correlationId());
        entity.setCausationId(message.causationId());
        entity.setAggregateId(message.aggregateId());
        entity.setAggregateVersion(message.aggregateVersion());
        entity.setIdempotencyKey(message.idempotencyKey());
        entity.setPayloadJson(message.payload());
        entity.setStatus("PENDING");
        entity.setNextAttemptAt(Instant.now());
        try {
            return repository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            return repository.findByMessageId(message.messageId()).orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public Optional<KfeMessageInboxEntity> findByMessageId(UUID messageId) {
        return repository.findByMessageId(messageId);
    }

    @Transactional
    public List<KfeMessageInboxEntity> claimDue(String workerId, int limit, Duration lease) {
        String owner = normalize(workerId);
        Instant now = Instant.now();
        Duration effectiveLease = lease == null ? Duration.ofMinutes(5) : lease;
        if (effectiveLease.isZero() || effectiveLease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        Instant until = now.plus(effectiveLease);
        return repository.findDue(DUE_STATUSES, now, PageRequest.of(0, Math.max(1, Math.min(500, limit))))
                .stream()
                .map(candidate -> claim(candidate, owner, now, until))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<KfeMessageInboxEntity> claim(
            KfeMessageInboxEntity candidate,
            String worker,
            Instant now,
            Instant until) {
        UUID token = UUID.randomUUID();
        if (repository.claim(candidate.getId(), DUE_STATUSES, now, worker, token, until) != 1) {
            return Optional.empty();
        }
        return repository.findById(candidate.getId())
                .filter(item -> token.equals(item.getClaimToken()));
    }

    @Transactional
    public boolean complete(KfeMessageInboxEntity item) {
        return item != null && item.getClaimToken() != null
                && repository.complete(item.getId(), item.getClaimToken(), Instant.now()) == 1;
    }

    @Transactional
    public boolean retry(KfeMessageInboxEntity item, String reason) {
        if (item == null || item.getClaimToken() == null) return false;
        int attempts = Math.max(1, item.getAttempts());
        Instant next = Instant.now().plusSeconds(Math.min(300L, 1L << Math.min(8, attempts)));
        return repository.retry(item.getId(), item.getClaimToken(), next, safeReason(reason)) == 1;
    }

    @Transactional
    public boolean quarantine(KfeMessageInboxEntity item, String reason) {
        return item != null && item.getClaimToken() != null
                && repository.quarantine(item.getId(), item.getClaimToken(), safeReason(reason)) == 1;
    }

    @Transactional
    public boolean replay(UUID inboxId) {
        return inboxId != null && repository.replay(inboxId) == 1;
    }

    private String normalize(String workerId) {
        String value = workerId == null ? "" : workerId.trim();
        if (value.isBlank()) return "kfe-message-inbox-worker";
        value = value.toLowerCase(Locale.ROOT);
        return value.substring(0, Math.min(128, value.length()));
    }

    private String safeReason(String reason) {
        return "Message processing failed; quarantined for operator review.";
    }
}
