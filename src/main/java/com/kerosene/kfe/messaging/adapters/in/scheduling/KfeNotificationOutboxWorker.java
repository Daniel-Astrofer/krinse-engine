package com.kerosene.kfe.messaging.adapters.in.scheduling;

import com.kerosene.kfe.messaging.adapters.out.notification.KfeNotificationOutboxProcessor;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "kfe.notification.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KfeNotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeNotificationOutboxWorker.class);
    private final String workerId = "kfe-notification-worker-" + UUID.randomUUID();

    private final KfeFinancialNotificationOutboxService outboxService;
    private final KfeNotificationOutboxProcessor processor;

    public KfeNotificationOutboxWorker(
            KfeFinancialNotificationOutboxService outboxService,
            KfeNotificationOutboxProcessor processor) {
        this.outboxService = outboxService;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${kfe.notification.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.notification.outbox.initial-delay-ms:10000}")
    public void drain() {
        List<KfeFinancialNotificationOutboxEntity> claimed = outboxService.claimDue(workerId);
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("[KFE Notif Outbox] claimed {} item(s) workerId={}", claimed.size(), workerId);
        for (KfeFinancialNotificationOutboxEntity item : claimed) {
            try {
                processor.process(item);
            } catch (RuntimeException exception) {
                log.warn("[KFE Notif Outbox] Processing failed for {}: {}", item.getId(), exception.getMessage());
            }
        }
    }
}
