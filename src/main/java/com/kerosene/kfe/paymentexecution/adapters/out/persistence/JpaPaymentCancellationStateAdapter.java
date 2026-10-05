package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Maps the managed state refreshed by the cancellation fence without acquiring locks out of order. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentCancellationStateAdapter implements PaymentCancellationStatePort {

    private final KfeTransactionRepository repository;

    public JpaPaymentCancellationStateAdapter(KfeTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentCancellationSnapshot load(PaymentExecutionId executionId) {
        return snapshot(find(executionId));
    }

    @Override
    public void markCancelled(PaymentCancellationSnapshot previous, String message) {
        var entity = find(previous.executionId());
        if (!snapshot(entity).equals(previous)) {
            throw new PaymentCancellationRejected();
        }
        entity.setStatus(KfeTransactionStatus.FAILED);
        entity.setFailureCode("USER_CANCELLED");
        entity.setFailureMessage(trim(message, 255));
        repository.save(entity);
    }

    private KfeTransactionEntity find(PaymentExecutionId executionId) {
        return repository.findById(executionId.value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    private static PaymentCancellationSnapshot snapshot(KfeTransactionEntity entity) {
        return new PaymentCancellationSnapshot(
                new PaymentExecutionId(entity.getId()),
                entity.getUserId(),
                ExecutionStatus.valueOf(entity.getStatus().name()),
                PaymentRail.valueOf(entity.getRail().name()),
                PaymentDirection.valueOf(entity.getDirection().name()),
                entity.getSourceWalletId(),
                entity.getDestinationWalletId(),
                entity.getTotalDebitSats(),
                entity.getBlockchainTxid());
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
