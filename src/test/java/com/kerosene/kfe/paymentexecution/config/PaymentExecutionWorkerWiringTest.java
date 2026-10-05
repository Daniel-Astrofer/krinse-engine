package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.adapters.in.transaction.ProcessExecutionAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExecutionStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.OutboxExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeExecutionOutboxProcessor;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeExecutionOutboxService;
import com.kerosene.kfe.paymentexecution.adapters.in.scheduling.KfeExecutionOutboxWorker;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeRailExecution;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real worker composition; persistence and the financial helper are covered separately in PostgreSQL. */
class PaymentExecutionWorkerWiringTest {
    private final ExecutionClaimPort claims = mock(ExecutionClaimPort.class);
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final KfeRailExecution rail = mock(KfeRailExecution.class);
    private final ExecutionClaim claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
    private final UUID transactionId = UUID.randomUUID();
    private final KfeExecutionTransactionHelper.PreparationResult preparation =
            new KfeExecutionTransactionHelper.PreparationResult(true, "ONCHAIN_OUTBOUND", transactionId,
                    7L, "wallet-label", UUID.randomUUID(), "destination", 5_000L, 500L,
                    "memo", "idempotency-key", "proposal-hash", 2L, 6, claim.claimToken());

    @BeforeEach
    void ready() {
        when(claims.heartbeat(claim)).thenReturn(true);
        when(claims.claimDue(anyString())).thenReturn(List.of(claim));
        when(claims.claimImmediate(claim.outboxId(), "immediate-worker")).thenReturn(Optional.of(claim));
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(preparation);
        when(rail.supports("ONCHAIN_OUTBOUND")).thenReturn(true);
    }

    @Test
    void compositionResolvesSinglePortsWithoutCircularReferencesOrLegacyClaimFacade() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .hasSingleBean(ProcessExecutionUseCase.class)
                    .hasSingleBean(ProcessExecutionService.class)
                    .hasSingleBean(ExecutionClaimPort.class)
                    .hasSingleBean(ExecutionPreparationPort.class)
                    .hasSingleBean(ExecutionOutcomePort.class)
                    .hasSingleBean(ExecutionCommandDispatcher.class)
                    .hasSingleBean(KfeExecutionOutboxProcessor.class)
                    .hasSingleBean(KfeExecutionOutboxWorker.class)
                    .doesNotHaveBean(KfeExecutionOutboxService.class);
            assertThat(ctx.getBean(ProcessExecutionUseCase.class)).isInstanceOf(ProcessExecutionAdapter.class);
            assertThat(ctx.getBean(ExecutionPreparationPort.class))
                    .isSameAs(ctx.getBean(ExecutionOutcomePort.class))
                    .isInstanceOf(LegacyExecutionStateAdapter.class);
            verifyNoInteractions(claims, helper, rail);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "facade", "scheduler", "dispatcher"})
    void everyEntryTraversesProductionPortsAndPreservesTheClaimAndPreparation(String entry) {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        }).when(rail).execute(claim.outboxId(), preparation);

        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            invoke(ctx, entry);

            var order = inOrder(claims, helper, rail);
            if (entry.equals("scheduler")) {
                order.verify(claims).claimDue(anyString());
            } else if (entry.equals("dispatcher")) {
                order.verify(claims).claimImmediate(claim.outboxId(), "immediate-worker");
            }
            order.verify(claims).heartbeat(claim);
            order.verify(helper).prepare(claim.outboxId(), claim.claimToken());
            order.verify(claims).heartbeat(claim);
            order.verify(rail).supports("ONCHAIN_OUTBOUND");
            order.verify(rail).execute(claim.outboxId(), preparation);
            order.verifyNoMoreInteractions();
        });
    }

    @Test
    void helperSkipNeverReachesExternalExecutionOrOutcomePersistence() {
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(false, null, null,
                        null, null, null, null, 0L, 0L, null, null, null, null, null, claim.claimToken()));

        context().run(ctx -> {
            ctx.getBean(ProcessExecutionUseCase.class).process(claim);

            verify(claims).heartbeat(claim);
            verify(helper).prepare(claim.outboxId(), claim.claimToken());
            verifyNoMoreInteractions(claims, helper);
            verifyNoInteractions(rail);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uncertainRpcUsesTheRealOutcomeBridgeAndPersistenceFailureNeverFallsBack(boolean failPersistence) {
        var providerFailure = new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                "private provider detail", "expected-txid", "internal-payload",
                new IllegalArgumentException("must not trigger FINAL_FAILURE"));
        doThrow(providerFailure).when(rail).execute(claim.outboxId(), preparation);
        String safeMessage = ExternalExecutionResult.unknown("expected-txid", "internal-payload").message();
        var persistenceFailure = new IllegalStateException("UNKNOWN commit unavailable");
        if (failPersistence) {
            doThrow(persistenceFailure).when(helper).markUnknown(claim.outboxId(), transactionId,
                    claim.claimToken(), "expected-txid", "internal-payload", safeMessage);
        }

        context().run(ctx -> {
            var input = ctx.getBean(ProcessExecutionUseCase.class);
            if (failPersistence) {
                assertThatThrownBy(() -> input.process(claim)).isSameAs(persistenceFailure);
            } else {
                input.process(claim);
            }

            verify(helper).prepare(claim.outboxId(), claim.claimToken());
            verify(helper).markUnknown(claim.outboxId(), transactionId, claim.claimToken(),
                    "expected-txid", "internal-payload", safeMessage);
            verifyNoMoreInteractions(helper);
            verify(rail).execute(claim.outboxId(), preparation);
        });
    }

    @Test
    void preparationFailurePropagatesWithoutBeingClassifiedAsAProviderFailure() {
        var failure = new IllegalArgumentException("preparation transaction failed");
        when(helper.prepare(claim.outboxId(), claim.claimToken())).thenThrow(failure);

        context().run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(ProcessExecutionUseCase.class).process(claim)).isSameAs(failure);
            verify(helper).prepare(claim.outboxId(), claim.claimToken());
            verifyNoMoreInteractions(helper);
            verifyNoInteractions(rail);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "facade", "scheduler", "dispatcher"})
    void everyPublicEntryRejectsAmbientTransactionsBeforeClaimsOrFinancialEffects(String entry) throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));

        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            transaction.executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThatThrownBy(() -> invoke(ctx, entry)).isInstanceOf(IllegalStateException.class);
                verifyNoInteractions(claims, helper, rail);
            });
        });

        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void disablingTheSchedulerDoesNotRemoveTheImmediateDispatchOrExecutionPorts() {
        context().withPropertyValues("kfe.execution.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(KfeExecutionOutboxWorker.class)
                    .hasSingleBean(ExecutionCommandDispatcher.class).hasSingleBean(ProcessExecutionUseCase.class);
            assertThat(ctx.getBean(ExecutionCommandDispatcher.class)
                    .dispatchImmediately(claim.outboxId(), "immediate-worker"))
                    .isEqualTo(ExecutionCommandDispatcher.DispatchResult.PROCESSED);
            verify(rail).execute(claim.outboxId(), preparation);
        });
    }

    private void invoke(org.springframework.context.ApplicationContext context, String entry) {
        switch (entry) {
            case "input" -> context.getBean(ProcessExecutionUseCase.class).process(claim);
            case "facade" -> context.getBean(KfeExecutionOutboxProcessor.class).process(
                    new KfeExecutionOutboxService.ExecutionClaim(claim.outboxId(), claim.claimToken()));
            case "scheduler" -> context.getBean(KfeExecutionOutboxWorker.class).drain();
            case "dispatcher" -> assertThat(context.getBean(ExecutionCommandDispatcher.class)
                    .dispatchImmediately(claim.outboxId(), "immediate-worker"))
                    .isEqualTo(ExecutionCommandDispatcher.DispatchResult.PROCESSED);
            default -> throw new IllegalArgumentException("Unknown test entry");
        }
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withAllowCircularReferences(false)
                .withUserConfiguration(Graph.class)
                .withBean(ExecutionClaimPort.class, () -> claims)
                .withBean(KfeExecutionTransactionHelper.class, () -> helper)
                .withBean(KfeFinancialNotificationOutboxService.class,
                        () -> mock(KfeFinancialNotificationOutboxService.class))
                .withBean(KfeRailExecution.class, () -> rail);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PaymentExecutionWorkerConfiguration.class, ProcessExecutionAdapter.class,
            LegacyExecutionStateAdapter.class, KfeExecutionOutboxProcessor.class,
            OutboxExecutionCommandDispatcher.class, KfeExecutionOutboxWorker.class})
    static class Graph {}
}
