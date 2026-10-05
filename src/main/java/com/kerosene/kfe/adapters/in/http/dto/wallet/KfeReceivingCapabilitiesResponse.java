package com.kerosene.kfe.adapters.in.http.dto.wallet;

import java.util.List;
import java.util.UUID;

/**
 * Describes the rails and constraints that the destination can currently accept from a sender.
 * Capability flags and eligible source wallets are computed for the current requester and destination.
 * @param canReceiveInternal whether an internal ledger transfer can reach the destination
 * @param canReceiveLightning whether a Lightning payment can reach the destination
 * @param canReceiveOnchain whether an on-chain payment can reach the destination
 * @param preferredRail recommended rail selected from currently available options
 * @param missingRequirements human-readable requirements preventing otherwise eligible rails
 * @param receiverDisplayName safe name to show for the receiving user or business
 * @param internalWalletId internal wallet identifier used for ledger routing
 * @param onchainReceiveAddress active on-chain address usable for dual-rail routing
 * @param onchainWalletId wallet owning the active on-chain receiving address
 * @param availableRails rail identifiers the sender may use for this destination
 * @param eligibleSourceWallets sender-owned wallets compatible with one or more available rails
 * @param limits asset, currencies, and minimum amounts that apply to each receiving path
 */
public record KfeReceivingCapabilitiesResponse(
        boolean canReceiveInternal,
        boolean canReceiveLightning,
        boolean canReceiveOnchain,
        String preferredRail,
        List<String> missingRequirements,
        String receiverDisplayName,
        UUID internalWalletId,
        String onchainReceiveAddress,
        UUID onchainWalletId,
        List<String> availableRails,
        List<SenderSourceWallet> eligibleSourceWallets,
        Limits limits) {

    /** Minimums and fiat display currencies applicable to the supported receiving rails.
     * @param asset asset ticker used by the amount limits
     * @param fiatCurrencies fiat currencies available for equivalent-value display
     * @param minInternalSats minimum amount for an internal ledger transfer
     * @param minLightningSats minimum amount for a Lightning payment
     * @param minOnchainSats minimum amount for an on-chain payment
     */
    public record Limits(
            String asset,
            List<String> fiatCurrencies,
            long minInternalSats,
            long minLightningSats,
            long minOnchainSats) {
    }

    /** Sender-owned wallet compatible with at least one destination receiving rail.
     * @param walletId source wallet identifier
     * @param kind wallet category used to determine compatible rails
     * @param label user-facing wallet label
     * @param compatibleRails receiving rails this wallet can fund
     */
    public record SenderSourceWallet(
            UUID walletId,
            String kind,
            String label,
            List<String> compatibleRails) {
    }
}
