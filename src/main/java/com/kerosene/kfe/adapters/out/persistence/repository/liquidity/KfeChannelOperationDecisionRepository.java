package com.kerosene.kfe.adapters.out.persistence.repository.liquidity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads channel-operation decisions for idempotent requests, recovery, and administrative history. */
@Repository
public interface KfeChannelOperationDecisionRepository
        extends JpaRepository<KfeChannelOperationDecisionEntity, UUID> {

    @Query("select count(d) > 0 from KfeChannelOperationDecisionEntity d where d.meshInjectPhase is not null and d.meshInjectPhase not in ('COMMITTED', 'RELEASED')")
    boolean hasUnresolvedFunding();

    /**
     * Lists decisions of one operation type newest first.
     * @param operation operation category to filter
     * @param pageable page window limiting the history result
     * @return matching decisions in descending creation-time order
     */
    List<KfeChannelOperationDecisionEntity> findByOperationOrderByCreatedAtDesc(
            KfeChannelOperationType operation,
            Pageable pageable);

    /**
     * Lists all operation decisions newest first for an administrative history view.
     * @param pageable page and size for the requested history window
     * @return decisions ordered by descending creation time
     */
    List<KfeChannelOperationDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** @param idempotencyKey stable caller command key
     * @return decision recorded for that key, if the command was previously received
     */
    Optional<KfeChannelOperationDecisionEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Finds approved, unexecuted channel-open decisions eligible to resume a staged mesh injection.
     *
     * <p>Peer comparison is case-insensitive; amount and phase must match exactly. The caller
     * supplies the allowed phases because recovery eligibility depends on the surrounding workflow.</p>
     *
     * @param op operation type, normally {@code OPEN}
     * @param peer Lightning peer public key to match without case sensitivity
     * @param amount exact requested local amount in satoshis
     * @param phases mesh injection phases safe to resume
     * @param pageable page size limiting the candidate recovery set
     * @return matching resumable decisions newest first
     */
    @Query(
            """
            select e from KfeChannelOperationDecisionEntity e
            where e.operation = :op
              and e.passed = true
              and e.executed = false
              and lower(e.peerPubkey) = lower(:peer)
              and e.amountSats = :amount
              and e.meshInjectPhase in :phases
            order by e.createdAt desc
            """)
    List<KfeChannelOperationDecisionEntity> findResumableOpens(
            @Param("op") KfeChannelOperationType op,
            @Param("peer") String peer,
            @Param("amount") Long amount,
            @Param("phases") List<String> phases,
            Pageable pageable);

    /**
     * Convenience lookup for the newest eligible open decision for a peer and amount.
     * @param peer Lightning peer public key
     * @param amount requested amount in satoshis
     * @param phases phases allowed for resumption
     * @return newest matching decision, or empty if no staged open can resume
     */
    default Optional<KfeChannelOperationDecisionEntity> findLatestResumableOpen(
            String peer, Long amount, List<String> phases) {
        List<KfeChannelOperationDecisionEntity> rows =
                findResumableOpens(
                        KfeChannelOperationType.OPEN, peer, amount, phases, Pageable.ofSize(1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Lists decisions at a particular mesh phase that have not yet been marked executed.
     * @param meshInjectPhase staged mesh phase to inspect
     * @param pageable page window limiting the recovery scan
     * @return oldest unexecuted decisions in that phase first
     */
    List<KfeChannelOperationDecisionEntity> findByMeshInjectPhaseAndExecutedFalseOrderByCreatedAtAsc(
            String meshInjectPhase, Pageable pageable);

    /**
     * Finds old, unexecuted reserve-phase decisions eligible for orphan cleanup.
     * @param op operation category to include in the cleanup scan
     * @param cutoff creation-time threshold; only older decisions are returned
     * @param pageable page window limiting the scan batch
     * @return orphaned reserve decisions oldest first
     */
    @Query(
            """
            select e from KfeChannelOperationDecisionEntity e
            where e.operation = :op
              and e.executed = false
              and e.meshInjectPhase = 'RESERVED'
              and e.createdAt < :cutoff
            order by e.createdAt asc
            """)
    List<KfeChannelOperationDecisionEntity> findOrphanedReserves(
            @Param("op") KfeChannelOperationType op,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    /**
     * Finds old orphaned reservations for channel-open decisions.
     * @param cutoff creation-time threshold for cleanup eligibility
     * @param pageable page window for bounded recovery work
     * @return oldest eligible unexecuted open reservations first
     */
    default List<KfeChannelOperationDecisionEntity> findOrphanedReserves(
            LocalDateTime cutoff, Pageable pageable) {
        return findOrphanedReserves(KfeChannelOperationType.OPEN, cutoff, pageable);
    }
}
