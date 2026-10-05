package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
public class JpaPaymentIdempotencyQueryAdapter implements PaymentIdempotencyQueryPort {
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;
    private final KfeResponseMapper responseMapper;

    public JpaPaymentIdempotencyQueryAdapter(KfeTransactionRepository transactions,
            EntityManager entityManager, KfeResponseMapper responseMapper) {
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.responseMapper = responseMapper;
    }

    @Override
    public Optional<PaymentExecutionResult> findOwnedByIdAndKey(
            long userId, PaymentExecutionId executionId, IdempotencyKey key) {
        if (userId <= 0L || executionId == null || key == null) {
            throw new IllegalArgumentException("Idempotent payment lookup identity is required.");
        }
        return transactions.findByIdAndUserId(executionId.value(), userId).flatMap(tx -> {
            // Refresh after the scoped query without adding a write lock or clearing unrelated managed state.
            entityManager.refresh(tx, LockModeType.NONE);
            if (!executionId.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())
                    || !key.value().equals(tx.getIdempotencyKey())) {
                return Optional.empty();
            }
            return Optional.of(LegacyPaymentExecutionResultMapper.toResult(responseMapper.toTransactionResponse(tx)));
        });
    }
}
