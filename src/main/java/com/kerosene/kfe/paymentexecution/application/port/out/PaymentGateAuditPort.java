package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGateEvaluation;
import java.util.UUID;

/** Audit participates in the caller transaction; a rejection row is lost on rollback. */
public interface PaymentGateAuditPort {
    /** Writes the decision and per-flag evidence in the caller's submit transaction. */
    /** @param executionId payment evaluated @param walletId source wallet, if applicable @param evaluation ordered result for all settlement flags */
    void record(PaymentExecutionId executionId, UUID walletId, SettlementGateEvaluation evaluation);
}
