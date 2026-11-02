package com.kerosene.kfe.paymentexecution.adapters.in;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeExecutionOutboxServiceTest {

    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final KfeExecutionOutboxService service = new KfeExecutionOutboxService(repository);

    @Test
    void claimsDueOutboxItemsWithNormalizedWorkerId() {
        KfeExecutionOutboxEntity candidate = new KfeExecutionOutboxEntity();
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(candidate));
        when(repository.claimDue(
                eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                eq("kfe-worker"), any(UUID.class), any()))
                .thenReturn(1);

        List<KfeExecutionOutboxService.ExecutionClaim> claimed = service.claimDue("KFE-WORKER");

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().outboxId()).isEqualTo(candidate.getId());
        assertThat(claimed.getFirst().claimToken()).isNotNull();
        verify(repository).claimDue(
                eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                eq("kfe-worker"), any(UUID.class), any());
    }
}
