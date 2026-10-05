package com.kerosene.kfe.paymentexecution.adapters.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInitiatedNotificationPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
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

/** Preserves the existing initiation contract and best-effort remote client's behavior. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentInitiatedNotificationAdapter implements PaymentInitiatedNotificationPort {
    private final FinancialNotificationPort notifications;
    private final KfeFinancialNotificationOutboxService outbox;
    private final ObjectMapper objectMapper;

    @Autowired
    public LegacyPaymentInitiatedNotificationAdapter(
            FinancialNotificationPort notifications,
            ObjectProvider<KfeFinancialNotificationOutboxService> outboxProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.notifications = notifications;
        this.outbox = outboxProvider == null ? null : outboxProvider.getIfAvailable();
        this.objectMapper = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
    }

    public LegacyPaymentInitiatedNotificationAdapter(FinancialNotificationPort notifications) {
        this(notifications, null, null);
    }

    @Override
    public void initiated(long userId, PaymentExecutionId executionId, UUID walletId,
                          PaymentRail rail, long grossAmountSats) {
        if (outbox != null) {
            outbox.enqueue(envelope(executionId, walletId, rail, grossAmountSats), userId, executionId.value());
            return;
        }
        notifications.notifyPaymentInitiated(userId, executionId.value(), walletId, rail.name(), grossAmountSats);
    }

    private MessageEnvelope envelope(PaymentExecutionId executionId, UUID walletId,
                                     PaymentRail rail, long grossAmountSats) {
        UUID messageId = UUID.nameUUIDFromBytes(
                ("PAYMENT_PROCESSING:" + executionId.value() + ":" + rail).getBytes(StandardCharsets.UTF_8));
        try {
            return new MessageEnvelope(
                    messageId, "EVENT", "PAYMENT_PROCESSING", 1, Instant.now(),
                    executionId.value(), null, executionId.value().toString(), 0, null,
                    objectMapper.writeValueAsString(Map.of(
                            "walletId", walletId,
                            "rail", rail.name(),
                            "amountSats", grossAmountSats)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize payment notification.", ex);
        }
    }
}
