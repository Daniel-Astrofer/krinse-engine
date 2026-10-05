package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentCanonicalDestinationAdapterTest {
    private final KfePlatformOnchainDestinationRouter router = mock(KfePlatformOnchainDestinationRouter.class);
    private final LegacyPaymentCanonicalDestinationAdapter adapter = new LegacyPaymentCanonicalDestinationAdapter(router);

    @ParameterizedTest
    @EnumSource(PaymentRail.class)
    void preservesTheEntireRequestAtTheLegacyBoundaryForEveryRailAndDirection(PaymentRail rail) {
        for (var direction : PaymentDirection.values()) {
            var command = command(rail, direction);
            when(router.resolve(any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return invocation.getArgument(0);
            });

            assertThat(adapter.resolve(command)).isEqualTo(new CanonicalPaymentDestination(" reference | ç ", " memo | ç "));

            var captured = ArgumentCaptor.forClass(KfeSubmitTransactionRequest.class);
            verify(router).resolve(captured.capture());
            assertThat(captured.getValue()).isEqualTo(LegacyPaymentSubmissionMapper.toLegacyRequest(command));
            assertThat(captured.getValue().appPin()).isEqualTo(" pin ");
            assertThat(captured.getValue().passkeyAssertionJson()).isEqualTo(" assertion ");
            assertThat(captured.getValue().feeRateSatPerVbyte()).isEqualTo(5L);
            assertThat(captured.getValue().feeTargetBlocks()).isEqualTo(6);
            assertThat(captured.getValue().quoteId()).isEqualTo(" quote ");
            clearInvocations(router);
        }
    }

    @Test
    void onlyCanonicalReferenceAndMemoCanCrossBackIntoTheCore() {
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);
        var unrelatedReturnedFields = new KfeSubmitTransactionRequest("different-key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                UUID.randomUUID(), UUID.randomUUID(), 42L, 0L, " canonical-reference ", " canonical-memo ",
                "different-totp", "different-assertion", "different-passphrase", "different-pin", "different-public",
                99L, 100, "different-quote");
        when(router.resolve(any())).thenReturn(unrelatedReturnedFields);

        var result = adapter.resolve(command);

        assertThat(result).isEqualTo(new CanonicalPaymentDestination(" canonical-reference ", " canonical-memo "));
        assertThat(result.getClass().getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("externalReference", "memo");
        assertThat(command.idempotencyKey()).isEqualTo(new IdempotencyKey(" key "));
        assertThat(command.amountSats()).isEqualTo(10_000L);
        assertThat(command.appPin()).isEqualTo(" pin ");
    }

    @Test
    void nullReferencesAndMemoRemainNull() {
        var command = command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL).withCanonicalDestination(null, null);
        when(router.resolve(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(adapter.resolve(command)).isEqualTo(new CanonicalPaymentDestination(null, null));
    }

    @Test
    void missingRouterResultFailsWithoutInventingADestination() {
        assertThatThrownBy(() -> adapter.resolve(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Canonical payment destination is missing.");
        verify(router).resolve(any());
        verifyNoMoreInteractions(router);
    }

    @Test
    void routerFailurePropagatesWithoutFallbackOrRetry() {
        var failure = new IllegalStateException("router unavailable");
        when(router.resolve(any())).thenThrow(failure);

        assertThatThrownBy(() -> adapter.resolve(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND))).isSameAs(failure);

        verify(router).resolve(any());
        verifyNoMoreInteractions(router);
    }

    private static SubmitPaymentCommand command(PaymentRail rail, PaymentDirection direction) {
        return new SubmitPaymentCommand(7L, new IdempotencyKey(" key "), rail, direction,
                UUID.randomUUID(), UUID.randomUUID(), 10_000L, 100L, " reference | ç ", " memo | ç ",
                " totp ", " assertion ", " passphrase ", " pin ", " public ", 5L, 6, " quote ", " device ");
    }
}
