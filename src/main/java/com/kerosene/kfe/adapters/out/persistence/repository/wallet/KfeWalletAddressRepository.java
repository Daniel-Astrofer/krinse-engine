package com.kerosene.kfe.adapters.out.persistence.repository.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Looks up wallet addresses by owner wallet, lifecycle state, and observed address value. */
@Repository
public interface KfeWalletAddressRepository extends JpaRepository<KfeWalletAddressEntity, UUID> {

    /** @param walletId owning wallet
     * @param status address state to select
     * @return matching wallet addresses newest first
     */
    List<KfeWalletAddressEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    /** @param walletId owning wallet
     * @return all retained address records for the wallet, newest first
     */
    List<KfeWalletAddressEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    /** @param walletId owning wallet
     * @param status address state to select
     * @return newest address in that state, if any
     */
    Optional<KfeWalletAddressEntity> findTopByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    /** @param address address observed from a payment or external lookup
     * @return first stored address matching without case sensitivity
     */
    Optional<KfeWalletAddressEntity> findFirstByAddressIgnoreCase(String address);
}
