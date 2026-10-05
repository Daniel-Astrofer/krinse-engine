package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.List;

/** Outbound boundary for discovering wallet outputs from the configured chain source. */
public interface WalletChainPort {
    /**
     * Lists unspent outputs for the wallet's currently active addresses.
     *
     * @param wallet immutable wallet snapshot containing its chain descriptor/configuration
     * @param activeAddresses addresses in scope for this scan
     * @param descriptorScanRange bounded derivation lookahead used when expanding descriptors
     * @return chain observations; callers decide how to present or filter their spendability
     */
    List<UtxoSnapshot> listUnspent(
            WalletSnapshot wallet,
            List<AddressSnapshot> activeAddresses,
            int descriptorScanRange);
}
