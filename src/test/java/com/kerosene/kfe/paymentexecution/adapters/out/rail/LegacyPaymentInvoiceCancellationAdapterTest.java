package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyPaymentInvoiceCancellationAdapterTest {

    private final LightningInvoiceGateway gateway = mock(LightningInvoiceGateway.class);
    private final LegacyPaymentInvoiceCancellationAdapter adapter =
            new LegacyPaymentInvoiceCancellationAdapter(gateway);
    private final CancelPaymentInvoiceCommand command =
            new CancelPaymentInvoiceCommand(7L, "payment-hash", "provider-reference", "ln-invoice");
    private final CustodyGateway.LightningInvoiceCancellationCommand providerCommand =
            new CustodyGateway.LightningInvoiceCancellationCommand(
                    7L, null, null, "payment-hash", "provider-reference", "ln-invoice");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsInvoiceIdentityAndReturnsProviderConfirmationUnchanged(boolean confirmed) {
        when(gateway.cancelLightningInvoice(providerCommand)).thenReturn(confirmed);

        assertThat(adapter.cancel(command)).isEqualTo(confirmed);

        verify(gateway).cancelLightningInvoice(providerCommand);
    }

    @Test
    void preservesOptionalInvoiceFields() {
        var optionalCommand = new CancelPaymentInvoiceCommand(7L, null, "reference", null);
        var expected = new CustodyGateway.LightningInvoiceCancellationCommand(
                7L, null, null, null, "reference", null);
        when(gateway.cancelLightningInvoice(expected)).thenReturn(true);

        assertThat(adapter.cancel(optionalCommand)).isTrue();

        verify(gateway).cancelLightningInvoice(expected);
    }

    @Test
    void propagatesProviderFailureWithoutReportingCancellation() {
        var failure = new IllegalStateException("invoice provider unavailable");
        when(gateway.cancelLightningInvoice(providerCommand)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.cancel(command)).isSameAs(failure);
    }

    @Test
    void rejectsCallsWithoutTheOwningCancellationTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().cancel(command))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(gateway, fixture.connection());
    }

    @Test
    void providerFailureMarksTheOwningTransactionRollbackOnlyEvenIfCaught() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("invoice provider unavailable");
        when(gateway.cancelLightningInvoice(providerCommand)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().cancel(command)).isSameAs(failure)))
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
                (PaymentInvoiceCancellationPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(
            PaymentInvoiceCancellationPort port,
            TransactionTemplate transaction,
            Connection connection) {
    }
}
