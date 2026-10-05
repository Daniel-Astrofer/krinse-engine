package com.kerosene.kfe.messaging.paymentrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.messaging.application.MessageHandler;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.paymentrequest.adapters.in.scheduling.KfePaymentRequestLightningMonitor;
import com.kerosene.kfe.paymentrequest.adapters.in.scheduling.KfePaymentRequestOnchainMonitor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Converts authenticated inbox observations into owner-scoped payment-request use cases. */
@Component
public final class KfePaymentRequestObservationMessageHandler implements MessageHandler {
    public static final String ONCHAIN_TYPE = "KFE_PAYMENT_REQUEST_ONCHAIN_OBSERVATION";
    public static final String LIGHTNING_TYPE = "KFE_PAYMENT_REQUEST_LIGHTNING_OBSERVATION";

    private final ObjectMapper objectMapper;
    private final KfePaymentRequestOnchainMonitor onchain;
    private final KfePaymentRequestLightningMonitor lightning;

    public KfePaymentRequestObservationMessageHandler(
            ObjectMapper objectMapper,
            KfePaymentRequestOnchainMonitor onchain,
            KfePaymentRequestLightningMonitor lightning) {
        this.objectMapper = objectMapper;
        this.onchain = onchain;
        this.lightning = lightning;
    }

    @Override
    public boolean supports(String type, int schemaVersion) {
        return schemaVersion == 1 && (ONCHAIN_TYPE.equals(type) || LIGHTNING_TYPE.equals(type));
    }

    @Override
    public void handle(MessageEnvelope message) {
        if (!supports(message.type(), message.schemaVersion())) {
            throw new IllegalArgumentException("unsupported payment request observation contract");
        }
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            UUID paymentRequestId = requiredUuid(payload, "paymentRequestId");
            if (ONCHAIN_TYPE.equals(message.type())) {
                String txid = requiredText(payload, "txid");
                long observedSats = requiredPositiveLong(payload, "observedSats");
                int confirmations = payload.path("confirmations").asInt(-1);
                if (confirmations < 0) {
                    throw new IllegalArgumentException("confirmations must be non-negative");
                }
                onchain.acceptObservation(paymentRequestId,
                        new KfePaymentRequestOnchainMonitor.ObservedPayment(
                                txid, observedSats, confirmations, boundedRawPayload(payload)));
                return;
            }

            String status = requiredText(payload, "status");
            String paymentHash = requiredText(payload, "paymentHash");
            long receivedSats = payload.path("receivedSats").asLong(0L);
            if (receivedSats < 0L) {
                throw new IllegalArgumentException("receivedSats cannot be negative");
            }
            lightning.acceptObservation(paymentRequestId, new CustodyGateway.IncomingLightningInvoiceStatus(
                    status,
                    receivedSats,
                    null,
                    boundedRawPayload(payload),
                    paymentHash,
                    payload.path("addIndex").asLong(0L),
                    payload.path("settleIndex").asLong(0L)));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid payment request observation payload", exception);
        }
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        String value = requiredText(payload, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private String requiredText(JsonNode payload, String field) {
        String value = payload == null ? null : payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        long value = payload.path(field).asLong(Long.MIN_VALUE);
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private String boundedRawPayload(JsonNode payload) {
        String raw = payload == null ? null : payload.toString();
        if (raw == null) return null;
        return raw.length() <= 16_384 ? raw : raw.substring(0, 16_384);
    }
}
