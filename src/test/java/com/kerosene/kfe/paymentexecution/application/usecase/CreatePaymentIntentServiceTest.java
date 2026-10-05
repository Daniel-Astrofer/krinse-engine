package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CreatePaymentIntentServiceTest {

    private final PaymentIntentStore store = mock(PaymentIntentStore.class);
    private final CreatePaymentIntentService service = new CreatePaymentIntentService(store);

    @Test
    void createsExactlyOneIntentAndReturnsOnlyItsExecutionIdentity() {
        var sourceId = UUID.randomUUID();
        var destinationId = UUID.randomUUID();
        var key = new IdempotencyKey("  exact-key  ");
        var command = new CreatePaymentIntentCommand(42L, key, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                sourceId, destinationId, 100_000L, "  external  ", "  memo  ", "  public-request  ");
        var id = new PaymentExecutionId(UUID.randomUUID());
        when(store.create(any())).thenReturn(id);

        assertThat(service.create(command)).isSameAs(id);

        verify(store).create(new PaymentIntent(42L, key, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                sourceId, destinationId, 100_000L, "public-request", "memo"));
        verifyNoMoreInteractions(store);
    }

    @ParameterizedTest
    @MethodSource("referenceCases")
    void preservesLegacyReferencePriorityAndExactBlankTrimmingBehavior(
            String publicId, String externalReference, String expectedReference) {
        var command = command(publicId, externalReference, null);

        service.create(command);

        var argument = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(store).create(argument.capture());
        assertThat(argument.getValue().externalReference()).isEqualTo(expectedReference);
    }

    @ParameterizedTest
    @MethodSource("memoCases")
    void preservesLegacyMemoBlankAndTrimBehavior(String memo, String expectedMemo) {
        service.create(command(null, "external", memo));

        var argument = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(store).create(argument.capture());
        assertThat(argument.getValue().memo()).isEqualTo(expectedMemo);
    }

    @Test
    void rejectsInvalidIntentBeforePersistence() {
        var command = new CreatePaymentIntentCommand(0L, new IdempotencyKey("key"),
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, null, 100L, null, null, null);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(store);
    }

    @Test
    void propagatesPersistenceFailuresWithoutFallbackIdentity() {
        var failure = new IllegalStateException("persistence unavailable");
        when(store.create(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(command(null, null, null))).isSameAs(failure);
    }

    @Test
    void commandDiagnosticsRedactAllReferenceAndMemoInputs() {
        var command = new CreatePaymentIntentCommand(42L, new IdempotencyKey("sensitive-key"),
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, null, null, 100L,
                "sensitive-external", "sensitive-memo", "sensitive-public-id");

        assertThat(command.toString()).contains("userId=42", "INTERNAL", "amountSats=100")
                .doesNotContain("sensitive-key", "sensitive-external", "sensitive-memo", "sensitive-public-id");
    }

    private static CreatePaymentIntentCommand command(String publicId, String externalReference, String memo) {
        return new CreatePaymentIntentCommand(42L, new IdempotencyKey("key"), PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL, null, null, 100L, externalReference, memo, publicId);
    }

    private static Stream<Arguments> referenceCases() {
        return Stream.of(
                Arguments.of(" public-id ", " external ", "public-id"),
                Arguments.of("public-id", null, "public-id"),
                Arguments.of(null, " external ", "external"),
                Arguments.of("", " external ", "external"),
                Arguments.of(" \t\n", " external ", "external"),
                Arguments.of("\u2003", " external ", "external"),
                Arguments.of(null, null, null),
                Arguments.of(null, " \t\n", null),
                Arguments.of(null, "\u2003", null),
                Arguments.of("\u2003public-id\u2003", "external", "\u2003public-id\u2003"),
                Arguments.of(null, "\u2003external\u2003", "\u2003external\u2003"));
    }

    private static Stream<Arguments> memoCases() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", null),
                Arguments.of(" \t\n", null),
                Arguments.of("\u2003", null),
                Arguments.of("  memo \t\n", "memo"),
                Arguments.of("\u2003memo\u2003", "\u2003memo\u2003"));
    }
}
