package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRoutingStatePort;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentRoutingStateAdapter implements PaymentRoutingStatePort {
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;

    public JpaPaymentRoutingStateAdapter(KfeTransactionRepository transactions, EntityManager entityManager) {
        this.transactions = transactions; this.entityManager = entityManager;
    }

    @Override
    public PaymentRoutingSnapshot lockAndLoad(long userId, PaymentExecutionId executionId) {
        requireIdentityInputs(userId, executionId);
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), userId)
                .orElseThrow(JpaPaymentRoutingStateAdapter::notFound);
        // Query auto-flush keeps quote and LOCKED changes from the caller before refreshing stale state.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        requireIdentity(tx, userId, executionId);
        return new PaymentRoutingSnapshot(executionId, userId, ExecutionStatus.valueOf(tx.getStatus().name()),
                PaymentRail.valueOf(tx.getRail().name()), PaymentDirection.valueOf(tx.getDirection().name()),
                new IdempotencyKey(tx.getIdempotencyKey()), tx.getSourceWalletId(), tx.getDestinationWalletId(),
                tx.getGrossAmountSats(), tx.getReceiverAmountSats(), tx.getNetworkFeeSats(), tx.getTotalDebitSats(),
                tx.getExternalReference(), tx.getMemo(), tx.getQuorumProposalHash());
    }

    @Override
    public void flush(long userId, PaymentExecutionId executionId) {
        requireIdentityInputs(userId, executionId);
        var tx = transactions.findByIdAndUserId(executionId.value(), userId).orElseThrow(JpaPaymentRoutingStateAdapter::notFound);
        requireIdentity(tx, userId, executionId);
        if (tx.getStatus() != KfeTransactionStatus.EXECUTING) {
            throw new IllegalStateException("External routing state was not persisted.");
        }
        transactions.saveAndFlush(tx);
    }

    private static void requireIdentityInputs(long userId, PaymentExecutionId id) {
        if (userId <= 0L || id == null) { throw notFound(); }
    }
    private static void requireIdentity(KfeTransactionEntity tx, long userId, PaymentExecutionId id) {
        if (!id.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())) { throw notFound(); }
    }
    private static IllegalArgumentException notFound() { return new IllegalArgumentException("KFE transaction not found."); }
}
