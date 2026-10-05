package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValidatePaymentRequestServiceTest {
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;
    private final PaymentDestinationValidationPort destinations = mock(PaymentDestinationValidationPort.class);
    private final ValidatePaymentRequestService service = new ValidatePaymentRequestService(destinations);

    @ParameterizedTest
    @CsvSource({"ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND"})
    void validOutboundPassesUntrimmedReferenceToTheCorrectRailPort(PaymentRail rail, PaymentDirection direction) {
        service.validate(new ValidatePaymentRequestCommand("  key  ", rail, direction, 10_000L, 100L, "  destination  "));
        verify(destinations).validate(rail, "  destination  ");
        verifyNoMoreInteractions(destinations);
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void nonOutboundNeverCallsExternalValidationOrRequiresAReference(PaymentRail rail, PaymentDirection direction) {
        service.validate(new ValidatePaymentRequestCommand("key", rail, direction, 1L, 0L, null));
        service.validate(new ValidatePaymentRequestCommand("key", rail, direction, 1L, 0L, " "));
        verifyNoInteractions(destinations);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingKeyFailsWithTheLegacyMessageBeforeAllOtherChecks(String key) {
        assertThatThrownBy(() -> service.validate(new ValidatePaymentRequestCommand(key, null, null, -1L, -1L, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("idempotencyKey is required.");
        verifyNoInteractions(destinations);
    }

    @Test
    void keyLengthIsMeasuredOnRawJavaStringCharactersBeforeAnyNormalization() {
        service.validate(new ValidatePaymentRequestCommand("x".repeat(180), PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 1L, 0L, null));
        assertThatThrownBy(() -> service.validate(new ValidatePaymentRequestCommand(" " + "x".repeat(180), null, null, -1L, -1L, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("idempotencyKey must have at most 180 characters.");
        verifyNoInteractions(destinations);
    }

    @ParameterizedTest
    @MethodSource("scalarFailures")
    void preservesAmountThenFeeThenRouteThenReferenceFailureOrder(InvalidRequest invalid) {
        assertThatThrownBy(() -> service.validate(invalid.command())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(invalid.message());
        verifyNoInteractions(destinations);
    }

    static Stream<InvalidRequest> scalarFailures() {
        return Stream.of(
                invalid(0L, -1L, PaymentRail.INTERNAL, PaymentDirection.OUTBOUND, "amountSats must be positive."),
                invalid(-1L, -1L, PaymentRail.INTERNAL, PaymentDirection.OUTBOUND, "amountSats must be positive."),
                invalid(Long.MIN_VALUE, -1L, null, null, "amountSats must be positive."),
                invalid(MAX_SATOSHIS + 1L, -1L, null, null, "amountSats exceeds maximum allowed limit (21M BTC)."),
                invalid(Long.MAX_VALUE, -1L, null, null, "amountSats exceeds maximum allowed limit (21M BTC)."),
                invalid(1L, -1L, PaymentRail.INTERNAL, PaymentDirection.OUTBOUND, "networkFeeSats must be non-negative."),
                invalid(1L, Long.MIN_VALUE, null, null, "networkFeeSats must be non-negative."),
                invalid(1L, MAX_SATOSHIS + 1L, PaymentRail.INTERNAL, PaymentDirection.OUTBOUND, "networkFeeSats exceeds maximum allowed limit (21M BTC)."),
                invalid(1L, Long.MAX_VALUE, null, null, "networkFeeSats exceeds maximum allowed limit (21M BTC)."),
                invalid(1L, 0L, null, PaymentDirection.OUTBOUND, "rail and direction are required"),
                invalid(1L, 0L, PaymentRail.ONCHAIN, null, "rail and direction are required"),
                invalid(1L, 0L, PaymentRail.INTERNAL, PaymentDirection.OUTBOUND, "INTERNAL rail requires INTERNAL direction."),
                invalid(1L, 0L, PaymentRail.INTERNAL, PaymentDirection.INBOUND, "INTERNAL rail requires INTERNAL direction."),
                invalid(1L, 0L, PaymentRail.ONCHAIN, PaymentDirection.INTERNAL, "INTERNAL direction requires INTERNAL rail."),
                invalid(1L, 0L, PaymentRail.LIGHTNING, PaymentDirection.INTERNAL, "INTERNAL direction requires INTERNAL rail."));
    }

    @Test
    void inclusiveAmountAndFeeMaximaRemainSeparateWithoutIntroducingATotalSumLimit() {
        service.validate(new ValidatePaymentRequestCommand("key", PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                MAX_SATOSHIS, MAX_SATOSHIS, "destination"));
        verify(destinations).validate(PaymentRail.ONCHAIN, "destination");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingExternalReferenceIsRejectedBeforeTheRailAdapter(String reference) {
        for (var rail : new PaymentRail[] {PaymentRail.ONCHAIN, PaymentRail.LIGHTNING}) {
            assertThatThrownBy(() -> service.validate(new ValidatePaymentRequestCommand("key", rail, PaymentDirection.OUTBOUND,
                    1L, 0L, reference))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("externalReference is required for external outbound transactions.");
        }
        verifyNoInteractions(destinations);
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN", "LIGHTNING"})
    void destinationFailurePropagatesWithoutTryingAnotherRail(PaymentRail rail) {
        var failure = new IllegalArgumentException("destination rejected");
        doThrow(failure).when(destinations).validate(rail, "raw destination");
        assertThatThrownBy(() -> service.validate(new ValidatePaymentRequestCommand("key", rail, PaymentDirection.OUTBOUND,
                1L, 0L, "raw destination"))).isSameAs(failure);
        verify(destinations).validate(rail, "raw destination");
        verifyNoMoreInteractions(destinations);
    }

    @Test
    void nullCommandOrDependencyIsNeverAccepted() {
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidatePaymentRequestService(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(destinations);
    }

    @Test
    void validationDiagnosticDoesNotExposeDestinationOrKey() {
        assertThat(new ValidatePaymentRequestCommand("secret-key", PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                1L, 0L, "secret-destination").toString()).contains("REDACTED").doesNotContain("secret-key", "secret-destination");
    }

    private static InvalidRequest invalid(long amount, long fee, PaymentRail rail, PaymentDirection direction, String message) {
        return new InvalidRequest(new ValidatePaymentRequestCommand("key", rail, direction, amount, fee, null), message);
    }
    record InvalidRequest(ValidatePaymentRequestCommand command, String message) {}
}
