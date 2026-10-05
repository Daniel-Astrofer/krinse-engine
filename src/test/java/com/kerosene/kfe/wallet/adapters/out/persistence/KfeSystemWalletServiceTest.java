package com.kerosene.kfe.wallet.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeSystemWalletServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeSystemWalletService service = new KfeSystemWalletService(
            walletRepository,
            balanceService,
            hashService,
            0L,
            "Fundos",
            "Lucro",
            "SUBLEDGER");

    @Test
    void createsMissingSystemWalletsWithEmptyBalances() {
        when(walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                any(), any(), anyCollection())).thenReturn(Optional.empty());
        when(hashService.sha256(any())).thenReturn("policy-hash");
        when(walletRepository.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KfeSystemWalletService.SystemWallets wallets = service.ensureSystemWallets();

        assertThat(wallets.fundsWalletId()).isNotNull();
        assertThat(wallets.profitWalletId()).isNotNull();
        verify(balanceService).createEmptyBalance(wallets.fundsWalletId(), "BTC");
        verify(balanceService).createEmptyBalance(wallets.profitWalletId(), "BTC");
    }

    @Test
    void returnsExistingProfitWalletId() {
        KfeWalletEntity wallet = new KfeWalletEntity();
        UUID walletId = UUID.randomUUID();
        wallet.setId(walletId);
        wallet.setUserId(0L);
        wallet.setKind(KfeWalletKind.SYSTEM_PROFIT);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        when(walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                any(), any(), anyCollection())).thenReturn(Optional.of(wallet));

        assertThat(service.requireProfitWalletId()).isEqualTo(walletId);
    }
}
