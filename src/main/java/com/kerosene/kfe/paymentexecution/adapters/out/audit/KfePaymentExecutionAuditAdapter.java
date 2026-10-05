package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adds persistence metadata and delegates to the append-only KFE audit chain. */
@Component
public class KfePaymentExecutionAuditAdapter implements PaymentExecutionAuditPort {

    /** Transaction repository used to resolve the persisted source wallet and idempotency key. */
    private final KfeTransactionRepository repository;
    /** Append-only audit writer for payment state transitions. */
    private final KfeAuditLogService auditLogService;
    /** Hash provider used to keep raw idempotency keys out of audit metadata. */
    private final KfeHashService hashService;

    /** Creates the adapter with persistence, audit, and hashing collaborators. */
    public KfePaymentExecutionAuditAdapter(
            KfeTransactionRepository repository,
            KfeAuditLogService auditLogService,
            KfeHashService hashService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.hashService = hashService;
    }

    /** Resolves the transaction, redacts the idempotency key, and records the transition metadata. */
    @Override
    public void record(
            PaymentExecutionId executionId,
            String eventType,
            ExecutionStatus previousStatus,
            ExecutionStatus currentStatus,
            Map<String, ?> payload) {
        var entity = repository.findById(executionId.value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("transactionId", executionId.value().toString());
        redacted.put("idempotencyHash", hashService.sha256(entity.getIdempotencyKey()));
        if (payload != null) {
            redacted.putAll(payload);
        }
        auditLogService.record(
                eventType,
                executionId.value(),
                entity.getSourceWalletId(),
                toPersistenceStatus(previousStatus),
                toPersistenceStatus(currentStatus),
                redacted);
    }

    /** Converts nullable domain status values to their persistence enum equivalents. */
    private static KfeTransactionStatus toPersistenceStatus(ExecutionStatus status) {
        return status == null ? null : KfeTransactionStatus.valueOf(status.name());
    }
}
