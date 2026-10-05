package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerBalance;
import java.util.UUID;

/** Loads and stores one ledger account under the caller's transaction and row lock. */
public interface LedgerAccountPort {
    /**
     * Loads the wallet/asset aggregate under a write lock for the remainder of the caller's
     * transaction. Implementations must use a stable wallet-and-asset key and must not return an
     * unlocked snapshot, because the application service mutates the returned instance.
     *
     * @param walletId wallet whose accounting buckets are being changed
     * @param asset asset code that scopes the balance row
     * @return locked mutable aggregate, or {@code null} when no balance exists
     */
    LedgerBalance lock(UUID walletId, String asset);

    /**
     * Persists the mutated aggregate in the same transaction as its immutable posting. The
     * transaction boundary is supplied by the caller so the two writes commit or roll back together.
     *
     * @param balance wallet/asset aggregate after the requested movement
     */
    void save(LedgerBalance balance);
}
