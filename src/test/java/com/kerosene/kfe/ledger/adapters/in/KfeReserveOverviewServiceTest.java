package com.kerosene.kfe.ledger.adapters.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeReserveOverviewResponse;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KfeReserveOverviewService} verifying correct
 * liability/asset separation per wallet kind.
 */
class KfeReserveOverviewServiceTest {

    private final KfeBalanceRepository balanceRepository = mock(KfeBalanceRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<BlockchainClient> blockchainClient = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightningChannelGateway> lightningChannelGateway = mock(ObjectProvider.class);

    private KfeReserveOverviewService service;

    @BeforeEach
    void setUp() {
        when(blockchainClient.getIfAvailable()).thenReturn(null);
        when(lightningChannelGateway.getIfAvailable()).thenReturn(null);
        when(balanceRepository.findAll()).thenReturn(List.of());
        when(walletRepository.findKindsByIds(anyCollection())).thenReturn(List.of());
        service = new KfeReserveOverviewService(
                balanceRepository, walletRepository, blockchainClient, lightningChannelGateway);
    }

    @Test
    void emptyLedgerReturnsUnknown() {
        KfeReserveOverviewResponse overview = service.overview();
        assertThat(overview.status()).isEqualTo("UNKNOWN");
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(0L);
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(0L);
        assertThat(overview.coverageRatio()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void custodialWalletIsCountedAsLiabilityAndAsset() {
        UUID walletId = UUID.randomUUID();
        KfeBalanceEntity balance = balance(walletId, 100_000L, 20_000L, 5_000L, 1_000L, 130_000L);
        when(balanceRepository.findAll()).thenReturn(List.of(balance));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(new Object[] {walletId, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        // Liabilities: available + pending + locked + auto_hold = 100k + 20k + 5k + 1k = 126k
        long expectedLiabilities = 100_000L + 20_000L + 5_000L + 1_000L;
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(100_000L);
        assertThat(overview.pendingLiabilitiesSats()).isEqualTo(20_000L);
        assertThat(overview.lockedLiabilitiesSats()).isEqualTo(5_000L + 1_000L);
        // Assets: observed = 130k
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(130_000L);
        long totalLiabilities = expectedLiabilities;
        assertThat(overview.coverageRatio()).isEqualTo((double) 130_000L / (double) totalLiabilities);
    }

    @Test
    void watchOnlyWalletExcludedFromLiabilitiesAndAssets() {
        UUID watchOnlyId = UUID.randomUUID();
        UUID custodialId = UUID.randomUUID();

        KfeBalanceEntity watchOnlyBalance = balance(watchOnlyId, 500_000L, 0L, 0L, 0L, 1_000_000L);
        KfeBalanceEntity custodialBalance = balance(custodialId, 100_000L, 0L, 0L, 0L, 100_000L);
        when(balanceRepository.findAll()).thenReturn(List.of(watchOnlyBalance, custodialBalance));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(
                        new Object[] {watchOnlyId, KfeWalletKind.WATCH_ONLY},
                        new Object[] {custodialId, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        // WATCH_ONLY excluded: only 100k customer liability from custodial
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(100_000L);
        // WATCH_ONLY observed (1M) excluded from platform reserves — user holds keys
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(100_000L);
    }

    @Test
    void systemFundsExcludedFromLiabilities() {
        UUID fundsId = UUID.randomUUID();
        UUID custodialId = UUID.randomUUID();

        KfeBalanceEntity fundsBalance = balance(fundsId, 10_000_000L, 0L, 0L, 0L, 10_000_000L);
        KfeBalanceEntity custodialBalance = balance(custodialId, 100_000L, 0L, 0L, 0L, 100_000L);
        when(balanceRepository.findAll()).thenReturn(List.of(fundsBalance, custodialBalance));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(
                        new Object[] {fundsId, KfeWalletKind.SYSTEM_FUNDS},
                        new Object[] {custodialId, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        // SYSTEM_FUNDS excluded: only 100k customer liability
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(100_000L);
        // SYSTEM_FUNDS observed excluded from assets (equity, not reserve)
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(100_000L);
    }

    @Test
    void systemProfitExcludedFromCustomerLiabilities() {
        UUID profitId = UUID.randomUUID();
        UUID custodialId = UUID.randomUUID();

        KfeBalanceEntity profitBalance = balance(profitId, 500_000L, 0L, 0L, 0L, 500_000L);
        KfeBalanceEntity custodialBalance = balance(custodialId, 100_000L, 0L, 0L, 0L, 100_000L);
        when(balanceRepository.findAll()).thenReturn(List.of(profitBalance, custodialBalance));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(
                        new Object[] {profitId, KfeWalletKind.SYSTEM_PROFIT},
                        new Object[] {custodialId, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        // SYSTEM_PROFIT excluded from customer liabilities
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(100_000L);
    }

    @Test
    void multipleCustodialWalletsAggregateCorrectly() {
        UUID wallet1 = UUID.randomUUID();
        UUID wallet2 = UUID.randomUUID();

        KfeBalanceEntity b1 = balance(wallet1, 100_000L, 10_000L, 5_000L, 0L, 120_000L);
        KfeBalanceEntity b2 = balance(wallet2, 50_000L, 0L, 0L, 2_000L, 55_000L);
        when(balanceRepository.findAll()).thenReturn(List.of(b1, b2));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(
                        new Object[] {wallet1, KfeWalletKind.CUSTODIAL_ONCHAIN},
                        new Object[] {wallet2, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        assertThat(overview.customerLiabilitiesSats()).isEqualTo(100_000L + 50_000L);
        assertThat(overview.pendingLiabilitiesSats()).isEqualTo(10_000L);
        assertThat(overview.lockedLiabilitiesSats()).isEqualTo(5_000L + 2_000L);
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(120_000L + 55_000L);

        long totalLiab = 100_000L + 50_000L + 10_000L + 5_000L + 2_000L;
        long totalAssets = 120_000L + 55_000L;
        assertThat(overview.equitySats()).isEqualTo(totalAssets - totalLiab);
    }

    @Test
    void orphanBalanceWithoutKindIncludedConservatively() {
        UUID orphanId = UUID.randomUUID();
        KfeBalanceEntity orphan = balance(orphanId, 50_000L, 0L, 0L, 0L, 0L);
        when(balanceRepository.findAll()).thenReturn(List.of(orphan));
        // No matching wallet kind
        when(walletRepository.findKindsByIds(anyCollection())).thenReturn(List.of());

        KfeReserveOverviewResponse overview = service.overview();

        // Orphan included in liabilities (conservative), but no asset data
        assertThat(overview.customerLiabilitiesSats()).isEqualTo(50_000L);
        assertThat(overview.onchainReserveAssetsSats()).isEqualTo(0L);
        assertThat(overview.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void staleProbeDegradesStatus() {
        UUID walletId = UUID.randomUUID();
        KfeBalanceEntity balance = balance(walletId, 100_000L, 0L, 0L, 0L, 100_000L);
        // No probe quality set — treated as stale
        when(balanceRepository.findAll()).thenReturn(List.of(balance));
        when(walletRepository.findKindsByIds(anyCollection()))
                .thenReturn(kindsList(new Object[] {walletId, KfeWalletKind.CUSTODIAL_ONCHAIN}));

        KfeReserveOverviewResponse overview = service.overview();

        // coverageRatio >= 1.0 but probe is stale → SOLVENT_STALE_PROBE
        assertThat(overview.status()).isEqualTo("SOLVENT_STALE_PROBE");
    }

    @SafeVarargs
    private static List<Object[]> kindsList(Object[]... rows) {
        return Arrays.asList(rows);
    }

    private static KfeBalanceEntity balance(
            UUID walletId,
            long available,
            long pending,
            long locked,
            long autoHold,
            long observed) {
        KfeBalanceEntity entity = new KfeBalanceEntity();
        entity.setId(new KfeBalanceId(walletId, "BTC"));
        entity.setAvailableSats(available);
        entity.setPendingSats(pending);
        entity.setLockedSats(locked);
        entity.setAutoHoldSats(autoHold);
        entity.setObservedSats(observed);
        entity.setNonce(0L);
        entity.setLastHash("hash");
        entity.setBalanceSignature("hash");
        return entity;
    }
}
