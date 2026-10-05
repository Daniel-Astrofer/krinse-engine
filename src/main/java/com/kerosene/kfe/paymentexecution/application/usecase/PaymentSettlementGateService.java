package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import java.util.ArrayList;
import java.util.List;

/** Binary gate rules and orchestration; used only within the owning, authorized submit transaction. */
public final class PaymentSettlementGateService {
    /** Maximum supported integer amount in satoshis (21 million BTC). */
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;
    /** Locks and reads source-wallet available balance inside the submit transaction. */
    private final PaymentGateBalancePort balance;
    /** Supplies ledger-derived solvency snapshots when the proof-of-reserve gate is enabled. */
    private final PaymentGateSolvencyPort solvency;
    /** Obtains consensus evidence for the proposal hash. */
    private final PaymentGateQuorumPort quorum;
    /** Supplies outbound Lightning capacity, health, jamming, and circuit-breaker state. */
    private final PaymentGateLightningPort lightning;
    /** Identifies whether the current runtime is production. */
    private final PaymentGateEnvironmentPort environment;
    /** Persists an immutable gate decision under the submit transaction's existing lock. */
    private final PaymentGateAuditPort audit;
    /** Emits gate, liquidity, and solvency outcome measurements. */
    private final PaymentGateTelemetryPort telemetry;
    /** Runtime policy controlling solvency and Lightning-risk enforcement thresholds. */
    private final SettlementGatePolicy policy;

    /**
     * Creates the settlement gate with all probes, audit/telemetry sinks, and policy.
     * @param balance source wallet balance lock and read port
     * @param solvency ledger-derived solvency snapshot port
     * @param quorum consensus evidence port
     * @param lightning Lightning capacity and risk port
     * @param environment production-mode detector
     * @param audit decision audit sink
     * @param telemetry gate telemetry sink
     * @param policy enforcement policy
     */
    public PaymentSettlementGateService(PaymentGateBalancePort balance, PaymentGateSolvencyPort solvency,
            PaymentGateQuorumPort quorum, PaymentGateLightningPort lightning, PaymentGateEnvironmentPort environment,
            PaymentGateAuditPort audit, PaymentGateTelemetryPort telemetry, SettlementGatePolicy policy) {
        this.balance = balance; this.solvency = solvency; this.quorum = quorum; this.lightning = lightning;
        this.environment = environment; this.audit = audit; this.telemetry = telemetry; this.policy = policy;
    }

    /**
     * Evaluates all gates, writes audit and telemetry, then rejects failed results.
     * Must run in the owning authorized submission transaction; it does not create
     * a nested audit transaction or convert a diagnostic result into a retry token.
     * @param command immutable facts about the execution being submitted
     * @return quorum counts required by downstream settlement handling
     * @throws SettlementGateRejectedException when any required gate fails
     */
    public PaymentSettlementGateResult requirePass(PaymentSettlementGateCommand command) {
        var result = evaluate(command);
        // No nested audit transaction: the submit already holds the shared audit appender lock.
        audit.record(command.executionId(), command.sourceWalletId(), result);
        telemetry.recordSettlementGate(result.passed());
        if (!result.passed()) {
            var liquidity = result.byFlag().get(SettlementFlag.V_LIQUIDEZ);
            if (liquidity != null && !liquidity.pass()) {
                telemetry.recordLiquidityReject(liquidity.reason() != null ? liquidity.reason() : "V_LIQUIDEZ");
            }
            throw new SettlementGateRejectedException(result);
        }
        return new PaymentSettlementGateResult(result.quorumAckCount(), result.quorumHealthyNodes());
    }

    /** Diagnostic evaluation still takes locks/probes; it is not an authorization or a retry ticket. */
    public SettlementGateEvaluation evaluate(PaymentSettlementGateCommand command) {
        List<FlagEvaluation> evaluations = new ArrayList<>();
        evaluations.add(evaluateIdempotencia(command));
        LockSaldoOutcome lockSaldo = evaluateLockSaldo(command);
        evaluations.add(lockSaldo.lockFlag());
        evaluations.add(evaluateAtomicidade(command));
        evaluations.add(lockSaldo.saldoFlag());
        evaluations.add(evaluateDinheiroReal());
        evaluations.add(evaluateLiquidez(command));
        evaluations.add(evaluateP2p());
        MpcOutcome mpc = evaluateMpc(command);
        evaluations.add(mpc.flag());
        evaluations.add(evaluateReservaMat(command, lockSaldo));
        evaluations.add(evaluateNoJamming(command));
        evaluations.add(evaluateCircuitBreaker(command));
        return new SettlementGateEvaluation(orderFlags(evaluations), mpc.ackCount(), mpc.healthyNodes());
    }

    /** Requires that idempotency has been reserved and the request key is present. */
    private FlagEvaluation evaluateIdempotencia(PaymentSettlementGateCommand command) {
        if (!command.idempotencyReserved()) {
            return FlagEvaluation.fail(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_NOT_RESERVED");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().value().isBlank()) {
            return FlagEvaluation.fail(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_KEY_MISSING");
        }
        return FlagEvaluation.pass(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_RESERVED_DB");
    }

    /** Validates positive integer amounts, nonnegative fee, maximum range, and sum overflow. */
    private FlagEvaluation evaluateAtomicidade(PaymentSettlementGateCommand command) {
        if (command.amountSats() <= 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "AMOUNT_NOT_POSITIVE");
        }
        if (command.networkFeeSats() < 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "FEE_NEGATIVE");
        }
        if (command.totalDebitSats() <= 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "TOTAL_DEBIT_NOT_POSITIVE");
        }
        if (command.amountSats() > MAX_SATOSHIS
                || command.networkFeeSats() > MAX_SATOSHIS
                || command.totalDebitSats() > MAX_SATOSHIS) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "EXCEEDS_MAX_SATS");
        }
        try {
            Math.addExact(command.amountSats(), command.networkFeeSats());
        } catch (ArithmeticException ex) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "SAT_OVERFLOW");
        }
        return FlagEvaluation.pass(SettlementFlag.V_ATOMICIDADE, "INTEGER_SATS_OK");
    }

    /** Acquires the source-wallet row lock and compares available funds with total debit. */
    private LockSaldoOutcome evaluateLockSaldo(PaymentSettlementGateCommand command) {
        if (!command.requiresSourceReserve()) {
            return new LockSaldoOutcome(
                    FlagEvaluation.pass(SettlementFlag.V_LOCK_BANDO, "LOCK_NOT_REQUIRED"),
                    FlagEvaluation.pass(SettlementFlag.V_SALDO_DISP, "RESERVE_NOT_REQUIRED"), null);
        }
        if (command.sourceWalletId() == null) {
            return new LockSaldoOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_LOCK_BANDO, "SOURCE_WALLET_MISSING"),
                    FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "SOURCE_WALLET_MISSING"), null);
        }
        try {
            long available = balance.lockAvailable(command.sourceWalletId());
            return new LockSaldoOutcome(
                    FlagEvaluation.pass(SettlementFlag.V_LOCK_BANDO, "ROW_LOCK_ACQUIRED"),
                    available < command.totalDebitSats()
                            ? FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "INSUFFICIENT_AVAILABLE")
                            : FlagEvaluation.pass(SettlementFlag.V_SALDO_DISP, "AVAILABLE_COVERS_TOTAL_DEBIT"), available);
        } catch (RuntimeException ex) {
            String reason = safeReason(ex);
            return new LockSaldoOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_LOCK_BANDO, "ROW_LOCK_FAILED:" + reason),
                    FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "BALANCE_UNAVAILABLE:" + reason), null);
        }
    }

    /** Prevents simulated balances in production and records the environment decision. */
    private FlagEvaluation evaluateDinheiroReal() {
        boolean production = environment.isProduction();
        if (production && policy.allowSimulatedBalances()) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_DINHEIRO_REAL,
                    "SIMULATED_BALANCES_FORBIDDEN_IN_PROD");
        }
        if (production) {
            return FlagEvaluation.pass(SettlementFlag.V_DINHEIRO_REAL, "PRODUCTION_NO_SIMULATION");
        }
        return FlagEvaluation.pass(SettlementFlag.V_DINHEIRO_REAL, "NON_PRODUCTION_OK");
    }

    /** Checks live outbound Lightning capacity for Lightning withdrawals. */
    private FlagEvaluation evaluateLiquidez(PaymentSettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_LIQUIDEZ, "NOT_APPLICABLE");
        }
        if (!lightning.isLive()) {
            return lightningRiskFlag(SettlementFlag.V_LIQUIDEZ, "LIGHTNING_GATEWAY_NOT_LIVE");
        }
        long free = lightning.freeOutboundCapacitySats();
        if (free < 0L) {
            return lightningRiskFlag(SettlementFlag.V_LIQUIDEZ, "OUTBOUND_CAPACITY_UNAVAILABLE");
        }
        if (!lightning.canCoverOutbound(command.totalDebitSats())) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_LIQUIDEZ,
                    "INSUFFICIENT_FREE_OUTBOUND_CAPACITY:" + free);
        }
        return FlagEvaluation.pass(SettlementFlag.V_LIQUIDEZ, "FREE_OUTBOUND_CAPACITY_OK:" + free);
    }

    /** Emits the current explicit P2P gate result, which is not applicable to this flow. */
    private FlagEvaluation evaluateP2p() {
        return FlagEvaluation.pass(SettlementFlag.V_P2P, "NOT_APPLICABLE");
    }

    /** Requires a proposal hash and evaluates accepted and healthy MPC quorum counts. */
    private MpcOutcome evaluateMpc(PaymentSettlementGateCommand command) {
        if (command.proposalHash() == null || command.proposalHash().isBlank()) {
            return new MpcOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_ASSINATURA_MPC, "MISSING_PROPOSAL_HASH"),
                    0,
                    0);
        }
        try {
            SettlementQuorumEvidence evidence =
                    quorum.requireConsensus(command.proposalHash());
            int accepted = evidence.acceptedNodes();
            int healthy = evidence.totalHealthyNodes();
            if (accepted >= policy.constitutionThreshold()) {
                String reason = "QUORUM_THRESHOLD_MET:" + accepted + "/" + policy.constitutionMemberCount()
                        + " (threshold=" + policy.constitutionThreshold() + ", healthy=" + healthy + ")";
                return new MpcOutcome(
                        FlagEvaluation.pass(SettlementFlag.V_ASSINATURA_MPC, reason),
                        accepted,
                        healthy);
            }
            String reason = "QUORUM_THRESHOLD_NOT_MET:" + accepted + "/" + policy.constitutionMemberCount()
                    + " (threshold=" + policy.constitutionThreshold() + ", healthy=" + healthy + ")";
            return new MpcOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_ASSINATURA_MPC, reason),
                    accepted,
                    healthy);
        } catch (RuntimeException ex) {
            return new MpcOutcome(
                    FlagEvaluation.fail(
                            SettlementFlag.V_ASSINATURA_MPC,
                            "QUORUM_REJECTED:" + safeReason(ex)),
                    0,
                    0);
        }
    }

    /** Uses the existing ledger-cached reserve proxy, not live UTXO attestation. */
    private FlagEvaluation evaluateReservaMat(PaymentSettlementGateCommand command, LockSaldoOutcome lockSaldo) {
        if (!policy.porGateEnabled()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "POR_GATE_NOT_ENFORCED");
        }
        if (!solvency.isEnabled()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "POR_SERVICE_DISABLED");
        }
        if (!command.requiresSourceReserve()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "NO_EXPOSURE_CHANGE");
        }
        if (lockSaldo.availableSats() == null) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "BALANCE_UNAVAILABLE");
        }

        // Local sanity check: source wallet must have enough available
        long availableAfter = lockSaldo.availableSats() - command.totalDebitSats();
        if (availableAfter < 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "NEGATIVE_AVAILABLE_AFTER");
        }

        // Global solvency check: compute liabilities and assets from ledger
        try {
            List<SettlementBalanceSnapshot> allBalances = solvency.loadBalances();

            long customerLiabilities = computeCustomerLiabilities(allBalances);
            long systemProfitSats = computeSystemProfitBalance(allBalances);
            long eligibleAssets = computeEligibleAssets(allBalances);
            SettlementSolvencySnapshot snapshot =
                    solvency.computeSnapshot(customerLiabilities, systemProfitSats, eligibleAssets);

            if (!snapshot.solvent()) {
                return FlagEvaluation.fail(
                        SettlementFlag.V_RESERVA_MAT,
                        String.format("INSOLVENT:coverage=%.4f,required=%.4f,liabilities=%d,assets=%d,buffer=%d",
                                snapshot.coverageRatio(),
                                snapshot.minimumCoverageRatio(),
                                snapshot.totalLiabilitiesSats(),
                                snapshot.eligibleAssetsSats(),
                                snapshot.safetyBufferSats()));
            }

            return FlagEvaluation.pass(
                    SettlementFlag.V_RESERVA_MAT,
                    String.format("SOLVENT:coverage=%.4f,liabilities=%d,assets=%d",
                            snapshot.coverageRatio(),
                            snapshot.totalLiabilitiesSats(),
                            snapshot.eligibleAssetsSats()));
        } catch (RuntimeException ex) {
            telemetry.recordSolvencyFailure(safeReason(ex));
            return FlagEvaluation.fail(
                    SettlementFlag.V_RESERVA_MAT,
                    "POR_CHECK_ERROR:" + safeReason(ex));
        }
    }

    /** Computes ledger liabilities and eligible assets, then applies the configured solvency gate. */
    private long computeCustomerLiabilities(List<SettlementBalanceSnapshot> balances) {
        long total = 0L;
        for (var b : balances) {
            if (b.role() == SettlementWalletRole.CUSTOMER) {
                // Preserve legacy bucket addition; validating corrupt/overflowing balances is a separate change.
                total = Math.addExact(total, b.availableSats() + b.pendingSats() + b.lockedSats() + b.autoHoldSats());
            }
        }
        return total;
    }

    /** Sums available balances assigned to the system-profit role. */
    private long computeSystemProfitBalance(List<SettlementBalanceSnapshot> balances) {
        long total = 0L;
        for (var b : balances) {
            if (b.role() == SettlementWalletRole.SYSTEM_PROFIT) {
                total = Math.addExact(total, b.availableSats());
            }
        }
        return total;
    }

    /** Sums observed asset amounts for customer-role balances. */
    private long computeEligibleAssets(List<SettlementBalanceSnapshot> balances) {
        long total = 0L;
        for (var b : balances) {
            if (b.role() == SettlementWalletRole.CUSTOMER) {
                total = Math.addExact(total, b.observedSats());
            }
        }
        return total;
    }

    /** Checks the Lightning jamming signal and applies strict or beta-limited policy. */
    private FlagEvaluation evaluateNoJamming(PaymentSettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, "NOT_APPLICABLE");
        }
        SettlementJammingCheck check = lightning.evaluateJamming();
        if (check.allowed()) {
            return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, check.reason());
        }
        if (policy.enforceLightningRisk() || check.hardBlock()) {
            return FlagEvaluation.fail(SettlementFlag.V_NO_JAMMING, check.reason());
        }
        return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, "BETA_LIMITED:" + check.reason());
    }

    /** Fails Lightning withdrawals while the outbound circuit is open or gateway is offline. */
    private FlagEvaluation evaluateCircuitBreaker(PaymentSettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_CIRCUIT_BREAKER, "NOT_APPLICABLE");
        }
        if (lightning.circuitBreakerOpen()) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_CIRCUIT_BREAKER,
                    "OUTBOUND_BELOW_CIRCUIT_FLOOR");
        }
        if (!lightning.isLive()) {
            return lightningRiskFlag(
                    SettlementFlag.V_CIRCUIT_BREAKER, "LIGHTNING_GATEWAY_NOT_LIVE");
        }
        return FlagEvaluation.pass(SettlementFlag.V_CIRCUIT_BREAKER, "CIRCUIT_CLOSED");
    }

    /** Converts unavailable Lightning risk evidence into a fail-closed flag result. */
    private FlagEvaluation lightningRiskFlag(SettlementFlag flag, String missingReason) {
        return FlagEvaluation.fail(flag, missingReason);
    }

    /** Identifies payments that consume outbound Lightning capacity. */
    private boolean isLightningOutbound(PaymentSettlementGateCommand command) {
        return command.rail() == PaymentRail.LIGHTNING && command.direction() == PaymentDirection.OUTBOUND;
    }

    /** Preserves the legacy diagnostic bound; truncation is not secret redaction. */
    private static String safeReason(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    /** Orders one evaluation per declared settlement flag for stable API and audit output. */
    private static List<FlagEvaluation> orderFlags(List<FlagEvaluation> evaluations) {
        List<FlagEvaluation> ordered = new ArrayList<>();
        for (SettlementFlag flag : SettlementFlag.values()) {
            evaluations.stream()
                    .filter(evaluation -> evaluation.flag() == flag)
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        return ordered;
    }

    /**
     * Result of the combined row-lock and available-balance probe.
     * @param lockFlag whether a database row lock was acquired
     * @param saldoFlag whether the locked balance covers the full debit
     * @param availableSats available satoshis observed under the lock, or null if unavailable
     */
    private record LockSaldoOutcome(
            FlagEvaluation lockFlag,
            FlagEvaluation saldoFlag,
            Long availableSats) {}

    /**
     * Consensus flag and member counts returned by the quorum probe.
     * @param flag pass/fail result for the MPC signature quorum
     * @param ackCount number of accepted quorum acknowledgements
     * @param healthyNodes number of healthy quorum members observed
     */
    private record MpcOutcome(
            FlagEvaluation flag,
            int ackCount,
            int healthyNodes) {}
}
