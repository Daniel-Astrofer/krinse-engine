package com.kerosene.kfe.messaging.paymentrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.paymentrequest.adapters.in.scheduling.KfePaymentRequestLightningMonitor;
import com.kerosene.kfe.paymentrequest.adapters.in.scheduling.KfePaymentRequestOnchainMonitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KfePaymentRequestObservationMessageHandlerTest {
    private final KfePaymentRequestOnchainMonitor onchain = mock(KfePaymentRequestOnchainMonitor.class);
    private final KfePaymentRequestLightningMonitor lightning = mock(KfePaymentRequestLightningMonitor.class);
    private final KfePaymentRequestObservationMessageHandler handler =
            new KfePaymentRequestObservationMessageHandler(new ObjectMapper(), onchain, lightning);

    @Test
    void routesValidatedOnchainObservationToTheBoundMonitor() {
        UUID requestId = UUID.randomUUID();
        String txid = "a".repeat(64);
        handler.handle(message(KfePaymentRequestObservationMessageHandler.ONCHAIN_TYPE,
                "{\"paymentRequestId\":\"" + requestId + "\",\"txid\":\"" + txid
                        + "\",\"observedSats\":1200,\"confirmations\":2}"));

        verify(onchain).acceptObservation(requestId,
                new KfePaymentRequestOnchainMonitor.ObservedPayment(txid, 1200L, 2,
                        "{\"paymentRequestId\":\"" + requestId + "\",\"txid\":\"" + txid
                                + "\",\"observedSats\":1200,\"confirmations\":2}"));
    }

    @Test
    void rejectsInvalidObservationContractsBeforeCallingAUseCase() {
        assertThatThrownBy(() -> handler.handle(message(
                KfePaymentRequestObservationMessageHandler.ONCHAIN_TYPE,
                "{\"paymentRequestId\":\"not-a-uuid\",\"txid\":\"bad\",\"observedSats\":0}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MessageEnvelope message(String type, String payload) {
        return new MessageEnvelope(UUID.randomUUID(), "OBSERVATION", type, 1,
                Instant.parse("2026-09-26T12:00:00Z"), null, null, null, 0L, null, payload);
    }
}
