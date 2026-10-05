package com.kerosene.kfe.adapters.out.rail.onchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BitcoinCoreRpcClientFeeEstimateTest {

    @Test
    void convertsBitcoinPerKvbyteToRoundedUpSatsPerVbyte() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        BitcoinCoreRpcClient client = new BitcoinCoreRpcClient(
                restTemplate,
                new ObjectMapper(),
                "http://bitcoin.test:18443",
                "rpc-user",
                "rpc-password",
                "");
        server.expect(requestTo("http://bitcoin.test:18443"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"method\":\"estimatesmartfee\"")))
                .andRespond(withSuccess(
                        "{\"result\":{\"feerate\":0.00001234,\"blocks\":3},\"error\":null}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.estimateSmartFeeRateSatPerVbyte(3)).isEqualTo(2L);
        server.verify();
    }

    @Test
    void rejectsMissingSmartFeeInsteadOfReturningZero() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        BitcoinCoreRpcClient client = new BitcoinCoreRpcClient(
                restTemplate,
                new ObjectMapper(),
                "http://bitcoin.test:18443",
                "rpc-user",
                "rpc-password",
                "");
        server.expect(requestTo("http://bitcoin.test:18443"))
                .andRespond(withSuccess(
                        "{\"result\":{\"errors\":[\"Insufficient data\"]},\"error\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.estimateSmartFeeRateSatPerVbyte(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smart fee rate");
        server.verify();
    }

    @Test
    void rejectsNonPositiveSmartFeeInsteadOfReturningAnUnusableQuote() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        BitcoinCoreRpcClient client = new BitcoinCoreRpcClient(
                restTemplate,
                new ObjectMapper(),
                "http://bitcoin.test:18443",
                "rpc-user",
                "rpc-password",
                "");
        server.expect(requestTo("http://bitcoin.test:18443"))
                .andRespond(withSuccess(
                        "{\"result\":{\"feerate\":0},\"error\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.estimateSmartFeeRateSatPerVbyte(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive smart fee rate");
        server.verify();
    }

    @Test
    void delegatesAddressValidationToBitcoinCore() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        BitcoinCoreRpcClient client = new BitcoinCoreRpcClient(
                restTemplate,
                new ObjectMapper(),
                "http://bitcoin.test:18443",
                "rpc-user",
                "rpc-password",
                "");
        server.expect(requestTo("http://bitcoin.test:18443"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"method\":\"validateaddress\"")))
                .andRespond(withSuccess(
                        "{\"result\":{\"isvalid\":true},\"error\":null}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isValidAddress("bc1ptestaddress")).isTrue();
        server.verify();
    }
}
