package com.kerosene.architecture;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.application.WorkloadOperationAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkloadOperationAuthorizerTest {

    @Test
    void unknownWorkloadAndOperationFailClosed() {
        var authorizer = new WorkloadOperationAuthorizer(Map.of(
                "spiffe://kerosene/kfe", Set.of("KFE_PAYMENT_CREATED")));
        var identity = new WorkloadIdentity("spiffe://kerosene/kfe");

        authorizer.requireAllowed(identity, "KFE_PAYMENT_CREATED");
        assertThatThrownBy(() -> authorizer.requireAllowed(identity, "KFE_WALLET_PROVISIONED"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> authorizer.requireAllowed(
                new WorkloadIdentity("spiffe://kerosene/unknown"), "KFE_PAYMENT_CREATED"))
                .isInstanceOf(SecurityException.class);
    }
}
