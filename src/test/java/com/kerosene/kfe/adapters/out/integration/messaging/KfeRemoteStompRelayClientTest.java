package com.kerosene.kfe.adapters.out.integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeRemoteStompRelayClientTest {

    @Test
    void postsAllowlistedPublishToAuthServer() throws Exception {
        KfeRemoteStompRelayClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("http://server.test/internal/kfe/stomp/publish"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "destination":"/queue/transactions",
                          "payload":{"id":"tx-1","status":"CONFIRMED"}
                        }
                        """))
                .andRespond(withSuccess());

        client.publishToUser(42L, "/queue/transactions", Map.of("id", "tx-1", "status", "CONFIRMED"));

        server.verify();
    }

    @Test
    void missingLegacySecretDoesNotBreakBestEffortRelay() {
        KfeRemoteStompRelayClient client = client(" ");
        assertDoesNotThrow(
                () -> client.publishToUser(1L, "/queue/balance", Map.of("walletId", "w")));
    }

    private static KfeRemoteStompRelayClient client(String secret) {
        return new KfeRemoteStompRelayClient(
                WorkloadIdentityTestClients.legacy(secret),
                new ObjectMapper(),
                "http://server.test",
                1000L,
                1000L);
    }

    private static RestTemplate restTemplate(KfeRemoteStompRelayClient client) throws Exception {
        Field field = KfeRemoteStompRelayClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
