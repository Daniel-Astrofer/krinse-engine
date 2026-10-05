package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionSnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;

/** Reads and writes submission state inside the authorized submit transaction and execution lock. */
public interface PaymentSubmissionStatePort {
    /** Locks and loads the owner-scoped execution snapshot. */
    PaymentSubmissionSnapshot lockAndLoad(long userId, PaymentExecutionId id);
    /** Applies the quote only if the supplied intent snapshot is still current. */
    void applyPricing(PaymentSubmissionSnapshot expectedIntent, PaymentSubmissionPricing pricing);
    /** Stores the proposal hash for the user's execution after proposal validation. */
    void recordProposal(long userId, PaymentExecutionId id, String proposalHash);
    /** Stores the number of acknowledgements that reached the required quorum. */
    void recordQuorum(long userId, PaymentExecutionId id, int ackCount);
}
