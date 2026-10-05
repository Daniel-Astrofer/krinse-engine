package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentProposal;

/** Computes the canonical settlement proposal digest used for quorum approval. */
public interface PaymentProposalHashPort {
    /** @param proposal immutable authorized payment proposal @return digest over its canonical wire representation */
    String hash(PaymentProposal proposal);
}
