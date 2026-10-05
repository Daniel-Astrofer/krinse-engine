package com.kerosene.kfe.paymentexecution.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;

import java.math.BigInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionFeePolicyTest {
    private final ExecutionFeePolicy policy = new ExecutionFeePolicy();

    @ParameterizedTest
    @CsvSource({
            "0, 100, 100, 0, 100, 0",
            "100, 1000, 1120, 40, 1060, 60",
            "100, 1000, 1120, 100, 1120, 0",
            "100, 1000, 1120, 0, 1020, 100",
            "30, 100, 130, 30, 130, 0",
            "30, 101, 131, 30, 131, 0",
            "1, 4, 5, 1, 5, 0"
    })
    void acceptedReconciliationOnlyReleasesUnusedNetworkReserve(
            long reserved, long receiver, long total, long actual, long expectedTotal, long expectedRelease) {
        var decision = policy.reconcile(reserved, receiver, total, actual);

        assertThat(decision).isEqualTo(new ExecutionFeePolicy.Decision(
                true, actual, expectedTotal, expectedRelease, null, null));
        assertThat(Math.addExact(decision.totalDebitSats(), decision.releaseSats())).isEqualTo(total);
    }

    @ParameterizedTest
    @CsvSource({
            "-1, -1, -1, -1, INVALID_ACTUAL_FEE",
            "0, 100, 100, -9223372036854775808, INVALID_ACTUAL_FEE",
            "-1, 100, 100, 1, INVALID_FEE_SNAPSHOT",
            "1, 0, 100, 1, INVALID_FEE_SNAPSHOT",
            "1, -1, 100, 1, INVALID_FEE_SNAPSHOT",
            "1, 100, 0, 1, INVALID_FEE_SNAPSHOT",
            "1, 100, -1, 1, INVALID_FEE_SNAPSHOT",
            "-9223372036854775808, 100, 100, 0, INVALID_FEE_SNAPSHOT",
            "0, -9223372036854775808, 100, 0, INVALID_FEE_SNAPSHOT",
            "0, 100, -9223372036854775808, 0, INVALID_FEE_SNAPSHOT",
            "30, 100, 130, 31, ACTUAL_FEE_EXCEEDS_RESERVED",
            "0, 1, 1, 9223372036854775807, ACTUAL_FEE_EXCEEDS_RESERVED",
            "100, 100, 50, 31, FEE_EXCEEDS_MAX_RATIO",
            "1, 3, 4, 1, FEE_EXCEEDS_MAX_RATIO",
            "100, 100, 50, 30, INVALID_RECONCILED_DEBIT",
            "100, 1000, 100, 0, INVALID_RECONCILED_DEBIT",
            "9223372036854775807, 9223372036854775807, 9223372036854775807, 0, INVALID_RECONCILED_DEBIT"
    })
    void rejectsInContractOrderAndNeverReturnsUsableMonetaryValues(
            long reserved, long receiver, long total, long actual, String failureCode) {
        assertRejected(policy.reconcile(reserved, receiver, total, actual), failureCode);
    }

    @Test
    void largeReserveAndDebitDoNotOverflowOrReleaseAnythingBeyondTheDifference() {
        long reserved = Long.MAX_VALUE - 1L;
        long ratioLimit = percentageLimit(Long.MAX_VALUE, 30L).longValueExact();
        for (long actual : new long[]{0L, 1L, ratioLimit - 1L, ratioLimit}) {
            var decision = policy.reconcile(reserved, Long.MAX_VALUE, Long.MAX_VALUE, actual);
            long expectedTotal = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(reserved))
                    .add(BigInteger.valueOf(actual)).longValueExact();
            long expectedRelease = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(expectedTotal))
                    .longValueExact();

            assertThat(decision).isEqualTo(new ExecutionFeePolicy.Decision(
                    true, actual, expectedTotal, expectedRelease, null, null));
        }
        assertRejected(policy.reconcile(reserved, Long.MAX_VALUE, Long.MAX_VALUE, ratioLimit + 1L),
                "FEE_EXCEEDS_MAX_RATIO");
        // With 0 <= actual <= reserved and total > 0, total-reserved+actual cannot overflow long.
        // FEE_RECONCILIATION_OVERFLOW is a defensive branch, not a reachable valid-snapshot fixture.
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 3L, 4L, 99L, 100L, 101L, 922337203685477580L, Long.MAX_VALUE})
    void thirtyPercentBoundaryUsesFloorAndMatchesArbitraryPrecision(long receiver) {
        long limit = percentageLimit(receiver, 30L).longValueExact();
        long reserved = limit + 1L;
        long total = reserved + 1L;

        assertThat(policy.reconcile(reserved, receiver, total, limit).accepted()).isTrue();
        assertRejected(policy.reconcile(reserved, receiver, total, limit + 1L), "FEE_EXCEEDS_MAX_RATIO");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 1, 0, 0",
            "30, 30, 100, 30, 30",
            "0, 100, 1, 1, 1",
            "101, 101, 100, 101, 101",
            "1000, 1000, 1, 0, 0",
            "9223372036854775807, 9223372036854775807, 9223372036854775807, 9223372036854775807, 100",
            "9223372036854775807, 9223372036854775807, 100, 0, 9223372036854775807"
    })
    void acceptsValidStaticFeeSnapshotWithInclusiveOrDisabledCaps(
            long estimated, long reserved, long amount, long absolute, long ratio) {
        assertThat(policy.validateBeforeBroadcast(estimated, reserved, amount, absolute, ratio))
                .isEqualTo(new ExecutionFeePolicy.Validation(true, null));
    }

    @ParameterizedTest
    @CsvSource({
            "-1, -1, 0, -1, -1, Estimated fee is negative",
            "-9223372036854775808, 0, 1, 0, 0, Estimated fee is negative",
            "10, -1, 1, 0, 0, Fee validation requires",
            "0, 0, 0, 0, 0, Fee validation requires",
            "0, 0, -1, 0, 0, Fee validation requires",
            "10, 0, 1, -1, 0, Fee validation requires",
            "10, 0, 1, 0, -1, Fee validation requires",
            "10, 0, 1, 1, 1, Fee 10 exceeds reserved",
            "10, 10, 1, 1, 1, Fee 10 exceeds absolute max",
            "1, 1, 3, 0, 30, Fee 1 exceeds 30%",
            "31, 31, 100, 0, 30, Fee 31 exceeds 30%",
            "9223372036854775807, 0, 1, 0, 0, Fee 9223372036854775807 exceeds reserved"
    })
    void staticValidationRejectsInContractOrder(
            long estimated, long reserved, long amount, long absolute, long ratio, String reasonPrefix) {
        var validation = policy.validateBeforeBroadcast(estimated, reserved, amount, absolute, ratio);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).startsWith(reasonPrefix);
    }

    @Test
    void arbitraryPercentageCapsIncludingAboveOneHundredAndLongMaxMatchBigInteger() {
        for (long amount : new long[]{1L, 3L, 100L, Long.MAX_VALUE}) {
            for (long percent : new long[]{0L, 1L, 30L, 100L, 101L, Long.MAX_VALUE}) {
                for (long fee : new long[]{0L, 1L, 30L, 100L, Long.MAX_VALUE}) {
                    boolean expected = percent == 0L
                            || BigInteger.valueOf(fee).compareTo(percentageLimit(amount, percent)) <= 0;
                    var validation = policy.validateBeforeBroadcast(fee, Long.MAX_VALUE, amount, 0L, percent);
                    assertThat(validation.valid()).as("fee=%s amount=%s percent=%s", fee, amount, percent)
                            .isEqualTo(expected);
                    if (expected) {
                        assertThat(validation.reason()).isNull();
                    } else {
                        assertThat(validation.reason()).contains("exceeds " + percent + "%");
                    }
                }
            }
        }
    }

    @Test
    void disablingOneStaticCapDoesNotDisableTheOtherOrTheReservedLimit() {
        assertThat(policy.validateBeforeBroadcast(31L, 100L, 100L, 0L, 30L).valid()).isFalse();
        assertThat(policy.validateBeforeBroadcast(31L, 100L, 100L, 30L, 0L).valid()).isFalse();
        assertThat(policy.validateBeforeBroadcast(101L, 100L, 100L, 0L, 0L).valid()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidDecisionComponents")
    void decisionConstructorRejectsContradictoryOrUsableRejectedPlans(
            boolean accepted, long actual, long total, long release, String code, String message) {
        assertThatThrownBy(() -> new ExecutionFeePolicy.Decision(accepted, actual, total, release, code, message))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validDecisionRecordsPermitExactlyTheirRespectiveResultShapes() {
        assertThatCode(() -> new ExecutionFeePolicy.Decision(true, 0L, 1L, 0L, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ExecutionFeePolicy.Decision(false, 0L, 0L, 0L, "DENIED", "Fixed reason."))
                .doesNotThrowAnyException();
    }

    @Test
    void validationConstructorRequiresAnExplanationOnlyForRejection() {
        assertThatCode(() -> new ExecutionFeePolicy.Validation(true, null)).doesNotThrowAnyException();
        assertThatCode(() -> new ExecutionFeePolicy.Validation(false, "Fixed reason.")).doesNotThrowAnyException();
        for (String reason : new String[]{"", " ", "unexpected"}) {
            assertThatThrownBy(() -> new ExecutionFeePolicy.Validation(true, reason))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String reason : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> new ExecutionFeePolicy.Validation(false, reason))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Stream<Arguments> invalidDecisionComponents() {
        return Stream.of(
                Arguments.of(true, -1L, 1L, 0L, null, null),
                Arguments.of(true, 0L, 0L, 0L, null, null),
                Arguments.of(true, 0L, -1L, 0L, null, null),
                Arguments.of(true, 0L, 1L, -1L, null, null),
                Arguments.of(true, 0L, 1L, 0L, "CODE", null),
                Arguments.of(true, 0L, 1L, 0L, null, "message"),
                Arguments.of(false, 1L, 0L, 0L, "CODE", "message"),
                Arguments.of(false, -1L, 0L, 0L, "CODE", "message"),
                Arguments.of(false, 0L, 1L, 0L, "CODE", "message"),
                Arguments.of(false, 0L, -1L, 0L, "CODE", "message"),
                Arguments.of(false, 0L, 0L, 1L, "CODE", "message"),
                Arguments.of(false, 0L, 0L, -1L, "CODE", "message"),
                Arguments.of(false, 0L, 0L, 0L, null, "message"),
                Arguments.of(false, 0L, 0L, 0L, "", "message"),
                Arguments.of(false, 0L, 0L, 0L, " ", "message"),
                Arguments.of(false, 0L, 0L, 0L, "CODE", null),
                Arguments.of(false, 0L, 0L, 0L, "CODE", ""),
                Arguments.of(false, 0L, 0L, 0L, "CODE", " "));
    }

    private static BigInteger percentageLimit(long amount, long percent) {
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100L));
    }

    private static void assertRejected(ExecutionFeePolicy.Decision decision, String code) {
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.failureCode()).isEqualTo(code);
        assertThat(decision.message()).isNotBlank();
        assertThat(decision.actualFeeSats()).isZero();
        assertThat(decision.totalDebitSats()).isZero();
        assertThat(decision.releaseSats()).isZero();
    }
}
