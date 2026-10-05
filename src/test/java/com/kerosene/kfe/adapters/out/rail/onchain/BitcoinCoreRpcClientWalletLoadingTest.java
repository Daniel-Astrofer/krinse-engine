package com.kerosene.kfe.adapters.out.rail.onchain;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BitcoinCoreRpcClientWalletLoadingTest {

    @Test
    void acceptsAConcurrentWalletLoadOnlyAfterTheWalletBecomesVisible() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        BitcoinCoreRpcClient client =
                new BitcoinCoreRpcClient(
                        restTemplate,
                        new ObjectMapper(),
                        "http://bitcoin-core:8332",
                        "rpc-user",
                        "rpc-password",
                        "kerosene");

        server.expect(once(), method(POST))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), method(POST))
                .andRespond(
                        withSuccess(
                                "{\"result\":{\"wallets\":[{\"name\":\"kerosene\"}]}}",
                                MediaType.APPLICATION_JSON));
        server.expect(once(), method(POST))
                .andRespond(
                        withServerError()
                                .body(
                                        "{\"result\":null,\"error\":{\"code\":-4,"
                                                + "\"message\":\"Wallet already loading.\"}}")
                                .contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), method(POST))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), method(POST))
                .andRespond(
                        withSuccess(
                                "{\"result\":[\"kerosene\"]}", MediaType.APPLICATION_JSON));

        client.ensureWalletLoaded("kerosene");

        server.verify();
    }
}
