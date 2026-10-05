package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentProposalHashPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentProposal;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.springframework.stereotype.Component;

/** Adapts canonical payment proposal content to the shared KFE hash implementation. */
@Component
public class LegacyPaymentProposalHashAdapter implements PaymentProposalHashPort {
    /** Shared cryptographic hashing implementation. */
    private final KfeHashService hashes;

    /** Creates the adapter with the established hash service. */
    public LegacyPaymentProposalHashAdapter(KfeHashService hashes) { this.hashes = hashes; }

    /** Hashes the domain proposal's canonical content representation. */
    @Override
    public String hash(PaymentProposal proposal) { return hashes.sha256(proposal.canonicalContent()); }
}
