package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.service.UtxoProjectionPolicy;

import java.util.List;
import java.util.Map;

/** Adapts blockchain observations and optional descriptor scans to wallet UTXO snapshots. */
@Component
public final class BitcoinWalletChainAdapter implements WalletChainPort {
    /** Optional blockchain client that supplies address and descriptor scan results. */
    private final ObjectProvider<BlockchainClient> provider;

    /** Creates the adapter with lazy access to the configured blockchain client. */
    public BitcoinWalletChainAdapter(ObjectProvider<BlockchainClient> provider) {
        this.provider = provider;
    }

    /**
     * Merges active-address outputs and a best-effort cold-wallet descriptor scan by outpoint.
     *
     * <p>If the optional descriptor scan fails, known active-address results are retained. Missing
     * the primary blockchain client is a provider availability error and fails the operation.
     */
    @Override
    public List<UtxoSnapshot> listUnspent(
            WalletSnapshot wallet,
            List<AddressSnapshot> activeAddresses,
            int descriptorScanRange) {
        BlockchainClient client = provider.getIfAvailable();
        if (client == null) {
            throw new FinancialProviderUnavailableException(
                    "Blockchain client is unavailable for KFE network data.");
        }
        Map<com.kerosene.kfe.wallet.domain.model.Outpoint, UtxoSnapshot> byOutpoint =
                UtxoProjectionPolicy.newProjection();
        if (activeAddresses != null) {
            for (AddressSnapshot address : activeAddresses) {
                for (BlockchainClient.AddressUtxo utxo :
                        client.getUnspentOutputsMerged(address.address())) {
                    add(byOutpoint, utxo, address.address());
                }
            }
        }
        // Descriptor scans are deliberately best-effort: known active addresses remain usable
        // if Core is busy with another scantxoutset request.
        if (wallet.isCold() && wallet.descriptorConfigured()) {
            try {
                for (BlockchainClient.AddressUtxo utxo : client.getUnspentOutputsFromScan(
                        wallet.descriptor(), descriptorScanRange)) {
                    add(byOutpoint, utxo, utxo.address());
                }
            } catch (RuntimeException ignored) {
                // Keep the address projection when the optional scan is unavailable.
            }
        }
        return List.copyOf(byOutpoint.values());
    }

    /** Converts a provider result to a domain snapshot and merges it by immutable outpoint. */
    private static void add(
            Map<com.kerosene.kfe.wallet.domain.model.Outpoint, UtxoSnapshot> byOutpoint,
            BlockchainClient.AddressUtxo raw,
            String fallbackAddress) {
        if (raw == null || raw.txid() == null || raw.txid().isBlank()) {
            return;
        }
        UtxoSnapshot candidate = new UtxoSnapshot(
                new com.kerosene.kfe.wallet.domain.model.Outpoint(raw.txid(), raw.vout()),
                raw.valueSats(),
                raw.scriptPubKey(),
                hasText(raw.address()) ? raw.address() : fallbackAddress,
                raw.confirmations());
        UtxoProjectionPolicy.merge(byOutpoint, candidate);
    }

    /** Returns whether a provider address contains non-whitespace text. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
