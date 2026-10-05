package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerPosting;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import java.util.UUID;

/** Persists immutable postings idempotently by operation and movement type. */
public interface LedgerPostingPort {
    /**
     * Checks the idempotency key used by ledger mutation services. Implementations should query
     * the unique operation/type constraint; the default is intended only for adapters that do not
     * support replay detection and should not be used for financial persistence.
     *
     * @param operationId stable identity of the initiating business operation
     * @param movementType movement whose prior application is being checked
     * @return {@code true} when that operation/type posting already exists
     */
    default boolean exists(UUID operationId, LedgerMovementType movementType) {
        return false;
    }

    /**
     * Appends a posting only if its operation/type idempotency key is unused. This operation must
     * participate in the same transaction as the corresponding balance save.
     *
     * @param posting immutable accounting event to append
     * @return {@code true} if inserted; {@code false} if another posting already owns the key
     */
    boolean appendIfAbsent(LedgerPosting posting);
}
