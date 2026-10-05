package com.kerosene.kfe.adapters.out.persistence.repository.liquidity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLiquidityReservationStatus;

import java.util.Optional;
import java.util.UUID;

/** Queries Lightning liquidity holds and provides transaction-scoped pool serialization. */
@Repository
public interface KfeLightningLiquidityReservationRepository
        extends JpaRepository<KfeLightningLiquidityReservationEntity, UUID> {

    /** @param transactionId payment transaction owning the reservation
     * @return unique reservation for the transaction, or empty when no liquidity is held
     */
    Optional<KfeLightningLiquidityReservationEntity> findByTransactionId(UUID transactionId);

    /**
     * Sums reserved satoshis across all rows in the requested lifecycle state.
     * @param status reservation state to aggregate, commonly {@code HELD}
     * @return total reserved amount in satoshis; zero when no matching rows exist
     */
    @Query("""
            select coalesce(sum(r.amountSats), 0)
            from KfeLightningLiquidityReservationEntity r
            where r.status = :status
            """)
    long sumAmountByStatus(@Param("status") KfeLiquidityReservationStatus status);

    /**
     * Acquires a transaction-scoped advisory lock for the platform Lightning liquidity pool.
     *
     * <p>Call within the same database transaction as capacity checks and reservation writes. The
     * shared lock key makes concurrent outbound submissions serialize their check-then-reserve work.</p>
     *
     * @param lockKey stable PostgreSQL advisory-lock key shared by pool operations
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    void acquirePoolLock(@Param("lockKey") long lockKey);
}
