package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCancellationPolicyTest {
    private final PaymentCancellationPolicy policy = new PaymentCancellationPolicy();

    @Test
    void executingRequiresPositiveEvidenceThatExternalCommandHasNeverStarted() {
        assertThatCode(() -> policy.requireUnstarted(ExecutionStatus.EXECUTING, false, true, true, true))
                .doesNotThrowAnyException();
        assertRejected(ExecutionStatus.EXECUTING, false, true, false, true);
        assertRejected(ExecutionStatus.EXECUTING, false, true, true, false);
        assertRejected(ExecutionStatus.EXECUTING, true, true, true, true);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "BROADCAST", "CONFIRMING", "REQUIRES_RECONCILIATION", "CONFLICTED_RECONCILING", "REORG_RECONCILIATION"})
    void unresolvedNetworkStatesCannotBeClosedByUserCancellation(ExecutionStatus status) {
        assertRejected(status, false, true, true, true);
    }

    @Test
    void observedInboundCannotBeDiscardedByCancellingThePaymentRequest() {
        assertRejected(ExecutionStatus.VALIDATING, true, false, false, true);
    }

    private void assertRejected(
            ExecutionStatus status, boolean observed, boolean external, boolean command, boolean untouched) {
        assertThatThrownBy(() -> policy.requireUnstarted(status, observed, external, command, untouched))
                .isInstanceOf(PaymentCancellationRejected.class);
    }
}
