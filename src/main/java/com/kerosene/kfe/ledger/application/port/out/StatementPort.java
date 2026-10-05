package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.StatementRecord;

/** Statement projection port; implementations must join the financial transaction. */
public interface StatementPort {
    /**
     * Creates or refreshes the participant-facing statement projection associated with a ledger
     * operation. The implementation joins the caller's financial transaction, so statement
     * persistence cannot claim a ledger change that later rolls back.
     *
     * @param record immutable statement identity, timestamps, expiry and display payload
     */
    void upsert(StatementRecord record);
}
