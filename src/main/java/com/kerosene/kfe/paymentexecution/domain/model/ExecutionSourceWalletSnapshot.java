package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/**
 * Committed source-wallet view at preparation time; it is not a revocation fence for a later RPC.
 * @param id wallet identifier
 * @param userId owning account identifier
 * @param label user-visible wallet label
 * @param asset wallet asset code
 * @param active whether the wallet is active
 * @param watchOnly whether the wallet cannot initiate spending
 * @param spendable whether wallet policy currently allows spending
 */
public record ExecutionSourceWalletSnapshot(UUID id, long userId, String label, String asset,
                                           boolean active, boolean watchOnly, boolean spendable) {
    /** Checks identity, ownership, BTC asset, active status, and spendability before execution. */
    /** @param expectedUserId account expected to own the source @param expectedId expected wallet identity @return true only when all source-wallet requirements hold */
    public boolean usableFor(long expectedUserId, UUID expectedId) {
        return expectedUserId > 0 && expectedId != null && expectedId.equals(id) && userId == expectedUserId
                && active && !watchOnly && spendable && "BTC".equals(asset) && label != null && !label.isBlank();
    }

    /** Returns wallet identity while redacting descriptive metadata. */
    @Override public String toString() {
        return "ExecutionSourceWalletSnapshot[id=" + id + ", metadata=REDACTED]";
    }
}
