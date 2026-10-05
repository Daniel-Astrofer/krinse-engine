package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.exception.InvalidPaymentExecutionTransition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentExecutionTest {

    private static final PaymentExecutionId ID = new PaymentExecutionId(
            UUID.fromString("38f20f59-6126-4f44-8a8e-8f04a46a78ea"));

    @ParameterizedTest
    @MethodSource("criticalAllowedTransitions")
    void acceptsLifecycleTransitionsOwnedByTheAggregate(ExecutionStatus from, ExecutionStatus to) {
        PaymentExecution execution = PaymentExecution.reconstitute(ID, from);

        var event = execution.transitionTo(to);

        assertThat(event.executionId()).isEqualTo(ID);
        assertThat(event.previousStatus()).isEqualTo(from);
        assertThat(event.currentStatus()).isEqualTo(to);
        assertThat(event.changed()).isTrue();
        assertThat(execution.status()).isEqualTo(to);
    }

    static Stream<Arguments> criticalAllowedTransitions() {
        return Stream.of(
                Arguments.of(ExecutionStatus.INTENT, ExecutionStatus.VALIDATING),
                Arguments.of(ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC),
                Arguments.of(ExecutionStatus.QUORUM_SYNC, ExecutionStatus.LOCKED),
                Arguments.of(ExecutionStatus.LOCKED, ExecutionStatus.EXECUTING),
                Arguments.of(ExecutionStatus.EXECUTING, ExecutionStatus.BROADCAST),
                Arguments.of(ExecutionStatus.BROADCAST, ExecutionStatus.CONFIRMING),
                Arguments.of(ExecutionStatus.CONFIRMING, ExecutionStatus.SETTLED),
                Arguments.of(ExecutionStatus.SETTLED, ExecutionStatus.REORG_RECONCILIATION),
                Arguments.of(ExecutionStatus.REORG_RECONCILIATION, ExecutionStatus.SETTLED),
                Arguments.of(ExecutionStatus.REQUIRES_RECONCILIATION, ExecutionStatus.EXECUTING));
    }

    @ParameterizedTest
    @MethodSource("terminalStatuses")
    void terminalStatusesRejectNewTransitions(ExecutionStatus terminal) {
        PaymentExecution execution = PaymentExecution.reconstitute(ID, terminal);

        assertThatThrownBy(() -> execution.transitionTo(ExecutionStatus.EXECUTING))
                .isInstanceOf(InvalidPaymentExecutionTransition.class)
                .hasMessageContaining(terminal.name())
                .hasMessageContaining(ExecutionStatus.EXECUTING.name());
    }

    static Stream<ExecutionStatus> terminalStatuses() {
        return Stream.of(
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED,
                ExecutionStatus.CONFLICTED,
                ExecutionStatus.DROPPED,
                ExecutionStatus.ABANDONED);
    }

    @Test
    void sameStatusIsIdempotent() {
        PaymentExecution execution = PaymentExecution.reconstitute(ID, ExecutionStatus.EXECUTING);

        var event = execution.transitionTo(ExecutionStatus.EXECUTING);

        assertThat(event.changed()).isFalse();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.EXECUTING);
    }

    @Test
    void displayStatusIsAPropertyOfTheDomainLifecycle() {
        assertThat(ExecutionStatus.INTENT.displayStatus()).isEqualTo("PENDING");
        assertThat(ExecutionStatus.SETTLED.displayStatus()).isEqualTo("CONFIRMED");
        assertThat(ExecutionStatus.REORG_RECONCILIATION.displayStatus()).isEqualTo("FAILED");
    }

    @Test
    void canonicalProductStatusIsDerivedByTheDomainLifecycle() {
        assertThat(ExecutionStatus.INTENT.productStatus()).isEqualTo("PENDING");
        assertThat(ExecutionStatus.EXECUTING.productStatus()).isEqualTo("PROCESSING");
        assertThat(ExecutionStatus.BROADCAST.productStatus()).isEqualTo("CONFIRMING");
        assertThat(ExecutionStatus.SETTLED.productStatus()).isEqualTo("COMPLETED");
        assertThat(ExecutionStatus.REORG_RECONCILIATION.productStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(ExecutionStatus.CONFLICTED.productStatus(false)).isEqualTo("FAILED");
        assertThat(ExecutionStatus.CONFLICTED.productStatus(true)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void cancellationIsAllowedOnlyBeforeExternalBroadcast() {
        assertThat(execution(ExecutionStatus.INTENT).canBeCancelled(null)).isTrue();
        assertThat(execution(ExecutionStatus.LOCKED).canBeCancelled(null)).isTrue();
        assertThat(execution(ExecutionStatus.EXECUTING).canBeCancelled(" ")).isTrue();
        assertThat(execution(ExecutionStatus.EXECUTING).canBeCancelled("txid")).isFalse();
        assertThat(execution(ExecutionStatus.BROADCAST).canBeCancelled(null)).isFalse();
        assertThat(execution(ExecutionStatus.SETTLED).canBeCancelled(null)).isFalse();
    }

    @Test
    void incompleteExecutionExcludesFinanciallyTerminalStatuses() {
        assertThat(execution(ExecutionStatus.CONFIRMING).isIncomplete()).isTrue();
        assertThat(execution(ExecutionStatus.REQUIRES_RECONCILIATION).isIncomplete()).isTrue();
        assertThat(execution(ExecutionStatus.SETTLED).isIncomplete()).isFalse();
        assertThat(execution(ExecutionStatus.FAILED).isIncomplete()).isFalse();
        assertThat(execution(ExecutionStatus.CONFLICTED_REFUNDED).isIncomplete()).isFalse();
    }

    private static PaymentExecution execution(ExecutionStatus status) {
        return PaymentExecution.reconstitute(ID, status);
    }
}
