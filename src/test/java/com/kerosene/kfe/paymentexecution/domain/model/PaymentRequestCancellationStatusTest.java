package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestCancellationStatusTest {
    @ParameterizedTest
    @EnumSource(value = PaymentRequestCancellationStatus.class, names = {"OPEN", "EXPIRED"})
    void onlyOpenOrExpiredRequestsCanBeCancelled(PaymentRequestCancellationStatus status) {
        assertThat(status.cancellable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestCancellationStatus.class, names = {"OPEN", "EXPIRED"},
            mode = EnumSource.Mode.EXCLUDE)
    void allOtherRequestStatesAreClosedToCancellation(PaymentRequestCancellationStatus status) {
        assertThat(status.cancellable()).isFalse();
    }
}
