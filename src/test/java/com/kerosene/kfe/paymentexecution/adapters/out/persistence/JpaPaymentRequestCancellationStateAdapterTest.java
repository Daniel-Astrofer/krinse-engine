package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaPaymentRequestCancellationStateAdapterTest {

    private final KfePaymentRequestRepository repository = mock(KfePaymentRequestRepository.class);
    private final JpaPaymentRequestCancellationStateAdapter adapter = new JpaPaymentRequestCancellationStateAdapter(repository);

    @Test
    void loadsTheScopedImmutableSnapshotWithoutAcquiringAnotherLock() {
        var entity = request();
        when(repository.findByIdAndUserId(entity.getId(), 42L)).thenReturn(Optional.of(entity));

        assertThat(adapter.load(42L, entity.getId())).isEqualTo(snapshot(entity));

        verify(repository).findByIdAndUserId(entity.getId(), 42L);
        verify(repository, never()).findById(any());
        verify(repository, never()).findByIdAndUserIdForUpdate(any(), any());
    }

    @Test
    void rejectsMissingRequests() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.load(42L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidScopeBeforeQuerying() {
        assertThatThrownBy(() -> adapter.load(0L, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.load(-1L, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.load(42L, null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("scopeChanges")
    void rejectsChangedIdentityOrOwnerWithoutRevealingTheRow(Consumer<KfePaymentRequestEntity> change) {
        var entity = request();
        var previous = snapshot(entity);
        when(repository.findByIdAndUserId(previous.id(), previous.userId())).thenReturn(Optional.of(entity));
        change.accept(entity);

        assertThatThrownBy(() -> adapter.load(previous.userId(), previous.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
        assertThatThrownBy(() -> adapter.markCancelled(previous))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");

        assertThat(entity.getCancelledAt()).isNull();
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = KfePaymentRequestStatus.class, names = {"OPEN", "EXPIRED"})
    void cancelsEligibleUnchangedRequestsWithCancellationTimestamp(KfePaymentRequestStatus status) {
        var entity = request();
        entity.setStatus(status);
        var previous = snapshot(entity);
        when(repository.findByIdAndUserId(entity.getId(), 42L)).thenReturn(Optional.of(entity));

        adapter.markCancelled(previous);

        assertThat(entity.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        assertThat(entity.getCancelledAt()).isNotNull();
        verify(repository).save(entity);
    }

    @ParameterizedTest
    @EnumSource(value = KfePaymentRequestStatus.class, names = {"PAID", "HIDDEN", "CANCELLED", "FAILED"})
    void rejectsIneligibleEvenWhenTheSnapshotIsUnchanged(KfePaymentRequestStatus status) {
        var entity = request();
        entity.setStatus(status);
        var previous = snapshot(entity);
        when(repository.findByIdAndUserId(entity.getId(), 42L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adapter.markCancelled(previous)).isInstanceOf(PaymentCancellationRejected.class);

        assertThat(entity.getStatus()).isEqualTo(status);
        assertThat(entity.getCancelledAt()).isNull();
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("requestStateChanges")
    void rejectsEveryChangedSnapshotFieldBeforeWriting(Consumer<KfePaymentRequestEntity> change) {
        var entity = request();
        var previous = snapshot(entity);
        when(repository.findByIdAndUserId(entity.getId(), 42L)).thenReturn(Optional.of(entity));
        change.accept(entity);

        assertThatThrownBy(() -> adapter.markCancelled(previous)).isInstanceOf(PaymentCancellationRejected.class);

        assertThat(entity.getCancelledAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCallsWithoutTheOwningFinancialTransaction() throws Exception {
        var fixture = fixture();
        var previous = snapshot(request());

        assertThatThrownBy(() -> fixture.port().load(previous.userId(), previous.id()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().markCancelled(previous))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(repository, fixture.connection());
    }

    @Test
    void staleStateFailureCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var entity = request();
        var previous = snapshot(entity);
        when(repository.findByIdAndUserId(entity.getId(), 42L)).thenReturn(Optional.of(entity));
        entity.setStatus(KfePaymentRequestStatus.PAID);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().markCancelled(previous))
                        .isInstanceOf(PaymentCancellationRejected.class)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(repository, never()).save(any());
    }

    private static Stream<Consumer<KfePaymentRequestEntity>> scopeChanges() {
        return Stream.of(
                entity -> entity.setUserId(99L),
                entity -> entity.setUserId(null),
                entity -> ReflectionTestUtils.setField(entity, "id", UUID.randomUUID()));
    }

    private static Stream<Consumer<KfePaymentRequestEntity>> requestStateChanges() {
        return Stream.of(
                entity -> entity.setWalletId(UUID.randomUUID()),
                entity -> entity.setPublicId("changed-public-id"),
                entity -> entity.setStatus(KfePaymentRequestStatus.EXPIRED),
                entity -> entity.setRail(KfeRail.ONCHAIN),
                entity -> entity.setPaymentHash("changed-hash"),
                entity -> entity.setProviderReference("changed-provider"),
                entity -> entity.setPaymentRequest("changed-invoice"),
                entity -> entity.setPaidTransactionId(UUID.randomUUID()));
    }

    private static KfePaymentRequestEntity request() {
        var entity = new KfePaymentRequestEntity();
        entity.setUserId(42L);
        entity.setWalletId(UUID.randomUUID());
        entity.setPublicId("public-id");
        entity.setStatus(KfePaymentRequestStatus.OPEN);
        entity.setRail(KfeRail.LIGHTNING);
        entity.setPaymentHash("hash");
        entity.setProviderReference("provider");
        entity.setPaymentRequest("invoice");
        return entity;
    }

    private static PaymentRequestCancellationSnapshot snapshot(KfePaymentRequestEntity entity) {
        return new PaymentRequestCancellationSnapshot(
                entity.getId(), entity.getUserId(), entity.getWalletId(), entity.getPublicId(),
                PaymentRequestCancellationStatus.valueOf(entity.getStatus().name()),
                PaymentRail.valueOf(entity.getRail().name()), entity.getPaymentHash(),
                entity.getProviderReference(), entity.getPaymentRequest(), entity.getPaidTransactionId());
    }

    private Fixture fixture() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var manager = new DataSourceTransactionManager(dataSource);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(adapter);
        factory.addAdvice(interceptor);
        return new Fixture(
                (PaymentRequestCancellationStatePort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentRequestCancellationStatePort port, TransactionTemplate transaction, Connection connection) {
    }
}
