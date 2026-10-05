package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionIntentBindingPolicyTest {
    private final ExecutionIntentBindingPolicy policy = new ExecutionIntentBindingPolicy();

    @ParameterizedTest
    @EnumSource(value = PaymentRail.class, names = {"ONCHAIN", "LIGHTNING"})
    void identicalOutboundBindingsMatchForEachExternalRail(PaymentRail rail) {
        var authorized = binding(fields -> fields.rail = rail);
        var message = binding(fields -> fields.rail = rail);

        assertThat(policy.matches(rail.name() + "_OUTBOUND", authorized, message)).isTrue();
        assertThat(message).isEqualTo(authorized).hasSameHashCodeAs(authorized);
    }

    @ParameterizedTest(name = "rejects divergent {0}")
    @MethodSource("divergentFields")
    void rejectsDivergenceInEveryBoundField(String name, Consumer<Fields> change) {
        var original = binding(fields -> { });
        var changed = binding(change);

        assertThat(policy.matches("ONCHAIN_OUTBOUND", original, changed)).as(name).isFalse();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", changed, original)).as(name).isFalse();
        assertThat(changed).isNotEqualTo(original);
    }

    private static Stream<Arguments> divergentFields() {
        return Stream.of(
                mutation("transactionId", fields -> fields.transactionId = UUID.fromString("22222222-2222-2222-2222-222222222222")),
                mutation("userId", fields -> fields.userId = 43L),
                mutation("idempotencyKey", fields -> fields.idempotencyKey = "another-idempotency-key"),
                mutation("rail", fields -> fields.rail = PaymentRail.LIGHTNING),
                mutation("direction", fields -> fields.direction = PaymentDirection.INBOUND),
                mutation("sourceWalletId", fields -> fields.sourceWalletId = UUID.fromString("33333333-3333-3333-3333-333333333333")),
                mutation("destinationWalletId", fields -> fields.destinationWalletId = UUID.fromString("44444444-4444-4444-4444-444444444444")),
                mutation("absent destinationWalletId", fields -> fields.destinationWalletId = null),
                mutation("amountSats", fields -> fields.amountSats++),
                mutation("networkFeeSats", fields -> fields.networkFeeSats++),
                mutation("totalDebitSats", fields -> fields.totalDebitSats++),
                mutation("externalReference", fields -> fields.externalReference = "different-target"),
                mutation("memo", fields -> fields.memo = "different-memo"),
                mutation("absent memo", fields -> fields.memo = null),
                mutation("quorumProposalHash", fields -> fields.quorumProposalHash = "different-proposal-hash"),
                mutation("idempotency whitespace", fields -> fields.idempotencyKey = " " + fields.idempotencyKey + " "),
                mutation("proposal whitespace", fields -> fields.quorumProposalHash = " " + fields.quorumProposalHash + " "),
                mutation("idempotency case", fields -> fields.idempotencyKey = fields.idempotencyKey.toUpperCase(Locale.ROOT)),
                mutation("proposal case", fields -> fields.quorumProposalHash = fields.quorumProposalHash.toUpperCase(Locale.ROOT)),
                mutation("reference case", fields -> fields.externalReference = fields.externalReference.toUpperCase(Locale.ROOT)),
                mutation("memo case", fields -> fields.memo = fields.memo.toUpperCase(Locale.ROOT))
        );
    }

    @ParameterizedTest(name = "rejects invalid {0} on either side, including identical invalid inputs")
    @MethodSource("invalidFields")
    void invalidBindingsNeverAuthorize(String name, Consumer<Fields> change) {
        var valid = binding(fields -> { });
        var invalid = binding(change);

        assertThat(policy.matches("ONCHAIN_OUTBOUND", invalid, valid)).as(name).isFalse();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", valid, invalid)).as(name).isFalse();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", invalid, invalid)).as(name).isFalse();
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                mutation("transaction identity", fields -> fields.transactionId = null),
                mutation("zero user", fields -> fields.userId = 0L),
                mutation("negative user", fields -> fields.userId = -1L),
                mutation("minimum user", fields -> fields.userId = Long.MIN_VALUE),
                mutation("missing idempotency", fields -> fields.idempotencyKey = null),
                mutation("empty idempotency", fields -> fields.idempotencyKey = ""),
                mutation("blank idempotency", fields -> fields.idempotencyKey = " \t\r\n"),
                mutation("unicode blank idempotency", fields -> fields.idempotencyKey = "\u2003"),
                mutation("missing rail", fields -> fields.rail = null),
                mutation("internal rail", fields -> fields.rail = PaymentRail.INTERNAL),
                mutation("missing direction", fields -> fields.direction = null),
                mutation("inbound direction", fields -> fields.direction = PaymentDirection.INBOUND),
                mutation("internal direction", fields -> fields.direction = PaymentDirection.INTERNAL),
                mutation("missing source wallet", fields -> fields.sourceWalletId = null),
                mutation("zero amount", fields -> fields.amountSats = 0L),
                mutation("negative amount", fields -> fields.amountSats = -1L),
                mutation("minimum amount", fields -> fields.amountSats = Long.MIN_VALUE),
                mutation("negative fee", fields -> fields.networkFeeSats = -1L),
                mutation("minimum fee", fields -> fields.networkFeeSats = Long.MIN_VALUE),
                mutation("total equal to fee", fields -> fields.totalDebitSats = fields.networkFeeSats),
                mutation("total below fee", fields -> fields.totalDebitSats = fields.networkFeeSats - 1L),
                mutation("zero total", fields -> fields.totalDebitSats = 0L),
                mutation("minimum total", fields -> fields.totalDebitSats = Long.MIN_VALUE),
                mutation("missing reference", fields -> fields.externalReference = null),
                mutation("empty reference", fields -> fields.externalReference = ""),
                mutation("blank reference", fields -> fields.externalReference = " \t\r\n"),
                mutation("unicode blank reference", fields -> fields.externalReference = "\u2003"),
                mutation("missing proposal", fields -> fields.quorumProposalHash = null),
                mutation("empty proposal", fields -> fields.quorumProposalHash = ""),
                mutation("blank proposal", fields -> fields.quorumProposalHash = " \t\r\n"),
                mutation("unicode blank proposal", fields -> fields.quorumProposalHash = "\u2003")
        );
    }

    @Test
    void missingBindingsAreRejectedWithoutThrowing() {
        var valid = binding(fields -> { });
        assertThat(policy.matches("ONCHAIN_OUTBOUND", null, valid)).isFalse();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", valid, null)).isFalse();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", null, null)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ONCHAIN_INBOUND", "LIGHTNING_OUTBOUND", "INTERNAL_OUTBOUND", "ONCHAIN", "ONCHAIN__OUTBOUND"})
    void wrongOperationNeverMatches(String operation) {
        var value = binding(fields -> { });
        assertThat(policy.matches(operation, value, value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"onchain_outbound", " OnChAiN_OuTbOuNd ", "\tONCHAIN_OUTBOUND\n"})
    void normalizesOperationCaseAndSurroundingWhitespace(String operation) {
        var value = binding(fields -> { });
        assertThat(policy.matches(operation, value, value)).isTrue();
    }

    @Test
    @ResourceLock("java.util.Locale")
    void operationNormalizationDoesNotDependOnTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var value = binding(fields -> fields.rail = PaymentRail.LIGHTNING);
            assertThat(policy.matches(" lightning_outbound ", value, value)).isTrue();
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

    @Test
    void referenceAndMemoAreComparedAfterTrimWithoutChangingTheSnapshots() {
        var original = binding(fields -> { });
        var padded = binding(fields -> {
            fields.externalReference = " \t" + fields.externalReference + "\n ";
            fields.memo = " \t" + fields.memo + "\n ";
        });
        assertThat(policy.matches("ONCHAIN_OUTBOUND", original, padded)).isTrue();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", padded, original)).isTrue();
        assertThat(padded.externalReference()).isEqualTo(" \tprivate-target\n ");
        assertThat(padded.memo()).isEqualTo(" \tprivate-memo\n ");
        assertThat(padded).isNotEqualTo(original);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u2003"})
    void missingOrBlankMemoIsEquivalentToNull(String memo) {
        var original = binding(fields -> fields.memo = null);
        var message = binding(fields -> fields.memo = memo);
        assertThat(policy.matches("ONCHAIN_OUTBOUND", original, message)).isTrue();
        assertThat(policy.matches("ONCHAIN_OUTBOUND", message, original)).isTrue();
    }

    @Test
    void absentDestinationIsAcceptedWhenBothSidesAgree() {
        var value = binding(fields -> fields.destinationWalletId = null);
        assertThat(policy.matches("ONCHAIN_OUTBOUND", value, value)).isTrue();
    }

    @Test
    void nonBlankIdempotencyAndProposalAreNotTrimmedOrCaseNormalized() {
        var value = binding(fields -> {
            fields.idempotencyKey = " Private-Key ";
            fields.quorumProposalHash = " Private-Proposal ";
        });
        assertThat(policy.matches("ONCHAIN_OUTBOUND", value, value)).isTrue();
        assertThat(value.idempotencyKey()).isEqualTo(" Private-Key ");
        assertThat(value.quorumProposalHash()).isEqualTo(" Private-Proposal ");
    }

    @Test
    void boundaryValuesDoNotTriggerOverflowOrInventAccountingConservation() {
        var value = binding(fields -> {
            fields.userId = Long.MAX_VALUE;
            fields.amountSats = Long.MAX_VALUE;
            fields.networkFeeSats = Long.MAX_VALUE - 1L;
            fields.totalDebitSats = Long.MAX_VALUE;
        });
        assertThat(policy.matches("ONCHAIN_OUTBOUND", value, value)).isTrue();

        var zeroFee = binding(fields -> {
            fields.amountSats = 1L;
            fields.networkFeeSats = 0L;
            fields.totalDebitSats = 1L;
        });
        assertThat(policy.matches("ONCHAIN_OUTBOUND", zeroFee, zeroFee)).isTrue();
    }

    @Test
    void recordConstructionDoesNotValidateUntrustedValues() {
        var value = new ExecutionIntentBinding(null, Long.MIN_VALUE, null, null, null,
                null, null, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, null, null, null);
        assertThat(value.transactionId()).isNull();
        assertThat(value.userId()).isEqualTo(Long.MIN_VALUE);
        assertThat(policy.matches(null, value, value)).isFalse();
        assertThat(value.toString()).isEqualTo("ExecutionIntentBinding[transactionId=null, rail=null, direction=null, references=REDACTED]");
    }

    @Test
    void recordDiagnosticStringExposesOnlyExecutionIdentityRailAndDirection() {
        var value = binding(fields -> { });
        assertThat(value.toString()).isEqualTo("ExecutionIntentBinding[transactionId=" + value.transactionId()
                + ", rail=ONCHAIN, direction=OUTBOUND, references=REDACTED]");
        assertThat(value.toString()).doesNotContain(value.idempotencyKey(), value.externalReference(), value.memo(),
                value.quorumProposalHash(), value.sourceWalletId().toString(), value.destinationWalletId().toString(),
                "userId", "amountSats", "networkFeeSats", "totalDebitSats");
    }

    private static Arguments mutation(String name, Consumer<Fields> change) {
        return Arguments.of(name, change);
    }

    private static ExecutionIntentBinding binding(Consumer<Fields> change) {
        Fields fields = new Fields();
        change.accept(fields);
        return fields.binding();
    }

    private static final class Fields {
        UUID transactionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        long userId = 42L;
        String idempotencyKey = "private-idempotency-key";
        PaymentRail rail = PaymentRail.ONCHAIN;
        PaymentDirection direction = PaymentDirection.OUTBOUND;
        UUID sourceWalletId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID destinationWalletId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        long amountSats = 100_000L;
        long networkFeeSats = 700L;
        long totalDebitSats = 101_600L;
        String externalReference = "private-target";
        String memo = "private-memo";
        String quorumProposalHash = "private-proposal-hash";

        ExecutionIntentBinding binding() {
            return new ExecutionIntentBinding(transactionId, userId, idempotencyKey, rail, direction,
                    sourceWalletId, destinationWalletId, amountSats, networkFeeSats, totalDebitSats,
                    externalReference, memo, quorumProposalHash);
        }
    }
}
