package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerBalance.Transition;

/** Optional projection hook; financial state is already persisted before it is called. */
public interface LedgerMutationObserver {
    /**
     * Publishes a projection or notification side effect after the ledger posting and balance save
     * have been applied. Implementations must respect the transaction/event semantics of their
     * adapter; this callback is not the authority for whether the financial mutation succeeded.
     *
     * @param balance aggregate reflecting the applied mutation
     * @param transition immutable movement description, including bucket delta and version
     */
    void afterApplied(LedgerBalance balance, Transition transition);
}
