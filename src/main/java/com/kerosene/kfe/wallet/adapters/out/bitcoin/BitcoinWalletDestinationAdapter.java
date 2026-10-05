package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;

/** Implements destination validation using the Bitcoin network validator. */
@Component
public final class BitcoinWalletDestinationAdapter implements WalletDestinationPort {
    /** Network-aware validator shared with address-oriented wallet operations. */
    private final BitcoinAddressValidator validator;

    /** Creates the port adapter with the configured-network validator. */
    public BitcoinWalletDestinationAdapter(BitcoinAddressValidator validator) {
        this.validator = validator;
    }

    /** Rejects a destination that the validator cannot parse for the configured network. */
    @Override
    public void requireValidBitcoinAddress(String address) {
        if (!validator.isValidBitcoinAddressForConfiguredNetwork(address)) {
            throw new IllegalArgumentException(
                    "destinationAddress is not valid for the configured Bitcoin network.");
        }
    }
}
