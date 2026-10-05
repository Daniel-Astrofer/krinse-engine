package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCancellationSnapshotTest {
    private static final PaymentExecutionId ID = new PaymentExecutionId(
            UUID.fromString("c1c3f8f8-2b88-4fbf-b079-0d1528a32081"));
    private static final UUID SOURCE = UUID.fromString("ae1f32d0-3233-4bda-9bfb-cc254bb817ec");
    private static final UUID DESTINATION = UUID.fromString("50cd08dc-183f-4433-a0dc-4a3ad47c0d53");

    @Test
    void representsAnImmutableValueWithIndependentLifecycleDecisions() {
        var previous = snapshot(ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, null);
        var equal = snapshot(ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, null);

        assertThat(PaymentCancellationSnapshot.class.isRecord()).isTrue();
        assertThat(Arrays.stream(PaymentCancellationSnapshot.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())))
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
        assertThat(previous).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(previous.executionId()).isEqualTo(ID);
        assertThat(previous.userId()).isEqualTo(41L);
        assertThat(previous.incomplete()).isTrue();
        assertThat(previous.cancellable()).isTrue();
        assertThat(previous.requiresReserveRelease()).isTrue();
        assertThat(previous.requiresLiquidityRelease()).isTrue();
        assertThat(previous.status()).isEqualTo(ExecutionStatus.EXECUTING);
        assertThat(previous.totalDebitSats()).isEqualTo(5_100L);
    }

    @ParameterizedTest
    @MethodSource("missingRequiredValues")
    void rejectsMissingRequiredIdentityAndClassification(
            PaymentExecutionId id, ExecutionStatus status, PaymentRail rail, PaymentDirection direction,
            String expectedMessage) {
        assertThatThrownBy(() -> new PaymentCancellationSnapshot(
                id, 41L, status, rail, direction, SOURCE, DESTINATION, 1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(expectedMessage);
    }

    static Stream<Arguments> missingRequiredValues() {
        return Stream.of(
                Arguments.of(null, ExecutionStatus.INTENT, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                        "execution id is required"),
                Arguments.of(ID, null, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                        "execution status is required"),
                Arguments.of(ID, ExecutionStatus.INTENT, null, PaymentDirection.OUTBOUND,
                        "payment rail is required"),
                Arguments.of(ID, ExecutionStatus.INTENT, PaymentRail.ONCHAIN, null,
                        "payment direction is required"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsInvalidOwnerBeforeFinancialEffects(long userId) {
        assertThatThrownBy(() -> new PaymentCancellationSnapshot(
                ID, userId, ExecutionStatus.LOCKED, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 1L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("user id must be positive");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void rejectsNegativeDebit(long debit) {
        assertThatThrownBy(() -> snapshot(ExecutionStatus.INTENT, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, debit, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("total debit cannot be negative");
    }

    @Test
    void allowsMissingWalletsAndBlockchainEvidenceWithoutCreatingARelease() {
        var previous = snapshot(ExecutionStatus.EXECUTING, PaymentRail.ONCHAIN, PaymentDirection.INBOUND,
                null, null, 0L, null);

        assertThat(previous.sourceWalletId()).isNull();
        assertThat(previous.destinationWalletId()).isNull();
        assertThat(previous.blockchainTransactionId()).isNull();
        assertThat(previous.statementWalletId()).isNull();
        assertThat(previous.requiresReserveRelease()).isFalse();
        assertThat(previous.requiresLiquidityRelease()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "SETTLED", "FAILED", "CANCELLED", "CONFLICTED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED"})
    void closedStatesAreNeitherIncompleteNorCancellable(ExecutionStatus status) {
        var previous = snapshot(status, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, null);

        assertThat(previous.incomplete()).isFalse();
        assertThat(previous.cancellable()).isFalse();
        assertThat(previous.requiresReserveRelease()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {
            "BROADCAST", "CONFIRMING", "REQUIRES_RECONCILIATION", "CONFLICTED_RECONCILING", "REORG_RECONCILIATION"})
    void unresolvedNetworkStatesStayIncompleteButCannotBeCancelled(ExecutionStatus status) {
        var previous = snapshot(status, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, null);

        assertThat(previous.incomplete()).isTrue();
        assertThat(previous.cancellable()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void executingWithoutBlockchainEvidenceIsPreliminarilyCancellable(String blockchainTransactionId) {
        var previous = snapshot(ExecutionStatus.EXECUTING, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, blockchainTransactionId);

        assertThat(previous.cancellable()).isTrue();
    }

    @Test
    void executingWithBlockchainEvidenceCannotBeCancelled() {
        var previous = snapshot(ExecutionStatus.EXECUTING, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 5_100L, "network-txid");

        assertThat(previous.cancellable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus.class)
    void onlyLockedAndExecutingStatesHaveReleasableReservedBalance(ExecutionStatus status) {
        var previous = snapshot(status, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                SOURCE, DESTINATION, 5_100L, null);

        assertThat(previous.requiresReserveRelease())
                .isEqualTo(status == ExecutionStatus.LOCKED || status == ExecutionStatus.EXECUTING);
    }

    @Test
    void reserveReleaseRequiresBothPositiveDebitAndSourceWallet() {
        assertThat(snapshot(ExecutionStatus.LOCKED, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 0L, null).requiresReserveRelease()).isFalse();
        assertThat(snapshot(ExecutionStatus.EXECUTING, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                null, DESTINATION, 5_100L, null).requiresReserveRelease()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("railAndDirectionCombinations")
    void onlyOutboundLightningHasChannelLiquidityToRelease(PaymentRail rail, PaymentDirection direction) {
        var previous = snapshot(ExecutionStatus.INTENT, rail, direction, SOURCE, DESTINATION, 5_100L, null);

        assertThat(previous.requiresLiquidityRelease())
                .isEqualTo(rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND);
    }

    static Stream<Arguments> railAndDirectionCombinations() {
        return Arrays.stream(PaymentRail.values())
                .flatMap(rail -> Arrays.stream(PaymentDirection.values())
                        .map(direction -> Arguments.of(rail, direction)));
    }

    @Test
    void statementWalletPrefersSourceAndFallsBackToInboundDestination() {
        assertThat(snapshot(ExecutionStatus.INTENT, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                SOURCE, DESTINATION, 0L, null).statementWalletId()).isEqualTo(SOURCE);
        assertThat(snapshot(ExecutionStatus.INTENT, PaymentRail.LIGHTNING, PaymentDirection.INBOUND,
                null, DESTINATION, 0L, null).statementWalletId()).isEqualTo(DESTINATION);
    }

    private static PaymentCancellationSnapshot snapshot(
            ExecutionStatus status, PaymentRail rail, PaymentDirection direction,
            UUID source, UUID destination, long debit, String blockchainTransactionId) {
        return new PaymentCancellationSnapshot(
                ID, 41L, status, rail, direction, source, destination, debit, blockchainTransactionId);
    }
}
