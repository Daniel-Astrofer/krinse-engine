package com.kerosene.kfe.wallet.application.port.out;

public interface WalletDestinationPort {
    void requireValidBitcoinAddress(String address);
}
