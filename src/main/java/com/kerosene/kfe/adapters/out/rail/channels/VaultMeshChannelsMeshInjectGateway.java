package com.kerosene.kfe.adapters.out.rail.channels;

import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Mesh CHANNELS inject: soft-reserve → on-chain CHANNELS Taproot PSBT to LND address →
 * openChannel → Intent commit.
 *
 * <p>CHANNELS uses a <strong>dedicated</strong> Taproot key ({@code GET /v1/bitcoin/deposit?bucket=CHANNELS}),
 * never the USERS omnibus {@code tb1p}.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class VaultMeshChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    /** VaultMesh bucket that owns channel funding capital and its signing key. */
    private static final String BUCKET_CHANNELS = "CHANNELS";
    /** Mesh allowlist tag for CHANNELS bucket (not LN peer pubkey). */
    static final String CHANNELS_DESTINATION = "ln-channel-rebalance";

    /** Port for reserving and settling intents and requesting protected PSBT signatures. */
    private final VaultMeshSettlementPort settlementPort;
    /** Optional Bitcoin Core integration used to construct, finalize, and broadcast funding PSBTs. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    /** Confirmation target passed to Bitcoin Core when selecting PSBT inputs. */
    private final int fundConfTarget;
    /** Maximum acceptable PSBT fee in satoshis; negative configuration is clamped to zero. */
    private final long maxFundFeeSats;
    /** Optional explicit fee rate in sat/vB; null delegates fee selection to Bitcoin Core. */
    private final Long fundFeeRateSatVb;

    /**
     * Creates the CHANNELS funding gateway and normalizes its fee-selection settings.
     *
     * @param settlementPort VaultMesh intent and signing operations
     * @param bitcoinCoreRpcClient provider for the optional Bitcoin Core RPC client
     * @param fundConfTarget desired transaction confirmation target, clamped to at least one
     * @param maxFundFeeSats maximum funding fee in satoshis, clamped to zero or greater
     * @param fundFeeRateSatVb explicit fee rate, enabled only when positive
     */
    public VaultMeshChannelsMeshInjectGateway(
            VaultMeshSettlementPort settlementPort,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${kfe.channel.mesh-inject-fund-conf-target:1}") int fundConfTarget,
            @Value("${kfe.channel.mesh-inject-fund-max-fee-sats:50000}") long maxFundFeeSats,
            @Value("${kfe.channel.mesh-inject-fund-fee-rate-sat-vb:0}") long fundFeeRateSatVb) {
        this.settlementPort = settlementPort;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.fundConfTarget = Math.max(1, fundConfTarget);
        this.maxFundFeeSats = Math.max(0L, maxFundFeeSats);
        this.fundFeeRateSatVb = fundFeeRateSatVb > 0L ? fundFeeRateSatVb : null;
    }

    /**
     * Checks the basic channel-open request before any capital reservation is attempted.
     *
     * @param amountSats requested channel capacity in satoshis
     * @param peerPubkey Lightning peer public key; must be present
     * @return authorization result with a stable reason code
     */
    @Override
    public InjectResult authorizeOpen(long amountSats, String peerPubkey) {
        if (amountSats <= 0L) {
            return InjectResult.refuse("CHANNELS_INJECT_INVALID_AMOUNT");
        }
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_PEER");
        }
        return InjectResult.ok("CHANNELS_INJECT_READY");
    }

    /**
     * Reserves CHANNELS capital under the supplied intent and allowlist destination.
     * Recognized duplicate/replay responses are treated as successful idempotent retries.
     *
     * @param intentId stable identifier shared by reserve, funding, and settlement operations
     * @param amountSats amount to reserve in satoshis
     * @param peerPubkey peer key used by the initial request authorization check
     * @return reservation receipt or refusal reason
     */
    @Override
    public DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        InjectResult gate = authorizeOpen(amountSats, peerPubkey);
        if (!gate.authorized()) {
            return DebitResult.refuse(gate.reasonCode());
        }
        if (intentId == null || intentId.isBlank()) {
            return DebitResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }

        VaultMeshIntent intent =
                new VaultMeshIntent(
                        intentId.trim(),
                        BUCKET_CHANNELS,
                        CHANNELS_DESTINATION,
                        amountSats,
                        "",
                        Instant.now(),
                        null,
                        null,
                        null,
                        null);

        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.reserveIntent(intent);
        } catch (RuntimeException ex) {
            return DebitResult.refuse(
                    "CHANNELS_INJECT_VAULT_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }

        if (receipt == null) {
            return DebitResult.refuse("CHANNELS_INJECT_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            if (isIdempotentReserve(receipt.reasonCode())) {
                return DebitResult.ok(
                        intentId.trim(),
                        "CHANNELS_INJECT_RESERVED_IDEMPOTENT:" + intentId.trim());
            }
            return DebitResult.refuse(
                    "CHANNELS_INJECT_RESERVE_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        String reservedId = receipt.intentId() == null || receipt.intentId().isBlank()
                ? intentId.trim()
                : receipt.intentId();
        return DebitResult.ok(reservedId, "CHANNELS_INJECT_RESERVED:" + reservedId);
    }

    /**
     * Builds a fee-capped on-chain funding transaction from the dedicated CHANNELS deposit key,
     * obtains its signature from VaultMesh, finalizes it, and broadcasts it through Bitcoin Core.
     * Address screening here is only a plausibility check; it does not validate a checksum.
     *
     * @param intentId reserved intent identifier used to bind the signing request
     * @param amountSats amount to send to the LND funding address
     * @param lndFundingAddress destination address supplied by LND
     * @return funding result containing the broadcast transaction identifier on success
     */
    @Override
    public FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        if (intentId == null || intentId.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        if (amountSats <= 0L) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_AMOUNT");
        }
        if (lndFundingAddress == null || lndFundingAddress.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_MISSING_LND_ADDRESS");
        }
        String addr = lndFundingAddress.trim();
        if (!isPlausibleBitcoinAddress(addr)) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_LND_ADDRESS");
        }

        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClient.getIfAvailable();
        if (bitcoinCore == null) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_NO_BITCOIN_CORE");
        }

        VaultMeshDepositInfo channelsDeposit = settlementPort.getChannelsDepositAddress();
        if (channelsDeposit == null
                || channelsDeposit.address() == null
                || channelsDeposit.address().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_NO_CHANNELS_DEPOSIT");
        }
        VaultMeshDepositInfo usersDeposit = settlementPort.getUsersDepositAddress();
        if (usersDeposit != null
                && usersDeposit.address() != null
                && channelsDeposit.address().equalsIgnoreCase(usersDeposit.address().trim())) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_KEY_COLLISION_USERS");
        }

        try {
            if (channelsDeposit.descriptor() != null && !channelsDeposit.descriptor().isBlank()) {
                bitcoinCore.importWatchOnlyDescriptor(channelsDeposit.descriptor(), null);
            }
        } catch (RuntimeException ex) {
            // Descriptor may already be imported; continue to PSBT build (fail later if unfunded).
        }

        BitcoinCoreRpcClient.FundedPsbt funded;
        try {
            funded = bitcoinCore.createFundedPsbt(
                    addr, amountSats, fundConfTarget, fundFeeRateSatVb, "bech32m");
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_PSBT_BUILD_FAILED:" + ex.getClass().getSimpleName());
        }
        if (funded == null || funded.psbt() == null || funded.psbt().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_EMPTY_PSBT");
        }
        if (funded.feeSats() > maxFundFeeSats) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_FEE_CAP:" + funded.feeSats() + ">" + maxFundFeeSats);
        }

        String sessionId = "btc-channels-fund-" + intentId.trim().toLowerCase(Locale.ROOT);
        VaultMeshPsbtReceipt signed = settlementPort.signPsbt(
                new VaultMeshPsbtRequest(
                        intentId.trim(),
                        sessionId,
                        BUCKET_CHANNELS,
                        addr,
                        amountSats,
                        funded.psbt(),
                        Boolean.FALSE));
        if (signed.status() == VaultMeshReceipt.Status.FAIL_STOP) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_MESH_FAIL_STOP:"
                            + (signed.reasonCode() == null ? "UNKNOWN" : signed.reasonCode()));
        }
        if (signed.status() != VaultMeshReceipt.Status.ACCEPTED
                || signed.signedPsbt() == null
                || signed.signedPsbt().isBlank()) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_MESH_SIGN_REFUSED:"
                            + (signed.reasonCode() == null ? "UNKNOWN" : signed.reasonCode()));
        }

        BitcoinCoreRpcClient.FinalizedPsbt finalized;
        try {
            finalized = bitcoinCore.finalizePsbt(signed.signedPsbt());
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_FINALIZE_FAILED:" + ex.getClass().getSimpleName());
        }
        if (finalized == null
                || !finalized.complete()
                || finalized.hex() == null
                || finalized.hex().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_FINALIZE_INCOMPLETE");
        }

        String txid;
        try {
            txid = bitcoinCore.sendRawTransaction(finalized.hex());
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_BROADCAST_FAILED:" + ex.getClass().getSimpleName());
        }
        if (txid == null || txid.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_BROADCAST_NO_TXID");
        }

        return FundResult.ok(
                txid.trim(),
                "CHANNELS_INJECT_FUNDED_ONCHAIN:" + txid.trim() + ":" + addr);
    }

    /**
     * Releases a prior CHANNELS reservation after an unsuccessful channel-open workflow.
     * The peer key is not needed by the VaultMesh release operation.
     *
     * @param intentId reservation identifier to release
     * @param amountSats amount associated with the reservation
     * @param peerPubkey peer key retained by the gateway contract but unused for release
     * @return release result or refusal reason
     */
    @Override
    public InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        if (intentId == null || intentId.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.releaseIntent(intentId.trim(), BUCKET_CHANNELS, amountSats);
        } catch (RuntimeException ex) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_RELEASE_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
        if (receipt == null) {
            return InjectResult.refuse("CHANNELS_INJECT_RELEASE_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_RELEASE_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        return InjectResult.ok(
                "CHANNELS_INJECT_RELEASED:" + intentId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Commits the reserved intent after the channel-open workflow succeeds.
     * Recognized duplicate/replay responses are treated as successful idempotent retries.
     *
     * @param intentId identifier of the reservation to commit
     * @return commit result or refusal reason
     */
    @Override
    public InjectResult commitOpen(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.commitIntent(intentId.trim());
        } catch (RuntimeException ex) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_COMMIT_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
        if (receipt == null) {
            return InjectResult.refuse("CHANNELS_INJECT_COMMIT_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            if (isIdempotentCommit(receipt.reasonCode())) {
                return InjectResult.ok("CHANNELS_INJECT_COMMITTED_IDEMPOTENT:" + intentId.trim());
            }
            return InjectResult.refuse(
                    "CHANNELS_INJECT_COMMIT_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        return InjectResult.ok("CHANNELS_INJECT_COMMITTED:" + intentId.trim());
    }

    /**
     * Performs inexpensive prefix and character checks before passing a destination to Bitcoin Core.
     * It deliberately does not check network-specific encoding rules or address checksums.
     *
     * @param addr trimmed destination address
     * @return whether the string resembles a supported Bitcoin address
     */
    private static boolean isPlausibleBitcoinAddress(String addr) {
        String lower = addr.toLowerCase(Locale.ROOT);
        boolean bech32 =
                lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1");
        boolean legacy =
                (lower.startsWith("1") || lower.startsWith("3") || lower.startsWith("m")
                                || lower.startsWith("n") || lower.startsWith("2"))
                        && lower.length() >= 26
                        && lower.chars().allMatch(c ->
                                (c >= '1' && c <= '9')
                                        || (c >= 'a' && c <= 'z')
                                        || (c >= 'A' && c <= 'Z'));
        if (!bech32 && !legacy) {
            return false;
        }
        if (!bech32 && (lower.contains("-") || lower.contains("_") || lower.contains(" "))) {
            return false;
        }
        return true;
    }

    /**
     * Identifies reserve responses that represent an already-applied request.
     *
     * @param reasonCode reason returned by VaultMesh
     * @return true when the response indicates replay, prior reservation, or duplication
     */
    private static boolean isIdempotentReserve(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return false;
        }
        String r = reasonCode.toLowerCase(Locale.ROOT);
        return r.contains("intent replay")
                || r.contains("already reserved")
                || r.contains("already_reserved")
                || r.contains("duplicate");
    }

    /**
     * Identifies commit responses that represent an already-applied request.
     *
     * @param reasonCode reason returned by VaultMesh
     * @return true when the response indicates replay, prior consumption, or duplication
     */
    private static boolean isIdempotentCommit(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return false;
        }
        String r = reasonCode.toLowerCase(Locale.ROOT);
        return r.contains("intent replay")
                || r.contains("already consumed")
                || r.contains("already_committed")
                || r.contains("duplicate");
    }
}
