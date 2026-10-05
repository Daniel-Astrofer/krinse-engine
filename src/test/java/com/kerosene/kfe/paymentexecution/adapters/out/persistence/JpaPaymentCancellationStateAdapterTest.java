package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

class JpaPaymentCancellationStateAdapterTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final JpaPaymentCancellationStateAdapter adapter = new JpaPaymentCancellationStateAdapter(repository);

    @Test
    void loadsTheFinancialSnapshotFromTheAlreadyFencedManagedEntity() {
        var entity = transaction();
        var id = new PaymentExecutionId(entity.getId());
        when(repository.findById(id.value())).thenReturn(Optional.of(entity));

        assertThat(adapter.load(id)).isEqualTo(snapshot(entity));

        verify(repository).findById(id.value());
        verify(repository, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsMissingTransactions() {
        var id = new PaymentExecutionId(UUID.randomUUID());
        when(repository.findById(id.value())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.load(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE transaction not found.");
        verify(repository, never()).save(any());
    }

    @Test
    void persistsLegacyCancelledStateAndTrimsTheFailureMessage() {
        var entity = transaction();
        var previous = snapshot(entity);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        adapter.markCancelled(previous, "  " + "x".repeat(300) + "  ");

        assertThat(entity.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(entity.getFailureCode()).isEqualTo("USER_CANCELLED");
        assertThat(entity.getFailureMessage()).isEqualTo("x".repeat(255));
        verify(repository).save(entity);
    }

    @Test
    void preservesNullFailureMessage() {
        var entity = transaction();
        var previous = snapshot(entity);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

        adapter.markCancelled(previous, null);

        assertThat(entity.getFailureMessage()).isNull();
        verify(repository).save(entity);
    }

    @ParameterizedTest
    @MethodSource("financialStateChanges")
    void rejectsEveryChangedSnapshotFieldBeforeWriting(Consumer<KfeTransactionEntity> change) {
        var entity = transaction();
        var previous = snapshot(entity);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        change.accept(entity);

        assertThatThrownBy(() -> adapter.markCancelled(previous, "cancelled"))
                .isInstanceOf(PaymentCancellationRejected.class);

        assertThat(entity.getFailureCode()).isNull();
        assertThat(entity.getFailureMessage()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCallsWithoutTheOwningFinancialTransaction() throws Exception {
        var fixture = fixture();
        var previous = snapshot(transaction());

        assertThatThrownBy(() -> fixture.port().load(previous.executionId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().markCancelled(previous, "cancelled"))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(repository, fixture.connection());
    }

    @Test
    void staleStateFailureCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var entity = transaction();
        var previous = snapshot(entity);
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        entity.setStatus(KfeTransactionStatus.SETTLED);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().markCancelled(previous, "cancelled"))
                        .isInstanceOf(PaymentCancellationRejected.class)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(repository, never()).save(any());
    }

    private static Stream<Consumer<KfeTransactionEntity>> financialStateChanges() {
        return Stream.of(
                entity -> entity.setUserId(99L),
                entity -> entity.setStatus(KfeTransactionStatus.SETTLED),
                entity -> entity.setRail(KfeRail.ONCHAIN),
                entity -> entity.setDirection(KfeDirection.INBOUND),
                entity -> entity.setSourceWalletId(UUID.randomUUID()),
                entity -> entity.setDestinationWalletId(UUID.randomUUID()),
                entity -> entity.setTotalDebitSats(5001L),
                entity -> entity.setBlockchainTxid("observed-on-network"));
    }

    private static KfeTransactionEntity transaction() {
        var entity = new KfeTransactionEntity();
        entity.setUserId(42L);
        entity.setStatus(KfeTransactionStatus.EXECUTING);
        entity.setRail(KfeRail.LIGHTNING);
        entity.setDirection(KfeDirection.OUTBOUND);
        entity.setSourceWalletId(UUID.randomUUID());
        entity.setDestinationWalletId(UUID.randomUUID());
        entity.setTotalDebitSats(5000L);
        return entity;
    }

    private static PaymentCancellationSnapshot snapshot(KfeTransactionEntity entity) {
        return new PaymentCancellationSnapshot(
                new PaymentExecutionId(entity.getId()), entity.getUserId(),
                ExecutionStatus.valueOf(entity.getStatus().name()),
                PaymentRail.valueOf(entity.getRail().name()),
                PaymentDirection.valueOf(entity.getDirection().name()),
                entity.getSourceWalletId(), entity.getDestinationWalletId(),
                entity.getTotalDebitSats(), entity.getBlockchainTxid());
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
                (PaymentCancellationStatePort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentCancellationStatePort port, TransactionTemplate transaction, Connection connection) {
    }
}
