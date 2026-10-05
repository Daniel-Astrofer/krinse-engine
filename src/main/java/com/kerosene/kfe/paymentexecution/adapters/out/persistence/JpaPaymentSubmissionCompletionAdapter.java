package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionCompletionPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentSubmissionCompletionAdapter implements PaymentSubmissionCompletionPort {
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;
    private final KfeResponseMapper responseMapper;

    public JpaPaymentSubmissionCompletionAdapter(KfeTransactionRepository transactions,
            EntityManager entityManager, KfeResponseMapper responseMapper) {
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.responseMapper = responseMapper;
    }

    @Override
    public PaymentSubmissionCompletionSnapshot lockAndLoad(long userId, PaymentExecutionId executionId) {
        requireIdentityInputs(userId, executionId);
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), userId).orElseThrow(
                JpaPaymentSubmissionCompletionAdapter::notFound);
        // Refresh only after the lock and query auto-flush; do not resurrect the caller's stale state.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        requireIdentity(tx, userId, executionId);
        return snapshot(tx);
    }

    @Override
    public PaymentExecutionResult saveAndProject(PaymentSubmissionCompletionSnapshot expected) {
        if (expected == null) { throw notFound(); }
        expected.requireReadyFor(expected.userId(), expected.executionId(), expected.idempotencyKey());
        var tx = transactions.findByIdAndUserId(expected.executionId().value(), expected.userId())
                .orElseThrow(JpaPaymentSubmissionCompletionAdapter::notFound);
        requireIdentity(tx, expected.userId(), expected.executionId());
        if (!expected.equals(snapshot(tx))) {
            throw new IllegalStateException("Payment submission changed during completion.");
        }
        // No refresh here: completion must not discard pending mutations in the owning transaction.
        return LegacyPaymentExecutionResultMapper.toResult(
                responseMapper.toTransactionResponse(transactions.save(tx)));
    }

    private static PaymentSubmissionCompletionSnapshot snapshot(KfeTransactionEntity tx) {
        return new PaymentSubmissionCompletionSnapshot(new PaymentExecutionId(tx.getId()), tx.getUserId(),
                new IdempotencyKey(tx.getIdempotencyKey()), ExecutionStatus.valueOf(tx.getStatus().name()),
                PaymentRail.valueOf(tx.getRail().name()), PaymentDirection.valueOf(tx.getDirection().name()),
                tx.getDestinationWalletId());
    }

    private static void requireIdentityInputs(long userId, PaymentExecutionId executionId) {
        if (userId <= 0L || executionId == null) { throw notFound(); }
    }

    private static void requireIdentity(KfeTransactionEntity tx, long userId, PaymentExecutionId executionId) {
        if (!executionId.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())) {
            throw notFound();
        }
    }

    private static IllegalArgumentException notFound() {
        return new IllegalArgumentException("KFE transaction not found.");
    }
}
