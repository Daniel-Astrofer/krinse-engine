package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntentTest {

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveOwners(long userId) {
        assertThatThrownBy(() -> intent(userId, key(), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("authenticated user id must be positive");
    }

    @Test
    void rejectsMissingIdempotencyKeyRailOrDirection() {
        assertThatThrownBy(() -> intent(42L, null, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent(42L, key(), null, PaymentDirection.OUTBOUND, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent(42L, key(), PaymentRail.ONCHAIN, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void rejectsNonPositiveAmounts(long amountSats) {
        assertThatThrownBy(() -> intent(42L, key(), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, amountSats))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("amountSats must be positive.");
    }

    @ParameterizedTest
    @ValueSource(longs = {2_100_000_000_000_001L, Long.MAX_VALUE})
    void rejectsAmountsAboveTheExistingMaximum(long amountSats) {
        assertThatThrownBy(() -> intent(42L, key(), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, amountSats))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountSats exceeds maximum allowed limit (21M BTC).");
    }

    @Test
    void allowsBothExistingAmountLimitsAndUnresolvedNullableWallets() {
        assertThat(intent(42L, key(), PaymentRail.ONCHAIN, PaymentDirection.INBOUND, 1L).amountSats()).isEqualTo(1L);
        var maximum = intent(42L, key(), PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 2_100_000_000_000_000L);
        assertThat(maximum.amountSats()).isEqualTo(2_100_000_000_000_000L);
        assertThat(maximum.sourceWalletId()).isNull();
        assertThat(maximum.destinationWalletId()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INBOUND", "INTERNAL,OUTBOUND", "ONCHAIN,INTERNAL", "LIGHTNING,INTERNAL"})
    void rejectsIncompatibleInternalRailAndDirection(PaymentRail rail, PaymentDirection direction) {
        assertThatThrownBy(() -> intent(42L, key(), rail, direction, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesTheValidatedIdempotencyKeyWithoutTrimmingIt() {
        var key = new IdempotencyKey("  exact-client-key  ");

        assertThat(intent(42L, key, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 1L).idempotencyKey()).isSameAs(key);
        assertThat(key.value()).isEqualTo("  exact-client-key  ");
    }

    @Test
    void diagnosticsNeverExposeIdempotencyReferenceOrMemo() {
        var intent = new PaymentIntent(42L, new IdempotencyKey("sensitive-key"),
                PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, null, null, 123L,
                "sensitive-invoice", "sensitive-memo");

        assertThat(intent.toString()).contains("userId=42", "LIGHTNING", "OUTBOUND", "amountSats=123")
                .doesNotContain("sensitive-key", "sensitive-invoice", "sensitive-memo");
    }

    private static PaymentIntent intent(
            long userId, IdempotencyKey key, PaymentRail rail, PaymentDirection direction, long amountSats) {
        return new PaymentIntent(userId, key, rail, direction, null, null, amountSats, null, null);
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("client-key");
    }
}
