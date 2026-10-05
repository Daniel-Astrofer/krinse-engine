package com.kerosene.kfe.adapters.out.persistence.repository.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read and lock operations for wallet/asset ledger balances. */
@Repository
public interface KfeBalanceRepository extends JpaRepository<KfeBalanceEntity, KfeBalanceId> {

    /**
     * Loads one wallet/asset balance while holding a pessimistic write lock.
     *
     * <p>Use inside the balance mutation transaction to serialize updates to the same ledger row
     * and avoid lost updates across concurrent debits or credits.</p>
     *
     * @param walletId wallet that owns the balance row
     * @param asset asset code that identifies the balance within the wallet
     * @return locked balance when the wallet/asset row exists, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from KfeBalanceEntity b where b.id.walletId = :walletId and b.id.asset = :asset")
    Optional<KfeBalanceEntity> findByWalletIdAndAssetForUpdate(
            @Param("walletId") UUID walletId,
            @Param("asset") String asset);

    /**
     * Loads balance rows belonging to any wallet in the supplied set.
     * @param walletIds wallet identifiers to include in the result
     * @return matching balances; each wallet may contribute rows for multiple assets
     */
    @Query("select b from KfeBalanceEntity b where b.id.walletId in :walletIds")
    List<KfeBalanceEntity> findByWalletIds(@Param("walletIds") Collection<UUID> walletIds);
}
