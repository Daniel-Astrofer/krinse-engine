package com.kerosene.kfe.messaging.adapters.in.scheduling;

import com.kerosene.kfe.messaging.adapters.out.persistence.KfeMessageInboxService;

import com.kerosene.kfe.messaging.application.MessageHandler;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeMessageInboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Delivers inbox messages at least once; stale claims are reclaimed by the adapter. */
@Component
@ConditionalOnProperty(name = "kfe.messaging.inbox.enabled", havingValue = "true", matchIfMissing = true)
public class KfeMessageInboxWorker {
    private static final Logger log = LoggerFactory.getLogger(KfeMessageInboxWorker.class);
    private final String workerId = "kfe-message-inbox-worker-" + UUID.randomUUID();
    private final KfeMessageInboxService inbox;
    private final List<MessageHandler> handlers;

    public KfeMessageInboxWorker(KfeMessageInboxService inbox, List<MessageHandler> handlers) {
        this.inbox = inbox;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    @Scheduled(
            fixedDelayString = "${kfe.messaging.inbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.messaging.inbox.initial-delay-ms:10000}")
    public void drain() {
        for (KfeMessageInboxEntity item : inbox.claimDue(workerId, 100, Duration.ofMinutes(5))) {
            process(item);
        }
    }

    private void process(KfeMessageInboxEntity item) {
        MessageHandler handler = handlers.stream()
                .filter(candidate -> candidate.supports(item.getMessageType(), item.getSchemaVersion()))
                .findFirst()
                .orElse(null);
        if (handler == null) {
            inbox.quarantine(item, "unsupported message contract");
            return;
        }
        try {
            handler.handle(new MessageEnvelope(
                    item.getMessageId(), item.getMessageKind(), item.getMessageType(),
                    item.getSchemaVersion(), item.getOccurredAt(), item.getCorrelationId(),
                    item.getCausationId(), item.getAggregateId(), item.getAggregateVersion(),
                    item.getIdempotencyKey(), item.getPayloadJson()));
            inbox.complete(item);
        } catch (IllegalArgumentException invalid) {
            inbox.quarantine(item, "invalid message contract");
        } catch (RuntimeException retryable) {
            if (item.getAttempts() >= 5) {
                inbox.quarantine(item, "message retry budget exhausted");
            } else {
                inbox.retry(item, "message handler failed");
            }
            log.warn("[KFE Inbox] handler failed type={} messageId={}",
                    item.getMessageType(), item.getMessageId());
        }
    }
}
