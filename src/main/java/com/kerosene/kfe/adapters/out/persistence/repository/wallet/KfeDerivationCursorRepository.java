package com.kerosene.kfe.adapters.out.persistence.repository.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeDerivationCursorEntity;

import java.util.Optional;

/** Reads HD derivation cursors while serializing allocation of the next address index. */
@Repository
public interface KfeDerivationCursorRepository extends JpaRepository<KfeDerivationCursorEntity, String> {

    /**
     * Loads a derivation cursor under a pessimistic write lock.
     *
     * <p>Keep the lock while selecting and persisting the next child index so concurrent address
     * issuers cannot allocate the same derivation index.</p>
     * @param cursorKey stable wallet/branch cursor identity
     * @return locked cursor, or empty when the cursor has not yet been created
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c
            from KfeDerivationCursorEntity c
            where c.cursorKey = :cursorKey
            """)
    Optional<KfeDerivationCursorEntity> findByCursorKeyForUpdate(@Param("cursorKey") String cursorKey);
}
