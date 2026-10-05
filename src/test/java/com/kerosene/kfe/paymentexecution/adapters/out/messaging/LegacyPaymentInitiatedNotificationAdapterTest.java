package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInitiatedNotificationPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.Mockito.*;

class LegacyPaymentInitiatedNotificationAdapterTest {
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final LegacyPaymentInitiatedNotificationAdapter adapter =
            new LegacyPaymentInitiatedNotificationAdapter(notifications);
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
    private final UUID walletId = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(PaymentRail.class)
    void preservesIdentityRailAndGrossAmount(PaymentRail rail) {
        adapter.initiated(7L, executionId, walletId, rail, 10_000L);

        verify(notifications).notifyPaymentInitiated(7L, executionId.value(), walletId, rail.name(), 10_000L);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void refusesCallsWithoutTheOwningTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().initiated(7L, executionId, walletId, PaymentRail.ONCHAIN, 10_000L))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(notifications, fixture.connection());
    }

    @Test
    void delegatesBeforeTheOwningTransactionCommits() throws Exception {
        var fixture = fixture();

        fixture.transaction().executeWithoutResult(status ->
                fixture.port().initiated(7L, executionId, walletId, PaymentRail.ONCHAIN, 10_000L));

        var order = inOrder(notifications, fixture.connection());
        order.verify(notifications).notifyPaymentInitiated(7L, executionId.value(), walletId, "ONCHAIN", 10_000L);
        order.verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void propagatedClientFailureCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("notification configuration unavailable");
        doThrow(failure).when(notifications)
                .notifyPaymentInitiated(7L, executionId.value(), walletId, "ONCHAIN", 10_000L);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().initiated(
                        7L, executionId, walletId, PaymentRail.ONCHAIN, 10_000L)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
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
        return new Fixture((PaymentInitiatedNotificationPort) factory.getProxy(),
                new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentInitiatedNotificationPort port, TransactionTemplate transaction, Connection connection) {}
}
