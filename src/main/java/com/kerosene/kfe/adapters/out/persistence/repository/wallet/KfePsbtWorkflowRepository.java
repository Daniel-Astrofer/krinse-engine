package com.kerosene.kfe.adapters.out.persistence.repository.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Retrieves user-owned PSBT signing workflows for wallet operations and history views. */
@Repository
public interface KfePsbtWorkflowRepository extends JpaRepository<KfePsbtWorkflowEntity, UUID> {

    /** @param id workflow UUID
     * @param userId required owner scope
     * @return workflow only when it belongs to the specified user
     */
    Optional<KfePsbtWorkflowEntity> findByIdAndUserId(UUID id, Long userId);

    /** @param walletId wallet whose signing workflows are requested
     * @param userId wallet owner, included to enforce the user boundary
     * @return workflows for that wallet and owner, newest first
     */
    List<KfePsbtWorkflowEntity> findByWalletIdAndUserIdOrderByCreatedAtDesc(UUID walletId, Long userId);

    /** @param userId workflow owner
     * @return the user's PSBT workflows ordered newest first
     */
    List<KfePsbtWorkflowEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
