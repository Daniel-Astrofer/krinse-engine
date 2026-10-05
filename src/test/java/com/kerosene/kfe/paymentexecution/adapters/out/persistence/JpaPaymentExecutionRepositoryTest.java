package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaPaymentExecutionRepositoryTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final JpaPaymentExecutionRepository adapter = new JpaPaymentExecutionRepository(repository);

    @Test
    void reconstitutesAggregateUnderWriteLock() {
        PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
        KfeTransactionEntity entity = new KfeTransactionEntity();
        entity.setStatus(KfeTransactionStatus.LOCKED);
        when(repository.findByIdForUpdate(id.value())).thenReturn(Optional.of(entity));

        var execution = adapter.findById(id);

        assertThat(execution).isPresent();
        assertThat(execution.orElseThrow().id()).isEqualTo(id);
        assertThat(execution.orElseThrow().status()).isEqualTo(ExecutionStatus.LOCKED);
    }

    @Test
    void mapsDomainStatusBackToExistingJpaEntity() {
        PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
        KfeTransactionEntity entity = new KfeTransactionEntity();
        entity.setStatus(KfeTransactionStatus.INTENT);
        when(repository.findByIdForUpdate(id.value())).thenReturn(Optional.of(entity));
        PaymentExecution execution = PaymentExecution.reconstitute(id, ExecutionStatus.VALIDATING);

        adapter.save(execution);

        assertThat(entity.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        verify(repository).save(entity);
    }
}
