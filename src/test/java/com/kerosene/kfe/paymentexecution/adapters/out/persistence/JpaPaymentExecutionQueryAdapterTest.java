package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaPaymentExecutionQueryAdapterTest {

    @Test
    void submissionLookupUsesOwnerAndKeyInsideThePersistenceQuery() {
        var key = new IdempotencyKey(UUID.randomUUID().toString());
        var entity = new KfeTransactionEntity();
        var response = legacyResponse();
        when(repository.findByUserIdAndIdempotencyKey(42L, key.value())).thenReturn(Optional.of(entity));
        when(responseMapper.toTransactionResponse(entity, 42L)).thenReturn(response);
        assertThat(adapter.findOwnedBySubmissionKey(42L, key)).isPresent();
        verify(repository).findByUserIdAndIdempotencyKey(42L, key.value());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findByIdempotencyKey(any());
    }

    @Test
    void aKeyFromAnotherAccountHasTheSameResultAsANonexistentKey() {
        var key = new IdempotencyKey(UUID.randomUUID().toString());
        when(repository.findByUserIdAndIdempotencyKey(7L, key.value())).thenReturn(Optional.empty());
        assertThat(adapter.findOwnedBySubmissionKey(7L, key)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(responseMapper);
    }

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final JpaPaymentExecutionQueryAdapter adapter =
            new JpaPaymentExecutionQueryAdapter(repository, responseMapper);

    @Test
    void enforcesParticipantVisibilityForSinglePayment() {
        UUID id = UUID.randomUUID();
        KfeTransactionEntity entity = new KfeTransactionEntity();
        KfeTransactionResponse response = legacyResponse();
        when(repository.findParticipantVisibleById(id, 42L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(entity));
        when(responseMapper.toTransactionResponse(entity, 42L)).thenReturn(response);

        var result = adapter.findParticipantVisibleById(42L, new PaymentExecutionId(id));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().status()).isEqualTo(ExecutionStatus.INTENT);
    }

    @Test
    void convertsIncrementalSyncInstantToUtcPersistenceTime() {
        Instant since = Instant.parse("2026-09-08T12:00:00Z");
        KfeTransactionEntity entity = new KfeTransactionEntity();
        KfeTransactionResponse response = legacyResponse();
        when(repository.findParticipantVisibleByUserIdSince(
                eq(42L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL),
                eq(LocalDateTime.ofInstant(since, ZoneOffset.UTC)), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(responseMapper.toTransactionResponse(entity, 42L)).thenReturn(response);

        var result = adapter.findParticipantVisible(42L, 2, 25, since);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findParticipantVisibleByUserIdSince(
                eq(42L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL),
                eq(LocalDateTime.ofInstant(since, ZoneOffset.UTC)), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(result).hasSize(1);
    }

    private static KfeTransactionResponse legacyResponse() {
        KfeTransactionResponse response = mock(KfeTransactionResponse.class);
        when(response.status()).thenReturn(KfeTransactionStatus.INTENT);
        when(response.rail()).thenReturn(KfeRail.ONCHAIN);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        return response;
    }
}
