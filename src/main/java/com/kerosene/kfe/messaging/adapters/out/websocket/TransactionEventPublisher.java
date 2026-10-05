package com.kerosene.kfe.messaging.adapters.out.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.adapters.out.integration.messaging.KfeRemoteStompRelayClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes statement/extrato rows to the authenticated user after commit.
 *
 * <p>Destination: {@code /user/queue/transactions}. Payload is the same display
 * map persisted in {@code user_statement_24h} ({@code buildDisplayPayload}).
 *
 * <p>In KFE standalone (no in-process broker), publishes via HTTP relay to Core.
 */
@Service
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    public static final String DESTINATION = "/queue/transactions";

    private final SimpMessagingTemplate messagingTemplate;
    private final KfeRemoteStompRelayClient remoteRelay;

    public TransactionEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.remoteRelay = remoteRelay.getIfAvailable();
    }

    public void publishAfterCommit(Long userId, Map<String, ?> payload) {
        if (userId == null || payload == null || payload.isEmpty()) {
            return;
        }
        if (messagingTemplate == null && remoteRelay == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        Runnable publish = () -> {
            try {
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            String.valueOf(userId),
                            DESTINATION,
                            body);
                } else {
                    remoteRelay.publishToUser(userId, DESTINATION, body);
                }
                log.info(
                        "[WS] Published transaction to user {} {} id={}",
                        userId,
                        DESTINATION,
                        body.getOrDefault("transactionId", body.get("id")));
            } catch (Exception e) {
                log.error(
                        "Failed to send transaction websocket event to user {}",
                        userId,
                        e);
            }
        };

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }
}
