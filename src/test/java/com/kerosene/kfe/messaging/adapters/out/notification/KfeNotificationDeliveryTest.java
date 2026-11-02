package com.kerosene.kfe.messaging.adapters.out.notification;

import com.kerosene.kfe.adapters.out.integration.messaging.KfeRemoteFinancialNotificationClient;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KfeNotificationDeliveryTest {
    private final KfeFinancialNotificationOutboxService outbox = mock(KfeFinancialNotificationOutboxService.class);
    private final KfeFinancialMetrics metrics = mock(KfeFinancialMetrics.class);

    private KfeFinancialNotificationOutboxEntity row() {
        var row = new KfeFinancialNotificationOutboxEntity();
        row.setEventId(UUID.randomUUID());
        row.setUserId(42L);
        row.setTransactionId(UUID.randomUUID());
        row.setEventType("PAYMENT_CONFIRMED");
        org.springframework.test.util.ReflectionTestUtils.setField(row, "createdAt", Instant.parse("2026-10-09T12:00:00Z"));
        row.setEventSequence(2);
        row.setAttempts(10); // A long Core outage must not discard a valid event.
        row.setClaimToken(UUID.randomUUID());
        row.setPayloadJson("""
                {"walletId":"00000000-0000-0000-0000-000000000001","rail":"ONCHAIN",
                 "amountSats":1500,"confirmations":1}
                """);
        return row;
    }

    private KfeRemoteFinancialNotificationClient client() {
        return new KfeRemoteFinancialNotificationClient(WorkloadIdentityTestClients.legacy("test-credential"),
                "http://server.test", 100, 100);
    }

    private MockRestServiceServer server(KfeRemoteFinancialNotificationClient client) throws Exception {
        var field = KfeRemoteFinancialNotificationClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return MockRestServiceServer.createServer((RestTemplate) field.get(client));
    }

    @Test void serverFailureKeepsTheEventRetryableBeyondTheOldLimit() throws Exception {
        var client = client();
        var server = server(client);
        server.expect(requestTo("http://server.test/internal/kfe/notifications/events")).andRespond(withServerError());
        var row = row();
        new KfeNotificationOutboxProcessor(outbox, client, metrics).process(row);
        verify(outbox).markRetryableFailure(any(KfeFinancialNotificationOutboxService.NotificationClaim.class), eq(10), anyString());
        verify(outbox, never()).markDelivered(any(KfeFinancialNotificationOutboxService.NotificationClaim.class));
        verify(outbox, never()).markDeadLetter(any(KfeFinancialNotificationOutboxService.NotificationClaim.class), anyString());
        server.verify();
    }

    @Test void mismatchedReceiptCannotBeMarkedDelivered() throws Exception {
        var client = client();
        var server = server(client);
        server.expect(requestTo("http://server.test/internal/kfe/notifications/events"))
                .andRespond(withSuccess("{\"eventId\":\"" + UUID.randomUUID() + "\",\"accepted\":true}", MediaType.APPLICATION_JSON));
        new KfeNotificationOutboxProcessor(outbox, client, metrics).process(row());
        verify(outbox, never()).markDelivered(any(KfeFinancialNotificationOutboxService.NotificationClaim.class));
        verify(outbox).markRetryableFailure(any(KfeFinancialNotificationOutboxService.NotificationClaim.class), eq(10), anyString());
        server.verify();
    }

    @Test void emptySuccessResponseIsNotDurableAcceptance() throws Exception {
        var client = client();
        var server = server(client);
        server.expect(requestTo("http://server.test/internal/kfe/notifications/events")).andRespond(withSuccess());
        new KfeNotificationOutboxProcessor(outbox, client, metrics).process(row());
        verify(outbox, never()).markDelivered(any(KfeFinancialNotificationOutboxService.NotificationClaim.class));
        verify(outbox).markRetryableFailure(any(KfeFinancialNotificationOutboxService.NotificationClaim.class), eq(10), anyString());
        server.verify();
    }

    @Test void matchingReceiptCompletesOnlyTheOwnedClaim() throws Exception {
        var client = client();
        var server = server(client);
        var row = row();
        server.expect(requestTo("http://server.test/internal/kfe/notifications/events"))
                .andExpect(header("X-KFE-Internal-Secret", "test-credential"))
                .andExpect(content().json("{\"eventId\":\"" + row.getEventId() + "\",\"eventSequence\":2}", false))
                .andRespond(withSuccess("{\"eventId\":\"" + row.getEventId() + "\",\"accepted\":true}", MediaType.APPLICATION_JSON));
        new KfeNotificationOutboxProcessor(outbox, client, metrics).process(row);
        verify(outbox).markDelivered(new KfeFinancialNotificationOutboxService.NotificationClaim(row.getId(), row.getClaimToken()));
        server.verify();
    }

    @Test void invalidFinancialPayloadIsQuarantinedInsteadOfBeingAcknowledged() {
        var row = row();
        row.setPayloadJson("{\"amountSats\":-1}");
        var port = mock(com.kerosene.common.financial.notification.FinancialNotificationPort.class);
        new KfeNotificationOutboxProcessor(outbox, port, metrics).process(row);
        verify(outbox).markDeadLetter(any(KfeFinancialNotificationOutboxService.NotificationClaim.class), anyString());
        verifyNoInteractions(port);
    }
}
