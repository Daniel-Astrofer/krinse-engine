package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

/** Outbound boundary for obtaining an address reserved for transaction change. */
public interface WalletChangeAddressPort {
    /** Issues a change address under the wallet's own descriptor and network policy. */
    String issueChangeAddress(WalletSnapshot wallet);
}
