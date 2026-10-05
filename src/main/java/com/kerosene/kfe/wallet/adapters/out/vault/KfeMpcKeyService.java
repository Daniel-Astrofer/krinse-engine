package com.kerosene.kfe.wallet.adapters.out.vault;

import org.springframework.stereotype.Service;
import com.kerosene.common.financial.operations.FinancialMpcKeyPort;
import com.kerosene.kfe.wallet.application.port.out.WalletKeyProvisioningPort;

import java.util.UUID;

/** Adapts custodial wallet key generation to the shared financial MPC key port. */
@Service
public class KfeMpcKeyService implements WalletKeyProvisioningPort {

    /** Cross-service MPC key generation boundary. */
    private final FinancialMpcKeyPort mpcKeyPort;

    /** Creates the service with the financial MPC provider. */
    public KfeMpcKeyService(FinancialMpcKeyPort mpcKeyPort) {
        this.mpcKeyPort = mpcKeyPort;
    }

    /** Requests MPC key generation for a wallet and its owning user. */
    public String keygenWallet(UUID walletId, Long userId) {
        return mpcKeyPort.keygenWallet(walletId, userId);
    }

    /** Implements the wallet provisioning port using the same MPC key-generation operation. */
    @Override
    public String provision(UUID walletId, long ownerId) {
        return keygenWallet(walletId, ownerId);
    }
}
