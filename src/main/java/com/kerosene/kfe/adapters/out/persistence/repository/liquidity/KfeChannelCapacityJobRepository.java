package com.kerosene.kfe.adapters.out.persistence.repository.liquidity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityIntent;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

/**
 * Queries and atomically claims asynchronous channel-capacity jobs for worker execution.
 *
 * <p>The bulk update operations encode the lease protocol directly in the database: a pending
 * job or an expired in-progress claim can be acquired, only the matching live token can heartbeat,
 * and only the current token can finish or fail a job. Returned update counts tell callers whether
 * the compare-and-set transition won.</p>
 */
public interface KfeChannelCapacityJobRepository extends JpaRepository<KfeChannelCapacityJobEntity, UUID> {

    boolean existsByStatusIn(Collection<KfeChannelCapacityJobStatus> statuses);

    /**
     * Selects pending jobs and in-progress jobs whose worker lease has expired.
     * @param now UTC instant used to evaluate expired claims
     * @param pageable batch size and ordering window for the worker poll
     * @return oldest eligible jobs first, with UUID as a deterministic tie-breaker
     */
    @Query("""
            select j from KfeChannelCapacityJobEntity j
            where j.status = 'PENDING'
               or (j.status = 'IN_PROGRESS' and (j.leaseExpiresAt is null or j.leaseExpiresAt <= :now))
            order by j.createdAt asc, j.id asc
            """)
    List<KfeChannelCapacityJobEntity> findClaimCandidates(
            @Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Atomically acquires an eligible job and assigns its worker lease and fencing token.
     * @param id job identifier to claim
     * @param now current UTC instant for testing whether an old lease has expired
     * @param worker identity of the worker taking ownership
     * @param token fresh opaque token that must accompany subsequent claim operations
     * @param until UTC expiry instant for the newly granted lease
     * @return affected row count: one if the compare-and-set claim succeeded, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelCapacityJobEntity j
            set j.status = 'IN_PROGRESS', j.claimedBy = :worker,
                j.claimToken = :token, j.leaseExpiresAt = :until
            where j.id = :id
              and (j.status = 'PENDING'
                or (j.status = 'IN_PROGRESS' and (j.leaseExpiresAt is null or j.leaseExpiresAt <= :now)))
            """)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now,
              @Param("worker") String worker, @Param("token") UUID token,
              @Param("until") LocalDateTime until);

    /**
     * Extends a still-live job lease only when the supplied token remains its current owner.
     * @param id job identifier being renewed
     * @param token token returned to the current claimant
     * @param now current UTC instant; an already expired lease cannot be renewed
     * @param until new UTC lease expiry instant
     * @return one when the matching unexpired claim was extended, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelCapacityJobEntity j
            set j.leaseExpiresAt = :until
            where j.id = :id and j.status = 'IN_PROGRESS'
              and j.claimToken = :token and j.leaseExpiresAt > :now
            """)
    int heartbeat(@Param("id") UUID id, @Param("token") UUID token,
                  @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    /**
     * Completes an in-progress job for the worker holding the matching claim token.
     * @param id job identifier to complete
     * @param token current claim token proving worker ownership
     * @param reference provider operation reference to retain on the job
     * @param now UTC completion instant
     * @return one when the matching active claim was completed, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelCapacityJobEntity j
            set j.status = 'COMPLETED', j.providerReference = :reference,
                j.completedAt = :now, j.claimedBy = null, j.claimToken = null, j.leaseExpiresAt = null
            where j.id = :id and j.status = 'IN_PROGRESS' and j.claimToken = :token
            """)
    int completeClaimed(@Param("id") UUID id, @Param("token") UUID token,
                        @Param("reference") String reference, @Param("now") LocalDateTime now);

    /**
     * Fails an in-progress job for the worker holding the matching claim token.
     * @param id job identifier to fail
     * @param token current claim token proving worker ownership
     * @param error failure detail to retain for operational diagnosis
     * @param now UTC terminal completion instant
     * @return one when the matching active claim was failed, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelCapacityJobEntity j
            set j.status = 'FAILED', j.lastError = :error,
                j.completedAt = :now, j.claimedBy = null, j.claimToken = null, j.leaseExpiresAt = null
            where j.id = :id and j.status = 'IN_PROGRESS' and j.claimToken = :token
            """)
    int failClaimed(@Param("id") UUID id, @Param("token") UUID token,
                    @Param("error") String error, @Param("now") LocalDateTime now);

    /**
     * Loads jobs in one lifecycle state, oldest first, within a caller-supplied page.
     * @param status lifecycle state to select
     * @param pageable page window limiting the returned jobs
     * @return jobs in the requested state ordered by creation time ascending
     */
    List<KfeChannelCapacityJobEntity> findByStatusOrderByCreatedAtAsc(
            KfeChannelCapacityJobStatus status,
            Pageable pageable);

    /**
     * Finds one job for a peer and intent whose state is in the supplied active-state set.
     * @param intent requested open/close operation
     * @param peerPubkey Lightning peer public key to match
     * @param statuses states considered active or otherwise relevant by the caller
     * @return first matching job, if a matching peer operation exists
     */
    Optional<KfeChannelCapacityJobEntity> findFirstByIntentAndPeerPubkeyAndStatusIn(
            KfeChannelCapacityIntent intent,
            String peerPubkey,
            Collection<KfeChannelCapacityJobStatus> statuses);

    /**
     * Finds one job for a channel outpoint and intent in any supplied lifecycle state.
     * @param intent requested operation category
     * @param channelPoint channel outpoint to match
     * @param statuses states included in the lookup
     * @return first matching job, if one exists
     */
    Optional<KfeChannelCapacityJobEntity> findFirstByIntentAndChannelPointAndStatusIn(
            KfeChannelCapacityIntent intent,
            String channelPoint,
            Collection<KfeChannelCapacityJobStatus> statuses);

    /**
     * Counts jobs of one operation category whose states are in the supplied set.
     * @param intent operation category to count
     * @param statuses lifecycle states included in the count
     * @return number of matching jobs
     */
    long countByIntentAndStatusIn(
            KfeChannelCapacityIntent intent,
            Collection<KfeChannelCapacityJobStatus> statuses);
}
