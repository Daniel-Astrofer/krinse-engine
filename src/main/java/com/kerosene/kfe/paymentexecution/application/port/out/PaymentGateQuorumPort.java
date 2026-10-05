package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementQuorumEvidence;

/** Verifies consensus acknowledgements for a settlement proposal digest. */
public interface PaymentGateQuorumPort {
    /** Requires healthy quorum evidence for the supplied immutable proposal. */
    /** @param proposalHash hash bound to the authorized payment @return accepted and healthy member counts @throws RuntimeException when quorum is unavailable or rejected */
    SettlementQuorumEvidence requireConsensus(String proposalHash);
}
