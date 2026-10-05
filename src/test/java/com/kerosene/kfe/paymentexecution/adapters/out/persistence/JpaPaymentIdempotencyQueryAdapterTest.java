package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentIdempotencyQueryAdapterTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final JpaPaymentIdempotencyQueryAdapter adapter = new JpaPaymentIdempotencyQueryAdapter(transactions, em, mapper);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());
    private final IdempotencyKey key = new IdempotencyKey(" key ");

    @Test
    void returnsFreshOwnerScopedProjectionAfterRefreshingWithoutWriteLock() {
        ready();
        doAnswer(invocation -> { tx.setStatus(KfeTransactionStatus.SETTLED); return null; })
                .when(em).refresh(tx, LockModeType.NONE);
        var response = response(KfeTransactionStatus.SETTLED);
        when(mapper.toTransactionResponse(tx)).thenAnswer(invocation -> {
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
            return response;
        });

        var actual = adapter.findOwnedByIdAndKey(7L, id, key).orElseThrow();

        assertThat(actual).isEqualTo(LegacyPaymentExecutionResultMapper.toResult(response));
        var order = inOrder(transactions, em, mapper);
        order.verify(transactions).findByIdAndUserId(id.value(), 7L);
        order.verify(em).refresh(tx, LockModeType.NONE);
        order.verify(mapper).toTransactionResponse(tx);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "null-owner", "key", "null-key", "trimmed-key"})
    void refusesMissingOrReboundIdentityWithoutMappingAnotherPayment(String invalid) {
        ready();
        if (invalid.equals("missing")) {
            when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.empty());
        }
        doAnswer(invocation -> {
            switch (invalid) {
                case "id" -> org.springframework.test.util.ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
                case "owner" -> tx.setUserId(8L);
                case "null-owner" -> tx.setUserId(null);
                case "key" -> tx.setIdempotencyKey("another-key");
                case "null-key" -> tx.setIdempotencyKey(null);
                case "trimmed-key" -> tx.setIdempotencyKey("key");
                default -> { }
            }
            return null;
        }).when(em).refresh(tx, LockModeType.NONE);

        assertThat(adapter.findOwnedByIdAndKey(7L, id, key)).isEmpty();

        verifyNoInteractions(mapper);
        verify(transactions, never()).findById(any());
        verify(transactions, never()).findByIdForUpdate(any());
        verify(transactions, never()).findByIdAndUserIdForUpdate(any(), any());
        verify(transactions, never()).save(any());
        verify(em, never()).clear();
    }

    @Test
    void invalidSelectorsFailBeforeReadingAnything() {
        assertThatThrownBy(() -> adapter.findOwnedByIdAndKey(0L, id, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.findOwnedByIdAndKey(7L, null, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.findOwnedByIdAndKey(7L, id, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactions, em, mapper);
    }

    @Test
    void refreshFailureCannotReturnTheStaleProjection() {
        ready();
        var failure = new IllegalStateException("refresh unavailable");
        doThrow(failure).when(em).refresh(tx, LockModeType.NONE);

        assertThatThrownBy(() -> adapter.findOwnedByIdAndKey(7L, id, key)).isSameAs(failure);

        verifyNoInteractions(mapper);
        verify(em, never()).clear();
    }

    @Test
    void preservesThePublicMapperSuppressionOfQuorumInternals() {
        ready();
        tx.setQuorumProposalHash("internal-proposal");
        tx.setQuorumAckCount(3);
        tx.setGrossAmountSats(10_000L);
        tx.setReceiverAmountSats(9_900L);
        var hints = mock(PaymentCancellationHintsUseCase.class);
        when(hints.hintsFor(7L, id)).thenReturn(PaymentCancellationHints.none());
        var realMapper = new KfeResponseMapper(mock(KfeWalletAddressRepository.class), mock(KfeWalletRepository.class), hints);
        var realAdapter = new JpaPaymentIdempotencyQueryAdapter(transactions, em, realMapper);

        var result = realAdapter.findOwnedByIdAndKey(7L, id, key).orElseThrow();

        assertThat(result.id()).isEqualTo(id.value());
        assertThat(result.status()).isEqualTo(ExecutionStatus.EXECUTING);
        assertThat(result.quorumProposalHash()).isNull();
        assertThat(result.quorumAckCount()).isZero();
        assertThat(result.grossAmountSats()).isEqualTo(10_000L);
        assertThat(result.receiverAmountSats()).isEqualTo(9_900L);
    }

    @Test
    void startsAReadOnlyTransactionWhenCalledWithoutOne() throws Exception {
        var fixture = fixture();

        assertThat(fixture.port().findOwnedByIdAndKey(7L, id, key)).isEmpty();

        var order = inOrder(fixture.connection(), transactions);
        order.verify(fixture.connection()).setReadOnly(true);
        order.verify(transactions).findByIdAndUserId(id.value(), 7L);
        order.verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void joinsTheExistingTransactionInsteadOfCommittingAnIndependentRead() throws Exception {
        var fixture = fixture();

        fixture.transaction().executeWithoutResult(status -> {
            assertThat(fixture.port().findOwnedByIdAndKey(7L, id, key)).isEmpty();
            assertThatCode(() -> verify(fixture.connection(), never()).commit()).doesNotThrowAnyException();
        });

        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void caughtRefreshFailureMakesTheJoinedTransactionRollback() throws Exception {
        ready();
        var fixture = fixture();
        var failure = new IllegalStateException("refresh failed");
        doThrow(failure).when(em).refresh(tx, LockModeType.NONE);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().findOwnedByIdAndKey(7L, id, key)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verifyNoInteractions(mapper);
    }

    private void ready() {
        tx.setUserId(7L);
        tx.setIdempotencyKey(key.value());
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(KfeDirection.OUTBOUND);
        when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
    }

    private KfeTransactionResponse response(KfeTransactionStatus status) {
        var response = mock(KfeTransactionResponse.class);
        when(response.id()).thenReturn(id.value());
        when(response.status()).thenReturn(status);
        when(response.rail()).thenReturn(KfeRail.LIGHTNING);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        return response;
    }

    private Fixture fixture() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var manager = new DataSourceTransactionManager(source);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(adapter);
        factory.addAdvice(interceptor);
        return new Fixture((PaymentIdempotencyQueryPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentIdempotencyQueryPort port, TransactionTemplate transaction, Connection connection) {}
}
