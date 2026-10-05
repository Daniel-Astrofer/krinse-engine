package com.kerosene.kfe.paymentexecution.adapters.out.liquidity;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyPaymentLiquidityAdapterTest {

    private final KfeLightningLiquidityService liquidity = mock(KfeLightningLiquidityService.class);
    private final LegacyPaymentLiquidityAdapter adapter = new LegacyPaymentLiquidityAdapter(liquidity);
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());

    @Test
    void delegatesReservationAndReleaseUsingThePaymentId() {
        adapter.reserve(executionId, 123L);
        adapter.release(executionId);

        verify(liquidity).reserveForTransaction(executionId.value(), 123L);
        verify(liquidity).releaseForTransaction(executionId.value());
    }

    @Test
    void rejectsCallsWithoutTheOwningTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().reserve(executionId, 123L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.port().release(executionId))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(liquidity, fixture.connection());
    }

    @Test
    void reservationFailurePropagatesAndRollsBackTheOwningTransaction() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("insufficient outbound capacity");
        doThrow(failure).when(liquidity).reserveForTransaction(executionId.value(), 123L);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                fixture.port().reserve(executionId, 123L))).isSameAs(failure);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    @Test
    void failedReleaseCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("liquidity store unavailable");
        doThrow(failure).when(liquidity).releaseForTransaction(executionId.value());

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().release(executionId)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
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
                (PaymentLiquidityPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentLiquidityPort port, TransactionTemplate transaction, Connection connection) {
    }
}
