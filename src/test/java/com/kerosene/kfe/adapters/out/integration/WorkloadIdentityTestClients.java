package com.kerosene.kfe.adapters.out.integration;

import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import com.kerosene.common.security.workload.WorkloadIdentityConfig;

import java.time.Duration;

public final class WorkloadIdentityTestClients {

    private WorkloadIdentityTestClients() {
    }

    public static InternalServiceRestTemplateFactory legacy(String secret) {
        return new InternalServiceRestTemplateFactory(
                new WorkloadIdentityConfig(false, "", "", "", 8443, Duration.ofSeconds(1)),
                null,
                secret);
    }
}
