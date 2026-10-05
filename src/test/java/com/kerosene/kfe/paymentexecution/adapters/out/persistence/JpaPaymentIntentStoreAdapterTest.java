package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JpaPaymentIntentStoreAdapterTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final JpaPaymentIntentStoreAdapter adapter = new JpaPaymentIntentStoreAdapter(repository);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,INBOUND", "ONCHAIN,OUTBOUND", "LIGHTNING,INBOUND", "LIGHTNING,OUTBOUND"})
    void mapsExactlyTheInitialFieldsAndPreservesIntentStatusAndUnpricedDefaults(PaymentRail rail, PaymentDirection direction) {
        var intent = new PaymentIntent(42L, new IdempotencyKey("  exact-key  "), rail, direction,
                UUID.randomUUID(), UUID.randomUUID(), 100_000L, "stored-reference", "stored-memo");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var id = adapter.create(intent);

        var argument = ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(repository).save(argument.capture());
        verifyNoMoreInteractions(repository);
        var entity = argument.getValue();
        assertThat(id.value()).isEqualTo(entity.getId());
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getIdempotencyKey()).isEqualTo("  exact-key  ");
        assertThat(entity.getRail()).isEqualTo(KfeRail.valueOf(rail.name()));
        assertThat(entity.getDirection()).isEqualTo(KfeDirection.valueOf(direction.name()));
        assertThat(entity.getSourceWalletId()).isEqualTo(intent.sourceWalletId());
        assertThat(entity.getDestinationWalletId()).isEqualTo(intent.destinationWalletId());
        assertThat(entity.getExternalReference()).isEqualTo("stored-reference");
        assertThat(entity.getMemo()).isEqualTo("stored-memo");
        assertThat(entity.getGrossAmountSats()).isEqualTo(100_000L);
        assertThat(entity.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
        assertThat(entity.getReceiverAmountSats()).isZero();
        assertThat(entity.getNetworkFeeSats()).isZero();
        assertThat(entity.getKeroseneFeeSats()).isZero();
        assertThat(entity.getTotalDebitSats()).isZero();
        assertThat(entity.getQuorumProposalHash()).isNull();
        assertThat(entity.getBlockchainTxid()).isNull();
        assertThat(entity.getFailureCode()).isNull();
    }

    @Test
    void preservesNullableWalletsReferenceAndMemoBeforeLaterValidationAndPricing() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.create(intent());

        var argument = ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(repository).save(argument.capture());
        assertThat(argument.getValue().getSourceWalletId()).isNull();
        assertThat(argument.getValue().getDestinationWalletId()).isNull();
        assertThat(argument.getValue().getExternalReference()).isNull();
        assertThat(argument.getValue().getMemo()).isNull();
    }

    @Test
    void returnsTheIdentityOfThePersistedResult() {
        var persisted = new KfeTransactionEntity();
        when(repository.save(any())).thenReturn(persisted);

        assertThat(adapter.create(intent()).value()).isEqualTo(persisted.getId());
    }

    @Test
    void rejectsCreationWithoutTheOwningFinancialTransaction() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().create(intent())).isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(repository, fixture.connection());
    }

    @Test
    void participatesInTheCallerRollbackWithoutCommittingIndependently() throws Exception {
        var fixture = fixture();
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.transaction().executeWithoutResult(status -> {
            fixture.port().create(intent());
            status.setRollbackOnly();
        });

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(repository).save(any());
    }

    @Test
    void persistenceFailureCannotBeCaughtAndCommitted() throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("intent persistence failed");
        when(repository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().create(intent())).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(42L, new IdempotencyKey("key"), PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL, null, null, 100L, null, null);
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
        return new Fixture((PaymentIntentStore) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentIntentStore port, TransactionTemplate transaction, Connection connection) {
    }
}
