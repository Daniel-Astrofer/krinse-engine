package com.kerosene.kfe.paymentexecution.adapters.out.vault;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.common.financial.operations.FinancialQuorumPort;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumService;

@Service
public class KfeQuorumGateway {
    private final KfeQuorumService quorumService;

    @Autowired
    public KfeQuorumGateway(KfeQuorumService quorumService) {
        this.quorumService = quorumService;
    }

    /** Compatibility constructor for isolated callers; new code injects the application service. */
    @Deprecated(forRemoval = true)
    public KfeQuorumGateway(FinancialQuorumPort quorumPort) {
        this(new KfeQuorumService(quorumPort));
    }

    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        KfeQuorumService.Result result = quorumService.requireHealthyUnanimousConsensus(proposalHash);
        return new Result(result.acceptedNodes(), result.totalHealthyNodes());
    }

    public record Result(int acceptedNodes, int totalHealthyNodes) {
    }
}
