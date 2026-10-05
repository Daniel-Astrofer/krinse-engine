package com.kerosene.kfe.ledger.adapters.out.persistence.statement;

import com.kerosene.kfe.messaging.adapters.out.websocket.TransactionEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeUserStatementEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeStatementServiceTest {

    @Mock
    private KfeUserStatementRepository statementRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<com.kerosene.kfe.messaging.adapters.out.websocket.TransactionEventPublisher>
            transactionEventPublisher;

    private KfeStatementService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionEventPublisher.getIfAvailable()).thenReturn(null);
        service = new KfeStatementService(
                statementRepository,
                new ObjectMapper(),
                entityManager,
                transactionEventPublisher,
                null);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.executeUpdate()).thenReturn(1);
    }

    @Test
    void firstWriteUsesNativeUpsert() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.EXECUTING);
        LocalDateTime ledgerCreated = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        setCreated(tx, ledgerCreated);

        service.recordUserStatement(7L, walletId, tx, Map.of(
                "status", "EXECUTING",
                "displayStatus", "PENDING"));

        verify(entityManager).createNativeQuery(anyString());
        verify(nativeQuery).executeUpdate();
        verify(statementRepository, never()).save(any());
    }

    @Test
    void secondWriteAlsoUsesNativeUpsertNotSecondInsert() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.SETTLED);
        setCreated(tx, LocalDateTime.of(2026, 1, 15, 12, 0, 0));

        service.recordUserStatement(7L, walletId, tx, Map.of("status", "SETTLED"));
        service.recordUserStatement(7L, walletId, tx, Map.of("status", "SETTLED", "confirmations", 3));

        verify(nativeQuery, times(2)).executeUpdate();
        verify(statementRepository, never()).save(any());
    }

    @Test
    void uniqueRaceFallsBackToJpaUpdate() {
        when(entityManager.createNativeQuery(anyString()))
                .thenThrow(new RuntimeException("duplicate key value violates unique constraint \"uq_user_statement_24h_user_tx\""));

        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.SETTLED);
        LocalDateTime ledgerCreated = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        setCreated(tx, ledgerCreated);

        KfeUserStatementEntity existing = new KfeUserStatementEntity();
        existing.markNotNew();
        existing.setUserId(7L);
        existing.setTransactionId(tx.getId());
        existing.setWalletId(walletId);
        existing.setCreatedAt(ledgerCreated);
        existing.setUpdatedAt(ledgerCreated);
        existing.setExpiresAt(ledgerCreated.plusHours(24));
        existing.setDisplayPayloadJson("{\"status\":\"EXECUTING\"}");

        when(statementRepository.findByUserIdAndTransactionId(7L, tx.getId()))
                .thenReturn(Optional.of(existing));
        when(statementRepository.save(any(KfeUserStatementEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUserStatement(7L, walletId, tx, Map.of("status", "SETTLED"));

        ArgumentCaptor<KfeUserStatementEntity> captor =
                ArgumentCaptor.forClass(KfeUserStatementEntity.class);
        verify(statementRepository).save(captor.capture());
        assertThat(captor.getValue().getDisplayPayloadJson()).contains("SETTLED");
    }

    @Test
    void nonUniqueFailureIsRethrownSoSubmitDoesNotSilentlyRollbackLater() {
        when(entityManager.createNativeQuery(anyString()))
                .thenThrow(new RuntimeException(
                        "insert or update on table \"user_statement_24h\" violates foreign key constraint"));

        KfeTransactionEntity tx = transaction(KfeTransactionStatus.EXECUTING);
        assertThatThrownBy(() ->
                        service.recordUserStatement(7L, UUID.randomUUID(), tx, Map.of("status", "EXECUTING")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("foreign key");
    }

    @Test
    void bestEffortSwallowsFailure() {
        when(entityManager.createNativeQuery(anyString()))
                .thenThrow(new RuntimeException("foreign key"));

        KfeTransactionEntity tx = transaction(KfeTransactionStatus.EXECUTING);
        service.recordUserStatementBestEffort(7L, UUID.randomUUID(), tx, Map.of("status", "EXECUTING"));
        // no throw
    }

    @Test
    void ifAbsentSkipsWhenRowExists() {
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.SETTLED);
        when(statementRepository.existsByUserIdAndTransactionId(7L, tx.getId())).thenReturn(true);

        service.recordUserStatementIfAbsent(7L, UUID.randomUUID(), tx, Map.of("status", "SETTLED"));

        verify(statementRepository, never()).save(any());
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    private static KfeTransactionEntity transaction(KfeTransactionStatus status) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(7L);
        tx.setIdempotencyKey("idem-" + UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(status);
        tx.setGrossAmountSats(1000L);
        return tx;
    }

    private static void setCreated(KfeTransactionEntity tx, LocalDateTime createdAt) {
        try {
            var field = KfeTransactionEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(tx, createdAt);
            var updated = KfeTransactionEntity.class.getDeclaredField("updatedAt");
            updated.setAccessible(true);
            updated.set(tx, createdAt);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
