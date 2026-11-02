package com.kerosene.kfe.paymentexecution.adapters.in;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeSubmitTransactionUseCase;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;

import java.util.UUID;
import java.util.Optional;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.*;

class KfeTransactionEngineTest {

    private final KfeSubmitTransactionUseCase submitTransactionUseCase = mock(KfeSubmitTransactionUseCase.class);
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase = mock(KfeTransactionIdempotencyUseCase.class);
    private final KfeTransactionEngine engine = new KfeTransactionEngine(submitTransactionUseCase, idempotencyUseCase);

    @Test
    void submitDelegatesToSubmitUseCase() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                100_000L,
                1000L,
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "memo",
                "totp-code-123",
                "passkey-json",
                "passphrase"
        );

        engine.submit(userId, request);

        verify(submitTransactionUseCase).submit(userId, request, null);
    }

    @Test
    void legacyReplayFacadeUsesOwnerScopedInputAndPreservesResponse() {
        var queries = mock(GetIdempotentPaymentUseCase.class);
        var hashes = mock(KfeHashService.class);
        var helper = new KfeTransactionIdempotencyUseCase(queries, hashes);
        var current = new KfeTransactionEngine(submitTransactionUseCase, helper);
        var response = mock(KfeTransactionResponse.class);
        when(response.id()).thenReturn(UUID.randomUUID());
        when(response.status()).thenReturn(KfeTransactionStatus.SETTLED);
        when(response.rail()).thenReturn(KfeRail.LIGHTNING);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        var result = LegacyPaymentExecutionResultMapper.toResult(response);
        var query = new GetIdempotentPaymentQuery(123L, new IdempotencyKey(" key "), new RequestFingerprint(" hash "));
        when(queries.find(query)).thenReturn(Optional.of(result));

        assertThat(current.getExistingByIdempotency(123L, " key ", " hash "))
                .isEqualTo(LegacyPaymentExecutionResultMapper.toLegacyResponse(result));
        verify(queries).find(query);
        verifyNoInteractions(submitTransactionUseCase, hashes);
    }

    @Test
    void missingReplayStillFailsWithoutStartingSubmission() {
        var queries = mock(GetIdempotentPaymentUseCase.class);
        var current = new KfeTransactionEngine(submitTransactionUseCase,
                new KfeTransactionIdempotencyUseCase(queries, new KfeHashService()));
        assertThatThrownBy(() -> current.getExistingByIdempotency(123L, "key", "hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Idempotency conflict detected, but no record found.");
        verifyNoInteractions(submitTransactionUseCase);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestHashPreservesLegacyNullSpacesAndSeparators(boolean referencesPresent) {
        var queries = mock(GetIdempotentPaymentUseCase.class);
        var hashes = mock(KfeHashService.class);
        var current = new KfeTransactionEngine(submitTransactionUseCase, new KfeTransactionIdempotencyUseCase(queries, hashes));
        var source = UUID.randomUUID();
        var destination = UUID.randomUUID();
        var request = new KfeSubmitTransactionRequest("opaque", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                source, destination, 10_000L, 99L, referencesPresent ? " ç | ref " : null,
                referencesPresent ? " memo | " : null, "totp-secret", "passkey-secret", "passphrase-secret",
                "two-factor-secret", referencesPresent ? " public | " : null);
        String wire = "KFE_TX_REQUEST|123|INTERNAL|INTERNAL|" + source + "|" + destination + "|10000|99|"
                + (referencesPresent ? " ç | ref | public | | memo | " : "||");
        when(hashes.sha256(wire)).thenReturn("fingerprint");

        assertThat(current.requestHash(123L, request)).isEqualTo("fingerprint");
        verify(hashes).sha256(wire);
        verifyNoInteractions(queries, submitTransactionUseCase);
    }
}
