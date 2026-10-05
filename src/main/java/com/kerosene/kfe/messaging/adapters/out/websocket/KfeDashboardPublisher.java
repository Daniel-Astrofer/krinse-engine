package com.kerosene.kfe.messaging.adapters.out.websocket;

import com.kerosene.kfe.audit.adapters.in.reporting.KfeDashboardService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.adapters.out.integration.messaging.KfeRemoteStompRelayClient;

import java.util.Map;

@Service
public class KfeDashboardPublisher {

    public static final String DESTINATION = "/queue/kfe-dashboard";

    private final SimpMessagingTemplate messagingTemplate;
    private final KfeDashboardService dashboardService;
    private final KfeRemoteStompRelayClient remoteRelay;

    public KfeDashboardPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            KfeDashboardService dashboardService,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.dashboardService = dashboardService;
        this.remoteRelay = remoteRelay.getIfAvailable();
    }

    public void publishAfterCommit(Long userId) {
        if (userId == null || (messagingTemplate == null && remoteRelay == null)) {
            return;
        }
        Runnable publish = () -> {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(userId),
                        DESTINATION,
                        dashboardService.dashboard(userId));
                return;
            }
            // Standalone KFE → Core: never POST the full dashboard (hits Tomcat
            // 2KB form/post limit → HTTP 413). Clients already consume balance +
            // transactions queues; send a tiny dirty tick only.
            remoteRelay.publishToUser(
                    userId,
                    DESTINATION,
                    Map.of(
                            "type", "KFE_DASHBOARD_DIRTY",
                            "userId", userId));
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
