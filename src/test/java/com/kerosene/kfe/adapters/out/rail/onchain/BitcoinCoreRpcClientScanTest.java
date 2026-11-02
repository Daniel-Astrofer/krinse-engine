package com.kerosene.kfe.adapters.out.rail.onchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BitcoinCoreRpcClientScanTest {
    private final RestTemplate transport = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(transport).build();
    private final BitcoinCoreRpcClient client = new BitcoinCoreRpcClient(
            transport, new ObjectMapper(), "http://bitcoin-core:8332", "test", "test", "", 1000);

    private void expect(String methodName, String result) {
        server.expect(jsonPath("$.method").value("scantxoutset"))
                .andExpect(jsonPath("$.params[0]").value(methodName))
                .andRespond(withSuccess("{\"result\":" + result + "}", MediaType.APPLICATION_JSON));
    }

    @Test
    void activeScanTimesOutWithoutStartingOrAbortingAnotherScan() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        jsonPath("$.method").value("scantxoutset"))
                .andExpect(jsonPath("$.params[0]").value("status"))
                .andRespond(withSuccess("{\"result\":{\"progress\":28}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getConfirmedBalanceForDescriptor("raw(deadbeef)", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("busy");
        server.verify();
    }

    @Test
    void successfulEmptyScanIsARealZeroObservation() {
        expect("status", "null");
        expect("start", "{\"success\":true,\"total_amount\":0,\"unspents\":[]}");
        assertThat(client.getConfirmedBalanceForDescriptor("raw(deadbeef)", 1)).isZero();
        server.verify();
    }

    @Test
    void abortedScanDoesNotBecomeAnEmptyUtxoSet() {
        expect("status", "null");
        expect("start", "{\"success\":false,\"total_amount\":0,\"unspents\":[]}");
        assertThatThrownBy(() -> client.getUnspentOutputsFromScan("raw(deadbeef)", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not complete");
        server.verify();
    }

    @Test
    void missingResultDoesNotBecomeAZeroBalance() {
        expect("status", "null");
        expect("start", "null");
        assertThatThrownBy(() -> client.getConfirmedBalanceForDescriptor("raw(deadbeef)", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not complete");
        server.verify();
    }

    @Test
    void statusTransportFailureNeverStartsOrAbortsAScan() {
        server.expect(jsonPath("$.params[0]").value("status"))
                .andRespond(withException(new java.net.SocketTimeoutException("RPC unavailable")));
        assertThatThrownBy(() -> client.getConfirmedBalanceForDescriptor("raw(deadbeef)", 1))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void interruptedWaitPreservesInterruptionAndMakesNoRpcCall() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.getConfirmedBalanceForDescriptor("raw(deadbeef)", 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }
}
