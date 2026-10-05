package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.domain.exception.ExecutionClaimLost;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyExecutionStateAdapterTest {
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final LegacyExecutionStateAdapter adapter = new LegacyExecutionStateAdapter(helper);
    private final ExecutionClaim claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
    private final UUID transactionId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @Test
    void mapsEveryPreparationFieldWithoutChangingFinancialData() {
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(preparation(claim.claimToken()));

        assertThat(adapter.prepare(claim)).contains(new ExecutionPreparation(
                "LIGHTNING_OUTBOUND", transactionId, 42L, "private wallet", walletId,
                "private invoice", 51_234L, 123L, "private memo", "private idempotency",
                "private quorum", 17L, 6));
        verify(helper).prepare(claim.outboxId(), claim.claimToken());
        verifyNoMoreInteractions(helper);
    }

    @Test
    void preservesNullableProjectionFields() {
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(true, "ONCHAIN_OUTBOUND", transactionId,
                        null, null, null, null, 0L, 0L, null, null, null, null, null, claim.claimToken()));

        assertThat(adapter.prepare(claim)).contains(new ExecutionPreparation("ONCHAIN_OUTBOUND", transactionId,
                null, null, null, null, 0L, 0L, null, null, null, null, null));
    }

    @Test
    void nullOrSkippedPreparationNeverCreatesExecutionWork() {
        assertThat(adapter.prepare(claim)).isEmpty();
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(false, null, null, null, null,
                        null, null, 0L, 0L, null, null, null, null, null, null));
        assertThat(adapter.prepare(claim)).isEmpty();
        verify(helper, times(2)).prepare(claim.outboxId(), claim.claimToken());
        verifyNoMoreInteractions(helper);
    }

    @Test
    void differentClaimTokenFailsClosedWithoutLeakingEitherToken() {
        UUID otherToken = UUID.randomUUID();
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(preparation(otherToken));

        assertThatThrownBy(() -> adapter.prepare(claim)).isInstanceOf(ExecutionClaimLost.class)
                .hasMessageContaining(claim.outboxId().toString())
                .hasMessageNotContaining(claim.claimToken().toString())
                .hasMessageNotContaining(otherToken.toString());
        verify(helper).prepare(claim.outboxId(), claim.claimToken());
        verifyNoMoreInteractions(helper);
    }

    @Test
    void absentPreparationClaimTokenFailsClosed() {
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(preparation(null));
        assertThatThrownBy(() -> adapter.prepare(claim)).isInstanceOf(ExecutionClaimLost.class);
    }

    @Test
    void preparationFailurePropagatesWithoutProducingAnyOutcome() {
        var failure = new IllegalStateException("transaction preparation failed");
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenThrow(failure);
        assertThatThrownBy(() -> adapter.prepare(claim)).isSameAs(failure);
        verify(helper).prepare(claim.outboxId(), claim.claimToken());
        verifyNoMoreInteractions(helper);
    }

    @Test
    void allOutcomesForwardExactFencedIdentifiersAndInternalData() {
        adapter.markUnknown(claim, transactionId, "internal reference", "{\"secret\":\"payload\"}", "safe message");
        adapter.markRetryableFailure(claim, transactionId, "RETRY", "retry message");
        adapter.markFinalFailure(claim, transactionId, "FINAL", "final message");

        verify(helper).markUnknown(claim.outboxId(), transactionId, claim.claimToken(),
                "internal reference", "{\"secret\":\"payload\"}", "safe message");
        verify(helper).markRetryableFailure(claim.outboxId(), transactionId, claim.claimToken(), "RETRY", "retry message");
        verify(helper).markFinalFailure(claim.outboxId(), transactionId, claim.claimToken(), "FINAL", "final message");
        verifyNoMoreInteractions(helper);
    }

    enum Outcome { UNKNOWN, RETRYABLE, FINAL }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void persistenceFailurePropagatesWithoutASecondOutcome(Outcome outcome) {
        var failure = new IllegalStateException("commit failed");
        switch (outcome) {
            case UNKNOWN -> {
                doThrow(failure).when(helper).markUnknown(claim.outboxId(), transactionId, claim.claimToken(), "ref", "payload", "message");
                assertThatThrownBy(() -> adapter.markUnknown(claim, transactionId, "ref", "payload", "message")).isSameAs(failure);
                verify(helper).markUnknown(claim.outboxId(), transactionId, claim.claimToken(), "ref", "payload", "message");
            }
            case RETRYABLE -> {
                doThrow(failure).when(helper).markRetryableFailure(claim.outboxId(), transactionId, claim.claimToken(), "code", "message");
                assertThatThrownBy(() -> adapter.markRetryableFailure(claim, transactionId, "code", "message")).isSameAs(failure);
                verify(helper).markRetryableFailure(claim.outboxId(), transactionId, claim.claimToken(), "code", "message");
            }
            case FINAL -> {
                doThrow(failure).when(helper).markFinalFailure(claim.outboxId(), transactionId, claim.claimToken(), "code", "message");
                assertThatThrownBy(() -> adapter.markFinalFailure(claim, transactionId, "code", "message")).isSameAs(failure);
                verify(helper).markFinalFailure(claim.outboxId(), transactionId, claim.claimToken(), "code", "message");
            }
        }
        verifyNoMoreInteractions(helper);
    }

    @Test
    void nullClaimsAreRejectedBeforeTouchingHelper() {
        assertThatThrownBy(() -> adapter.prepare(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.markUnknown(null, transactionId, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.markRetryableFailure(null, transactionId, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.markFinalFailure(null, transactionId, null, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(helper);
    }

    @Test
    void delegatesThroughInjectedProxyRatherThanUnwrappingTransactionalHelper() {
        var intercepted = new AtomicInteger();
        var proxyFactory = new ProxyFactory(helper);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            intercepted.incrementAndGet();
            return invocation.proceed();
        });
        var bridged = new LegacyExecutionStateAdapter((KfeExecutionTransactionHelper) proxyFactory.getProxy());
        bridged.prepare(claim);
        bridged.markUnknown(claim, transactionId, null, null, "safe");
        bridged.markRetryableFailure(claim, transactionId, "RETRY", "safe");
        bridged.markFinalFailure(claim, transactionId, "FINAL", "safe");
        assertThat(intercepted.get()).isEqualTo(4);
    }

    @Test
    void componentExposesBothPortsWithoutExtendingTransactionAroundOrchestration() {
        assertThat(LegacyExecutionStateAdapter.class).isAssignableTo(ExecutionPreparationPort.class)
                .isAssignableTo(ExecutionOutcomePort.class);
        assertThat(LegacyExecutionStateAdapter.class.getAnnotation(Component.class)).isNotNull();
        assertThat(LegacyExecutionStateAdapter.class.getAnnotation(Transactional.class)).isNull();
        for (var method : LegacyExecutionStateAdapter.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class)).isNull();
        }
    }

    private KfeExecutionTransactionHelper.PreparationResult preparation(UUID token) {
        return new KfeExecutionTransactionHelper.PreparationResult(true, "LIGHTNING_OUTBOUND", transactionId,
                42L, "private wallet", walletId, "private invoice", 51_234L, 123L,
                "private memo", "private idempotency", "private quorum", 17L, 6, token);
    }
}
