package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionDashboardPort;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentSubmissionDashboardAdapterTest {
    private final KfeDashboardPublisher publisher = mock(KfeDashboardPublisher.class);
    private final LegacyPaymentSubmissionDashboardAdapter adapter = new LegacyPaymentSubmissionDashboardAdapter(publisher);

    @Test
    void delegatesExactRecipientWithoutChangingPublicationContract() {
        adapter.publishAfterCommit(7L);
        verify(publisher).publishAfterCommit(7L);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void rejectsInvalidRecipientWithoutPublishing() {
        assertThatThrownBy(() -> adapter.publishAfterCommit(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.publishAfterCommit(-1L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsCallsWithoutTheOwningTransaction() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.port().publishAfterCommit(7L)).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(publisher, fixture.connection());
    }

    @Test
    void registeredCallbackRunsOnlyAfterCommitAndNotAfterRollback() throws Exception {
        var fixture = fixture();
        var published = new AtomicInteger();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { published.incrementAndGet(); }
            });
            return null;
        }).when(publisher).publishAfterCommit(7L);
        fixture.transaction().executeWithoutResult(status -> {
            fixture.port().publishAfterCommit(7L);
            assertThat(published).hasValue(0);
        });
        assertThat(published).hasValue(1);
        fixture.transaction().executeWithoutResult(status -> {
            fixture.port().publishAfterCommit(7L);
            status.setRollbackOnly();
        });
        assertThat(published).hasValue(1);
        verify(fixture.connection()).commit();
        verify(fixture.connection()).rollback();
    }

    @Test
    void caughtRegistrationFailureMarksTheOwningTransactionRollbackOnly() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("publication registration failed");
        doThrow(failure).when(publisher).publishAfterCommit(7L);
        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().publishAfterCommit(7L)).isSameAs(failure)))
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
        return new Fixture((PaymentSubmissionDashboardPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentSubmissionDashboardPort port, TransactionTemplate transaction, Connection connection) {}
}
