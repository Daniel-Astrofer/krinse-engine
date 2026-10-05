package com.kerosene.kfe.messaging.adapters.out.websocket;

import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.adapters.out.integration.messaging.KfeRemoteStompRelayClient;

import java.math.BigDecimal;

@Service
public class BalanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventPublisher.class);
    public static final String DESTINATION = "/queue/balance";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    private final KfeRemoteStompRelayClient remoteRelay;

    public BalanceEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.balanceMetrics = balanceMetrics;
        this.remoteRelay = remoteRelay.getIfAvailable();
    }

    /** Legacy scalar publish — prefer {@link #publishBalanceUpdateAfterCommit(BalanceUpdateEvent)}. */
    public void publishBalanceUpdateAfterCommit(Long userId, String walletId, String walletName,
            BigDecimal newBalance, BigDecimal amount, String context) {
        publishBalanceUpdateAfterCommit(new BalanceUpdateEvent(
                walletId, walletName, userId, newBalance, amount, context));
    }

    public void publishBalanceUpdateAfterCommit(BalanceUpdateEvent event) {
        if (event == null || event.getUserId() == null) {
            return;
        }
        if (messagingTemplate == null && remoteRelay == null) {
            return;
        }
        Runnable publish = () -> {
            try {
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            String.valueOf(event.getUserId()),
                            DESTINATION,
                            event);
                } else {
                    remoteRelay.publishToUser(event.getUserId(), DESTINATION, event);
                }
                KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
                if (metrics != null) {
                    metrics.recordWsPublish(event.getBucket());
                }
                log.info(
                        "[WS] Published balance update to user {} {} - Wallet: {}, kind={}, bucket={}, primarySats={}, NewBalance: {}, Amount: {}",
                        event.getUserId(),
                        DESTINATION,
                        event.getWalletName(),
                        event.getKind(),
                        event.getBucket(),
                        event.getPrimarySats(),
                        event.getNewBalance(),
                        event.getAmount());
            } catch (Exception e) {
                log.error(
                        "Failed to convert or send balance update websocket event to user {}",
                        event.getUserId(),
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
