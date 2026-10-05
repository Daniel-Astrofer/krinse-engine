package com.kerosene.kfe.adapters.out.persistence.repository.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;

import java.util.Collection;
import java.util.UUID;

/** Persistence queries for the append-only history of movements between ledger buckets. */
@Repository
public interface KfeBalanceMovementRepository extends JpaRepository<KfeBalanceMovementEntity, UUID> {

    /**
     * Checks whether a transaction already has a movement of the specified type.
     * @param transactionId transaction whose recorded movements should be inspected
     * @param movementType exact movement category to look for
     * @return {@code true} when at least one matching movement exists
     */
    boolean existsByTransactionIdAndMovementType(UUID transactionId, String movementType);

    /**
     * Checks whether a transaction already has any movement from a set of types.
     * @param transactionId transaction whose recorded movements should be inspected
     * @param movementTypes candidate movement categories; an empty collection matches no rows
     * @return {@code true} when a movement exists with one of the supplied categories
     */
    boolean existsByTransactionIdAndMovementTypeIn(UUID transactionId, Collection<String> movementTypes);
}
