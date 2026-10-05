package com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Loads idempotency records while serializing access to a single user/request key. */
@Repository
public interface KfeIdempotencyRepository extends JpaRepository<KfeIdempotencyEntity, com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId> {
    /**
     * Loads one idempotency record under a pessimistic write lock.
     *
     * <p>The lock lets request processing inspect the stored request hash and outcome, then safely
     * decide whether to replay the original result or persist a first result without racing another
     * retry for the same composite key.</p>
     *
     * @param id composite user/idempotency-key identity to lock
     * @return locked row when the key exists, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from KfeIdempotencyEntity reservation where reservation.id = :id")
    Optional<KfeIdempotencyEntity> findByIdForUpdate(@Param("id") KfeIdempotencyId id);
}
