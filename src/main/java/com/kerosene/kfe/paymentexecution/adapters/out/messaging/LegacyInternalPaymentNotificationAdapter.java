package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentNotificationPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Preserves existing notification contracts; durable delivery is a separate migration step. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyInternalPaymentNotificationAdapter implements InternalPaymentNotificationPort {
    private final FinancialNotificationPort notifications;
    private final KfeFinancialNotificationOutboxService outbox;
    private final ObjectMapper objectMapper;

    @Autowired
    public LegacyInternalPaymentNotificationAdapter(
            FinancialNotificationPort notifications,
            ObjectProvider<KfeFinancialNotificationOutboxService> outboxProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.notifications = notifications;
        this.outbox = outboxProvider == null ? null : outboxProvider.getIfAvailable();
        this.objectMapper = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
    }

    public LegacyInternalPaymentNotificationAdapter(FinancialNotificationPort notifications) {
        this(notifications, null, null);
    }

    @Override
    public void sent(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats) {
        if (outbox != null) {
            outbox.enqueue(envelope("INTERNAL_TRANSFER_SENT", executionId, walletId, amountSats),
                    userId, executionId.value());
            return;
        }
        notifications.notifyInternalTransferSent(userId, executionId.value(), walletId, amountSats);
    }

    @Override
    public void received(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats) {
        if (outbox != null) {
            outbox.enqueue(envelope("INTERNAL_TRANSFER_RECEIVED", executionId, walletId, amountSats),
                    userId, executionId.value());
            return;
        }
        notifications.notifyInternalTransferReceived(userId, executionId.value(), walletId, amountSats);
    }

    private MessageEnvelope envelope(String type, PaymentExecutionId executionId,
                                     UUID walletId, long amountSats) {
        UUID messageId = UUID.nameUUIDFromBytes(
                (type + ":" + executionId.value() + ":" + walletId).getBytes(StandardCharsets.UTF_8));
        try {
            return new MessageEnvelope(
                    messageId, "EVENT", type, 1, Instant.now(),
                    executionId.value(), null, executionId.value().toString(), 0, null,
                    objectMapper.writeValueAsString(Map.of(
                            "walletId", walletId,
                            "amountSats", amountSats)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize internal payment notification.", ex);
        }
    }
}
