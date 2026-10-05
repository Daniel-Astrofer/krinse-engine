package com.kerosene.kfe.paymentexecution.adapters.out.vault;

import org.junit.jupiter.api.Test;
import com.kerosene.common.financial.operations.FinancialQuorumPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeQuorumGatewayTest {

    private final FinancialQuorumPort quorumPort = mock(FinancialQuorumPort.class);
    private final KfeQuorumGateway gateway = new KfeQuorumGateway(quorumPort);

    @Test
    void delegatesConsensusToFinancialQuorumPort() {
        when(quorumPort.requireHealthyUnanimousConsensus("hash123"))
                .thenReturn(new FinancialQuorumPort.Result(2, 3));

        KfeQuorumGateway.Result result = gateway.requireHealthyUnanimousConsensus("hash123");

        assertEquals(2, result.acceptedNodes());
        assertEquals(3, result.totalHealthyNodes());
        verify(quorumPort).requireHealthyUnanimousConsensus("hash123");
    }
}
