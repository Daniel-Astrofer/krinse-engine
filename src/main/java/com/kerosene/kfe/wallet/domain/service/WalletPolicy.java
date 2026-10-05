package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure invariants shared by HTTP, workers and non-HTTP custody entry points. */
public final class WalletPolicy {

    /** Bitcoin dust threshold used to reject uneconomic cold-wallet payment outputs. */
    private static final long DUST_SATS = 546L;

    /** Prevents instantiation of the stateless wallet policy utility. */
    private WalletPolicy() {
    }

    /** Requires exact wallet identity, owner match, and active lifecycle state. */
    public static WalletSnapshot requireOwnedActive(
            WalletSnapshot wallet,
            long ownerId,
            UUID requestedWalletId) {
        if (wallet == null || requestedWalletId == null || !requestedWalletId.equals(wallet.id())
                || wallet.ownerId() != ownerId) {
            throw new WalletRuleViolation("KFE wallet not found.");
        }
        if (!wallet.isActive()) {
            throw new WalletRuleViolation("Wallet is not active.");
        }
        return wallet;
    }

    /** Requires the watch-only wallet kind used by offline PSBT preparation. */
    public static void requireColdPsbtWallet(WalletSnapshot wallet) {
        if (wallet == null || !wallet.isCold()) {
            throw new WalletRuleViolation("Cold wallet PSBT creation requires a WATCH_ONLY wallet.");
        }
    }

    /** Validates required payment fields and fee controls, returning a trimmed destination. */
    public static String requireDestination(ColdPsbtRequest request) {
        if (request == null || request.destinationAddress() == null
                || request.destinationAddress().isBlank()) {
            throw new WalletRuleViolation("destinationAddress is required.");
        }
        if (request.amountSats() < DUST_SATS) {
            throw new WalletRuleViolation("amountSats must be at least dust threshold.");
        }
        if (request.confirmationTarget() != null && request.confirmationTarget() < 1) {
            throw new WalletRuleViolation("confirmationTarget must be positive.");
        }
        if (request.feeRateSatsPerVbyte() != null && request.feeRateSatsPerVbyte() < 1) {
            throw new WalletRuleViolation("feeRateSatsPerVbyte must be positive.");
        }
        return request.destinationAddress().trim();
    }

    /**
     * Selects only live inputs. A partially valid client selection is rejected rather than
     * silently reduced, which prevents signing a different transaction than the user approved.
     *
     * @param available current live outputs associated with the wallet
     * @param requested optional explicit user-selected inputs
     * @return immutable, deduplicated selection in deterministic observation/request order
     * @throws WalletRuleViolation when no outputs exist or a requested output is not owned and live
     */
    public static List<UtxoSnapshot> selectOwnedInputs(
            List<UtxoSnapshot> available,
            List<Outpoint> requested) {
        Map<Outpoint, UtxoSnapshot> byOutpoint = new LinkedHashMap<>();
        if (available != null) {
            for (UtxoSnapshot utxo : available) {
                if (utxo != null) {
                    byOutpoint.putIfAbsent(utxo.outpoint(), utxo);
                }
            }
        }
        if (byOutpoint.isEmpty()) {
            throw new WalletRuleViolation("No owned UTXOs are available for this cold wallet.");
        }
        if (requested == null || requested.isEmpty()) {
            return List.copyOf(byOutpoint.values());
        }

        Map<Outpoint, UtxoSnapshot> selected = new LinkedHashMap<>();
        for (Outpoint outpoint : requested) {
            if (outpoint == null || !byOutpoint.containsKey(outpoint)) {
                throw new WalletRuleViolation(
                        "Requested PSBT input is not an owned live UTXO of this wallet.");
            }
            selected.putIfAbsent(outpoint, byOutpoint.get(outpoint));
        }
        return List.copyOf(selected.values());
    }

    /** Restricts this custody flow to Bitcoin-denominated wallets. */
    public static void requireBitcoinWallet(WalletSnapshot wallet) {
        if (!"BTC".equals(wallet.asset())) {
            throw new WalletRuleViolation("Wallet asset is not supported by the Bitcoin custody flow.");
        }
    }

    /** Identifies active receive/monitor addresses eligible for inbound observation. */
    public static boolean isReceiveAddress(AddressSnapshot address) {
        return address != null && address.active()
                && address.address() != null && !address.address().isBlank()
                && (address.role() == null
                || address.role() == com.kerosene.kfe.wallet.domain.model.AddressRole.RECEIVE
                || address.role() == com.kerosene.kfe.wallet.domain.model.AddressRole.MONITOR);
    }
}
