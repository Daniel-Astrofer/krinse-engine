package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSettlementGateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.settlement.LegacyPaymentGateAuditAdapter;
import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentSettlementGateWiringTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID wallet = UUID.randomUUID();
    private final PaymentGateBalancePort balance = mock(PaymentGateBalancePort.class);
    private final PaymentGateSolvencyPort solvency = mock(PaymentGateSolvencyPort.class);
    private final PaymentGateQuorumPort quorum = mock(PaymentGateQuorumPort.class);
    private final PaymentGateLightningPort lightning = mock(PaymentGateLightningPort.class);
    private final PaymentGateEnvironmentPort environment = mock(PaymentGateEnvironmentPort.class);
    private final PaymentGateTelemetryPort telemetry = mock(PaymentGateTelemetryPort.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);

    @Test
    void graphHasOneInputPortAndKeepsEnforcedDefaultsWithoutCallingDependenciesOutsideATransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PaymentSettlementGateUseCase.class)
                    .hasSingleBean(PaymentGateAuditPort.class).hasSingleBean(SettlementGatePolicy.class);
            assertThat(ctx.getBean(SettlementGatePolicy.class)).isEqualTo(new SettlementGatePolicy("enforce", true, false, 3, 2));
            assertThatThrownBy(() -> ctx.getBean(PaymentSettlementGateUseCase.class).requirePass(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(balance, solvency, quorum, lightning, environment, audit, telemetry, connection);
        });
    }

    @Test
    void permissiveOrInvalidPolicyConfigurationFailsStartup() throws Exception {
        context(mock(Connection.class)).withPropertyValues("kfe.settlement.lightning.risk-gate-mode= BETA-PASS ",
                "kfe.settlement.por-gate-enabled=false",
                "kfe.vaultmesh.constitution.member-count=4", "kfe.vaultmesh.constitution.threshold=9").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("risk-gate-mode");
                });
    }

    @Test
    void acceptedGateAuditsAndRecordsTelemetryBeforeTheCallerCommit() throws Exception {
        ready();
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var result = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .execute(status -> ctx.getBean(PaymentSettlementGateUseCase.class).requirePass(command()));
            assertThat(result).isEqualTo(new PaymentSettlementGateResult(2, 3));
            var order = inOrder(balance, quorum, audit, telemetry, connection);
            order.verify(balance).lockAvailable(wallet);
            order.verify(quorum).requireConsensus("proposal");
            order.verify(audit).record(eq("KFE_SETTLEMENT_GATE"), eq(id.value()), eq(wallet), eq(KfeTransactionStatus.VALIDATING),
                    eq(KfeTransactionStatus.QUORUM_SYNC), anyMap());
            order.verify(telemetry).recordSettlementGate(true);
            order.verify(connection).commit();
            verify(connection, never()).rollback();
            verify(audit, never()).recordInNewTransaction(any(), any(), any(), any(), any(), any());
        });
    }

    @Test
    void capturedGateRejectionStillRequiresRollbackAndDoesNotSkipQuorumOrAudit() throws Exception {
        ready(); when(balance.lockAvailable(wallet)).thenReturn(0L);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PaymentSettlementGateUseCase.class).requirePass(command()))
                            .isInstanceOf(SettlementGateRejectedException.class).hasMessageContaining("V_SALDO_DISP")))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(quorum).requireConsensus("proposal");
            verify(audit).record(eq("KFE_SETTLEMENT_GATE"), eq(id.value()), eq(wallet), eq(KfeTransactionStatus.VALIDATING),
                    eq(KfeTransactionStatus.FAILED), anyMap());
            verify(telemetry).recordSettlementGate(false);
            verify(connection).rollback(); verify(connection, never()).commit();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit", "telemetry"})
    void capturedObserverFailureCannotCommitAnApproval(String stage) throws Exception {
        ready(); var failure = new IllegalStateException("observer unavailable");
        if (stage.equals("audit")) { doThrow(failure).when(audit).record(any(), any(), any(), any(), any(), any()); }
        else { doThrow(failure).when(telemetry).recordSettlementGate(true); }
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PaymentSettlementGateUseCase.class).requirePass(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback(); verify(connection, never()).commit();
            if (stage.equals("audit")) { verifyNoInteractions(telemetry); }
        });
    }

    private void ready() {
        when(balance.lockAvailable(wallet)).thenReturn(10_100L);
        when(quorum.requireConsensus("proposal")).thenReturn(new SettlementQuorumEvidence(2, 3));
    }
    private PaymentSettlementGateCommand command() {
        return new PaymentSettlementGateCommand(7L, id, wallet, new IdempotencyKey("key"), true,
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 10_000L, 100L, 10_100L, true, "proposal");
    }
    private ApplicationContextRunner context(Connection connection) throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(PaymentGateBalancePort.class, () -> balance).withBean(PaymentGateSolvencyPort.class, () -> solvency)
                .withBean(PaymentGateQuorumPort.class, () -> quorum).withBean(PaymentGateLightningPort.class, () -> lightning)
                .withBean(PaymentGateEnvironmentPort.class, () -> environment).withBean(PaymentGateTelemetryPort.class, () -> telemetry)
                .withBean(KfeAuditLogService.class, () -> audit);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentSettlementGateConfiguration.class, TransactionalPaymentSettlementGateAdapter.class, LegacyPaymentGateAuditAdapter.class})
    static class Graph {}
}
