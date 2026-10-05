package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.in.KfeProofOfReservesService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.liquidity.adapters.out.lightning.KfeLightningJammingGuard;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumGateway;

import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentGateAdaptersTest {
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeQuorumGateway quorum = mock(KfeQuorumGateway.class);
    private final KfeLightningLiquidityService liquidity = mock(KfeLightningLiquidityService.class);
    private final KfeLightningJammingGuard jamming = mock(KfeLightningJammingGuard.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
    private final UUID wallet = UUID.randomUUID();

    @Test
    void balancePortAcquiresBtcLockAndCopiesOnlyAvailableAmount() {
        var row = new KfeBalanceEntity(); row.setAvailableSats(10100L);
        when(balances.requireForUpdate(wallet, "BTC")).thenReturn(row);
        assertThat(new LegacyPaymentGateBalanceAdapter(balances).lockAvailable(wallet)).isEqualTo(10100L);
        verify(balances).requireForUpdate(wallet, "BTC");
        verifyNoMoreInteractions(balances);
    }

    @Test
    void quorumPortPreservesProposalBytesAndBothCounts() {
        when(quorum.requireHealthyUnanimousConsensus("  proposal  ")).thenReturn(new KfeQuorumGateway.Result(2, 4));
        assertThat(new LegacyPaymentGateQuorumAdapter(quorum).requireConsensus("  proposal  "))
                .isEqualTo(new SettlementQuorumEvidence(2, 4));
        verify(quorum).requireHealthyUnanimousConsensus("  proposal  "); verifyNoMoreInteractions(quorum);
    }

    @Test
    void lightningAdapterLeavesProbeOrderAndReservationDecisionToTheCaller() {
        when(liquidity.isLive()).thenReturn(true); when(liquidity.freeOutboundCapacitySats()).thenReturn(-1L);
        when(liquidity.canCoverOutbound(10100L)).thenReturn(false); when(liquidity.circuitBreakerOpen()).thenReturn(true);
        when(jamming.evaluate()).thenReturn(new KfeLightningJammingGuard.JammingCheck(false, true, "HTLC_LIMIT"));
        var adapter = new LegacyPaymentGateLightningAdapter(liquidity, jamming);
        assertThat(adapter.isLive()).isTrue(); assertThat(adapter.freeOutboundCapacitySats()).isEqualTo(-1L);
        assertThat(adapter.canCoverOutbound(10100L)).isFalse(); assertThat(adapter.circuitBreakerOpen()).isTrue();
        assertThat(adapter.evaluateJamming()).isEqualTo(new SettlementJammingCheck(false, true, "HTLC_LIMIT"));
        var order = inOrder(liquidity, jamming); order.verify(liquidity).isLive();
        order.verify(liquidity).freeOutboundCapacitySats(); order.verify(liquidity).canCoverOutbound(10100L);
        order.verify(liquidity).circuitBreakerOpen(); order.verify(jamming).evaluate();
        verifyNoMoreInteractions(liquidity, jamming);
    }

    @ParameterizedTest
    @CsvSource({"prod,true", "production,true", "PrOd,true", "PRODUCTION,true", "dev,false", "prod-like,false"})
    void environmentRecognizesOnlyExistingCaseInsensitiveProductionProfiles(String profile, boolean production) {
        var environment = new MockEnvironment(); environment.setActiveProfiles("test", profile);
        assertThat(new SpringPaymentGateEnvironmentAdapter(environment).isProduction()).isEqualTo(production);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditUsesOnlyTheCallerAppendAndExactPayload(boolean passed) {
        var result = evaluation(passed);
        new LegacyPaymentGateAuditAdapter(audit).record(id, wallet, result);
        verify(audit).record("KFE_SETTLEMENT_GATE", id.value(), wallet, KfeTransactionStatus.VALIDATING,
                passed ? KfeTransactionStatus.QUORUM_SYNC : KfeTransactionStatus.FAILED, result.toAuditPayload());
        verifyNoMoreInteractions(audit);
    }

    @Test
    void everyFinancialAdapterRequiresAnOwningTransactionBeforeAccessingDependencies() throws Exception {
        var fixture = fixture();
        var balanceRepo = mock(KfeBalanceRepository.class); var walletRepo = mock(KfeWalletRepository.class);
        var reserves = mock(KfeProofOfReservesService.class);
        PaymentGateBalancePort balancePort = proxy(new LegacyPaymentGateBalanceAdapter(balances), fixture.manager());
        PaymentGateSolvencyPort solvencyPort = proxy(new LegacyPaymentGateSolvencyAdapter(balanceRepo, walletRepo, reserves), fixture.manager());
        PaymentGateQuorumPort quorumPort = proxy(new LegacyPaymentGateQuorumAdapter(quorum), fixture.manager());
        PaymentGateLightningPort lightningPort = proxy(new LegacyPaymentGateLightningAdapter(liquidity, jamming), fixture.manager());
        PaymentGateAuditPort auditPort = proxy(new LegacyPaymentGateAuditAdapter(audit), fixture.manager());
        assertThatThrownBy(() -> balancePort.lockAvailable(wallet)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(solvencyPort::isEnabled).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(solvencyPort::loadBalances).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> solvencyPort.computeSnapshot(1L, 2L, 3L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> quorumPort.requireConsensus("hash")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(lightningPort::isLive).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(lightningPort::freeOutboundCapacitySats).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> lightningPort.canCoverOutbound(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(lightningPort::circuitBreakerOpen).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(lightningPort::evaluateJamming).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> auditPort.record(id, wallet, evaluation(true))).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(balances, balanceRepo, walletRepo, reserves, quorum, liquidity, jamming, audit, fixture.connection());
    }

    @Test
    void balanceQuorumAndAuditJoinOneCallerCommitWithoutOpeningANestedTransaction() throws Exception {
        var fixture = fixture(); var row = new KfeBalanceEntity(); row.setAvailableSats(10100L);
        when(balances.requireForUpdate(wallet, "BTC")).thenReturn(row);
        when(quorum.requireHealthyUnanimousConsensus("hash")).thenReturn(new KfeQuorumGateway.Result(2, 3));
        PaymentGateBalancePort balancePort = proxy(new LegacyPaymentGateBalanceAdapter(balances), fixture.manager());
        PaymentGateQuorumPort quorumPort = proxy(new LegacyPaymentGateQuorumAdapter(quorum), fixture.manager());
        PaymentGateAuditPort auditPort = proxy(new LegacyPaymentGateAuditAdapter(audit), fixture.manager());

        new TransactionTemplate(fixture.manager()).executeWithoutResult(status -> {
            balancePort.lockAvailable(wallet); quorumPort.requireConsensus("hash"); auditPort.record(id, wallet, evaluation(true));
        });

        var order = inOrder(balances, quorum, audit, fixture.connection());
        order.verify(balances).requireForUpdate(wallet, "BTC"); order.verify(quorum).requireHealthyUnanimousConsensus("hash");
        order.verify(audit).record(eq("KFE_SETTLEMENT_GATE"), eq(id.value()), eq(wallet), eq(KfeTransactionStatus.VALIDATING),
                eq(KfeTransactionStatus.QUORUM_SYNC), anyMap()); order.verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback(); verify(fixture.source()).getConnection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balance", "quorum", "audit"})
    void caughtAdapterFailureCannotBeCommitted(String stage) throws Exception {
        var fixture = fixture(); var failure = new IllegalStateException("provider unavailable");
        Runnable action;
        if (stage.equals("balance")) {
            when(balances.requireForUpdate(wallet, "BTC")).thenThrow(failure);
            PaymentGateBalancePort port = proxy(new LegacyPaymentGateBalanceAdapter(balances), fixture.manager());
            action = () -> port.lockAvailable(wallet);
        } else if (stage.equals("quorum")) {
            when(quorum.requireHealthyUnanimousConsensus("hash")).thenThrow(failure);
            PaymentGateQuorumPort port = proxy(new LegacyPaymentGateQuorumAdapter(quorum), fixture.manager());
            action = () -> port.requireConsensus("hash");
        } else {
            doThrow(failure).when(audit).record(any(), any(), any(), any(), any(), any());
            PaymentGateAuditPort port = proxy(new LegacyPaymentGateAuditAdapter(audit), fixture.manager());
            action = () -> port.record(id, wallet, evaluation(true));
        }
        assertThatThrownBy(() -> new TransactionTemplate(fixture.manager()).executeWithoutResult(status ->
                assertThatThrownBy(action::run).isSameAs(failure))).isInstanceOf(UnexpectedRollbackException.class);
        verify(fixture.connection()).rollback(); verify(fixture.connection(), never()).commit();
    }

    @Test
    void optionalTelemetryProvidersStayOptionalAndPreserveStressOrderAndFallback() {
        ObjectProvider<KfeLightningOpsMetrics> metricsProvider = mock(ObjectProvider.class);
        ObjectProvider<KfeCapacitySignalStore> signalsProvider = mock(ObjectProvider.class);
        var adapter = new LegacyPaymentGateTelemetryAdapter(metricsProvider, signalsProvider);
        adapter.recordSettlementGate(true); adapter.recordLiquidityReject(null);
        var metrics = mock(KfeLightningOpsMetrics.class); var signals = mock(KfeCapacitySignalStore.class);
        when(metricsProvider.getIfAvailable()).thenReturn(metrics); when(signalsProvider.getIfAvailable()).thenReturn(signals);
        adapter.recordSettlementGate(true); adapter.recordSettlementGate(false); adapter.recordLiquidityReject(null);
        adapter.recordLiquidityReject("FREE_CAPACITY_LOW");
        var order = inOrder(metrics, signals); order.verify(metrics).recordSettlementGate("pass");
        order.verify(metrics).recordSettlementGate("fail"); order.verify(signals).recordLiquidityReject();
        order.verify(metrics).recordLiquidityReject("V_LIQUIDEZ"); order.verify(signals).recordLiquidityReject();
        order.verify(metrics).recordLiquidityReject("FREE_CAPACITY_LOW"); verifyNoMoreInteractions(metrics, signals);
    }

    private SettlementGateEvaluation evaluation(boolean pass) {
        return new SettlementGateEvaluation(List.of(new FlagEvaluation(SettlementFlag.V_SALDO_DISP, pass, "reason")), 2, 3);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object adapter, DataSourceTransactionManager manager) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(adapter); factory.addAdvice(interceptor); return (T) factory.getProxy();
    }
    private Fixture fixture() throws Exception {
        var connection = mock(Connection.class); when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class); when(source.getConnection()).thenReturn(connection);
        return new Fixture(new DataSourceTransactionManager(source), connection, source);
    }
    private record Fixture(DataSourceTransactionManager manager, Connection connection, DataSource source) {}
}
