package com.kerosene.kfe.adapters.out.integration.audit;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.notification.FinancialNotificationAuditPort;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;

import java.util.Map;

/** Adapts shared financial notification audit events into the KFE audit log. */
@Component
@Primary
public class KfeNotificationAuditAdapter implements FinancialNotificationAuditPort {

    /** Persistence-facing audit service that stores sanitized KFE audit entries. */
    private final KfeAuditLogService auditLogService;

    /** @param auditLogService KFE audit-log writer used for notification events */
    public KfeNotificationAuditAdapter(KfeAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * Persists a notification event with its already-redacted payload and no user, wallet,
     * or transaction association because device-token activity may not have those identifiers.
     *
     * @param eventType event name used for audit classification
     * @param redactedPayload sanitized event attributes safe to persist
     */
    @Override
    public void recordDeviceTokenEvent(String eventType, Map<String, ?> redactedPayload) {
        auditLogService.record(
                eventType,
                null,
                null,
                null,
                null,
                redactedPayload);
    }
}
