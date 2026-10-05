package com.kerosene.kfe.paymentexecution.adapters.out.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalPort;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Challenge;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Context;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Proof;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Request;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Response;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Boundary tests with a mocked Core port; signature verification itself belongs to Core tests. */
class BoundFinancialPaymentApprovalAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BINDING = "c2198060c47b2af87c51971deacbb85c6bf48463392d55e8ff217be4ed05782e";
    private static final String SIGNATURE = "cVVXaJfe-0ddoAshklOVkIA6X-vRpSQ7JkZe1Mia3wfKpWEs-yarBnogLY77BUn1s0E_hXaNZLo_Uc1XVy3aCw";
    private final FinancialPaymentApprovalPort remote = mock(FinancialPaymentApprovalPort.class);
    private final BoundFinancialPaymentApprovalAdapter adapter = new BoundFinancialPaymentApprovalAdapter(remote);

    @Test
    void mapsEveryCanonicalCommandFieldWithoutTrimmingAndPreservesTheSharedVectorBinding() {
        var challenge = challenge(now() - 1, now() + 90);
        when(remote.approve(any())).thenReturn(challengeResponse(challenge));

        var error = catchThrowableOfType(() -> adapter.approve(command(null)), StructuredPlatformException.class);

        assertChallenge(error, challenge);
        var request = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(remote).approve(request.capture());
        assertThat(request.getValue()).isEqualTo(new Request(1, context(), " pin ", " totp ", " confirmation phrase ", null));
        assertThat(FinancialPaymentApprovalV1.bindingHash(request.getValue().context())).isEqualTo(BINDING);
        verifyNoMoreInteractions(remote);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void absentProofRequestsFinancialChallengeWithoutFabricatingCredentials(String assertion) {
        var challenge = challenge(now() - 1, now() + 90);
        when(remote.approve(any())).thenReturn(challengeResponse(challenge));

        assertChallenge(catchThrowableOfType(() -> adapter.approve(command(assertion)), StructuredPlatformException.class), challenge);

        verify(remote).approve(new Request(1, context(), " pin ", " totp ", " confirmation phrase ", null));
        verifyNoMoreInteractions(remote);
    }

    @Test
    void acceptsOnlyBoundApprovedAckForTheSubmittedFinancialChallenge() throws Exception {
        var challenge = challenge(now() - 1, now() + 90);
        var proof = proof(challenge);
        when(remote.approve(any())).thenReturn(approved(challenge.challengeId(), BINDING, challenge.expiresAtEpochSeconds()));

        adapter.approve(command(JSON.writeValueAsString(proof)));

        verify(remote).approve(new Request(1, context(), " pin ", " totp ", " confirmation phrase ", proof));
        verifyNoMoreInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"generic", "unknown", "duplicate", "coerced", "fractional", "null-version", "missing-version", "trailing", "array", "broken"})
    void rejectsNonFinancialOrNonStrictProofBeforeRemoteCall(String mutation) throws Exception {
        String valid = JSON.writeValueAsString(proof(challenge(now() - 1, now() + 90)));
        String invalid = switch (mutation) {
            case "generic" -> valid.replace("FINANCIAL_DEVICE_KEY", "DEVICE_KEY");
            case "unknown" -> valid.substring(0, valid.length() - 1) + ",\"username\":\"SECRET-PROOF\"}";
            case "duplicate" -> valid.replace("\"version\":1", "\"version\":1,\"version\":1");
            case "coerced" -> valid.replace("\"version\":1", "\"version\":\"1\"");
            case "fractional" -> valid.replace("\"version\":1", "\"version\":1.5");
            case "null-version" -> valid.replace("\"version\":1", "\"version\":null");
            case "missing-version" -> valid.replace("\"version\":1,", "");
            case "trailing" -> valid + " {}";
            case "array" -> "[" + valid + "]";
            default -> "{SECRET-PROOF";
        };

        assertInvalidProof(() -> adapter.approve(command(invalid)));
        verifyNoInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bindingHash", "type", "credentialId", "deviceInstallId", "duplicate", "counter-string", "counter-fractional", "unknown", "version", "trailing"})
    void rejectsUnboundOrAmbiguousSignedPayloadBeforeRemoteCall(String mutation) throws Exception {
        var original = proof(challenge(now() - 1, now() + 90));
        var payload = (ObjectNode) JSON.readTree(original.signedPayload());
        switch (mutation) {
            case "bindingHash" -> payload.put("bindingHash", "b".repeat(64));
            case "type" -> payload.put("type", "AUTH_DEVICE_KEY");
            case "credentialId", "deviceInstallId" -> payload.put(mutation, "other-device-secret");
            case "counter-string" -> payload.put("counter", "7");
            case "counter-fractional" -> payload.put("counter", 7.5);
            case "unknown" -> payload.put("unexpected", "SECRET-PROOF");
            case "version" -> payload.put("version", 2);
            default -> { }
        }
        String serialized = payload.toString();
        if (mutation.equals("duplicate")) { serialized = serialized.replace("\"counter\":7", "\"counter\":7,\"counter\":7"); }
        if (mutation.equals("trailing")) { serialized += " {}"; }
        var invalid = new Proof(1, FinancialPaymentApprovalV1.PROOF_TYPE, original.credentialId(), original.deviceInstallId(), serialized, SIGNATURE);
        String assertion = JSON.writeValueAsString(invalid);

        assertInvalidProof(() -> adapter.approve(command(assertion)));
        verifyNoInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "binding", "approval-id", "expired", "too-long"})
    void incoherentOrStaleAcknowledgmentFailsClosed(String mutation) throws Exception {
        var challenge = challenge(now() - 1, now() + 90);
        Response response = switch (mutation) {
            case "null" -> null;
            case "binding" -> approved(challenge.challengeId(), "b".repeat(64), now() + 90);
            case "approval-id" -> approved("different-challenge", BINDING, now() + 90);
            case "expired" -> approved(challenge.challengeId(), BINDING, now() - 1);
            default -> approved(challenge.challengeId(), BINDING, now() + 600);
        };
        when(remote.approve(any())).thenReturn(response);
        var assertion = JSON.writeValueAsString(proof(challenge));

        assertInvalidAck(() -> adapter.approve(command(assertion)));
        verify(remote).approve(any());
        verifyNoMoreInteractions(remote);
    }

    @Test
    void approvedWithoutSubmittedProofIsNeverTreatedAsAuthorization() {
        when(remote.approve(any())).thenReturn(approved("forged-approval", BINDING, now() + 90));

        assertInvalidAck(() -> adapter.approve(command(null)));
        verify(remote).approve(any());
        verifyNoMoreInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"binding", "expired", "future"})
    void invalidFinancialChallengeCannotBeForwardedToClients(String mutation) {
        Challenge challenge = switch (mutation) {
            case "binding" -> new Challenge(1, FinancialPaymentApprovalV1.PURPOSE, "challenge-id", "a".repeat(64),
                    "b".repeat(64), "alice", "auth-test", now() - 1, now() + 90, "Ed25519", FinancialPaymentApprovalV1.CANONICALIZATION);
            case "expired" -> challenge(now() - 91, now() - 1);
            default -> challenge(now() + 600, now() + 690);
        };
        when(remote.approve(any())).thenReturn(challengeResponse(challenge));

        assertInvalidAck(() -> adapter.approve(command(null)));
        verify(remote).approve(any());
    }

    @Test
    void remoteDenialPropagatesWithoutAdditionalAttemptsOrUnboundFallback() {
        var failure = new StructuredPlatformException("denied", HttpStatus.FORBIDDEN, "AUTH_APP_PIN_INVALID", null);
        when(remote.approve(any())).thenThrow(failure);

        assertThatThrownBy(() -> adapter.approve(command(null))).isSameAs(failure);
        verify(remote).approve(any());
        verifyNoMoreInteractions(remote);
    }

    @Test
    void missingTrustedDeviceIdentityFailsBeforeRemoteApproval() {
        var original = command(null);
        var missingDevice = new SubmitPaymentCommand(original.userId(), original.idempotencyKey(), original.rail(), original.direction(),
                original.sourceWalletId(), original.destinationWalletId(), original.amountSats(), original.networkFeeSats(),
                original.externalReference(), original.memo(), original.totpCode(), original.passkeyAssertionJson(),
                original.confirmationPassphrase(), original.appPin(), original.paymentRequestPublicId(), original.feeRateSatPerVbyte(),
                original.feeTargetBlocks(), original.quoteId(), null);

        assertInvalidProof(() -> adapter.approve(missingDevice));
        verifyNoInteractions(remote);
    }

    private static void assertChallenge(StructuredPlatformException error, Challenge challenge) {
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(error.getErrorCode()).isEqualTo("KFE_PAYMENT_APPROVAL_REQUIRED");
        assertThat(error.getData()).isEqualTo(Map.of("action", "ASSERT_FINANCIAL_DEVICE_KEY", "financialChallenge", challenge));
    }

    private static void assertInvalidProof(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        var error = catchThrowableOfType(action, StructuredPlatformException.class);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getErrorCode()).isEqualTo("KFE_PAYMENT_PROOF_INVALID");
        assertThat(error.getMessage()).doesNotContain("SECRET-PROOF", " pin ", "confirmation phrase", SIGNATURE);
        assertThat(error.getCause()).isNull();
    }

    private static void assertInvalidAck(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        var error = catchThrowableOfType(action, StructuredPlatformException.class);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(error.getErrorCode()).isEqualTo("KFE_PAYMENT_APPROVAL_INVALID");
        assertThat(error.getMessage()).doesNotContain(BINDING, SIGNATURE, " pin ");
        assertThat(error.getCause()).isNull();
    }

    private static Context context() {
        return new Context(41, "device-ref-test", " chave | á ", "ONCHAIN", "OUTBOUND", "00000000-0000-0000-0000-000000000001",
                null, 12345, 234, "bc1-example", " memo | 漢 ", null, 27L, 3, "quote-test");
    }

    private static SubmitPaymentCommand command(String assertion) {
        var context = context();
        return new SubmitPaymentCommand(context.userId(), new IdempotencyKey(context.idempotencyKey()), PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, UUID.fromString(context.sourceWalletId()), null, context.amountSats(), context.networkFeeSats(),
                context.externalReference(), context.memo(), " totp ", assertion, " confirmation phrase ", " pin ",
                context.paymentRequestPublicId(), context.feeRateSatPerVbyte(), context.feeTargetBlocks(), context.quoteId(), context.deviceRef());
    }

    private static Challenge challenge(long issued, long expires) {
        return new Challenge(1, FinancialPaymentApprovalV1.PURPOSE, "00000000-0000-0000-0000-000000000003", "a".repeat(64),
                BINDING, "alice", "auth-test", issued, expires, "Ed25519", FinancialPaymentApprovalV1.CANONICALIZATION);
    }

    private static Proof proof(Challenge challenge) {
        return new Proof(1, FinancialPaymentApprovalV1.PROOF_TYPE, "credential-test", "install-test",
                FinancialPaymentApprovalV1.signedPayload(challenge, "credential-test", "install-test", 7, challenge.issuedAtEpochSeconds()), SIGNATURE);
    }

    private static Response approved(String approvalId, String binding, long expiry) { return new Response(1, "APPROVED", binding, approvalId, expiry, null); }
    private static Response challengeResponse(Challenge challenge) { return new Response(1, "CHALLENGE", challenge.bindingHash(), null, challenge.expiresAtEpochSeconds(), challenge); }
    private static long now() { return Instant.now().getEpochSecond(); }
}
