package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreflightPaymentServiceTest {
    private final UUID source = UUID.randomUUID();
    private final UUID originalDestination = UUID.randomUUID();
    private final UUID resolvedDestination = UUID.randomUUID();
    private final IdempotencyKey key = new IdempotencyKey(" opaque-key ");
    private final RequestFingerprint fingerprint = new RequestFingerprint(" opaque-fingerprint ");
    private final PaymentWalletsUseCase wallets = mock(PaymentWalletsUseCase.class);
    private final PaymentCanonicalDestinationPort canonical = mock(PaymentCanonicalDestinationPort.class);
    private final PaymentDestinationValidationPort destinations = mock(PaymentDestinationValidationPort.class);
    private final ValidatePaymentRequestService validation = spy(new ValidatePaymentRequestService(destinations));
    private final PaymentRequestFingerprintPort fingerprints = mock(PaymentRequestFingerprintPort.class);
    private final GetIdempotentPaymentUseCase replays = mock(GetIdempotentPaymentUseCase.class);
    private final AuthorizePaymentUseCase authorization = mock(AuthorizePaymentUseCase.class);
    private final PreflightPaymentService service = new PreflightPaymentService(wallets, canonical, validation, fingerprints, replays, authorization);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void resolvesCanonicalizesValidatesFingerprintsReplaysThenChecksSelfPaymentAndAuthorizes(PaymentRail rail, PaymentDirection direction) {
        ready();
        var command = command(rail, direction, originalDestination, " original reference ", " original memo ");
        var resolved = command(rail, direction, resolvedDestination, " original reference ", " original memo ");
        var expected = command(rail, direction, resolvedDestination, " canonical reference ", " canonical memo ");
        var result = service.preflight(command);

        assertThat(result.command()).isEqualTo(expected);
        assertThat(result.fingerprint()).isSameAs(fingerprint);
        assertThat(result.existingPayment()).isEmpty();
        assertThat(command.destinationWalletId()).isEqualTo(originalDestination);
        assertThat(command.externalReference()).isEqualTo(" original reference ");
        var order = inOrder(wallets, canonical, validation, destinations, fingerprints, replays, authorization);
        order.verify(wallets).resolveDestinationReference(walletCommand(command));
        order.verify(canonical).resolve(resolved);
        order.verify(validation).validate(new ValidatePaymentRequestCommand(key.value(), rail, direction, 10_000L, 100L, " canonical reference "));
        if (direction == PaymentDirection.OUTBOUND) { order.verify(destinations).validate(rail, " canonical reference "); }
        order.verify(fingerprints).fingerprint(expected);
        order.verify(replays).find(new GetIdempotentPaymentQuery(7L, key, fingerprint));
        order.verify(wallets).requireNotSelfPayment(walletCommand(expected));
        order.verify(authorization).authorize(expected);
        order.verifyNoMoreInteractions();
        verify(wallets, never()).resolve(any());
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void replayReturnsCanonicalCommandWithoutSelfCheckOrAuthorization(PaymentRail rail, PaymentDirection direction) {
        ready();
        var response = mock(PaymentExecutionResult.class);
        when(replays.find(any())).thenReturn(Optional.of(response));

        var result = service.preflight(command(rail, direction, originalDestination, " original reference ", " original memo "));

        assertThat(result.command()).isEqualTo(command(rail, direction, resolvedDestination, " canonical reference ", " canonical memo "));
        assertThat(result.existingPayment()).containsSame(response);
        assertThat(result.fingerprint()).isSameAs(fingerprint);
        verify(wallets, never()).requireNotSelfPayment(any());
        verify(wallets, never()).resolve(any());
        verifyNoInteractions(authorization);
        verify(validation).validate(any());
        verify(fingerprints).fingerprint(result.command());
    }

    @Test
    void unchangedWalletResolutionKeepsTheOriginalCommandForCanonicalRouting() {
        ready();
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, originalDestination, "reference", "memo");
        when(wallets.resolveDestinationReference(any())).thenReturn(originalDestination);
        service.preflight(command);
        verify(canonical).resolve(same(command));
    }

    @Test
    void externalWalletResolutionMayLegitimatelyReturnNoDestinationWallet() {
        ready();
        when(wallets.resolveDestinationReference(any())).thenReturn(null);
        var result = service.preflight(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, originalDestination, "reference", "memo"));
        assertThat(result.command().destinationWalletId()).isNull();
        verify(canonical).resolve(command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, null, "reference", "memo"));
        verify(authorization).authorize(result.command());
    }

    @Test
    void nullableCanonicalFieldsRemainNullWhenTheRouteDoesNotNeedAnExternalDestination() {
        ready();
        when(canonical.resolve(any())).thenReturn(new CanonicalPaymentDestination(null, null));
        var result = service.preflight(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, "@recipient", "memo"));
        assertThat(result.command()).isEqualTo(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, resolvedDestination, null, null));
        verifyNoInteractions(destinations);
        verify(fingerprints).fingerprint(result.command());
        verify(authorization).authorize(result.command());
    }

    @Test
    void invalidCanonicalExternalReferenceFailsBeforeHashReplaySelfCheckOrAuthorization() {
        ready();
        when(canonical.resolve(any())).thenReturn(new CanonicalPaymentDestination(" ", "memo"));
        assertThatThrownBy(() -> service.preflight(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, "valid original", "memo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("externalReference is required for external outbound transactions.");
        verifyNoInteractions(destinations, fingerprints, replays, authorization);
        verify(wallets, never()).requireNotSelfPayment(any());
    }

    @Test
    void scalarRejectionStopsBeforeFingerprintDespiteEarlierDestinationResolution() {
        ready();
        var invalid = new SubmitPaymentCommand(7L, key, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, null,
                2_100_000_000_000_001L, 100L, "reference", "memo", null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.preflight(invalid)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountSats exceeds maximum allowed limit (21M BTC).");
        verify(wallets).resolveDestinationReference(any());
        verify(canonical).resolve(any());
        verifyNoInteractions(destinations, fingerprints, replays, authorization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "fingerprint", "replay"})
    void absentRequiredPortResultsFailClosedWithoutAuthorization(String missing) {
        ready();
        switch (missing) {
            case "canonical" -> when(canonical.resolve(any())).thenReturn(null);
            case "fingerprint" -> when(fingerprints.fingerprint(any())).thenReturn(null);
            case "replay" -> when(replays.find(any())).thenReturn(null);
        }
        assertThatThrownBy(() -> service.preflight(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, "reference", "memo")))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(authorization);
        verify(wallets, never()).requireNotSelfPayment(any());
        if (missing.equals("canonical")) { verifyNoInteractions(validation, destinations, fingerprints, replays); }
        if (missing.equals("fingerprint")) { verifyNoInteractions(replays); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wallet", "canonical", "validation", "destination", "fingerprint", "replay", "self", "authorization"})
    void everyFailurePropagatesAndStopsTheFollowingStepsWithoutFallback(String stage) {
        ready();
        var failure = new IllegalStateException("preflight unavailable");
        switch (stage) {
            case "wallet" -> doThrow(failure).when(wallets).resolveDestinationReference(any());
            case "canonical" -> doThrow(failure).when(canonical).resolve(any());
            case "validation" -> doThrow(failure).when(validation).validate(any());
            case "destination" -> doThrow(failure).when(destinations).validate(any(), any());
            case "fingerprint" -> doThrow(failure).when(fingerprints).fingerprint(any());
            case "replay" -> doThrow(failure).when(replays).find(any());
            case "self" -> doThrow(failure).when(wallets).requireNotSelfPayment(any());
            case "authorization" -> doThrow(failure).when(authorization).authorize(any());
        }
        assertThatThrownBy(() -> service.preflight(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, "reference", "memo")))
                .isSameAs(failure);
        int step = List.of("wallet", "canonical", "validation", "destination", "fingerprint", "replay", "self", "authorization").indexOf(stage);
        if (step < 1) { verifyNoInteractions(canonical); }
        if (step < 2) { verifyNoInteractions(validation); }
        if (step < 3) { verifyNoInteractions(destinations); }
        if (step < 4) { verifyNoInteractions(fingerprints); }
        if (step < 5) { verifyNoInteractions(replays); }
        if (step < 6) { verify(wallets, never()).requireNotSelfPayment(any()); }
        if (step < 7) { verifyNoInteractions(authorization); }
        verify(wallets, never()).resolve(any());
        verify(wallets).resolveDestinationReference(any());
    }

    @Test
    void copyOperationsPreserveEveryValueAndOpaqueAuthorizationField() {
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, originalDestination, " original reference ", " original memo ");
        assertThat(command.withDestinationWalletId(resolvedDestination))
                .isEqualTo(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, resolvedDestination, " original reference ", " original memo "));
        assertThat(command.withCanonicalDestination(" canonical reference ", " canonical memo "))
                .isEqualTo(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, originalDestination, " canonical reference ", " canonical memo "));
        assertThat(command.withDestinationWalletId(null).withCanonicalDestination(null, null))
                .isEqualTo(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, null, null));
    }

    @Test
    void nullCommandCannotReachAnyCollaborator() {
        assertThatThrownBy(() -> service.preflight(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(wallets, canonical, validation, destinations, fingerprints, replays, authorization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wallets", "canonical", "validation", "fingerprints", "replays", "authorization"})
    void missingDependencyCannotCreateABypassablePreflight(String missing) {
        assertThatThrownBy(() -> new PreflightPaymentService(missing.equals("wallets") ? null : wallets,
                missing.equals("canonical") ? null : canonical, missing.equals("validation") ? null : validation,
                missing.equals("fingerprints") ? null : fingerprints, missing.equals("replays") ? null : replays,
                missing.equals("authorization") ? null : authorization)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultAndCanonicalDiagnosticsRedactSensitiveReferencesAndFactors() {
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, "sensitive-reference", "sensitive-memo");
        var response = mock(PaymentExecutionResult.class);
        when(response.toString()).thenReturn("sensitive-transaction-details");
        assertThat(new PaymentPreflightResult(command, fingerprint, Optional.of(response)).toString())
                .contains("REDACTED").doesNotContain("sensitive-reference", "sensitive-memo", "sensitive-transaction-details", fingerprint.value());
        assertThat(new CanonicalPaymentDestination("sensitive-reference", "sensitive-memo").toString())
                .contains("REDACTED").doesNotContain("sensitive-reference", "sensitive-memo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "fingerprint", "existing"})
    void resultCannotRepresentAnIncompletePreflight(String missing) {
        assertThatThrownBy(() -> new PaymentPreflightResult(missing.equals("command") ? null
                : command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, "reference", "memo"),
                missing.equals("fingerprint") ? null : fingerprint, missing.equals("existing") ? null : Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    private void ready() {
        when(wallets.resolveDestinationReference(any())).thenReturn(resolvedDestination);
        when(canonical.resolve(any())).thenReturn(new CanonicalPaymentDestination(" canonical reference ", " canonical memo "));
        when(fingerprints.fingerprint(any())).thenReturn(fingerprint);
        when(replays.find(any())).thenReturn(Optional.empty());
    }

    private SubmitPaymentCommand command(PaymentRail rail, PaymentDirection direction, UUID destination, String reference, String memo) {
        return new SubmitPaymentCommand(7L, key, rail, direction, source, destination, 10_000L, 100L, reference, memo,
                " totp ", " {assertion} ", " confirmation ", " 0123 ", " request public id ", 12L, 3, " quote id ", " device hash ");
    }

    private ResolvePaymentWalletsCommand walletCommand(SubmitPaymentCommand command) {
        return new ResolvePaymentWalletsCommand(command.userId(), command.rail(), command.direction(), command.sourceWalletId(),
                command.destinationWalletId(), command.externalReference());
    }
}
