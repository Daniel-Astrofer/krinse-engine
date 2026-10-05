package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfePaymentCancellationAuditAdapterTest {

    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfePaymentCancellationAuditAdapter adapter = new KfePaymentCancellationAuditAdapter(audit);

    @Test
    void preservesTheOutboundEventWalletStatusesAndExactPayload() {
        var previous = snapshot(UUID.randomUUID(), UUID.randomUUID(), PaymentDirection.OUTBOUND);

        adapter.recordCancelled(previous);

        verify(audit).record(
                "KFE_TRANSACTION_CANCELLED", previous.executionId().value(), previous.sourceWalletId(),
                KfeTransactionStatus.LOCKED, KfeTransactionStatus.FAILED,
                Map.of("failureCode", "USER_CANCELLED", "rail", "LIGHTNING", "direction", "OUTBOUND"));
    }

    @Test
    void preservesDestinationWalletForInboundCancellation() {
        var previous = snapshot(null, UUID.randomUUID(), PaymentDirection.INBOUND);

        adapter.recordCancelled(previous);

        verify(audit).record(
                "KFE_TRANSACTION_CANCELLED", previous.executionId().value(), previous.destinationWalletId(),
                KfeTransactionStatus.LOCKED, KfeTransactionStatus.FAILED,
                Map.of("failureCode", "USER_CANCELLED", "rail", "LIGHTNING", "direction", "INBOUND"));
    }

    @Test
    void preservesNullWalletWhenNeitherWalletExists() {
        var previous = snapshot(null, null, PaymentDirection.INBOUND);

        adapter.recordCancelled(previous);

        verify(audit).record(
                "KFE_TRANSACTION_CANCELLED", previous.executionId().value(), null,
                KfeTransactionStatus.LOCKED, KfeTransactionStatus.FAILED,
                Map.of("failureCode", "USER_CANCELLED", "rail", "LIGHTNING", "direction", "INBOUND"));
    }

    @Test
    void rejectsAuditWithoutTheOwningFinancialTransaction() throws Exception {
        var fixture = fixture();
        var previous = snapshot(UUID.randomUUID(), null, PaymentDirection.OUTBOUND);

        assertThatThrownBy(() -> fixture.port().recordCancelled(previous))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(audit, fixture.connection());
    }

    @Test
    void failedAuditCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var previous = snapshot(UUID.randomUUID(), UUID.randomUUID(), PaymentDirection.OUTBOUND);
        var failure = new IllegalStateException("audit unavailable");
        when(audit.record(any(), any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().recordCancelled(previous)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private static PaymentCancellationSnapshot snapshot(
            UUID sourceWalletId, UUID destinationWalletId, PaymentDirection direction) {
        return new PaymentCancellationSnapshot(
                new PaymentExecutionId(UUID.randomUUID()), 42L, ExecutionStatus.LOCKED,
                PaymentRail.LIGHTNING, direction, sourceWalletId, destinationWalletId, 5000L, null);
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
                (PaymentCancellationAuditPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentCancellationAuditPort port, TransactionTemplate transaction, Connection connection) {
    }
}
