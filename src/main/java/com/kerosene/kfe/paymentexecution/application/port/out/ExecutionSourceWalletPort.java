package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionSourceWalletSnapshot;
import java.util.Optional;
import java.util.UUID;

/** Read boundary for the source wallet metadata needed by external execution preparation. */
public interface ExecutionSourceWalletPort {
    /** Fresh, owner-scoped view in the caller's transaction; does not lock against subsequent archival. */
    /** @param userId expected wallet owner @param walletId source wallet identity @return current owner-scoped snapshot, or empty when missing/not owned */
    Optional<ExecutionSourceWalletSnapshot> findOwned(long userId, UUID walletId);
}
