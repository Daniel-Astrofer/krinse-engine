package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelRebalanceJobRepository;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeChannelRebalanceQueueServiceTest {

    private final KfeChannelRebalanceJobRepository jobRepository = mock(KfeChannelRebalanceJobRepository.class);
    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private KfeChannelRebalanceQueueService service;

    @BeforeEach
    void setUp() {
        service = new KfeChannelRebalanceQueueService(
                jobRepository, systemWalletService, balanceService, auditLogService);
    }

    @Test
    void enqueueCreatesPendingJobWhenProfitCoversCost() {
        UUID profitId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        when(jobRepository.findFirstByChannelPointAndStatusIn(eq("txid:0"), any()))
                .thenReturn(Optional.empty());
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitId);
        KfeBalanceEntity profit = new KfeBalanceEntity();
        profit.setId(new KfeBalanceId(profitId, "BTC"));
        profit.setAvailableSats(100_000L);
        when(balanceService.requireForUpdate(profitId, "BTC")).thenReturn(profit);
        when(jobRepository.saveAndFlush(any(KfeChannelRebalanceJobEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<KfeChannelRebalanceJobEntity> job = service.enqueueIfAbsent(
                decisionId, "txid:0", "peer", 5_000L, 50_000L);

        assertThat(job).isPresent();
        assertThat(job.get().getStatus()).isEqualTo(KfeChannelRebalanceJobStatus.PENDING);
        assertThat(job.get().getEstimatedCostSats()).isEqualTo(5_000L);
        verify(auditLogService).record(eq("KFE_CHANNEL_DECISION"), any(), eq(profitId), any(), any(), any());
    }

    @Test
    void enqueueRejectedWhenProfitInsufficient() {
        UUID profitId = UUID.randomUUID();
        when(jobRepository.findFirstByChannelPointAndStatusIn(eq("txid:0"), any()))
                .thenReturn(Optional.empty());
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitId);
        KfeBalanceEntity profit = new KfeBalanceEntity();
        profit.setId(new KfeBalanceId(profitId, "BTC"));
        profit.setAvailableSats(100L);
        when(balanceService.requireForUpdate(profitId, "BTC")).thenReturn(profit);

        Optional<KfeChannelRebalanceJobEntity> job = service.enqueueIfAbsent(
                UUID.randomUUID(), "txid:0", "peer", 5_000L, 50_000L);

        assertThat(job).isEmpty();
    }

    @Test
    void enqueueIsIdempotentWhenPendingExists() {
        KfeChannelRebalanceJobEntity existing = new KfeChannelRebalanceJobEntity();
        existing.setChannelPoint("txid:0");
        existing.setStatus(KfeChannelRebalanceJobStatus.PENDING);
        when(jobRepository.findFirstByChannelPointAndStatusIn(
                eq("txid:0"),
                eq(EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS))))
                .thenReturn(Optional.of(existing));

        Optional<KfeChannelRebalanceJobEntity> job = service.enqueueIfAbsent(
                UUID.randomUUID(), "txid:0", "peer", 1L, 2L);

        assertThat(job).contains(existing);
    }
}
