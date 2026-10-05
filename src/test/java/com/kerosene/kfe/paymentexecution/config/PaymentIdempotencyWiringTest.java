package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentIdempotencyAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaIdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentIdempotencyQueryAdapter;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spring composition/transaction boundaries; persistence, isolation and concurrency use the PostgreSQL fixture. */
class PaymentIdempotencyWiringTest {
    private final IdempotencyReservationStore store = mock(IdempotencyReservationStore.class);
    private final PaymentIdempotencyQueryPort queries = mock(PaymentIdempotencyQueryPort.class);
    private final IdempotencyKey key = new IdempotencyKey(" key ");
    private final RequestFingerprint fingerprint = new RequestFingerprint(" fingerprint ");
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());

    @Test
    void graphExposesUniqueInputPortsAndReservationCannotOpenItsOwnTransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(GetIdempotentPaymentUseCase.class)
                    .hasSingleBean(ReservePaymentIdempotencyUseCase.class);
            assertThat(ctx.getBean(GetIdempotentPaymentUseCase.class)).isSameAs(ctx.getBean(ReservePaymentIdempotencyUseCase.class));
            assertThatThrownBy(() -> ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(store, queries, connection);
        });
    }

    @Test
    void preflightQueryOwnsAShortReadOnlyTransactionAndNeverReserves() throws Exception {
        var connection = mock(Connection.class);
        when(store.find(7L, key)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return Optional.empty();
        });
        context(connection).run(ctx -> {
            assertThat(ctx.getBean(GetIdempotentPaymentUseCase.class).find(query())).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verify(connection).commit();
            verify(store, never()).reserve(anyLong(), any());
            verifyNoInteractions(queries);
        });
    }

    @Test
    void newReservationUsesOneCallerCommitAndDoesNotReadOrProject() throws Exception {
        var connection = mock(Connection.class);
        when(store.reserve(anyLong(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return true;
        });
        context(connection).run(ctx -> {
            var tx = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            tx.executeWithoutResult(status -> {
                assertThat(ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command()).reserved()).isTrue();
                assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
            });
            verify(connection).commit();
            verify(store, never()).find(anyLong(), any());
            verifyNoInteractions(queries);
        });
    }

    @Test
    void losingReservationReplaysCurrentStateInTheSameCallerTransactionWithoutCompletingAgain() throws Exception {
        var connection = mock(Connection.class);
        var reservation = IdempotencyReservation.pending(key, fingerprint);
        reservation.complete(id);
        var result = mock(PaymentExecutionResult.class);
        when(result.id()).thenReturn(id.value());
        when(result.status()).thenReturn(ExecutionStatus.SETTLED);
        when(store.reserve(anyLong(), any())).thenReturn(false);
        when(store.find(7L, key)).thenReturn(Optional.of(reservation));
        when(queries.findOwnedByIdAndKey(7L, id, key)).thenReturn(Optional.of(result));
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                var response = ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command());
                assertThat(response.reserved()).isFalse();
                assertThat(response.existingPayment()).isSameAs(result);
                assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
            });
            var order = inOrder(store, queries);
            order.verify(store).reserve(eq(7L), any());
            order.verify(store).find(7L, key);
            order.verify(queries).findOwnedByIdAndKey(7L, id, key);
            order.verifyNoMoreInteractions();
            verify(connection).commit();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caughtConflictOrPendingMarksCallerRollbackOnlyAndSuppressesAfterCommit(boolean conflictingHash) throws Exception {
        var connection = mock(Connection.class);
        var published = new AtomicInteger();
        when(store.reserve(anyLong(), any())).thenReturn(false);
        when(store.find(7L, key)).thenReturn(Optional.of(IdempotencyReservation.pending(key,
                conflictingHash ? new RequestFingerprint("other") : fingerprint)));
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { published.incrementAndGet(); }
                });
                assertThatThrownBy(() -> ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command()))
                        .isInstanceOf(conflictingHash ? IdempotencyKeyConflict.class : PaymentExecutionStillProcessing.class);
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(published).hasValue(0);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(queries);
        });
    }

    @Test
    void callerRollbackAfterInsertDoesNotCommitAnIndependentReservation() throws Exception {
        var connection = mock(Connection.class);
        when(store.reserve(anyLong(), any())).thenReturn(true);
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command());
                status.setRollbackOnly();
            });
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(queries);
        });
    }

    @Test
    void completeGraphHasUniqueAdaptersAndAtomicInsertUsesTheOwningTransaction() throws Exception {
        var connection = mock(Connection.class);
        var persistence = persistenceFixture(connection);
        when(persistence.insert().executeUpdate()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            verify(connection, never()).commit();
            return 1;
        });
        persistence.context().run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(GetIdempotentPaymentUseCase.class)
                    .hasSingleBean(ReservePaymentIdempotencyUseCase.class)
                    .hasSingleBean(IdempotencyReservationStore.class)
                    .hasSingleBean(PaymentIdempotencyQueryPort.class)
                    .hasSingleBean(JpaIdempotencyReservationStore.class)
                    .hasSingleBean(JpaPaymentIdempotencyQueryAdapter.class);
            var input = ctx.getBean(ReservePaymentIdempotencyUseCase.class);
            assertThat(input).isSameAs(ctx.getBean(GetIdempotentPaymentUseCase.class));
            assertThatThrownBy(() -> input.reserve(command())).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(IdempotencyReservationStore.class)
                    .reserve(7L, IdempotencyReservation.pending(key, fingerprint)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(persistence.em(), persistence.insert(), persistence.session(), connection);

            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                var result = input.reserve(command());
                assertThat(result.reserved()).isTrue();
                assertThat(result.existingPayment()).isNull();
            });

            var order = inOrder(connection, persistence.em(), persistence.insert());
            order.verify(connection).getTransactionIsolation();
            order.verify(persistence.em()).createNativeQuery(contains("ON CONFLICT (user_id, idempotency_key) DO NOTHING"));
            order.verify(persistence.insert()).setParameter("userId", 7L);
            order.verify(persistence.insert()).setParameter("key", key.value());
            order.verify(persistence.insert()).setParameter("fingerprint", fingerprint.value());
            order.verify(persistence.insert()).executeUpdate();
            order.verify(connection).commit();
            verify(persistence.em(), never()).createQuery(anyString(), eq(Object[].class));
            verifyNoInteractions(persistence.scalar(), persistence.reservations(), persistence.transactions(), persistence.mapper());
        });
    }

    @Test
    void completeGraphReplaysThroughFreshScalarAndOwnerScopedProjectionWithoutAWriteLockOrCompletion() throws Exception {
        var connection = mock(Connection.class);
        var persistence = persistenceFixture(connection);
        var tx = new KfeTransactionEntity();
        tx.setUserId(7L); tx.setIdempotencyKey(key.value());
        tx.setStatus(KfeTransactionStatus.SETTLED); tx.setRail(KfeRail.ONCHAIN); tx.setDirection(KfeDirection.OUTBOUND);
        tx.setQuorumProposalHash("private-proposal"); tx.setQuorumAckCount(3);
        // The reservation keeps its original completion status; replay projects the current execution status.
        when(persistence.scalar().getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{7L, key.value(), fingerprint.value(), tx.getId(), "EXECUTING"}));
        when(persistence.insert().executeUpdate()).thenReturn(0);
        when(persistence.transactions().findByIdAndUserId(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        var projection = mock(KfeTransactionResponse.class);
        when(projection.id()).thenReturn(tx.getId());
        when(projection.status()).thenReturn(KfeTransactionStatus.SETTLED);
        when(projection.rail()).thenReturn(KfeRail.ONCHAIN);
        when(projection.direction()).thenReturn(KfeDirection.OUTBOUND);
        when(persistence.mapper().toTransactionResponse(tx)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return projection;
        });

        persistence.context().run(ctx -> {
            var result = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).execute(status -> {
                var reserved = ctx.getBean(ReservePaymentIdempotencyUseCase.class).reserve(command());
                assertThat(reserved.reserved()).isFalse();
                assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
                return reserved.existingPayment();
            });
            assertThat(result.id()).isEqualTo(tx.getId());
            assertThat(result.status()).isEqualTo(ExecutionStatus.SETTLED);
            assertThat(result.quorumProposalHash()).isNull();
            assertThat(result.quorumAckCount()).isZero();
            var order = inOrder(persistence.insert(), persistence.em(), persistence.scalar(),
                    persistence.transactions(), persistence.mapper(), connection);
            order.verify(persistence.insert()).executeUpdate();
            order.verify(persistence.em()).createQuery(contains("select reservation.id.userId"), eq(Object[].class));
            order.verify(persistence.scalar()).setParameter("userId", 7L);
            order.verify(persistence.scalar()).setParameter("key", key.value());
            order.verify(persistence.scalar()).getResultList();
            order.verify(persistence.transactions()).findByIdAndUserId(tx.getId(), 7L);
            order.verify(persistence.em()).refresh(tx, LockModeType.NONE);
            order.verify(persistence.mapper()).toTransactionResponse(tx);
            order.verify(connection).commit();

            // Outside submit the same graph opens its own short read-only transaction, without inserting again.
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                return null;
            }).when(persistence.em()).refresh(tx, LockModeType.NONE);
            assertThat(ctx.getBean(GetIdempotentPaymentUseCase.class).find(query())).contains(result);
            verify(persistence.insert()).executeUpdate();
            verify(persistence.transactions(), never()).findById(any());
            verify(persistence.transactions(), never()).findByIdAndUserIdForUpdate(any(), any());
            verify(persistence.transactions(), never()).save(any());
            verify(persistence.em(), never()).refresh(any(), eq(LockModeType.PESSIMISTIC_WRITE));
            verifyNoInteractions(persistence.reservations());
            verify(connection, times(2)).commit();
        });
    }

    private ReservePaymentIdempotencyCommand command() { return new ReservePaymentIdempotencyCommand(7L, key, fingerprint); }
    private GetIdempotentPaymentQuery query() { return new GetIdempotentPaymentQuery(7L, key, fingerprint); }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source))
                .withBean(IdempotencyReservationStore.class, () -> store).withBean(PaymentIdempotencyQueryPort.class, () -> queries);
    }

    @SuppressWarnings("unchecked")
    private PersistenceFixture persistenceFixture(Connection connection) throws Exception {
        var source = mock(DataSource.class);
        var em = mock(EntityManager.class);
        var session = mock(Session.class);
        var insert = mock(Query.class);
        TypedQuery<Object[]> scalar = mock(TypedQuery.class);
        var reservations = mock(KfeIdempotencyRepository.class);
        var transactions = mock(KfeTransactionRepository.class);
        var mapper = mock(KfeResponseMapper.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.doReturningWork(any())).thenAnswer(invocation ->
                invocation.<ReturningWork<Integer>>getArgument(0).execute(connection));
        when(em.createNativeQuery(anyString())).thenReturn(insert);
        when(insert.setParameter(anyString(), any())).thenReturn(insert);
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(scalar);
        when(scalar.setParameter(anyString(), any())).thenReturn(scalar);
        var runner = new ApplicationContextRunner().withAllowCircularReferences(false)
                .withUserConfiguration(PersistenceGraph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source))
                .withBean(EntityManager.class, () -> em)
                .withBean(KfeIdempotencyRepository.class, () -> reservations)
                .withBean(KfeTransactionRepository.class, () -> transactions)
                .withBean(KfeResponseMapper.class, () -> mapper);
        return new PersistenceFixture(runner, em, session, insert, scalar, reservations, transactions, mapper);
    }

    private record PersistenceFixture(ApplicationContextRunner context, EntityManager em, Session session,
            Query insert, TypedQuery<Object[]> scalar, KfeIdempotencyRepository reservations,
            KfeTransactionRepository transactions, KfeResponseMapper mapper) {}

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentIdempotencyConfiguration.class, TransactionalPaymentIdempotencyAdapter.class})
    static class Graph {}

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentIdempotencyConfiguration.class, TransactionalPaymentIdempotencyAdapter.class,
            JpaIdempotencyReservationStore.class, JpaPaymentIdempotencyQueryAdapter.class})
    static class PersistenceGraph {}
}
