package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentFundsReservationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentFundsReservationStateAdapter implements PaymentFundsReservationStatePort {
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;

    public JpaPaymentFundsReservationStateAdapter(KfeTransactionRepository transactions, EntityManager entityManager) {
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Override
    public PaymentFundsReservationSnapshot lockAndLoad(long userId, PaymentExecutionId executionId) {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        // Query auto-flush preserves the caller's pending quote/quorum fields before the refresh.
        // Never authorize a repeated reservation from an older managed QUORUM_SYNC snapshot.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        if (!executionId.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())) {
            throw new IllegalArgumentException("KFE transaction not found.");
        }
        return new PaymentFundsReservationSnapshot(executionId, userId,
                ExecutionStatus.valueOf(tx.getStatus().name()), PaymentRail.valueOf(tx.getRail().name()),
                PaymentDirection.valueOf(tx.getDirection().name()), tx.getSourceWalletId(),
                tx.getTotalDebitSats(), tx.getQuorumProposalHash(), tx.getQuorumAckCount());
    }
}
