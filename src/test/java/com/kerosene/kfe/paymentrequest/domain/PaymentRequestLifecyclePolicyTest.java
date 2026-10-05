package com.kerosene.kfe.paymentrequest.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestLifecyclePolicyTest {
    @Test
    void expiryIsInclusiveAndNullSafe() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 26, 12, 0);

        assertThat(PaymentRequestLifecyclePolicy.isExpired(now, now)).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.isExpired(now.minusSeconds(1), now)).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.isExpired(now.plusSeconds(1), now)).isFalse();
        assertThat(PaymentRequestLifecyclePolicy.isExpired(null, now)).isFalse();
    }

    @Test
    void observationAndSettlementOnlyAcceptOpenOrExpiredRequests() {
        assertThat(PaymentRequestLifecyclePolicy.canObserve("OPEN")).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.canObserve("EXPIRED")).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.canObserve("PAID")).isFalse();
        assertThat(PaymentRequestLifecyclePolicy.canSettle("CANCELLED")).isFalse();
    }

    @Test
    void amountRuleRejectsZeroAndUnderpayment() {
        assertThat(PaymentRequestLifecyclePolicy.acceptsAmount(null, 1)).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.acceptsAmount(100L, 100L)).isTrue();
        assertThat(PaymentRequestLifecyclePolicy.acceptsAmount(100L, 99L)).isFalse();
        assertThat(PaymentRequestLifecyclePolicy.acceptsAmount(null, 0L)).isFalse();
    }
}
