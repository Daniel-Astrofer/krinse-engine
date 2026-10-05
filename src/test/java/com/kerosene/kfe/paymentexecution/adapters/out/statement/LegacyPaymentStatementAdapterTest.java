package com.kerosene.kfe.paymentexecution.adapters.out.statement;

import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.adapters.out.ledger.LegacyPaymentLedgerAdapter;
import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyPaymentStatementAdapterTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final LegacyPaymentStatementAdapter adapter =
            new LegacyPaymentStatementAdapter(repository, mapper, statements);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());
    private final UUID walletId = UUID.randomUUID();

    @Test
    void preservesParticipantProjectionAndRequestMemo() {
        when(repository.findById(id.value())).thenReturn(Optional.of(tx));
        var projection = Map.<String, Object>of("status", "EXECUTING", "memo", "stored");
        when(mapper.buildDisplayPayload(tx, 42L)).thenReturn(projection);

        adapter.record(new RecordPaymentStatementCommand(42L, id, walletId, " request memo ", false));

        verify(statements).recordUserStatement(
                42L, walletId, tx, Map.of("status", "EXECUTING", "memo", " request memo "));
        assertThat(projection.get("memo")).isEqualTo("stored");
    }

    @Test
    void cancellationAddsMarkerAndPreservesStoredMemo() {
        when(repository.findById(id.value())).thenReturn(Optional.of(tx));
        when(mapper.buildDisplayPayload(tx, 42L)).thenReturn(Map.of("memo", "original", "status", "FAILED"));

        adapter.record(new RecordPaymentStatementCommand(42L, id, walletId, null, true));

        verify(statements).recordUserStatement(
                42L, walletId, tx, Map.of("memo", "original", "status", "FAILED", "cancelled", true));
    }

    @Test
    void rejectsCallsWithoutTheFinancialTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.statementPort().record(command()))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(repository, mapper, statements);
    }

    @Test
    void statementFailureRollsBackLedgerAndSuppressesAfterCommit() throws Exception {
        var fixture = fixture();
        doThrow(new IllegalStateException("statement unavailable")).when(statements)
                .recordUserStatement(any(), any(), any(), any());

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status -> {
            fixture.ledgerPort().reserve(id, walletId, 100L);
            fixture.statementPort().record(command());
        })).isInstanceOf(IllegalStateException.class).hasMessage("statement unavailable");

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        assertThat(fixture.published().get()).isZero();
    }

    @Test
    void ledgerAndStatementShareOneCommitAndPublishOnlyAfterCommit() throws Exception {
        var fixture = fixture();
        fixture.transaction().executeWithoutResult(status -> {
            fixture.ledgerPort().reserve(id, walletId, 100L);
            fixture.statementPort().record(command());
            assertThat(fixture.published().get()).isZero();
            try {
                verify(fixture.connection(), never()).commit();
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        });

        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
        assertThat(fixture.published().get()).isEqualTo(1);
    }

    private RecordPaymentStatementCommand command() {
        return new RecordPaymentStatementCommand(42L, id, walletId, null, false);
    }

    // Real Spring interception with a mocked JDBC connection: verifies transaction participation,
    // rollback and synchronization, not PostgreSQL SQL/locking behavior.
    private Fixture fixture() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var manager = new DataSourceTransactionManager(dataSource);
        var published = new AtomicInteger();
        var balances = mock(KfeBalanceService.class);
        var movements = mock(KfeBalanceMovementRecorder.class);
        when(balances.reserve(walletId, "BTC", 100L)).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    published.incrementAndGet();
                }
            });
            return null;
        });
        when(movements.record(id.value(), walletId, "RESERVE", 100L, "AVAILABLE", "LOCKED")).thenReturn(true);
        when(repository.findById(id.value())).thenReturn(Optional.of(tx));
        when(mapper.buildDisplayPayload(tx, 42L)).thenReturn(Map.of("status", "LOCKED"));
        return new Fixture(
                (PaymentStatementPort) transactionalProxy(adapter, manager),
                (PaymentLedgerPort) transactionalProxy(new LegacyPaymentLedgerAdapter(balances, movements), manager),
                new TransactionTemplate(manager), connection, published);
    }

    private Object transactionalProxy(Object target, DataSourceTransactionManager manager) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return factory.getProxy();
    }

    private record Fixture(
            PaymentStatementPort statementPort,
            PaymentLedgerPort ledgerPort,
            TransactionTemplate transaction,
            Connection connection,
            AtomicInteger published) {
    }
}
