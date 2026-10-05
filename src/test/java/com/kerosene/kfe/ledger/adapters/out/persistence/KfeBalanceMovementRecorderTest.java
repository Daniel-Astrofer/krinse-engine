package com.kerosene.kfe.ledger.adapters.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeBalanceMovementRecorderTest {

    private final KfeBalanceMovementRepository repository = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceMovementRecorder recorder = new KfeBalanceMovementRecorder(repository);

    @Test
    void recordsNewCreditMovement() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(repository.existsByTransactionIdAndMovementType(txId, KfeLedgerMovementTypes.CREDIT_INBOUND))
                .thenReturn(false);
        when(repository.save(any(KfeBalanceMovementEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean wrote = recorder.record(
                txId, walletId, KfeLedgerMovementTypes.CREDIT_INBOUND, 1000L, null, "AVAILABLE");

        assertThat(wrote).isTrue();
        verify(repository).save(any(KfeBalanceMovementEntity.class));
    }

    @Test
    void skipsWhenCreditAlreadyExists() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(repository.existsByTransactionIdAndMovementType(
                        txId, KfeLedgerMovementTypes.CREDIT_PAYMENT_REQUEST))
                .thenReturn(true);

        boolean wrote = recorder.record(
                txId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_PAYMENT_REQUEST,
                1000L,
                null,
                "AVAILABLE");

        assertThat(wrote).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void treatsUniqueViolationAsIdempotentSkip() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(repository.existsByTransactionIdAndMovementType(txId, KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE))
                .thenReturn(false);
        when(repository.save(any(KfeBalanceMovementEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        boolean wrote = recorder.record(
                txId, walletId, KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE, 90L, null, "AVAILABLE");

        assertThat(wrote).isFalse();
    }
}
