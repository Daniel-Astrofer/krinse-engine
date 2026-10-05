package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaPaymentWalletLookupAdapter implements PaymentWalletLookupPort {
    private final KfeWalletRepository wallets;
    private final KfeWalletAddressRepository addresses;
    private final EntityManager entityManager;

    public JpaPaymentWalletLookupAdapter(KfeWalletRepository wallets, KfeWalletAddressRepository addresses, EntityManager entityManager) {
        this.wallets = wallets; this.addresses = addresses; this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentWalletSnapshot> lockOwnedSource(long userId, UUID walletId) {
        return wallets.findByIdAndUserIdForUpdate(walletId, userId).map(wallet -> {
            entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
            if (!walletId.equals(wallet.getId()) || !Long.valueOf(userId).equals(wallet.getUserId())) {
                throw new IllegalArgumentException("Source KFE wallet not found.");
            }
            return snapshot(wallet);
        });
    }

    @Override
    public Optional<PaymentWalletSnapshot> findById(UUID walletId) {
        return wallets.findById(walletId).map(JpaPaymentWalletLookupAdapter::snapshot);
    }

    @Override
    public Optional<PaymentWalletSnapshot> findOwnedDestination(long userId, UUID walletId) {
        return wallets.findByIdAndUserId(walletId, userId).map(JpaPaymentWalletLookupAdapter::snapshot);
    }

    @Override
    public Optional<PaymentWalletSnapshot> findByAddress(String address) {
        return addresses.findFirstByAddressIgnoreCase(address)
                .flatMap(row -> wallets.findById(row.getWalletId())).map(JpaPaymentWalletLookupAdapter::snapshot);
    }

    @Override
    public List<PaymentWalletSnapshot> findForUserNewestFirst(long userId) {
        return wallets.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(JpaPaymentWalletLookupAdapter::snapshot).toList();
    }

    private static PaymentWalletSnapshot snapshot(KfeWalletEntity wallet) {
        return new PaymentWalletSnapshot(wallet.getId(), wallet.getUserId(),
                wallet.getStatus() == KfeWalletStatus.ACTIVE, wallet.getKind() == KfeWalletKind.WATCH_ONLY,
                wallet.getKind() != null && wallet.isSpendable());
    }
}
