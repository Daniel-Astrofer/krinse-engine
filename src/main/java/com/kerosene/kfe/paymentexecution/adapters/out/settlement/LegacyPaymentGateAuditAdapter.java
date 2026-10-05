package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGateEvaluation;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Joins the caller; a new transaction here can deadlock against the global audit appender lock. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentGateAuditAdapter implements PaymentGateAuditPort {
    private final KfeAuditLogService audit;
    public LegacyPaymentGateAuditAdapter(KfeAuditLogService audit) { this.audit = audit; }

    @Override public void record(PaymentExecutionId id, UUID walletId, SettlementGateEvaluation evaluation) {
        audit.record("KFE_SETTLEMENT_GATE", id.value(), walletId, KfeTransactionStatus.VALIDATING,
                evaluation.passed() ? KfeTransactionStatus.QUORUM_SYNC : KfeTransactionStatus.FAILED,
                evaluation.toAuditPayload());
    }
}
