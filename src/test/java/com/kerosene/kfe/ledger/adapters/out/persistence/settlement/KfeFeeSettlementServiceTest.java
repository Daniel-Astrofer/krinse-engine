package com.kerosene.kfe.ledger.adapters.out.persistence.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;

import java.util.UUID;

import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeFeeSettlementServiceTest {

    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics>
            metricsProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    private final KfeFeeSettlementService service = new KfeFeeSettlementService(
            systemWalletService,
            balanceService,
            movementRecorder,
            movementRepository,
            auditLogService,
            metricsProvider,
            "SUBLEDGER",
            true);

    @Test
    void skipsTransactionsWithoutKeroseneFee() {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setKeroseneFeeSats(0L);

        service.creditKeroseneFee(tx);

        verify(systemWalletService, never()).requireProfitWalletId();
    }

    @Test
    void creditsKeroseneFeeToSystemProfitWallet() {
        UUID profitWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setKeroseneFeeSats(900L);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitWalletId);
        when(movementRepository.existsByTransactionIdAndMovementType(tx.getId(), "CREDIT_KEROSENE_FEE"))
                .thenReturn(false);

        when(movementRecorder.record(
                        tx.getId(), profitWalletId, "CREDIT_KEROSENE_FEE", 900L, null, "AVAILABLE"))
                .thenReturn(true);

        service.creditKeroseneFee(tx);

        verify(movementRecorder).record(tx.getId(), profitWalletId, "CREDIT_KEROSENE_FEE", 900L, null, "AVAILABLE");
        verify(balanceService).creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
    }

    @Test
    void skipsWhenKeroseneFeeAlreadySettled() {
        UUID profitWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setKeroseneFeeSats(900L);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        when(movementRepository.existsByTransactionIdAndMovementType(tx.getId(), "CREDIT_KEROSENE_FEE"))
                .thenReturn(true);

        service.creditKeroseneFee(tx);

        verify(systemWalletService, never()).requireProfitWalletId();
        verify(balanceService, never()).creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
    }
}
