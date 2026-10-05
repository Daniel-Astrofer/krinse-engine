package com.kerosene.kfe.paymentexecution.adapters.out.remote;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentVaultIntentPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeVaultMeshIntentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LegacyPaymentVaultIntentAdapterTest {
    private final KfeVaultMeshIntentService intents = mock(KfeVaultMeshIntentService.class);
    private final LegacyPaymentVaultIntentAdapter adapter = new LegacyPaymentVaultIntentAdapter(intents);
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
    private final String persistedReference = "persisted-reference";

    @ParameterizedTest(name = "enabled={0}, meshOnly={1}, rail={2}, direction={3}")
    @MethodSource("routingModes")
    void preservesFlagShortCircuitingAndExactIntentInputs(
            boolean enabled, boolean meshOnly, PaymentRail rail, PaymentDirection direction) {
        when(intents.isSubmitOnOutboundEnabled()).thenReturn(enabled);
        when(intents.isMeshOnly()).thenReturn(meshOnly);

        adapter.notifyOutbound(executionId, rail, direction, persistedReference, 10_000L);

        verify(intents).isSubmitOnOutboundEnabled();
        boolean examinesMeshMode = enabled && direction == PaymentDirection.OUTBOUND && rail == PaymentRail.ONCHAIN;
        verify(intents, times(examinesMeshMode ? 1 : 0)).isMeshOnly();
        boolean sends = enabled && direction == PaymentDirection.OUTBOUND && !(rail == PaymentRail.ONCHAIN && meshOnly);
        verify(intents, times(sends ? 1 : 0)).submitOutboundIntent(
                executionId.value(), persistedReference, 10_000L, executionId.value().toString());
        verifyNoMoreInteractions(intents);
    }

    @Test
    void optionalRuntimeFailureDoesNotAbortTheOwningTransaction() throws Exception {
        when(intents.isSubmitOnOutboundEnabled()).thenReturn(true);
        when(intents.submitOutboundIntent(executionId.value(), persistedReference, 10_000L, executionId.value().toString()))
                .thenThrow(new IllegalStateException("remote payload must not be logged"));
        var fixture = fixture();

        fixture.transaction().executeWithoutResult(status -> fixture.port().notifyOutbound(
                executionId, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, persistedReference, 10_000L));

        verify(intents).submitOutboundIntent(executionId.value(), persistedReference, 10_000L, executionId.value().toString());
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void doesNotSwallowErrorsFromSubmittingTheIntent() {
        when(intents.isSubmitOnOutboundEnabled()).thenReturn(true);
        var failure = new AssertionError("broken remote implementation");
        when(intents.submitOutboundIntent(executionId.value(), persistedReference, 10_000L, executionId.value().toString()))
                .thenThrow(failure);

        assertThatThrownBy(() -> adapter.notifyOutbound(executionId, PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND, persistedReference, 10_000L)).isSameAs(failure);
    }

    @Test
    void doesNotSwallowFailureReadingEnabledFlag() {
        var failure = new IllegalStateException("flag unavailable");
        when(intents.isSubmitOnOutboundEnabled()).thenThrow(failure);

        assertThatThrownBy(() -> adapter.notifyOutbound(executionId, PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, persistedReference, 10_000L)).isSameAs(failure);

        verify(intents).isSubmitOnOutboundEnabled();
        verifyNoMoreInteractions(intents);
    }

    @Test
    void failureReadingMeshModeCannotBeCaughtAndCommitted() throws Exception {
        when(intents.isSubmitOnOutboundEnabled()).thenReturn(true);
        var failure = new IllegalStateException("mesh mode unavailable");
        when(intents.isMeshOnly()).thenThrow(failure);
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.port().notifyOutbound(executionId, PaymentRail.ONCHAIN,
                        PaymentDirection.OUTBOUND, persistedReference, 10_000L)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(intents, never()).submitOutboundIntent(any(), any(), anyLong(), any());
    }

    @Test
    void requiresOwningTransactionEvenWhenNotificationIsDisabled() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.port().notifyOutbound(executionId, PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, persistedReference, 10_000L))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(intents, fixture.connection());
    }

    private static Stream<Arguments> routingModes() {
        return Stream.of(false, true).flatMap(enabled -> Stream.of(false, true).flatMap(meshOnly ->
                Arrays.stream(PaymentRail.values()).flatMap(rail -> Arrays.stream(PaymentDirection.values())
                        .map(direction -> Arguments.of(enabled, meshOnly, rail, direction)))));
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
        return new Fixture((PaymentVaultIntentPort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentVaultIntentPort port, TransactionTemplate transaction, Connection connection) {}
}
