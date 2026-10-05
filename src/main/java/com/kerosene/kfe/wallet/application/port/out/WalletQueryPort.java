package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound read boundary for owner-scoped wallet and address snapshots. */
public interface WalletQueryPort {
    /** Finds a wallet only when the requested owner has access to it. */
    Optional<WalletSnapshot> findOwned(long ownerId, UUID walletId);

    /** Lists the active addresses currently persisted for the wallet. */
    List<AddressSnapshot> activeAddresses(UUID walletId);
}
