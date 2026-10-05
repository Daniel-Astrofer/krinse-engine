package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePlatformOnchainDestinationRouterTest {

    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletService walletService = mock(KfeWalletService.class);
    private final KfePlatformOnchainDestinationRouter router =
            new KfePlatformOnchainDestinationRouter(addressRepository, walletRepository, walletService);

    @Test
    void leavesTrueExternalAddressUnchanged() {
        when(addressRepository.findFirstByAddressIgnoreCase("tb1qexternalonly")).thenReturn(Optional.empty());

        KfeSubmitTransactionRequest request = onchain("tb1qexternalonly");
        KfeSubmitTransactionRequest resolved = router.resolve(request);

        assertThat(resolved.externalReference()).isEqualTo("tb1qexternalonly");
        verify(walletService, never()).ensureActiveReceiveAddress(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsAddressWhenAlreadyOnRecipientCustodial() {
        UUID custodialId = UUID.randomUUID();
        String addr = "tb1qcustodialdest000000000000000000000000";
        KfeWalletAddressEntity row = address(custodialId, addr);
        when(addressRepository.findFirstByAddressIgnoreCase(addr)).thenReturn(Optional.of(row));
        when(walletRepository.findById(custodialId))
                .thenReturn(Optional.of(wallet(79L, custodialId, KfeWalletKind.CUSTODIAL_ONCHAIN)));

        KfeSubmitTransactionRequest resolved = router.resolve(onchain(addr));

        assertThat(resolved.externalReference()).isEqualTo(addr);
    }

    @Test
    void rewritesInternalPlatformAddressToRecipientCustodialReceive() {
        UUID internalId = UUID.randomUUID();
        UUID custodialId = UUID.randomUUID();
        String internalAddr = "tb1qinternalplatform00000000000000000000";
        String custodialAddr = "tb1qcustodialsink0000000000000000000000";

        when(addressRepository.findFirstByAddressIgnoreCase(internalAddr))
                .thenReturn(Optional.of(address(internalId, internalAddr)));
        when(walletRepository.findById(internalId))
                .thenReturn(Optional.of(wallet(79L, internalId, KfeWalletKind.INTERNAL)));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(79L))
                .thenReturn(List.of(
                        wallet(79L, internalId, KfeWalletKind.INTERNAL),
                        wallet(79L, custodialId, KfeWalletKind.CUSTODIAL_ONCHAIN)));

        KfeWalletAddressEntity custodialReceive = address(custodialId, custodialAddr);
        custodialReceive.setAddressRole(KfeWalletAddressRole.RECEIVE);
        custodialReceive.setStatus(KfeWalletAddressStatus.ACTIVE);
        when(addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                        custodialId, KfeWalletAddressStatus.ACTIVE))
                .thenReturn(List.of(custodialReceive));

        KfeSubmitTransactionRequest resolved = router.resolve(onchain(internalAddr));

        assertThat(resolved.externalReference()).isEqualTo(custodialAddr);
        assertThat(resolved.rail()).isEqualTo(KfeRail.ONCHAIN);
        assertThat(resolved.direction()).isEqualTo(KfeDirection.OUTBOUND);
        // Original user memo is preserved when present.
        assertThat(resolved.memo()).isEqualTo("memo");
    }

    private static KfeSubmitTransactionRequest onchain(String address) {
        return new KfeSubmitTransactionRequest(
                "idemp",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                10_000L,
                500L,
                address,
                "memo");
    }

    private static KfeWalletAddressEntity address(UUID walletId, String value) {
        KfeWalletAddressEntity entity = new KfeWalletAddressEntity();
        entity.setWalletId(walletId);
        entity.setAddress(value);
        entity.setStatus(KfeWalletAddressStatus.ACTIVE);
        entity.setAddressRole(KfeWalletAddressRole.RECEIVE);
        return entity;
    }

    private static KfeWalletEntity wallet(Long userId, UUID id, KfeWalletKind kind) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setUserId(userId);
        wallet.setKind(kind);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(true);
        try {
            var field = KfeWalletEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(wallet, id);
            var created = KfeWalletEntity.class.getDeclaredField("createdAt");
            created.setAccessible(true);
            created.set(wallet, java.time.LocalDateTime.now());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return wallet;
    }
}
