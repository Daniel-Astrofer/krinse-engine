package com.kerosene.kfe.paymentexecution.adapters.out.vault;

import com.kerosene.common.financial.operations.FinancialQuorumPort;
import org.springframework.stereotype.Service;

/**
 * Application boundary for financial quorum decisions.
 *
 * This class deliberately depends on the port from shared only; transport,
 * TLS and Vault protocol details belong to the integration adapters.
 */
@Service
public final class KfeQuorumService {
    private final FinancialQuorumPort quorumPort;

    public KfeQuorumService(FinancialQuorumPort quorumPort) {
        this.quorumPort = quorumPort;
    }

    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        FinancialQuorumPort.Result result =
                quorumPort.requireHealthyUnanimousConsensus(proposalHash);
        return new Result(result.acceptedNodes(), result.totalHealthyNodes());
    }

    public record Result(int acceptedNodes, int totalHealthyNodes) { }
}
