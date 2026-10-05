package com.kerosene.kfe.adapters.out.persistence.repository.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeTaxEventClassificationEntity;

import java.util.List;
import java.util.Optional;

/** Queries user-scoped tax classifications stored for financial events. */
@Repository
public interface KfeTaxEventClassificationRepository
        extends JpaRepository<KfeTaxEventClassificationEntity, KfeTaxEventClassificationEntity.Key> {

    /** @param userId owner of the classifications to load
     * @return all event classifications belonging to that user
     */
    List<KfeTaxEventClassificationEntity> findByUserId(Long userId);

    /**
     * Looks up the classification for one user/event pair, matching the composite identity.
     * @param userId user namespace in which the event was classified
     * @param eventId stable financial event identifier
     * @return matching classification, or empty when the user has not classified this event
     */
    Optional<KfeTaxEventClassificationEntity> findByUserIdAndEventId(Long userId, String eventId);
}
