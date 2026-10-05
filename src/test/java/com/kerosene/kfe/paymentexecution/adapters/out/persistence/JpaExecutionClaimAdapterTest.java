package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeExecutionOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JpaExecutionClaimAdapterTest {
    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final JpaExecutionClaimAdapter adapter = new JpaExecutionClaimAdapter(repository, 600L);

    @Test void candidateQueryUsesRealDatabaseLimitAndOnlyReturnsWonClaims() {
        var one = new KfeExecutionOutboxEntity(); var two = new KfeExecutionOutboxEntity();
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any(), any())).thenReturn(List.of(one, two));
        when(repository.claimDue(eq(one.getId()), anyCollection(), anyCollection(), any(), anyString(), any(), any())).thenReturn(1);
        var result = adapter.claimDue("  KFE-WORKER  ");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().outboxId()).isEqualTo(one.getId());
        verify(repository).findTop100ClaimCandidates(eq(List.of("PENDING", "FAILED_RETRYABLE")),
                eq(List.of("ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND")), any(),
                argThat((Pageable page) -> page.getPageNumber() == 0 && page.getPageSize() == 100));
        verify(repository).claimDue(eq(one.getId()), anyCollection(), anyCollection(), any(), eq("kfe-worker"), any(), any());
        assertThat(result.getFirst().toString()).doesNotContain(result.getFirst().claimToken().toString());
    }

    @Test void immediateClaimCreatesFreshTokensAndUsesBoundedLease() {
        UUID id = UUID.randomUUID();
        when(repository.claimImmediate(eq(id), any(), anyString(), any(), any())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(1), expiry = call.getArgument(4);
            assertThat(expiry).isEqualTo(now.plusSeconds(600));
            return 1;
        });
        var one = adapter.claimImmediate(id, "  ").orElseThrow();
        var two = adapter.claimImmediate(id, null).orElseThrow();
        assertThat(one.claimToken()).isNotEqualTo(two.claimToken());
        verify(repository, times(2)).claimImmediate(eq(id), any(), eq("kfe-execution-worker"), any(), any());
    }

    @Test void lostCasAndInvalidRequestsNeverManufactureOwnership() {
        assertThat(adapter.claimImmediate(UUID.randomUUID(), "worker")).isEmpty();
        assertThat(adapter.claimImmediate(null, "worker")).isEmpty();
        assertThat(adapter.heartbeat(null)).isFalse();
        assertThatThrownBy(() -> new ExecutionClaim(null, UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionClaim(UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
        var claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
        assertThat(adapter.heartbeat(claim)).isFalse();
        when(repository.heartbeat(eq(claim.outboxId()), eq(claim.claimToken()), any(), any())).thenReturn(1);
        assertThat(adapter.heartbeat(claim)).isTrue();
    }

    @ParameterizedTest @ValueSource(longs = {0, 29, 3601, Long.MAX_VALUE})
    void refusesUnsafeLeaseConfiguration(long seconds) {
        assertThatThrownBy(() -> new JpaExecutionClaimAdapter(repository, seconds)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workerNameIsBoundedAndLocaleIndependent() {
        adapter.claimImmediate(UUID.randomUUID(), "  " + "A".repeat(150) + "  ");
        verify(repository).claimImmediate(any(), any(), eq("a".repeat(128)), any(), any());
    }

    @Test void actualSpringCompositionHasOnePortAndCompatibilityFacade() {
        new ApplicationContextRunner().withBean(KfeExecutionOutboxRepository.class, () -> repository)
                .withUserConfiguration(JpaExecutionClaimAdapter.class, KfeExecutionOutboxService.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ExecutionClaimPort.class).hasSingleBean(KfeExecutionOutboxService.class);
                    assertThat(ctx.getBean(ExecutionClaimPort.class)).isInstanceOf(JpaExecutionClaimAdapter.class);
                    assertThat(ctx.getBean(KfeExecutionOutboxService.class).heartbeat(null)).isFalse();
                });
    }

    @Test void transactionDemarcationBelongsToAdapterNotLegacyFacade() throws Exception {
        for (var method : ExecutionClaimPort.class.getMethods()) {
            assertThat(JpaExecutionClaimAdapter.class.getMethod(method.getName(), method.getParameterTypes())
                    .getAnnotation(Transactional.class)).isNotNull();
        }
        assertThat(KfeExecutionOutboxService.class.getMethod("claimDue", String.class).getAnnotation(Transactional.class)).isNull();
    }
}
