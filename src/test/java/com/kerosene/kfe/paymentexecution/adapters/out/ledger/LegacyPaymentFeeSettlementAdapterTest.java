package com.kerosene.kfe.paymentexecution.adapters.out.ledger;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyPaymentFeeSettlementAdapterTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final LegacyPaymentFeeSettlementAdapter adapter =
            new LegacyPaymentFeeSettlementAdapter(repository, fees);
    private final KfeTransactionEntity transaction = new KfeTransactionEntity();
    private final PaymentExecutionId executionId = new PaymentExecutionId(transaction.getId());

    @ParameterizedTest
    @ValueSource(longs = {0L, 123L})
    void usesTheStoredPaymentAndLeavesFeeAndNoOpRulesToTheExistingService(long amountSats) {
        transaction.setKeroseneFeeSats(amountSats);
        when(repository.findById(executionId.value())).thenReturn(Optional.of(transaction));

        adapter.settleFee(executionId);

        verify(fees).creditKeroseneFee(transaction);
    }

    @Test
    void missingPaymentCannotProduceAFeeCredit() {
        when(repository.findById(executionId.value())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.settleFee(executionId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");

        verifyNoInteractions(fees);
    }

    @Test
    void rejectsSettlementWithoutTheOwningTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().settleFee(executionId))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(repository, fees, fixture.connection());
    }

    @Test
    void settlementFailurePropagatesAndPreventsCommitEvenIfCaught() throws Exception {
        var fixture = fixture();
        when(repository.findById(executionId.value())).thenReturn(Optional.of(transaction));
        var failure = new IllegalStateException("fee movement unavailable");
        doThrow(failure).when(fees).creditKeroseneFee(transaction);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().settleFee(executionId)).isSameAs(failure)))
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
                (PaymentFeeSettlementPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentFeeSettlementPort port, TransactionTemplate transaction, Connection connection) {
    }
}
