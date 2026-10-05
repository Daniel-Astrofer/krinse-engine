package com.kerosene.kfe.adapters.out.persistence.repository.liquidity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

/** Queries, claims, and completes asynchronous channel-rebalance jobs. */
@Repository
public interface KfeChannelRebalanceJobRepository extends JpaRepository<KfeChannelRebalanceJobEntity, UUID> {

    boolean existsByStatusIn(Collection<KfeChannelRebalanceJobStatus> statuses);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from KfeChannelRebalanceJobEntity j where j.id = :id")
    Optional<KfeChannelRebalanceJobEntity> findForUpdate(@Param("id") UUID id);

    /**
     * Selects pending jobs and jobs whose active worker lease has expired.
     * @param now UTC time used to identify expired in-progress claims
     * @param pageable bounded worker poll size
     * @return oldest eligible jobs first with UUID tie-breaking
     */
    @Query("""
            select j from KfeChannelRebalanceJobEntity j
            where j.status = 'PENDING'
               or (j.status = 'IN_PROGRESS' and (j.leaseExpiresAt is null or j.leaseExpiresAt <= :now))
            order by j.createdAt asc, j.id asc
            """)
    List<KfeChannelRebalanceJobEntity> findClaimCandidates(
            @Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Atomically assigns an eligible job to a worker with a fresh fencing token and lease.
     * @param id job identifier to claim
     * @param now current UTC time for checking an expired previous lease
     * @param worker worker identity taking the claim
     * @param token fresh claim token required for follow-up writes
     * @param until UTC deadline for the new lease
     * @return one if the compare-and-set claim succeeds; zero if another worker won or it is ineligible
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelRebalanceJobEntity j
            set j.status = 'IN_PROGRESS', j.claimedBy = :worker,
                j.claimToken = :token, j.leaseExpiresAt = :until, j.rowVersion = j.rowVersion + 1
            where j.id = :id
              and (j.status = 'PENDING'
                or (j.status = 'IN_PROGRESS' and (j.leaseExpiresAt is null or j.leaseExpiresAt <= :now)))
            """)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now,
              @Param("worker") String worker, @Param("token") UUID token,
              @Param("until") LocalDateTime until);

    /**
     * Renews an unexpired lease only for the worker holding the matching token.
     * @param id job whose lease is being renewed
     * @param token active claim token
     * @param now current UTC time; expired claims cannot heartbeat
     * @param until new UTC lease deadline
     * @return one when renewed, otherwise zero when ownership or liveness checks fail
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelRebalanceJobEntity j
            set j.leaseExpiresAt = :until, j.rowVersion = j.rowVersion + 1
            where j.id = :id and j.status = 'IN_PROGRESS'
              and j.claimToken = :token and j.leaseExpiresAt > :now
            """)
    int heartbeat(@Param("id") UUID id, @Param("token") UUID token,
                  @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    /**
     * Completes a running job and clears its lease when the claim token matches.
     * @param id job to complete
     * @param token current worker claim token
     * @param reference provider-side reference to persist
     * @param now UTC time the job completed
     * @return one when completion is accepted, otherwise zero for a stale/non-active claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelRebalanceJobEntity j
            set j.status = 'COMPLETED', j.providerReference = :reference,
                j.completedAt = :now, j.claimedBy = null, j.claimToken = null, j.leaseExpiresAt = null
            where j.id = :id and j.status = 'IN_PROGRESS' and j.claimToken = :token
            """)
    int completeClaimed(@Param("id") UUID id, @Param("token") UUID token,
                        @Param("reference") String reference, @Param("now") LocalDateTime now);

    /**
     * Fails a running job and clears its lease when the claim token matches.
     * @param id job to fail
     * @param token current worker claim token
     * @param error failure detail to store
     * @param now UTC terminal time for the failed job
     * @return one when failure is accepted, otherwise zero for a stale/non-active claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeChannelRebalanceJobEntity j
            set j.status = 'FAILED', j.lastError = :error,
                j.completedAt = :now, j.claimedBy = null, j.claimToken = null, j.leaseExpiresAt = null
            where j.id = :id and j.status = 'IN_PROGRESS' and j.claimToken = :token
            """)
    int failClaimed(@Param("id") UUID id, @Param("token") UUID token,
                    @Param("error") String error, @Param("now") LocalDateTime now);

    /**
     * Finds a job for an existing channel in one of the supplied lifecycle states.
     * @param channelPoint channel outpoint to match
     * @param statuses states considered relevant by the caller
     * @return a matching job, if one exists
     */
    Optional<KfeChannelRebalanceJobEntity> findFirstByChannelPointAndStatusIn(
            String channelPoint,
            Collection<KfeChannelRebalanceJobStatus> statuses);

    /**
     * Loads jobs in one lifecycle state in oldest-first order.
     * @param status lifecycle state to select
     * @param pageable page window used to bound the result
     * @return matching jobs ordered by creation time ascending
     */
    List<KfeChannelRebalanceJobEntity> findByStatusOrderByCreatedAtAsc(
            KfeChannelRebalanceJobStatus status,
            Pageable pageable);
}
