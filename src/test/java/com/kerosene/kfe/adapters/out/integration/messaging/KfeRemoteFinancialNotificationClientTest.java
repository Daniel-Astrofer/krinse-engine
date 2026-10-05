package com.kerosene.kfe.adapters.out.integration.messaging;

import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeRemoteFinancialNotificationClientTest {

    @Test
    void postsDepositConfirmedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/deposit-confirmed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "creditedSats":1500,
                          "confirmations":3
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyDepositConfirmed(42L, transactionId, walletId, "ONCHAIN", 1500L, 3);

        server.verify();
    }

    @Test
    void postsDepositDetectedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/deposit-detected"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "creditedSats":1500,
                          "confirmations":1
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyDepositDetected(42L, transactionId, walletId, "ONCHAIN", 1500L, 1);

        server.verify();
    }

    @Test
    void postsDepositConfirmationProgressNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/deposit-progress"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "creditedSats":1500,
                          "confirmations":2
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyDepositConfirmationProgress(42L, transactionId, walletId, "ONCHAIN", 1500L, 2);

        server.verify();
    }

    @Test
    void postsPaymentRequestDepositConfirmedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID paymentRequestId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/payment-request-deposit-confirmed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "paymentRequestId":"%s",
                          "publicId":"public-id",
                          "walletId":"%s",
                          "rail":"LIGHTNING",
                          "creditedSats":2500
                        }
                        """.formatted(transactionId, paymentRequestId, walletId)))
                .andRespond(withSuccess());

        client.notifyPaymentRequestDepositConfirmed(
                42L,
                transactionId,
                paymentRequestId,
                "public-id",
                walletId,
                "LIGHTNING",
                2500L);

        server.verify();
    }

    @Test
    void postsOutboundDetectedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/outbound-detected"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "amountSats":99000,
                          "confirmations":0,
                          "destinationHint":"bc1qdest"
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyOutboundDetected(
                42L, transactionId, walletId, "ONCHAIN", 99000L, 0, "bc1qdest");

        server.verify();
    }

    @Test
    void postsOutboundConfirmedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/outbound-confirmed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "amountSats":99000,
                          "confirmations":6
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyOutboundConfirmed(42L, transactionId, walletId, "ONCHAIN", 99000L, 6);

        server.verify();
    }

    @Test
    void missingLegacyCredentialDoesNotRollbackFinancialOperation() {
        KfeRemoteFinancialNotificationClient client = client("");

        assertDoesNotThrow(
                () -> client.notifyDepositConfirmed(
                        42L,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ONCHAIN",
                        1500L,
                        3));
    }

    private KfeRemoteFinancialNotificationClient client(String credential) {
        return new KfeRemoteFinancialNotificationClient(
                WorkloadIdentityTestClients.legacy(credential),
                "http://server.test",
                100,
                100);
    }

    private RestTemplate restTemplate(KfeRemoteFinancialNotificationClient client) throws Exception {
        Field field = KfeRemoteFinancialNotificationClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
