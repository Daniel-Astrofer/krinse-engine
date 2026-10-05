package com.kerosene.kfe.paymentexecution.adapters.out.ledger;

import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyPaymentLedgerAdapterTest {

    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final LegacyPaymentLedgerAdapter adapter =
            new LegacyPaymentLedgerAdapter(balanceService, movementRecorder);
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
    private final UUID walletId = UUID.randomUUID();

    @Test
    void recordsReservationAfterReservingBalance() {
        when(movementRecorder.record(executionId.value(), walletId, "RESERVE", 100L, "AVAILABLE", "LOCKED"))
                .thenReturn(true);

        adapter.reserve(executionId, walletId, 100L);

        var order = inOrder(balanceService, movementRecorder);
        order.verify(balanceService).reserve(walletId, "BTC", 100L);
        order.verify(movementRecorder).record(executionId.value(), walletId, "RESERVE", 100L, "AVAILABLE", "LOCKED");
    }

    @Test
    void recordsDebitAfterSettlingReservedBalance() {
        when(movementRecorder.record(executionId.value(), walletId, "SETTLE_DEBIT", 100L, "LOCKED", null))
                .thenReturn(true);

        adapter.settleReservedDebit(executionId, walletId, 100L);

        var order = inOrder(balanceService, movementRecorder);
        order.verify(balanceService).settleReservedDebit(walletId, "BTC", 100L);
        order.verify(movementRecorder).record(executionId.value(), walletId, "SETTLE_DEBIT", 100L, "LOCKED", null);
    }

    @Test
    void recordsCreditAfterCreditingBalance() {
        when(movementRecorder.record(executionId.value(), walletId, "CREDIT", 100L, null, "AVAILABLE"))
                .thenReturn(true);

        adapter.creditAvailable(executionId, walletId, 100L);

        var order = inOrder(balanceService, movementRecorder);
        order.verify(balanceService).creditAvailable(walletId, "BTC", 100L);
        order.verify(movementRecorder).record(executionId.value(), walletId, "CREDIT", 100L, null, "AVAILABLE");
    }

    @Test
    void doesNotRecordMovementWhenBalanceMutationFails() {
        var failure = new IllegalStateException("insufficient balance");
        when(balanceService.reserve(walletId, "BTC", 100L)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.reserve(executionId, walletId, 100L)).isSameAs(failure);

        verifyNoInteractions(movementRecorder);
    }

    @Test
    void releasesReservationWithoutAddingANewMovementType() {
        adapter.releaseReserved(executionId, walletId, 100L);

        verify(balanceService).releaseReserved(walletId, "BTC", 100L);
        verifyNoInteractions(movementRecorder);
    }

    @Test
    void refusesMutationWithoutTheOwningTransaction() throws Exception {
        var fixture = transactionalFixture();

        assertThatThrownBy(() -> fixture.port().reserve(executionId, walletId, 100L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().settleReservedDebit(executionId, walletId, 100L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().creditAvailable(executionId, walletId, 100L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().releaseReserved(executionId, walletId, 100L))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(balanceService, movementRecorder, fixture.connection());
    }

    @Test
    void commitsCreditAndMovementWithTheOwningTransaction() throws Exception {
        var fixture = transactionalFixture();
        when(movementRecorder.record(executionId.value(), walletId, "CREDIT", 100L, null, "AVAILABLE"))
                .thenReturn(true);

        fixture.transaction().executeWithoutResult(status ->
                fixture.port().creditAvailable(executionId, walletId, 100L));

        var order = inOrder(balanceService, movementRecorder, fixture.connection());
        order.verify(balanceService).creditAvailable(walletId, "BTC", 100L);
        order.verify(movementRecorder).record(executionId.value(), walletId, "CREDIT", 100L, null, "AVAILABLE");
        order.verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void duplicateCreditMovementRollsBackTheBalanceMutation() throws Exception {
        var fixture = transactionalFixture();
        when(movementRecorder.record(executionId.value(), walletId, "CREDIT", 100L, null, "AVAILABLE"))
                .thenReturn(false);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                fixture.port().creditAvailable(executionId, walletId, 100L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREDIT");

        verify(balanceService).creditAvailable(walletId, "BTC", 100L);
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    @Test
    void ledgerFailureRollsBackEvenWhenTheCallerCatchesIt() throws Exception {
        var fixture = transactionalFixture();
        var failure = new IllegalStateException("movement store unavailable");
        when(movementRecorder.record(executionId.value(), walletId, "RESERVE", 100L, "AVAILABLE", "LOCKED"))
                .thenThrow(failure);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status -> {
            assertThatThrownBy(() -> fixture.port().reserve(executionId, walletId, 100L))
                    .isSameAs(failure);
        })).isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private TransactionalFixture transactionalFixture() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory(adapter);
        proxyFactory.addAdvice(interceptor);
        return new TransactionalFixture(
                (PaymentLedgerPort) proxyFactory.getProxy(),
                new TransactionTemplate(transactionManager),
                connection);
    }

    private record TransactionalFixture(
            PaymentLedgerPort port, TransactionTemplate transaction, Connection connection) {
    }
}
