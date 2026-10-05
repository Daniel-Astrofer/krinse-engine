package com.kerosene.kfe.messaging.adapters.out.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class KfeFinancialNotificationMetrics {

    private final MeterRegistry registry;

    public KfeFinancialNotificationMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    public void recordPortActive(String type) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.financial_notification_port_active")
                .description("Financial notification port implementation type (remote, local, noop)")
                .tags(Tags.of("type", type != null ? type : "unknown"))
                .register(registry)
                .increment();
    }
}
