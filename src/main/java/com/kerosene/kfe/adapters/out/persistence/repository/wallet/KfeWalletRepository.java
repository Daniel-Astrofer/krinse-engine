package com.kerosene.kfe.adapters.out.persistence.repository.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeDashboardWalletRow;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retrieves wallet records under user, custody, state, and dashboard projection filters.
 *
 * <p>User-scoped methods support access-controlled flows; the locking variant serializes a wallet
 * mutation. Batch kind reads support aggregate reserve calculations, and the native dashboard
 * query maps only active operational wallet fields into a lightweight projection.</p>
 */
@Repository
public interface KfeWalletRepository extends JpaRepository<KfeWalletEntity, UUID> {

    /** @param userId wallet owner
     * @return the user's wallets newest first
     */
    List<KfeWalletEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** @param userId wallet owner
     * @param statuses wallet states eligible for the caller's operation
     * @return matching wallets newest first
     */
    List<KfeWalletEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId,
            Collection<KfeWalletStatus> statuses);

    /**
     * Checks whether the user already has a wallet of a kind in any supplied state.
     * @param userId wallet owner
     * @param kind wallet category to check
     * @param statuses candidate lifecycle states
     * @return {@code true} when a matching wallet exists
     */
    boolean existsByUserIdAndKindAndStatusIn(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    /**
     * Counts a user's wallets of a category within a set of lifecycle states.
     * @param userId wallet owner
     * @param kind wallet category
     * @param statuses states included in the count
     * @return number of matching wallets
     */
    long countByUserIdAndKindAndStatusIn(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    /** @param userId wallet owner
     * @param kind wallet category
     * @return newest wallet of that kind owned by the user
     */
    Optional<KfeWalletEntity> findFirstByUserIdAndKindOrderByCreatedAtDesc(Long userId, KfeWalletKind kind);

    /** @param userId wallet owner
     * @param kind wallet category
     * @param statuses eligible lifecycle states
     * @return newest wallet matching owner, category, and one of the states
     */
    Optional<KfeWalletEntity> findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    /** @param id wallet UUID
     * @param userId required owner scope
     * @return wallet only if it belongs to the specified user
     */
    Optional<KfeWalletEntity> findByIdAndUserId(UUID id, Long userId);

    /** @param kinds wallet categories to include
     * @param status exact lifecycle state
     * @return wallets matching one of the kinds and the requested state
     */
    List<KfeWalletEntity> findByKindInAndStatus(
            Collection<KfeWalletKind> kinds,
            KfeWalletStatus status);

    /**
     * Loads an owned wallet under a write lock before changing its state or configuration.
     * @param id wallet UUID
     * @param userId required owner scope
     * @return locked wallet when it belongs to the user
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from KfeWalletEntity w where w.id = :id and w.userId = :userId")
    Optional<KfeWalletEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    /**
     * Reads the wallet dashboard projection for operationally visible states only.
     *
     * <p>The database view supplies balance buckets and active receiving address, avoiding loading
     * full wallet entities for a read-only dashboard. Archived or other non-operational states are
     * intentionally excluded by the query.</p>
     * @param userId owner whose dashboard rows are requested
     * @return projected wallet status, balances, address, and lifecycle times newest first
     */
    @Query(value = """
            SELECT
                wallet_id AS walletId,
                kind AS kind,
                status AS status,
                label AS label,
                asset AS asset,
                spendable AS spendable,
                available_sats AS availableSats,
                pending_sats AS pendingSats,
                locked_sats AS lockedSats,
                auto_hold_sats AS autoHoldSats,
                observed_sats AS observedSats,
                active_address AS activeAddress,
                created_at AS createdAt,
                updated_at AS updatedAt
            FROM financial.wallet_dashboard_view
            WHERE user_id = :userId
              AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS')
            ORDER BY created_at DESC
            """, nativeQuery = true)
    List<KfeDashboardWalletRow> findDashboardRows(@Param("userId") Long userId);

    /**
     * Returns wallet category metadata for a batch of identifiers.
     *
     * <p>Reserve overview and settlement solvency use these categories to distinguish customer
     * liabilities from watch-only and platform-owned balances.</p>
     * @param walletIds wallet UUIDs to inspect
     * @return two-column rows containing wallet UUID and its {@link KfeWalletKind}
     */
    @Query("select w.id, w.kind from KfeWalletEntity w where w.id in :walletIds")
    List<Object[]> findKindsByIds(@Param("walletIds") Collection<UUID> walletIds);
}
