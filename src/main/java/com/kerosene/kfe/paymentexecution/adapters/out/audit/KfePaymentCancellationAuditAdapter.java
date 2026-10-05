package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Preserves the existing cancellation event, including the destination wallet for inbound payments. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class KfePaymentCancellationAuditAdapter implements PaymentCancellationAuditPort {

    /** Append-only audit writer invoked in the caller's existing financial transaction. */
    private final KfeAuditLogService auditLogService;

    /** Creates the adapter with the shared audit log service. */
    public KfePaymentCancellationAuditAdapter(KfeAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** Records cancellation status and rail/direction while retaining the statement wallet association. */
    @Override
    public void recordCancelled(PaymentCancellationSnapshot previous) {
        auditLogService.record(
                "KFE_TRANSACTION_CANCELLED",
                previous.executionId().value(),
                previous.statementWalletId(),
                KfeTransactionStatus.valueOf(previous.status().name()),
                KfeTransactionStatus.FAILED,
                Map.of(
                        "failureCode", "USER_CANCELLED",
                        "rail", previous.rail().name(),
                        "direction", previous.direction().name()));
    }
}
