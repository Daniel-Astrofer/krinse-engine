package com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class KfeTransactionRepositoryTest {

    @Test
    void participantQueriesLimitReceiverVisibilityToInternalDestinationWallets() {
        Method detail = method("findParticipantVisibleById");
        Method list = method("findParticipantVisibleByUserId");

        assertParticipantScope(detail.getAnnotation(Query.class).value());
        assertParticipantScope(list.getAnnotation(Query.class).value());
    }

    private Method method(String name) {
        return Arrays.stream(KfeTransactionRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void assertParticipantScope(String query) {
        assertThat(query)
                .contains("t.userId = :userId")
                .contains("t.rail = :internalRail")
                .contains("t.direction = :internalDirection")
                .contains("destinationWallet.userId = :userId")
                .doesNotContain("sourceWallet.userId = :userId");
    }
}
