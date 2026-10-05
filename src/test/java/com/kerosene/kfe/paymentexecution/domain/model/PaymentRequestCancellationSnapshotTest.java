package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRequestCancellationSnapshotTest {

    @ParameterizedTest
    @EnumSource(PaymentRequestCancellationStatus.class)
    void onlyOpenAndExpiredRequestsAreCancellable(PaymentRequestCancellationStatus status) {
        var snapshot = snapshot(status, PaymentRail.LIGHTNING, "hash", null, null);

        assertThat(snapshot.cancellable()).isEqualTo(
                status == PaymentRequestCancellationStatus.OPEN || status == PaymentRequestCancellationStatus.EXPIRED);
    }

    @Test
    void requiresInvoiceCancellationForEachNonBlankLightningIdentifier() {
        assertThat(snapshot(PaymentRail.LIGHTNING, "hash", null, null).invoiceCancellationRequired()).isTrue();
        assertThat(snapshot(PaymentRail.LIGHTNING, null, "provider", null).invoiceCancellationRequired()).isTrue();
        assertThat(snapshot(PaymentRail.LIGHTNING, null, null, "invoice").invoiceCancellationRequired()).isTrue();
    }

    @Test
    void missingOrBlankIdentifiersDoNotRequireInvoiceCancellation() {
        assertThat(snapshot(PaymentRail.LIGHTNING, null, null, null).invoiceCancellationRequired()).isFalse();
        assertThat(snapshot(PaymentRail.LIGHTNING, " ", "\t", "\n").invoiceCancellationRequired()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRail.class, names = {"INTERNAL", "ONCHAIN"})
    void nonLightningRequestsNeverRequireProviderInvoiceCancellation(PaymentRail rail) {
        assertThat(snapshot(rail, "hash", "provider", "invoice").invoiceCancellationRequired()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveOwners(long userId) {
        assertThatThrownBy(() -> new PaymentRequestCancellationSnapshot(
                UUID.randomUUID(), userId, null, null, PaymentRequestCancellationStatus.OPEN,
                PaymentRail.ONCHAIN, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("user id must be positive");
    }

    @Test
    void rejectsMissingIdentityStatusAndRail() {
        assertThatThrownBy(() -> new PaymentRequestCancellationSnapshot(
                null, 42L, null, null, PaymentRequestCancellationStatus.OPEN,
                PaymentRail.ONCHAIN, null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequestCancellationSnapshot(
                UUID.randomUUID(), 42L, null, null, null,
                PaymentRail.ONCHAIN, null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequestCancellationSnapshot(
                UUID.randomUUID(), 42L, null, null, PaymentRequestCancellationStatus.OPEN,
                null, null, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void diagnosticStringDoesNotExposeInvoiceOrProviderCredentials() {
        var snapshot = snapshot(PaymentRail.LIGHTNING, "sensitive-hash", "sensitive-provider", "sensitive-invoice");

        assertThat(snapshot.toString()).contains(snapshot.id().toString(), "OPEN", "LIGHTNING")
                .doesNotContain("sensitive-hash", "sensitive-provider", "sensitive-invoice",
                        "paymentHash", "providerReference", "paymentRequest=");
    }

    private static PaymentRequestCancellationSnapshot snapshot(
            PaymentRail rail, String hash, String providerReference, String invoice) {
        return snapshot(PaymentRequestCancellationStatus.OPEN, rail, hash, providerReference, invoice);
    }

    private static PaymentRequestCancellationSnapshot snapshot(
            PaymentRequestCancellationStatus status,
            PaymentRail rail, String hash, String providerReference, String invoice) {
        return new PaymentRequestCancellationSnapshot(
                UUID.randomUUID(), 42L, null, null, status, rail, hash, providerReference, invoice, null);
    }
}
