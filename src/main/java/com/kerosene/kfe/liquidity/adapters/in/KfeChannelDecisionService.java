package com.kerosene.kfe.liquidity.adapters.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;
import com.kerosene.kfe.adapters.out.rail.channels.ChannelsMeshInjectGateway;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.liquidity.adapters.out.lightning.KfeLightningJammingGuard;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumGateway;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strict binary AND decisions for channel structural actions (doc §3).
 * Does not execute LND mutations — only evaluates flags.
 */
@Service
public class KfeChannelDecisionService {

    /** Requires unanimous healthy-node approval for structural channel proposals when configured. */
    private final KfeQuorumGateway quorumGateway;
    /** Confirms that structural operating costs can be attributed to SYSTEM_PROFIT. */
    private final KfeSystemWalletService systemWalletService;
    /** Adds peer-jamming/denylist evidence to the open-channel peer gate. */
    private final KfeLightningJammingGuard jammingGuard;
    /** Non-mutating CHANNELS capital authorization check used when mesh injection is mandatory. */
    private final ChannelsMeshInjectGateway channelsMeshInject;
    /** Minimum local channel capital accepted by the open decision. */
    private final long minOpenCapitalSats;
    /** Maximum ordinary on-chain fee rate for an open and bounded safe-close fallback. */
    private final long maxOnchainFeeRateSatVb;
    /** Local-balance ratio below which a channel is considered drained for rebalance evaluation. */
    private final double rebalanceDrainRatio;
    /** Minimum ppm target applied when accelerated drain is requested. */
    private final long drainDeterrentPpm;
    /** Routing ppm threshold used to classify a channel as above break-even. */
    private final long breakEvenPpm;
    /** Normalized peer public keys excluded from structural opens. */
    private final Set<String> peerDenylist;
    /** Whether structural operations require unanimous MPC quorum approval. */
    private final boolean requireMpcForStructural;
    /** Whether an open proposal must be backed by mesh CHANNELS injection authorization. */
    private final boolean requireChannelsMeshInject;

    /**
     * Creates the non-mutating channel policy evaluator and normalizes all configured thresholds.
     *
     * @param quorumGateway unanimous structural-action approval provider
     * @param systemWalletService SYSTEM_PROFIT wallet resolver
     * @param jammingGuard Lightning peer/jamming safety evaluator
     * @param channelsMeshInject mesh capital authorization provider
     * @param minOpenCapitalSats minimum local channel capital, clamped nonnegative
     * @param maxOnchainFeeRateSatVb maximum fee rate, clamped to at least one sat/vbyte
     * @param rebalanceDrainRatio drained-channel ratio threshold, clamped to 0..1
     * @param drainDeterrentPpm minimum accelerated-drain fee target, clamped nonnegative
     * @param breakEvenPpm acceptable baseline routing fee threshold, clamped nonnegative
     * @param peerDenylistCsv comma-separated peer public keys excluded from opening
     * @param requireMpcForStructural require unanimous quorum on structural operations
     * @param requireChannelsMeshInject require mesh authorization for opening
     */
    public KfeChannelDecisionService(
            KfeQuorumGateway quorumGateway,
            KfeSystemWalletService systemWalletService,
            KfeLightningJammingGuard jammingGuard,
            ChannelsMeshInjectGateway channelsMeshInject,
            @Value("${kfe.channel.min-open-capital-sats:10000000}") long minOpenCapitalSats,
            @Value("${kfe.channel.max-onchain-fee-rate-sat-vb:50}") long maxOnchainFeeRateSatVb,
            @Value("${kfe.channel.rebalance-drain-ratio:0.20}") double rebalanceDrainRatio,
            @Value("${kfe.channel.drain-deterrent-ppm:5000}") long drainDeterrentPpm,
            @Value("${kfe.channel.break-even-ppm:1000}") long breakEvenPpm,
            @Value("${kfe.lightning.peer-denylist:}") String peerDenylistCsv,
            @Value("${kfe.channel.require-mpc:true}") boolean requireMpcForStructural,
            @Value("${kfe.channel.require-channels-mesh-inject:false}") boolean requireChannelsMeshInject) {
        this.quorumGateway = quorumGateway;
        this.systemWalletService = systemWalletService;
        this.jammingGuard = jammingGuard;
        this.channelsMeshInject = channelsMeshInject;
        this.minOpenCapitalSats = Math.max(0L, minOpenCapitalSats);
        this.maxOnchainFeeRateSatVb = Math.max(1L, maxOnchainFeeRateSatVb);
        this.rebalanceDrainRatio = Math.min(1.0d, Math.max(0.0d, rebalanceDrainRatio));
        this.drainDeterrentPpm = Math.max(0L, drainDeterrentPpm);
        this.breakEvenPpm = Math.max(0L, breakEvenPpm);
        this.peerDenylist = parseDenylist(peerDenylistCsv);
        this.requireMpcForStructural = requireMpcForStructural;
        this.requireChannelsMeshInject = requireChannelsMeshInject;
    }

    /**
     * Evaluates an OPEN proposal as the conjunction of capital, known/bounded fee, anchors, MPC,
     * peer allowability, and optional mesh-injection gates; it does not mutate provider or ledger state.
     *
     * @param peerPubkey remote peer public key
     * @param localAmountSats requested local channel capital
     * @param estimatedFeeRateSatVb estimated chain fee rate, which must be positive and within the cap
     * @param anchorsEnabled whether the proposal includes the required anchor outputs
     * @param proposalHash stable proposal identity used for quorum approval
     * @return per-flag evidence and aggregate OPEN pass result
     */
    public ChannelDecisionResult evaluateOpen(
            String peerPubkey,
            long localAmountSats,
            long estimatedFeeRateSatVb,
            boolean anchorsEnabled,
            String proposalHash) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        flags.add(localAmountSats >= minOpenCapitalSats
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_CAPITAL_MINIMO, "CAPITAL_OK:" + localAmountSats)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_CAPITAL_MINIMO,
                        "CAPITAL_BELOW_MIN:" + localAmountSats + "<" + minOpenCapitalSats));
        flags.add(estimatedFeeRateSatVb > 0 && estimatedFeeRateSatVb <= maxOnchainFeeRateSatVb
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA, "FEE_RATE_OK:" + estimatedFeeRateSatVb)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA,
                        "FEE_RATE_HIGH_OR_UNKNOWN:" + estimatedFeeRateSatVb));
        flags.add(anchorsEnabled
                ? ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_SAIDA_ANCORA, "ANCHORS_ENABLED")
                : ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_SAIDA_ANCORA, "ANCHORS_REQUIRED"));
        flags.add(evaluateMpc(proposalHash));
        flags.add(evaluatePeerNotDenylisted(peerPubkey));
        flags.add(evaluateChannelsMeshInject(localAmountSats, peerPubkey));
        return new ChannelDecisionResult(KfeChannelOperationType.OPEN, flags);
    }

    /**
     * Evaluates a rebalance using drained local liquidity, positive expected gain net of cost, and
     * SYSTEM_PROFIT wallet availability gates.
     *
     * @param channel current channel snapshot, or null when channel data is unavailable
     * @param estimatedRebalanceCostSats expected rebalance cost
     * @param expectedFutureGainSats expected future routing gain
     * @return per-flag evidence and aggregate REBALANCE pass result
     */
    public ChannelDecisionResult evaluateRebalance(
            LightningChannelGateway.ChannelSnapshot channel,
            long estimatedRebalanceCostSats,
            long expectedFutureGainSats) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        double localRatio = channel != null ? channel.localRatio() : 1.0d;
        flags.add(localRatio < rebalanceDrainRatio
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_LIMIAR_DRENAGEM,
                        "DRAINED:" + formatRatio(localRatio))
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_LIMIAR_DRENAGEM,
                        "NOT_DRAINED:" + formatRatio(localRatio)));
        long net = expectedFutureGainSats - estimatedRebalanceCostSats;
        flags.add(net > 0L
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_LUCRO_MATEMATICO, "NET_POSITIVE:" + net)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_LUCRO_MATEMATICO, "NET_NON_POSITIVE:" + net));
        flags.add(evaluateProfitFund());
        return new ChannelDecisionResult(KfeChannelOperationType.REBALANCE, flags);
    }

    /**
     * Evaluates a CLOSE proposal: the channel must be dead/offline, and close must be cooperative
     * or satisfy the anchor plus bounded-force-fee fallback.
     *
     * @param channel current channel snapshot
     * @param peerOfflineBeyondThreshold whether external monitoring confirms prolonged peer absence
     * @param cooperativePossible whether a cooperative close is available
     * @param anchorsOk whether anchor policy permits the force-close fallback
     * @param currentFeeRateSatVb current chain fee-rate estimate
     * @return per-flag evidence and aggregate CLOSE pass result
     */
    public ChannelDecisionResult evaluateClose(
            LightningChannelGateway.ChannelSnapshot channel,
            boolean peerOfflineBeyondThreshold,
            boolean cooperativePossible,
            boolean anchorsOk,
            long currentFeeRateSatVb) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        boolean dead = peerOfflineBeyondThreshold
                || (channel != null && !channel.active() && channel.pendingHtlcs() == 0);
        flags.add(dead
                ? ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_CANAL_MORTO, "CHANNEL_DEAD_OR_OFFLINE")
                : ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_CANAL_MORTO, "CHANNEL_STILL_VIABLE"));
        boolean safeClose = cooperativePossible
                || (anchorsOk && currentFeeRateSatVb > 0 && currentFeeRateSatVb <= maxOnchainFeeRateSatVb * 3);
        flags.add(safeClose
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_SEGURANCA_DE_FECHAMENTO, "CLOSE_SAFE")
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_SEGURANCA_DE_FECHAMENTO, "CLOSE_UNSAFE_FORCE_FEE"));
        return new ChannelDecisionResult(KfeChannelOperationType.CLOSE, flags);
    }

    /**
     * Evaluates the product's OR rule for fee-policy adjustment: either below-break-even pricing or
     * an accelerated-drain signal is actionable. When one rule is not needed, its returned flag is
     * padded as a pass so the result object's aggregate AND remains true for the OR-selected action.
     *
     * @param currentPpm current channel forwarding fee in parts per million
     * @param acceleratedDrain whether channel monitoring raised a drain alert
     * @return flag evidence whose aggregate passes if either action condition is met
     */
    public ChannelDecisionResult evaluatePpm(long currentPpm, boolean acceleratedDrain) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        flags.add(currentPpm <= breakEvenPpm
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_BELOW_BE:" + currentPpm)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_ABOVE_BE:" + currentPpm));
        flags.add(acceleratedDrain
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "DRAIN_ALERT")
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "NO_DRAIN_ALERT"));
        // PPM adjust is OR of the two product rules in doc — we still return both flags;
        // lifecycle treats pass if either actionable condition holds.
        boolean actionable = currentPpm <= breakEvenPpm || acceleratedDrain;
        if (!actionable) {
            return new ChannelDecisionResult(KfeChannelOperationType.PPM_ADJUST, flags);
        }
        // Force AND product to 1 when any action rule matches by padding a synthetic pass set:
        // recompute as explicit pass list when actionable.
        List<ChannelFlagEvaluation> actionableFlags = new ArrayList<>();
        if (currentPpm <= breakEvenPpm) {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_BELOW_BE:" + currentPpm));
        } else {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "NOT_REQUIRED"));
        }
        if (acceleratedDrain) {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "DRAIN_ALERT:" + drainDeterrentPpm));
        } else {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "NOT_REQUIRED"));
        }
        return new ChannelDecisionResult(KfeChannelOperationType.PPM_ADJUST, actionableFlags);
    }

    /**
     * Recommends a deterrent ppm: accelerated drain raises but never lowers the current fee; otherwise
     * a below-break-even fee is raised above break-even by one quarter of that threshold, capped at
     * the configured drain deterrent. Above-break-even fees are left unchanged.
     *
     * @param currentPpm current forwarding fee in parts per million
     * @param acceleratedDrain whether a drain alert is active
     * @return recommended forwarding ppm
     */
    public long recommendedPpm(long currentPpm, boolean acceleratedDrain) {
        if (acceleratedDrain) {
            return Math.max(currentPpm, drainDeterrentPpm);
        }
        if (currentPpm <= breakEvenPpm) {
            return Math.min(drainDeterrentPpm, Math.max(breakEvenPpm + 1, currentPpm + breakEvenPpm / 4));
        }
        return currentPpm;
    }

    /**
     * Applies the structural MPC policy and requires a nonempty proposal hash plus unanimous healthy
     * node acceptance when quorum enforcement is enabled.
     *
     * @param proposalHash stable digest bound to the proposal
     * @return MPC flag result, including a bounded diagnostic reason on provider failure
     */
    private ChannelFlagEvaluation evaluateMpc(String proposalHash) {
        if (!requireMpcForStructural) {
            return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "MPC_NOT_REQUIRED");
        }
        if (proposalHash == null || proposalHash.isBlank()) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "MISSING_PROPOSAL_HASH");
        }
        try {
            var quorum = quorumGateway.requireHealthyUnanimousConsensus(proposalHash);
            if (quorum.acceptedNodes() > 0 && quorum.acceptedNodes() == quorum.totalHealthyNodes()) {
                return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "QUORUM_UNANIMOUS");
            }
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "QUORUM_NOT_UNANIMOUS");
        } catch (RuntimeException ex) {
            return ChannelFlagEvaluation.fail(
                    ChannelDecisionFlag.V_AUTORIZACAO_MPC,
                    "QUORUM_REJECTED:" + safe(ex));
        }
    }

    /**
     * When enabled (mesh go-live), OPEN must be backed by a wired CHANNELS→LND inject gateway.
     * Decision-gate is non-mutating; ledger soft-reserve happens in
     * {@code KfeChannelLifecycleService.openChannel}. Default adapter is fail-closed
     * ({@code CHANNELS_MESH_INJECT_NOT_WIRED}) — LND wallet balance alone is not mesh CHANNELS capital.
     */
    private ChannelFlagEvaluation evaluateChannelsMeshInject(long amountSats, String peerPubkey) {
        if (!requireChannelsMeshInject) {
            return ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_CHANNELS_MESH_INJECT, "CHANNELS_INJECT_NOT_REQUIRED");
        }
        ChannelsMeshInjectGateway.InjectResult result =
                channelsMeshInject.authorizeOpen(amountSats, peerPubkey);
        if (result.authorized()) {
            return ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_CHANNELS_MESH_INJECT, result.reasonCode());
        }
        return ChannelFlagEvaluation.fail(
                ChannelDecisionFlag.V_CHANNELS_MESH_INJECT, result.reasonCode());
    }

    /**
     * Rejects absent or configured-denylisted peers and also honors denylist-related jamming guard output.
     *
     * @param peerPubkey candidate remote peer public key
     * @return peer gate result
     */
    private ChannelFlagEvaluation evaluatePeerNotDenylisted(String peerPubkey) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_MISSING");
        }
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        if (peerDenylist.contains(normalized)) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_DENYLISTED");
        }
        // Soft check: jamming guard may also block denylisted peers with pending HTLCs.
        var jam = jammingGuard.evaluate();
        if (!jam.allowed() && jam.reason() != null && jam.reason().contains("DENYLIST")) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, jam.reason());
        }
        return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_OK");
    }

    /**
     * Treats a resolvable SYSTEM_PROFIT wallet as the rebalance structural-cost funding gate.
     *
     * @return pass when the profit wallet exists, otherwise a failure with a bounded diagnostic
     */
    private ChannelFlagEvaluation evaluateProfitFund() {
        try {
            systemWalletService.requireProfitWalletId();
            return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_FUNDO_CORRETO, "PROFIT_WALLET_READY");
        } catch (RuntimeException ex) {
            return ChannelFlagEvaluation.fail(
                    ChannelDecisionFlag.V_FUNDO_CORRETO, "PROFIT_WALLET_MISSING:" + safe(ex));
        }
    }

    /** @param ratio local balance fraction to render in stable locale-independent form
     * @return ratio formatted to four decimal places
     */
    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
    }

    /**
     * Bounds exception diagnostics embedded in decision reasons.
     *
     * @param ex provider or wallet failure
     * @return nonblank message or exception type, limited to 100 characters
     */
    private static String safe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    /**
     * Parses peer keys into an immutable, trimmed, case-insensitive denylist.
     *
     * @param csv configured comma-separated peer keys
     * @return normalized immutable denylist, or an empty set for blank input
     */
    private static Set<String> parseDenylist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
