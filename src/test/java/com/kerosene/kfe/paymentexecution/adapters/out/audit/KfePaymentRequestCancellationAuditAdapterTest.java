package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import org.junit.jupiter.api.Test;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfePaymentRequestCancellationAuditAdapterTest {

    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfePaymentRequestCancellationAuditAdapter adapter = new KfePaymentRequestCancellationAuditAdapter(audit);

    @Test
    void preservesTheExistingEventWalletAndExactPayloadWithoutProviderCredentials() {
        var previous = snapshot(UUID.randomUUID(), "public-id");

        adapter.recordCancelled(previous);

        verify(audit).record(
                "KFE_PAYMENT_REQUEST_CANCELLED", null, previous.walletId(), null, null,
                Map.of("paymentRequestId", previous.id().toString(), "publicId", "public-id",
                        "previousStatus", "EXPIRED", "rail", "LIGHTNING"));
    }

    @Test
    void acceptsNullableWalletAndPublicIdWithoutAddingSensitiveSnapshotFields() {
        var previous = snapshot(null, null);

        adapter.recordCancelled(previous);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("paymentRequestId", previous.id().toString());
        expected.put("publicId", null);
        expected.put("previousStatus", "EXPIRED");
        expected.put("rail", "LIGHTNING");
        verify(audit).record("KFE_PAYMENT_REQUEST_CANCELLED", null, null, null, null, expected);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void neverIncludesInvoiceHashProviderReferenceOrInvoiceInThePayload() {
        var previous = snapshot(UUID.randomUUID(), "public-id");
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);

        adapter.recordCancelled(previous);

        verify(audit).record(eq("KFE_PAYMENT_REQUEST_CANCELLED"), isNull(), eq(previous.walletId()),
                isNull(), isNull(), payload.capture());
        assertThat(payload.getValue()).doesNotContainKeys("paymentHash", "providerReference", "paymentRequest");
        assertThat(payload.getValue().values()).doesNotContain("sensitive-hash", "sensitive-provider", "sensitive-invoice");
    }

    @Test
    void rejectsAuditWithoutTheOwningFinancialTransaction() throws Exception {
        var fixture = fixture();
        var previous = snapshot(UUID.randomUUID(), "public-id");

        assertThatThrownBy(() -> fixture.port().recordCancelled(previous))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(audit, fixture.connection());
    }

    @Test
    void failedAuditCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var previous = snapshot(UUID.randomUUID(), "public-id");
        var failure = new IllegalStateException("audit unavailable");
        when(audit.record(any(), any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().recordCancelled(previous)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private static PaymentRequestCancellationSnapshot snapshot(UUID walletId, String publicId) {
        return new PaymentRequestCancellationSnapshot(
                UUID.randomUUID(), 42L, walletId, publicId, PaymentRequestCancellationStatus.EXPIRED,
                PaymentRail.LIGHTNING, "sensitive-hash", "sensitive-provider", "sensitive-invoice", null);
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
                (PaymentRequestCancellationAuditPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentRequestCancellationAuditPort port, TransactionTemplate transaction, Connection connection) {
    }
}
