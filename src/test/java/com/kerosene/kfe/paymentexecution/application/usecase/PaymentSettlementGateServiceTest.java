package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentSettlementGateServiceTest {
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;
    private final PaymentExecutionId executionId = new PaymentExecutionId(UUID.randomUUID());
    private final UUID sourceWalletId = UUID.randomUUID();
    private final PaymentGateBalancePort balance = mock(PaymentGateBalancePort.class);
    private final PaymentGateSolvencyPort solvency = mock(PaymentGateSolvencyPort.class);
    private final PaymentGateQuorumPort quorum = mock(PaymentGateQuorumPort.class);
    private final PaymentGateLightningPort lightning = mock(PaymentGateLightningPort.class);
    private final PaymentGateEnvironmentPort environment = mock(PaymentGateEnvironmentPort.class);
    private final PaymentGateAuditPort audit = mock(PaymentGateAuditPort.class);
    private final PaymentGateTelemetryPort telemetry = mock(PaymentGateTelemetryPort.class);
    private PaymentSettlementGateService gate;

    @BeforeEach
    void setUp() {
        when(balance.lockAvailable(sourceWalletId)).thenReturn(1_000_000L);
        when(quorum.requireConsensus("proposal")).thenReturn(new SettlementQuorumEvidence(3, 3));
        when(lightning.freeOutboundCapacitySats()).thenReturn(-1L);
        when(lightning.evaluateJamming()).thenReturn(new SettlementJammingCheck(true, false, "BETA_LIMITED:TEST"));
        gate = gate("enforce", false, false, 3, 2);
    }

    @Test
    void internalWithoutReserveHasEveryFlagInCanonicalOrderAndNoInfrastructureEffects() {
        var result = gate.evaluate(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, 10_000L, 0L, 10_000L, false));

        assertThat(result.passed()).isTrue();
        assertThat(result.evaluations()).extracting(FlagEvaluation::flag).containsExactly(SettlementFlag.values());
        assertFlag(result, SettlementFlag.V_LOCK_BANDO, true, "LOCK_NOT_REQUIRED");
        assertFlag(result, SettlementFlag.V_SALDO_DISP, true, "RESERVE_NOT_REQUIRED");
        assertFlag(result, SettlementFlag.V_P2P, true, "NOT_APPLICABLE");
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, true, "NOT_APPLICABLE");
        assertFlag(result, SettlementFlag.V_NO_JAMMING, true, "NOT_APPLICABLE");
        assertFlag(result, SettlementFlag.V_CIRCUIT_BREAKER, true, "NOT_APPLICABLE");
        assertThat(result.quorumAckCount()).isEqualTo(3);
        assertThat(result.quorumHealthyNodes()).isEqualTo(3);
        verifyNoInteractions(balance, lightning, solvency, audit, telemetry);
    }

    @Test
    void insufficientAvailableFailsOnlyBalanceFlagAndStillEvaluatesQuorum() {
        when(balance.lockAvailable(sourceWalletId)).thenReturn(500L);
        var result = gate.evaluate(command());
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_SALDO_DISP);
        assertFlag(result, SettlementFlag.V_LOCK_BANDO, true, "ROW_LOCK_ACQUIRED");
        assertFlag(result, SettlementFlag.V_SALDO_DISP, false, "INSUFFICIENT_AVAILABLE");
        var order = inOrder(balance, quorum);
        order.verify(balance).lockAvailable(sourceWalletId);
        order.verify(quorum).requireConsensus("proposal");
        verifyNoInteractions(audit, telemetry);
    }

    @Test
    void availableExactlyCoveringDebitPassesWithoutChangingBalance() {
        when(balance.lockAvailable(sourceWalletId)).thenReturn(1_100L);
        assertThat(gate.evaluate(command()).passed()).isTrue();
        verify(balance).lockAvailable(sourceWalletId);
        verifyNoMoreInteractions(balance);
    }

    @Test
    void missingSourceFailsBothBalanceFlagsWithoutAttemptingLock() {
        var result = gate.evaluate(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, 1_000L, 100L, 1_100L, true));
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_LOCK_BANDO, SettlementFlag.V_SALDO_DISP);
        assertFlag(result, SettlementFlag.V_LOCK_BANDO, false, "SOURCE_WALLET_MISSING");
        assertFlag(result, SettlementFlag.V_SALDO_DISP, false, "SOURCE_WALLET_MISSING");
        verifyNoInteractions(balance);
        verify(quorum).requireConsensus("proposal");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "lock unavailable"})
    void lockRuntimeFailureBecomesTwoFailedFlagsAndEvaluationContinues(String reason) {
        when(balance.lockAvailable(sourceWalletId)).thenThrow(new IllegalStateException(reason));
        var result = gate.evaluate(command());
        String safeReason = reason == null || reason.isBlank() ? "IllegalStateException" : reason;
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_LOCK_BANDO, SettlementFlag.V_SALDO_DISP);
        assertFlag(result, SettlementFlag.V_LOCK_BANDO, false, "ROW_LOCK_FAILED:" + safeReason);
        assertFlag(result, SettlementFlag.V_SALDO_DISP, false, "BALANCE_UNAVAILABLE:" + safeReason);
        verify(quorum).requireConsensus("proposal");
    }

    @Test
    void lockAndQuorumErrorReasonsRetainBoundedLegacyDiagnostics() {
        when(balance.lockAvailable(sourceWalletId)).thenThrow(new IllegalStateException("x".repeat(150)));
        when(quorum.requireConsensus("proposal")).thenThrow(new IllegalStateException("y".repeat(150)));
        var result = gate.evaluate(command());
        assertFlag(result, SettlementFlag.V_LOCK_BANDO, false, "ROW_LOCK_FAILED:" + "x".repeat(120));
        assertFlag(result, SettlementFlag.V_ASSINATURA_MPC, false, "QUORUM_REJECTED:" + "y".repeat(120));
    }

    @ParameterizedTest
    @CsvSource({"false,false,IDEMPOTENCY_NOT_RESERVED", "false,true,IDEMPOTENCY_NOT_RESERVED", "true,false,IDEMPOTENCY_KEY_MISSING"})
    void idempotencyFailureIsReportedWithoutSkippingRemainingChecks(boolean reserved, boolean present, String reason) {
        var original = command();
        var command = new PaymentSettlementGateCommand(1L, executionId, sourceWalletId,
                present ? new IdempotencyKey("key") : null, reserved, original.rail(), original.direction(),
                original.amountSats(), original.networkFeeSats(), original.totalDebitSats(), true, "proposal");
        var result = gate.evaluate(command);
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_IDEMPOTENCIA);
        assertFlag(result, SettlementFlag.V_IDEMPOTENCIA, false, reason);
        verify(balance).lockAvailable(sourceWalletId);
        verify(quorum).requireConsensus("proposal");
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,AMOUNT_NOT_POSITIVE", "-1,0,1,AMOUNT_NOT_POSITIVE", "1,-1,1,FEE_NEGATIVE", "1,0,0,TOTAL_DEBIT_NOT_POSITIVE", "1,0,-1,TOTAL_DEBIT_NOT_POSITIVE", "2100000000000001,0,1,EXCEEDS_MAX_SATS", "1,2100000000000001,1,EXCEEDS_MAX_SATS", "1,0,2100000000000001,EXCEEDS_MAX_SATS"})
    void atomicityRejectsInvalidAmountsWithStableReasons(long amount, long fee, long total, String reason) {
        var result = gate.evaluate(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, amount, fee, total, false));
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_ATOMICIDADE);
        assertFlag(result, SettlementFlag.V_ATOMICIDADE, false, reason);
    }

    @Test
    void atomicityAllowsInclusiveMaximumAndDoesNotInventGrossPlusFeeEquality() {
        var result = gate.evaluate(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, MAX_SATOSHIS, MAX_SATOSHIS, MAX_SATOSHIS, false));
        assertFlag(result, SettlementFlag.V_ATOMICIDADE, true, "INTEGER_SATS_OK");
        // Receiver-side pricing can make debit differ from gross + fee; pricing owns that invariant.
        assertThat(gate.evaluate(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, 1_000L, 100L, 1_000L, false)).passed()).isTrue();
    }

    @Test
    void simulatedBalancesAreRejectedByThePolicy() {
        assertThatThrownBy(() -> new SettlementGatePolicy("enforce", false, true, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simulated balances are forbidden");
    }

    @ParameterizedTest
    @CsvSource({"1,3,false", "2,3,true", "3,3,true", "2,2,true"})
    void quorumRequiresConstitutionThresholdAndPreservesReportedCounts(int accepted, int healthy, boolean pass) {
        when(quorum.requireConsensus("proposal")).thenReturn(new SettlementQuorumEvidence(accepted, healthy));
        var result = gate.evaluate(command());
        assertFlag(result, SettlementFlag.V_ASSINATURA_MPC, pass,
                (pass ? "QUORUM_THRESHOLD_MET:" : "QUORUM_THRESHOLD_NOT_MET:") + accepted + "/3 (threshold=2, healthy=" + healthy + ")");
        assertThat(result.quorumAckCount()).isEqualTo(accepted);
        assertThat(result.quorumHealthyNodes()).isEqualTo(healthy);
    }

    @Test
    void thresholdAboveMemberCountIsRejected() {
        assertThatThrownBy(() -> new SettlementGatePolicy("enforce", false, false, 3, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"3,0,3,0", "3,-1,3,-1", "0,2,1,0", "-2,2,1,-2"})
    void invalidConstitutionConfigurationIsRejected(
            int members, int threshold, int normalizedMembers, int normalizedThreshold) {
        assertThatThrownBy(() -> new SettlementGatePolicy("enforce", false, false, members, threshold))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void missingProposalFailsWithoutRequestingConsensus(String proposal) {
        var original = command();
        var command = new PaymentSettlementGateCommand(1L, executionId, sourceWalletId, new IdempotencyKey("key"), true,
                original.rail(), original.direction(), 1_000L, 100L, 1_100L, true, proposal);
        var result = gate.evaluate(command);
        assertFlag(result, SettlementFlag.V_ASSINATURA_MPC, false, "MISSING_PROPOSAL_HASH");
        assertThat(result.quorumAckCount()).isZero();
        assertThat(result.quorumHealthyNodes()).isZero();
        verifyNoInteractions(quorum);
    }

    @Test
    void quorumFailureIsAFlagRatherThanAnUncaughtGatewayException() {
        when(quorum.requireConsensus("proposal")).thenThrow(new IllegalStateException("quorum down"));
        var result = gate.evaluate(command());
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_ASSINATURA_MPC);
        assertFlag(result, SettlementFlag.V_ASSINATURA_MPC, false, "QUORUM_REJECTED:quorum down");
        assertThat(result.quorumAckCount()).isZero();
        assertThat(result.quorumHealthyNodes()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" BETA-PASS ", "unknown"})
    void permissiveOrUnknownRiskModesAreRejected(String mode) {
        assertThatThrownBy(() -> gate(mode, false, false, 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enforce", " ENFORCE "})
    void enforceModeFailsMissingLightningEvidence(String mode) {
        gate = gate(mode, false, false, 3, 2);
        var result = gate.evaluate(lightningCommand());
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_LIQUIDEZ, SettlementFlag.V_CIRCUIT_BREAKER);
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, false, "LIGHTNING_GATEWAY_NOT_LIVE");
        assertFlag(result, SettlementFlag.V_CIRCUIT_BREAKER, false, "LIGHTNING_GATEWAY_NOT_LIVE");
    }

    @Test
    void unknownLiveCapacityFailsClosed() {
        gate = gate("enforce", false, false, 3, 2);
        when(lightning.isLive()).thenReturn(true);
        var result = gate.evaluate(lightningCommand());
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, false, "OUTBOUND_CAPACITY_UNAVAILABLE");
        assertFlag(result, SettlementFlag.V_CIRCUIT_BREAKER, true, "CIRCUIT_CLOSED");
        verify(lightning, never()).canCoverOutbound(anyLong());
    }

    @Test
    void knownInsufficientCapacityBlocks() {
        gate = gate("enforce", false, false, 3, 2);
        when(lightning.isLive()).thenReturn(true);
        when(lightning.freeOutboundCapacitySats()).thenReturn(500L);
        var result = gate.evaluate(lightningCommand());
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_LIQUIDEZ);
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, false, "INSUFFICIENT_FREE_OUTBOUND_CAPACITY:500");
        verify(lightning).canCoverOutbound(1_100L);
    }

    @Test
    void liveCapacityAllowsLightningAndChecksTotalDebitNotGross() {
        liveLightning();
        var result = gate.evaluate(lightningCommand());
        assertThat(result.passed()).isTrue();
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, true, "FREE_OUTBOUND_CAPACITY_OK:5000000");
        var order = inOrder(balance, lightning, quorum);
        order.verify(balance).lockAvailable(sourceWalletId);
        order.verify(lightning).isLive();
        order.verify(lightning).freeOutboundCapacitySats();
        order.verify(lightning).canCoverOutbound(1_100L);
        order.verify(quorum).requireConsensus("proposal");
        order.verify(lightning).evaluateJamming();
        order.verify(lightning).circuitBreakerOpen();
        order.verify(lightning).isLive();
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,true"})
    void jammingDecisionAlwaysFailsClosedWhenNotAllowed(boolean allowed, boolean hardBlock, boolean pass) {
        gate = gate("enforce", false, false, 3, 2);
        liveLightning();
        when(lightning.evaluateJamming()).thenReturn(new SettlementJammingCheck(allowed, hardBlock, "JAMMING"));
        assertFlag(gate.evaluate(lightningCommand()), SettlementFlag.V_NO_JAMMING, pass, "JAMMING");
    }

    @Test
    void circuitBreakerOpenAlwaysBlocksEvenIfGatewayIsNotLive() {
        gate = gate("enforce", false, false, 3, 2);
        when(lightning.circuitBreakerOpen()).thenReturn(true);
        assertFlag(gate.evaluate(lightningCommand()), SettlementFlag.V_CIRCUIT_BREAKER, false, "OUTBOUND_BELOW_CIRCUIT_FLOOR");
        verify(lightning, times(1)).isLive();
    }

    @ParameterizedTest
    @CsvSource({"LIGHTNING,INBOUND", "ONCHAIN,OUTBOUND", "ONCHAIN,INBOUND", "INTERNAL,INTERNAL"})
    void nonLightningOutboundRoutesNeverConsultLightning(PaymentRail rail, PaymentDirection direction) {
        var result = gate.evaluate(command(rail, direction, sourceWalletId, 1_000L, 100L, 1_100L, true));
        assertFlag(result, SettlementFlag.V_LIQUIDEZ, true, "NOT_APPLICABLE");
        assertFlag(result, SettlementFlag.V_NO_JAMMING, true, "NOT_APPLICABLE");
        assertFlag(result, SettlementFlag.V_CIRCUIT_BREAKER, true, "NOT_APPLICABLE");
        verifyNoInteractions(lightning);
    }

    @Test
    void disabledPorGateDoesNotConsultSolvencyService() {
        assertFlag(gate.evaluate(command()), SettlementFlag.V_RESERVA_MAT, true, "POR_GATE_NOT_ENFORCED");
        verifyNoInteractions(solvency);
    }

    @Test
    void disabledPorServiceBypassesSnapshotButNotOtherFlags() {
        gate = gate("enforce", true, false, 3, 2);
        assertFlag(gate.evaluate(command()), SettlementFlag.V_RESERVA_MAT, true, "POR_SERVICE_DISABLED");
        verify(solvency).isEnabled();
        verifyNoMoreInteractions(solvency);
    }

    @Test
    void noExposureChangeSkipsAggregateSolvency() {
        enablePor();
        assertFlag(gate.evaluate(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, 1_000L, 0L, 1_000L, false)),
                SettlementFlag.V_RESERVA_MAT, true, "NO_EXPOSURE_CHANGE");
        verify(solvency).isEnabled();
        verifyNoMoreInteractions(solvency);
    }

    @Test
    void unavailableLockedBalanceFailsPorWithoutLoadingGlobalBalances() {
        enablePor();
        when(balance.lockAvailable(sourceWalletId)).thenThrow(new IllegalStateException("lock unavailable"));
        assertFlag(gate.evaluate(command()), SettlementFlag.V_RESERVA_MAT, false, "BALANCE_UNAVAILABLE");
        verify(solvency).isEnabled();
        verifyNoMoreInteractions(solvency);
    }

    @Test
    void negativeAvailableAfterFailsPorBeforeGlobalScan() {
        enablePor();
        when(balance.lockAvailable(sourceWalletId)).thenReturn(1_099L);
        assertFlag(gate.evaluate(command()), SettlementFlag.V_RESERVA_MAT, false, "NEGATIVE_AVAILABLE_AFTER");
        verify(solvency).isEnabled();
        verifyNoMoreInteractions(solvency);
    }

    @Test
    void porAggregatesCustomerComponentsAndProfitSeparatelyExcludingOtherWallets() {
        enablePor();
        when(solvency.loadBalances()).thenReturn(List.of(
                new SettlementBalanceSnapshot(SettlementWalletRole.CUSTOMER, 100L, 20L, 30L, 40L, 300L),
                new SettlementBalanceSnapshot(SettlementWalletRole.CUSTOMER, 200L, 10L, 20L, 30L, 400L),
                new SettlementBalanceSnapshot(SettlementWalletRole.SYSTEM_PROFIT, 50L, 500L, 600L, 700L, 8_000L),
                new SettlementBalanceSnapshot(SettlementWalletRole.OTHER, 9_000L, 9_000L, 9_000L, 9_000L, 9_000L)));
        when(solvency.computeSnapshot(450L, 50L, 700L))
                .thenReturn(new SettlementSolvencySnapshot(true, 1.4, 1.0, 500L, 700L, 50L));
        var result = gate.evaluate(command());
        assertThat(result.passed()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_RESERVA_MAT).reason()).startsWith("SOLVENT:").contains("liabilities=500,assets=700");
        var order = inOrder(balance, quorum, solvency);
        order.verify(balance).lockAvailable(sourceWalletId);
        order.verify(quorum).requireConsensus("proposal");
        order.verify(solvency).isEnabled();
        order.verify(solvency).loadBalances();
        order.verify(solvency).computeSnapshot(450L, 50L, 700L);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(telemetry);
    }

    @Test
    void insolvencyFailsWithSnapshotEvidence() {
        enablePor();
        when(solvency.loadBalances()).thenReturn(List.of());
        when(solvency.computeSnapshot(0L, 0L, 0L))
                .thenReturn(new SettlementSolvencySnapshot(false, 0.9, 1.0, 1_000L, 900L, 100L));
        var result = gate.evaluate(command());
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_RESERVA_MAT);
        assertThat(result.byFlag().get(SettlementFlag.V_RESERVA_MAT).reason())
                .startsWith("INSOLVENT:").contains("liabilities=1000,assets=900,buffer=100");
        verifyNoInteractions(telemetry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "snapshot"})
    void porRuntimeFailureBecomesFailedFlagWithDiagnosticTelemetry(String stage) {
        enablePor();
        when(solvency.loadBalances()).thenReturn(List.of());
        if (stage.equals("load")) { when(solvency.loadBalances()).thenThrow(new IllegalStateException("por unavailable")); }
        else { when(solvency.computeSnapshot(0L, 0L, 0L)).thenThrow(new IllegalStateException("por unavailable")); }
        assertFlag(gate.evaluate(command()), SettlementFlag.V_RESERVA_MAT, false, "POR_CHECK_ERROR:por unavailable");
        verify(telemetry).recordSolvencyFailure("por unavailable");
        if (stage.equals("load")) { verify(solvency, never()).computeSnapshot(anyLong(), anyLong(), anyLong()); }
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"liabilities", "profit", "assets"})
    void aggregateOverflowFailsPorBeforeSnapshot(String component) {
        enablePor();
        SettlementWalletRole role = component.equals("profit") ? SettlementWalletRole.SYSTEM_PROFIT : SettlementWalletRole.CUSTOMER;
        when(solvency.loadBalances()).thenReturn(List.of(
                new SettlementBalanceSnapshot(role, component.equals("assets") ? 0L : Long.MAX_VALUE, 0L, 0L, 0L,
                        component.equals("assets") ? Long.MAX_VALUE : 0L),
                new SettlementBalanceSnapshot(role, component.equals("assets") ? 0L : 1L, 0L, 0L, 0L,
                        component.equals("assets") ? 1L : 0L)));
        var result = gate.evaluate(command());
        assertThat(result.byFlag().get(SettlementFlag.V_RESERVA_MAT).pass()).isFalse();
        assertThat(result.byFlag().get(SettlementFlag.V_RESERVA_MAT).reason()).startsWith("POR_CHECK_ERROR:");
        verify(solvency, never()).computeSnapshot(anyLong(), anyLong(), anyLong());
        verify(telemetry).recordSolvencyFailure(anyString());
    }

    @Test
    void requirePassAuditsThenRecordsMetricAndReturnsQuorumEvidence() {
        var result = gate.requirePass(command());
        assertThat(result.quorumAckCount()).isEqualTo(3);
        assertThat(result.quorumHealthyNodes()).isEqualTo(3);
        var evaluation = ArgumentCaptor.forClass(SettlementGateEvaluation.class);
        var order = inOrder(quorum, audit, telemetry);
        order.verify(quorum).requireConsensus("proposal");
        order.verify(audit).record(eq(executionId), eq(sourceWalletId), evaluation.capture());
        order.verify(telemetry).recordSettlementGate(true);
        order.verifyNoMoreInteractions();
        assertThat(evaluation.getValue().passed()).isTrue();
        assertThat(evaluation.getValue().evaluations()).hasSize(SettlementFlag.values().length);
    }

    @Test
    void requirePassAuditsAndRecordsFailureBeforeThrowingStructuredRejection() {
        when(balance.lockAvailable(sourceWalletId)).thenReturn(1L);
        Throwable thrown = catchThrowable(() -> gate.requirePass(command()));
        assertThat(thrown).isInstanceOf(SettlementGateRejectedException.class).hasMessageContaining("V_SALDO_DISP");
        var rejection = (SettlementGateRejectedException) thrown;
        assertThat(rejection.result().failedFlags()).containsExactly(SettlementFlag.V_SALDO_DISP);
        var order = inOrder(audit, telemetry);
        order.verify(audit).record(executionId, sourceWalletId, rejection.result());
        order.verify(telemetry).recordSettlementGate(false);
        order.verifyNoMoreInteractions();
    }

    @Test
    void liquidityRejectionRecordsStressAfterAuditAndGateMetric() {
        when(lightning.isLive()).thenReturn(true);
        when(lightning.freeOutboundCapacitySats()).thenReturn(500L);
        Throwable thrown = catchThrowable(() -> gate.requirePass(lightningCommand()));
        assertThat(thrown).isInstanceOf(SettlementGateRejectedException.class).hasMessageContaining("V_LIQUIDEZ");
        var rejection = (SettlementGateRejectedException) thrown;
        var order = inOrder(audit, telemetry);
        order.verify(audit).record(executionId, sourceWalletId, rejection.result());
        order.verify(telemetry).recordSettlementGate(false);
        order.verify(telemetry).recordLiquidityReject("INSUFFICIENT_FREE_OUTBOUND_CAPACITY:500");
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit", "gate-metric", "liquidity-metric"})
    void mandatoryObserverFailuresPropagateWithoutReturningAnApproval(String stage) {
        var failure = new IllegalStateException("observer unavailable");
        when(lightning.isLive()).thenReturn(true);
        when(lightning.freeOutboundCapacitySats()).thenReturn(500L);
        if (stage.equals("audit")) { doThrow(failure).when(audit).record(any(), any(), any()); }
        else if (stage.equals("gate-metric")) { doThrow(failure).when(telemetry).recordSettlementGate(false); }
        else { doThrow(failure).when(telemetry).recordLiquidityReject(anyString()); }
        assertThatThrownBy(() -> gate.requirePass(lightningCommand())).isSameAs(failure);
        if (stage.equals("audit")) { verifyNoInteractions(telemetry); }
        if (!stage.equals("liquidity-metric")) { verify(telemetry, never()).recordLiquidityReject(anyString()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "capacity", "coverage", "jamming", "circuit", "environment", "por-enabled"})
    void unhandledInfrastructureFailuresAbortBeforeAuditAndApproval(String stage) {
        enablePor();
        liveLightning();
        when(solvency.loadBalances()).thenReturn(List.of());
        when(solvency.computeSnapshot(0L, 0L, 0L)).thenReturn(new SettlementSolvencySnapshot(true, 2.0, 1.0, 0L, 0L, 0L));
        var failure = new IllegalStateException("dependency unavailable");
        switch (stage) {
            case "live" -> when(lightning.isLive()).thenThrow(failure);
            case "capacity" -> when(lightning.freeOutboundCapacitySats()).thenThrow(failure);
            case "coverage" -> when(lightning.canCoverOutbound(anyLong())).thenThrow(failure);
            case "jamming" -> when(lightning.evaluateJamming()).thenThrow(failure);
            case "circuit" -> when(lightning.circuitBreakerOpen()).thenThrow(failure);
            case "environment" -> when(environment.isProduction()).thenThrow(failure);
            case "por-enabled" -> when(solvency.isEnabled()).thenThrow(failure);
        }
        assertThatThrownBy(() -> gate.requirePass(lightningCommand())).isSameAs(failure);
        verifyNoInteractions(audit, telemetry);
    }

    @Test
    void resultCopiesFlagListAndExposesImmutableCanonicalAuditEvidence() {
        var flags = new ArrayList<>(List.of(FlagEvaluation.pass(SettlementFlag.V_IDEMPOTENCIA, "OK"),
                FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "INSUFFICIENT_AVAILABLE")));
        var evaluation = new SettlementGateEvaluation(flags, 2, 3);
        flags.clear();
        assertThat(evaluation.evaluations()).hasSize(2);
        assertThatThrownBy(() -> evaluation.evaluations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.byFlag().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(evaluation.toAuditPayload()).containsEntry("passed", 0)
                .containsEntry("failedFlags", List.of("V_SALDO_DISP"))
                .containsEntry("quorumAckCount", 2).containsEntry("quorumHealthyNodes", 3);
        assertThat(evaluation.byFlag().get(SettlementFlag.V_IDEMPOTENCIA).binary()).isEqualTo(1);
        assertThat(evaluation.byFlag().get(SettlementFlag.V_SALDO_DISP).binary()).isZero();
    }

    private void enablePor() {
        gate = gate("enforce", true, false, 3, 2);
        when(solvency.isEnabled()).thenReturn(true);
    }

    private void liveLightning() {
        when(lightning.isLive()).thenReturn(true);
        when(lightning.freeOutboundCapacitySats()).thenReturn(5_000_000L);
        when(lightning.canCoverOutbound(1_100L)).thenReturn(true);
    }

    private PaymentSettlementGateService gate(String mode, boolean porEnabled, boolean allowSimulation, int members, int threshold) {
        return new PaymentSettlementGateService(balance, solvency, quorum, lightning, environment, audit, telemetry,
                new SettlementGatePolicy(mode, porEnabled, allowSimulation, members, threshold));
    }

    private PaymentSettlementGateCommand command() {
        return command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, sourceWalletId, 1_000L, 100L, 1_100L, true);
    }

    private PaymentSettlementGateCommand lightningCommand() {
        return command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, sourceWalletId, 1_000L, 100L, 1_100L, true);
    }

    private PaymentSettlementGateCommand command(PaymentRail rail, PaymentDirection direction, UUID source,
            long amount, long fee, long total, boolean reserve) {
        return new PaymentSettlementGateCommand(1L, executionId, source, new IdempotencyKey("idemp-key"), true,
                rail, direction, amount, fee, total, reserve, "proposal");
    }

    private static void assertFlag(SettlementGateEvaluation result, SettlementFlag flag, boolean pass, String reason) {
        assertThat(result.byFlag().get(flag)).isEqualTo(new FlagEvaluation(flag, pass, reason));
    }
}
