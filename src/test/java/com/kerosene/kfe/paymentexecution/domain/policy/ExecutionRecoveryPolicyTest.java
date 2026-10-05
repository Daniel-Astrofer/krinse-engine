package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExternalExecutionEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionRecoveryPolicyTest {
    private final ExecutionRecoveryPolicy policy = new ExecutionRecoveryPolicy();

    @ParameterizedTest
    @MethodSource("markerCombinations")
    void anyEvidenceRequiresReconciliationForEveryMarkerCombination(int mask) {
        ExternalExecutionEvidence evidence = new ExternalExecutionEvidence(
                (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0);

        assertThat(policy.requiresReconciliation(evidence)).isEqualTo(mask != 0);
    }

    @Test
    void missingEvidenceDoesNotBecomeEvidenceOfAbsence() {
        assertThatThrownBy(() -> policy.requiresReconciliation(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 2", "2147483646, 2147483647", "2147483647, 2147483647"})
    void incrementsAndSaturatesInsteadOfOverflowing(int attempts, int expected) {
        assertThat(policy.nextAttempt(attempts)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1, true", "0, 2, false", "6, 8, false", "7, 8, true", "8, 8, true",
            "2147483645, 2147483647, false", "2147483646, 2147483647, true",
            "2147483647, 2147483647, true", "2147483647, 1, true"
    })
    void comparesMathematicalNextAttemptAgainstPositiveBudget(int attempts, int limit, boolean exhausted) {
        assertThat(policy.retryExhausted(attempts, limit)).isEqualTo(exhausted);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativePersistedAttemptsFailClosed(int attempts) {
        assertThatThrownBy(() -> policy.nextAttempt(attempts)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryExhausted(attempts, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidRetryBudgetFailsClosed(int limit) {
        assertThatThrownBy(() -> policy.retryExhausted(0, limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"1, 2", "2, 4", "3, 8", "4, 16", "5, 32", "6, 32", "2147483647, 32"})
    void preservesRetryDelayAndTheActualThirtyTwoMinuteMaximum(int attempts, long minutes) {
        assertThat(policy.retryDelay(attempts)).isEqualTo(Duration.ofMinutes(minutes));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 2", "2, 4", "3, 8", "4, 16", "5, 32", "6, 64", "7, 128",
            "8, 256", "9, 256", "2147483647, 256"
    })
    void preservesUnknownDelayAndTheActualTwoHundredFiftySixSecondMaximum(int attempts, long seconds) {
        assertThat(policy.unknownDelay(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void delayRequiresAnAlreadyIncrementedAttempt(int attempts) {
        assertThatThrownBy(() -> policy.retryDelay(attempts)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.unknownDelay(attempts)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconciliationRetainsFiveMinuteDelay() {
        assertThat(policy.reconciliationDelay()).isEqualTo(Duration.ofMinutes(5));
    }

    private static IntStream markerCombinations() {
        return IntStream.range(0, 128);
    }
}
