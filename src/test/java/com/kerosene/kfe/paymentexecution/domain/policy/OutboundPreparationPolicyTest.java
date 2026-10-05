package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Locale;

import static com.kerosene.kfe.paymentexecution.domain.policy.OutboundPreparationPolicy.Action;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundPreparationPolicyTest {
    private final OutboundPreparationPolicy policy = new OutboundPreparationPolicy();

    @ParameterizedTest
    @EnumSource(ExecutionStatus.class)
    void preservesStatusPrecedenceAcrossEveryOperationAndBroadcastState(ExecutionStatus status) {
        String[] operations = {null, "", " ", "unsupported", "ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND",
                "ONCHAIN_INBOUND", "LIGHTNING_INBOUND"};
        for (String operation : operations) {
            for (boolean hasTxid : new boolean[]{false, true}) {
                var decision = policy.decide(status, operation, hasTxid);
                String normalized = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
                Action expected = switch (status) {
                    case SETTLED, FAILED -> Action.TERMINAL_SKIP;
                    case EXECUTING, REQUIRES_RECONCILIATION -> switch (normalized) {
                        case "ONCHAIN_OUTBOUND" -> hasTxid ? Action.ALREADY_BROADCAST : Action.EXECUTE;
                        case "LIGHTNING_OUTBOUND" -> Action.EXECUTE;
                        case "ONCHAIN_INBOUND", "LIGHTNING_INBOUND" -> Action.RECONCILE_INBOUND;
                        default -> Action.UNSUPPORTED;
                    };
                    default -> Action.INVALID_STATUS;
                };
                assertThat(decision).as("status=%s operation=%s txid=%s", status, operation, hasTxid)
                        .isEqualTo(new OutboundPreparationPolicy.Decision(expected, normalized));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "' onchain_outbound ', ONCHAIN_OUTBOUND, EXECUTE",
            "' LiGhTnInG_Outbound ', LIGHTNING_OUTBOUND, EXECUTE",
            "' onchain_inbound ', ONCHAIN_INBOUND, RECONCILE_INBOUND",
            "' lightning_inbound ', LIGHTNING_INBOUND, RECONCILE_INBOUND",
            "' custom_inbound ', CUSTOM_INBOUND, UNSUPPORTED"
    })
    void trimsAndNormalizesWithoutTreatingEveryInboundSuffixAsKnown(String raw, String normalized, Action action) {
        assertThat(policy.decide(ExecutionStatus.EXECUTING, raw, false))
                .isEqualTo(new OutboundPreparationPolicy.Decision(action, normalized));
    }

    @Test
    @ResourceLock("java.util.Locale")
    void normalizationDoesNotDependOnTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(policy.decide(ExecutionStatus.EXECUTING, " lightning_outbound ", false))
                    .isEqualTo(new OutboundPreparationPolicy.Decision(Action.EXECUTE, "LIGHTNING_OUTBOUND"));
            assertThat(policy.decide(ExecutionStatus.EXECUTING, "onchain_inbound", true))
                    .isEqualTo(new OutboundPreparationPolicy.Decision(Action.RECONCILE_INBOUND, "ONCHAIN_INBOUND"));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

    @Test
    void missingStatusFailsClosedRegardlessOfOperation() {
        assertThatThrownBy(() -> policy.decide(null, "ONCHAIN_OUTBOUND", true))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 12L, Long.MAX_VALUE})
    void preservesPositiveExplicitRateBeforeConsideringEstimate(long rate) {
        for (long reserve : new long[]{0L, 1L, Long.MAX_VALUE}) {
            for (Long estimated : new Long[]{null, -1L, 0L, 1L, Long.MAX_VALUE}) {
                assertThat(policy.resolveFeeRate(rate, reserve, estimated)).isEqualTo(rate);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "179, 1", "180, 1", "181, 2", "360, 2", "361, 3"})
    void absentEstimateUsesOneHundredEightyVbytesAndRoundsUp(long reserve, long rate) {
        for (Long explicit : new Long[]{null, 0L, -1L, Long.MIN_VALUE}) {
            assertThat(policy.resolveFeeRate(explicit, reserve, null)).isEqualTo(rate);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 500, 1", "1000, 250, 4", "1001, 250, 5", "9223372036854775807, 2, 4611686018427387904"})
    void derivesCeilingWithoutOverflowingIntermediateSum(long reserve, long estimated, long rate) {
        assertThat(policy.resolveFeeRate(null, reserve, estimated)).isEqualTo(rate);
    }

    @Test
    void ceilingMatchesArbitraryPrecisionReferenceAtLongBoundaries() {
        for (long reserve : new long[]{1L, 179L, 180L, 181L, Long.MAX_VALUE - 1L, Long.MAX_VALUE}) {
            for (long estimated : new long[]{1L, 2L, 180L, Long.MAX_VALUE - 1L, Long.MAX_VALUE}) {
                long expected = BigInteger.valueOf(reserve).add(BigInteger.valueOf(estimated).subtract(BigInteger.ONE))
                        .divide(BigInteger.valueOf(estimated)).longValueExact();
                assertThat(policy.resolveFeeRate(null, reserve, estimated))
                        .as("reserve=%s estimated=%s", reserve, estimated).isEqualTo(expected);
            }
        }
    }

    @Test
    void zeroReservePreservesTheOriginalRateIncludingAbsenceAndInvalidLegacyHints() {
        for (Long rate : new Long[]{null, 0L, -1L, Long.MIN_VALUE, 12L}) {
            for (Long estimated : new Long[]{null, 0L, -1L, 180L}) {
                assertThat(policy.resolveFeeRate(rate, 0L, estimated)).isEqualTo(rate);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void invalidEstimatePreservesExistingHintWithoutInventingVsize(long estimated) {
        for (Long rate : new Long[]{null, 0L, -1L, Long.MIN_VALUE}) {
            assertThat(policy.resolveFeeRate(rate, Long.MAX_VALUE, estimated)).isEqualTo(rate);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void negativeReserveIsRejectedEvenWhenExplicitRateWouldOtherwiseWin(long reserve) {
        for (Long rate : new Long[]{null, 0L, -1L, 12L, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> policy.resolveFeeRate(rate, reserve, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
