package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateQuorumPort;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementQuorumEvidence;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumGateway;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentGateQuorumAdapter implements PaymentGateQuorumPort {
    private final KfeQuorumGateway quorum;
    public LegacyPaymentGateQuorumAdapter(KfeQuorumGateway quorum) { this.quorum = quorum; }

    @Override
    public SettlementQuorumEvidence requireConsensus(String proposalHash) {
        var result = quorum.requireHealthyUnanimousConsensus(proposalHash);
        return new SettlementQuorumEvidence(result.acceptedNodes(), result.totalHealthyNodes());
    }
}
