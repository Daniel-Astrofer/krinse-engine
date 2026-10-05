package com.kerosene.kfe.wallet.application.port.out;

import java.util.UUID;

/** Outbound boundary for provisioning custody or MPC material for a wallet. */
public interface WalletKeyProvisioningPort {
    /**
     * Provisions the key material associated with a newly created wallet.
     *
     * @param walletId stable identity allocated before external provisioning begins
     * @param ownerId account that controls the wallet
     * @return provider reference persisted by the caller; it is not itself key material
     */
    String provision(UUID walletId, long ownerId);
}
