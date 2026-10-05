package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaIdempotencyReservationStoreTest {

    private final KfeIdempotencyRepository repository = mock(KfeIdempotencyRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final JpaIdempotencyReservationStore store = new JpaIdempotencyReservationStore(repository, em);
    private final KfeIdempotencyId identity = new KfeIdempotencyId(42L, "checkout-42");
    private final UUID executionId = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void reservesByAtomicInsertOnlyWithoutMergingExistingRows(int affected) throws Exception {
        var connection = configureIsolation(Connection.TRANSACTION_READ_COMMITTED);
        var insert = insertQuery(affected);
        var pending = IdempotencyReservation.pending(new IdempotencyKey(" checkout-42 "), new RequestFingerprint("request-hash"));

        assertThat(store.reserve(42L, pending)).isEqualTo(affected == 1);

        var sql = ArgumentCaptor.forClass(String.class);
        var order = inOrder(connection, em, insert);
        order.verify(em).unwrap(Session.class);
        order.verify(connection).getTransactionIsolation();
        order.verify(em).createNativeQuery(sql.capture());
        order.verify(insert).setParameter("userId", 42L);
        order.verify(insert).setParameter("key", " checkout-42 ");
        order.verify(insert).setParameter("fingerprint", "request-hash");
        order.verify(insert).executeUpdate();
        assertThat(sql.getValue()).contains("INSERT INTO financial.transaction_idempotency",
                "(user_id, idempotency_key, transaction_id, request_hash, status, created_at, expires_at)",
                "NULL, :fingerprint, 'PENDING', clock_timestamp() AT TIME ZONE 'UTC', NULL",
                "ON CONFLICT (user_id, idempotency_key) DO NOTHING").doesNotContain("DO UPDATE");
        verifyNoInteractions(repository);
        verify(em, never()).merge(any());
        verify(em, never()).persist(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_NONE, Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void unsupportedIsolationFailsBeforeAnyWrite(int isolation) throws Exception {
        configureIsolation(isolation);
        assertThatThrownBy(() -> store.reserve(42L, pendingReservation()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
        verify(em, never()).createNativeQuery(anyString());
        verifyNoInteractions(repository);
    }

    @Test
    void invalidReservationDoesNotEvenAcquireAConnection() {
        assertThatThrownBy(() -> store.reserve(0L, pendingReservation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reserve(42L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reserve(42L, completedReservation())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(em, repository);
    }

    @Test
    void unexpectedInsertCountFailsClosed() throws Exception {
        configureIsolation(Connection.TRANSACTION_READ_COMMITTED);
        insertQuery(2);
        assertThatThrownBy(() -> store.reserve(42L, pendingReservation())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void insertFailureIsNotCaughtOrConvertedToReplay() throws Exception {
        configureIsolation(Connection.TRANSACTION_READ_COMMITTED);
        var query = insertQuery(0);
        var failure = new IllegalStateException("database failed");
        when(query.executeUpdate()).thenThrow(failure);
        assertThatThrownBy(() -> store.reserve(42L, pendingReservation())).isSameAs(failure);
        verify(em, never()).createQuery(anyString(), eq(Object[].class));
        verifyNoInteractions(repository);
    }

    @Test
    void isolationReadFailureCannotFallBackToAnInsert() throws Exception {
        var connection = configureIsolation(Connection.TRANSACTION_READ_COMMITTED);
        var failure = new java.sql.SQLException("isolation unavailable");
        when(connection.getTransactionIsolation()).thenThrow(failure);
        assertThatThrownBy(() -> store.reserve(42L, pendingReservation())).isSameAs(failure);
        verify(em, never()).createNativeQuery(anyString());
        verifyNoInteractions(repository);
    }

    @Test
    void lookupUsesAnExactScopedFreshScalarProjectionWithoutManagedEntitiesOrLocks() {
        var query = lookupQuery();
        when(query.getResultList()).thenReturn(List.<Object[]>of(row(null, "PENDING")),
                List.<Object[]>of(row(executionId, "EXECUTING")));

        assertThat(store.find(42L, new IdempotencyKey("checkout-42")).orElseThrow().isPending()).isTrue();
        var fresh = store.find(42L, new IdempotencyKey("checkout-42")).orElseThrow();

        assertThat(fresh.completedExecutionId()).isEqualTo(new PaymentExecutionId(executionId));
        assertThat(fresh.fingerprint()).isEqualTo(new RequestFingerprint("request-hash"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createQuery(sql.capture(), eq(Object[].class));
        assertThat(sql.getValue()).contains("reservation.id.userId = :userId", "reservation.id.idempotencyKey = :key",
                "reservation.requestHash, reservation.transactionId, reservation.status");
        verify(query, times(2)).setParameter("userId", 42L);
        verify(query, times(2)).setParameter("key", "checkout-42");
        verify(em, never()).refresh(any(), any(LockModeType.class));
        verify(query, never()).setLockMode(any());
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "key", "fingerprint", "unknown-status", "pending-linked", "unlinked-completed", "null-status", "id", "columns", "multiple"})
    void inconsistentPersistedProjectionFailsClosed(String invalid) {
        var query = lookupQuery();
        var row = row(executionId, "EXECUTING");
        switch (invalid) {
            case "owner" -> row[0] = 43L;
            case "key" -> row[1] = "another-key";
            case "fingerprint" -> row[2] = " ";
            case "unknown-status" -> row[4] = "UNKNOWN";
            case "pending-linked" -> row[4] = "PENDING";
            case "unlinked-completed" -> row[3] = null;
            case "null-status" -> row[4] = null;
            case "id" -> row[3] = "not-a-uuid";
            case "columns" -> row = new Object[0];
            case "multiple" -> { }
            default -> throw new AssertionError(invalid);
        }
        when(query.getResultList()).thenReturn(invalid.equals("multiple") ? List.of(row, row) : List.<Object[]>of(row));

        assertThatThrownBy(() -> store.find(42L, new IdempotencyKey("checkout-42")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Idempotency reservation state is invalid.");
        verifyNoInteractions(repository);
    }

    @Test
    void missingReservationIsAbsentButInvalidLookupFailsBeforePersistence() {
        assertThatThrownBy(() -> store.find(0L, new IdempotencyKey("checkout-42"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.find(42L, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(em, repository);
        lookupQuery();
        assertThat(store.find(42L, new IdempotencyKey("checkout-42"))).isEmpty();
    }

    @Test
    void completesThePersistedReservationWithExecutionIdentityAndStatus() {
        var reservation = completedReservation();
        var entity = pendingEntity();
        when(repository.findByIdForUpdate(identity)).thenReturn(Optional.of(entity));

        assertThat(store.complete(42L, reservation, ExecutionStatus.EXECUTING)).isTrue();

        assertThat(entity.getTransactionId()).isEqualTo(executionId);
        assertThat(entity.getStatus()).isEqualTo("EXECUTING");
        var order = inOrder(repository, em);
        order.verify(repository).findByIdForUpdate(identity);
        order.verify(em).refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        order.verify(repository).save(entity);
        order.verifyNoMoreInteractions();
    }

    @Test
    void sameBindingAndStatusAreAnUnchangedReplayAfterRefresh() {
        var entity = pendingEntity();
        when(repository.findByIdForUpdate(identity)).thenReturn(Optional.of(entity));
        doAnswer(invocation -> {
            entity.setTransactionId(executionId);
            entity.setStatus("EXECUTING");
            return null;
        }).when(em).refresh(entity, LockModeType.PESSIMISTIC_WRITE);

        assertThat(store.complete(42L, completedReservation(), ExecutionStatus.EXECUTING)).isFalse();

        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "id", "status", "owner", "key", "null-identity", "invalid-pending", "null-status"})
    void persistedConflictsCannotBeOverwritten(String conflict) {
        var entity = pendingEntity();
        when(repository.findByIdForUpdate(identity)).thenReturn(Optional.of(entity));
        doAnswer(invocation -> {
            switch (conflict) {
                case "hash" -> entity.setRequestHash("another-hash");
                case "id" -> { entity.setTransactionId(UUID.randomUUID()); entity.setStatus("EXECUTING"); }
                case "status" -> { entity.setTransactionId(executionId); entity.setStatus("SETTLED"); }
                case "owner" -> entity.setId(new KfeIdempotencyId(43L, "checkout-42"));
                case "key" -> entity.setId(new KfeIdempotencyId(42L, "different-key"));
                case "null-identity" -> entity.setId(null);
                case "invalid-pending" -> entity.setStatus("SETTLED");
                case "null-status" -> entity.setStatus(null);
                default -> throw new AssertionError(conflict);
            }
            return null;
        }).when(em).refresh(entity, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> store.complete(42L, completedReservation(), ExecutionStatus.EXECUTING))
                .isInstanceOf(IdempotencyKeyConflict.class);

        verify(repository, never()).save(any());
    }

    @Test
    void missingRowFailsWithoutRefreshOrSave() {
        assertThatThrownBy(() -> store.complete(42L, completedReservation(), ExecutionStatus.EXECUTING))
                .isInstanceOf(IllegalStateException.class).hasMessage("Idempotency reservation is missing.");
        verifyNoInteractions(em);
        verify(repository, never()).save(any());
    }

    @Test
    void invalidCommandsDoNotAccessPersistence() {
        var reservation = completedReservation();
        assertThatThrownBy(() -> store.complete(0L, reservation, ExecutionStatus.EXECUTING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.complete(42L, null, ExecutionStatus.EXECUTING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.complete(42L, reservation, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.complete(42L,
                IdempotencyReservation.pending(reservation.key(), reservation.fingerprint()), ExecutionStatus.EXECUTING))
                .isInstanceOf(PaymentExecutionStillProcessing.class);
        verifyNoInteractions(repository, em);
    }

    @Test
    void refreshFailureDoesNotSaveOrFallBack() {
        var entity = pendingEntity();
        when(repository.findByIdForUpdate(identity)).thenReturn(Optional.of(entity));
        var failure = new IllegalStateException("refresh failed");
        doThrow(failure).when(em).refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> store.complete(42L, completedReservation(), ExecutionStatus.EXECUTING)).isSameAs(failure);
        verify(repository, never()).save(any());
    }

    @Test
    void mutationsRequireOwningTransactionButLookupDoesNot() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.port().complete(42L, completedReservation(), ExecutionStatus.EXECUTING))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().reserve(42L, pendingReservation()))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(repository, em, fixture.connection());
        lookupQuery();
        assertThat(fixture.port().find(42L, new IdempotencyKey("checkout-42"))).isEmpty();
        verifyNoInteractions(repository);
        verifyNoInteractions(fixture.connection());
    }

    @Test
    void caughtCompletionFailureStillRollsBackOwningTransaction() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().complete(42L, completedReservation(), ExecutionStatus.EXECUTING))
                        .isInstanceOf(IllegalStateException.class)))
                .isInstanceOf(UnexpectedRollbackException.class);
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    @Test
    void caughtReservationFailureStillRollsBackOwningTransaction() throws Exception {
        var fixture = fixture();
        configureIsolation(Connection.TRANSACTION_READ_COMMITTED);
        var insert = insertQuery(0);
        var failure = new IllegalStateException("insert failed");
        when(insert.executeUpdate()).thenThrow(failure);
        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().reserve(42L, pendingReservation())).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private IdempotencyReservation completedReservation() {
        var reservation = pendingReservation();
        reservation.complete(new PaymentExecutionId(executionId));
        return reservation;
    }

    private IdempotencyReservation pendingReservation() {
        return IdempotencyReservation.pending(new IdempotencyKey("checkout-42"), new RequestFingerprint("request-hash"));
    }

    private Connection configureIsolation(int isolation) throws Exception {
        var session = mock(Session.class);
        var connection = mock(Connection.class);
        when(connection.getTransactionIsolation()).thenReturn(isolation);
        when(em.unwrap(Session.class)).thenReturn(session);
        doAnswer(invocation -> invocation.<ReturningWork<Integer>>getArgument(0).execute(connection))
                .when(session).doReturningWork(any());
        return connection;
    }

    private Query insertQuery(int affected) {
        var query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(affected);
        return query;
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> lookupQuery() {
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }

    private Object[] row(UUID transactionId, String status) {
        return new Object[] {42L, "checkout-42", "request-hash", transactionId, status};
    }

    private KfeIdempotencyEntity pendingEntity() {
        var entity = new KfeIdempotencyEntity();
        entity.setId(identity);
        entity.setRequestHash("request-hash");
        entity.setStatus("PENDING");
        return entity;
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
        var factory = new ProxyFactory(store);
        factory.addAdvice(interceptor);
        return new Fixture((IdempotencyReservationStore) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(IdempotencyReservationStore port, TransactionTemplate transaction, Connection connection) {}
}
