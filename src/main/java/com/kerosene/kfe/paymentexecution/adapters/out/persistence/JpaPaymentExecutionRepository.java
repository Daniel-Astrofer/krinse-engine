package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionRepository;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Maps the aggregate write model onto the existing transactions_master JPA entity. */
@Component
public class JpaPaymentExecutionRepository implements PaymentExecutionRepository {

    private final KfeTransactionRepository repository;

    public JpaPaymentExecutionRepository(KfeTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PaymentExecution> findById(PaymentExecutionId id) {
        return repository.findByIdForUpdate(id.value())
                .map(entity -> PaymentExecution.reconstitute(
                        id,
                        ExecutionStatus.valueOf(entity.getStatus().name())));
    }

    @Override
    public void save(PaymentExecution execution) {
        var entity = repository.findByIdForUpdate(execution.id().value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        entity.setStatus(KfeTransactionStatus.valueOf(execution.status().name()));
        repository.save(entity);
    }
}
