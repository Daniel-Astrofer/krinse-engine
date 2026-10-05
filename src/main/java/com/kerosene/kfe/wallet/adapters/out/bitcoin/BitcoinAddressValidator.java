package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

/** Validates Bitcoin address syntax against configured network parameters and Core fallback rules. */
@Component
public class BitcoinAddressValidator {
    /** Configured network label used to select address prefixes and witness HRP. */
    private final String bitcoinNetwork;
    /** Optional Core RPC used for newer witness formats unsupported by the bundled parser. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider;

    /** Creates the validator with the configured Bitcoin network and optional Core RPC. */
    public BitcoinAddressValidator(
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider) {
        this.bitcoinNetwork = bitcoinNetwork;
        this.bitcoinCoreProvider = bitcoinCoreProvider;
    }

    /** Returns false for blank/oversized/malformed or wrong-network addresses. */
    public boolean isValidBitcoinAddressForConfiguredNetwork(String address) {
        String candidate = address != null ? address.trim() : "";
        if (candidate.isEmpty() || candidate.length() > 90) {
            return false;
        }
        try {
            NetworkParameters network = configuredBitcoinNetwork();
            Address.fromString(network, candidate);
            return true;
        } catch (AddressFormatException exception) {
            return validateNewerSegwitWithBitcoinCore(candidate);
        }
    }

    /** Uses Core only for consistently cased addresses with the configured network's witness HRP. */
    private boolean validateNewerSegwitWithBitcoinCore(String candidate) {
        NetworkParameters network = configuredBitcoinNetwork();
        String lower = candidate.toLowerCase();
        boolean consistentCase = candidate.equals(lower) || candidate.equals(candidate.toUpperCase());
        if (!consistentCase || !lower.startsWith(network.getSegwitAddressHrp() + "1")) {
            return false;
        }
        try {
            BitcoinCoreRpcClient bitcoinCore = bitcoinCoreProvider.getIfAvailable();
            if (bitcoinCore == null) {
                return false;
            }
            return bitcoinCore.isValidAddress(candidate);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Resolves supported network aliases to bitcoinj parameters or rejects unknown values. */
    private NetworkParameters configuredBitcoinNetwork() {
        String normalized = bitcoinNetwork != null ? bitcoinNetwork.trim().toLowerCase() : "mainnet";
        return switch (normalized) {
            case "main", "mainnet", "livenet" -> MainNetParams.get();
            case "test", "testnet", "testnet3", "testnet4", "signet" -> TestNet3Params.get();
            case "regtest", "reg" -> RegTestParams.get();
            default -> throw new IllegalStateException("Unsupported bitcoin.network: " + bitcoinNetwork);
        };
    }
}
