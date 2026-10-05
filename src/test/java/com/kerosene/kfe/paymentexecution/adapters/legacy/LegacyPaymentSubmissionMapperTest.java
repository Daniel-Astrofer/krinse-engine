package com.kerosene.kfe.paymentexecution.adapters.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class LegacyPaymentSubmissionMapperTest {
    @Test
    void roundTripPreservesEveryRawFieldAndDeviceBinding() {
        var request = request(" addr | á ", " memo | 漢 ", "otp-secret", "assertion-secret",
                "passphrase-secret", "pin-secret");
        var command = LegacyPaymentSubmissionMapper.toCommand(41L, request, "device-secret");
        assertThat(command.userId()).isEqualTo(41L);
        assertThat(command.deviceHash()).isEqualTo("device-secret");
        assertThat(LegacyPaymentSubmissionMapper.toLegacyRequest(command)).isEqualTo(request);
        assertThat(command.toString()).doesNotContain("otp-secret", "assertion-secret",
                "passphrase-secret", "pin-secret", "device-secret", " addr | á ", " memo | 漢 ");
    }

    @Test
    void roundTripDoesNotReplaceNullsWithEmptyStrings() {
        var request = new KfeSubmitTransactionRequest("key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                null, null, 1L, 0L, null, null);
        var command = LegacyPaymentSubmissionMapper.toCommand(1L, request, null);
        assertThat(LegacyPaymentSubmissionMapper.toLegacyRequest(command)).isEqualTo(request);
        assertThat(command.deviceHash()).isNull();
    }

    @Test
    void canonicalCopiesPreserveOwnerKeyValuesCredentialsAndHints() {
        var original = LegacyPaymentSubmissionMapper.toCommand(41L,
                request("raw", "memo", "otp", "assertion", "passphrase", "pin"), "device");
        UUID destination = UUID.randomUUID();
        var copy = original.withDestinationWalletId(destination).withCanonicalDestination("canonical", "new memo");
        assertThat(copy).usingRecursiveComparison()
                .ignoringFields("destinationWalletId", "externalReference", "memo").isEqualTo(original);
        assertThat(copy.destinationWalletId()).isEqualTo(destination);
        assertThat(copy.externalReference()).isEqualTo("canonical");
        assertThat(copy.memo()).isEqualTo("new memo");
        assertThat(original.externalReference()).isEqualTo("raw");
        assertThat(original.memo()).isEqualTo("memo");
    }

    @Test
    void preflightOutputIsRedactedAndRequiresAllComponents() {
        var command = LegacyPaymentSubmissionMapper.toCommand(41L,
                request("reference-secret", "memo-secret", "otp", "assertion", "passphrase", "pin"), "device");
        var fingerprint = new RequestFingerprint("fingerprint-secret");
        assertThat(new PaymentPreflightResult(command, fingerprint, Optional.empty()).toString())
                .doesNotContain("reference-secret", "memo-secret", "fingerprint-secret", "assertion", "device");
        assertThat(new CanonicalPaymentDestination("reference-secret", "memo-secret").toString())
                .doesNotContain("reference-secret", "memo-secret");
        assertThatNullPointerException().isThrownBy(() -> new PaymentPreflightResult(null, fingerprint, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new PaymentPreflightResult(command, null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new PaymentPreflightResult(command, fingerprint, null));
    }

    private KfeSubmitTransactionRequest request(String ref, String memo, String otp, String assertion, String passphrase, String pin) {
        return new KfeSubmitTransactionRequest(" key | ", KfeRail.ONCHAIN, KfeDirection.OUTBOUND,
                UUID.randomUUID(), UUID.randomUUID(), 12345L, 234L, ref, memo, otp, assertion, passphrase,
                pin, " request | ", 27L, 3, " quote | ");
    }
}
