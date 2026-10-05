package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundConflictPolicyTest {
    private final OutboundConflictPolicy policy = new OutboundConflictPolicy();

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class,
            names = {"FAILED", "CANCELLED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED"})
    void closedExecutionsAreNotReopened(ExecutionStatus current) {
        assertThat(policy.target(current)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"SETTLED", "REORG_RECONCILIATION"})
    void settledExecutionsNeedReorgAccountingNotAReservedBalanceRefund(ExecutionStatus current) {
        assertThat(policy.target(current)).contains(ExecutionStatus.REORG_RECONCILIATION);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"FAILED", "CANCELLED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED",
                    "SETTLED", "REORG_RECONCILIATION"})
    void everyOtherStateRequiresReconciliation(ExecutionStatus current) {
        assertThat(policy.target(current)).contains(ExecutionStatus.REQUIRES_RECONCILIATION);
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus.class)
    void neverDecidesToRefundOrAdoptAReplacement(ExecutionStatus current) {
        policy.target(current).ifPresent(target -> assertThat(target)
                .isIn(ExecutionStatus.REQUIRES_RECONCILIATION, ExecutionStatus.REORG_RECONCILIATION));
    }

    @Test
    void missingStatusFailsClosed() {
        assertThatThrownBy(() -> policy.target(null)).isInstanceOf(NullPointerException.class);
    }
}
