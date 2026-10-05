package com.kerosene.kfe.adapters.out.persistence.repository.messaging;

import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeMessageInboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates durable message deduplication, consumer claims, completion, retry, and quarantine.
 *
 * <p>Bulk state transitions compare the current processing status and claim token. A row count of
 * zero means the caller's lease is stale or the message no longer satisfies the transition
 * precondition; successful transitions clear ownership metadata so another worker can proceed.</p>
 */
@Repository
public interface KfeMessageInboxRepository extends JpaRepository<KfeMessageInboxEntity, UUID> {
    /** @param messageId immutable producer or broker identifier
     * @return deduplicated inbox row, or empty when this message has not been received before
     */
    Optional<KfeMessageInboxEntity> findByMessageId(UUID messageId);

    /**
     * Loads due messages and expired processing leases in deterministic creation order.
     * @param statuses states eligible to run again
     * @param now current instant used to test retry and lease deadlines
     * @param pageable batch window for the consumer poll
     * @return bounded set of oldest eligible messages first
     */
    @Query("""
            select i from KfeMessageInboxEntity i
            where (i.status in :statuses and (i.nextAttemptAt is null or i.nextAttemptAt <= :now))
               or (i.status = 'PROCESSING' and (i.claimedUntil is null or i.claimedUntil <= :now))
            order by i.createdAt asc, i.id asc
            """)
    List<KfeMessageInboxEntity> findDue(
            @Param("statuses") Collection<String> statuses,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Atomically claims a due or abandoned message and increments its attempt count.
     * @param id inbox row UUID to claim
     * @param statuses states eligible for processing
     * @param now current instant for due and expired-lease checks
     * @param worker worker identity taking ownership
     * @param token fresh token required for acknowledgement or retry
     * @param until lease expiry instant assigned to the claim
     * @return one if claimed successfully, otherwise zero when another worker won
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeMessageInboxEntity i
            set i.status = 'PROCESSING', i.claimedBy = :worker,
                i.claimedUntil = :until, i.claimToken = :token,
                i.attempts = i.attempts + 1
            where i.id = :id
              and ((i.status in :statuses and (i.nextAttemptAt is null or i.nextAttemptAt <= :now))
                or (i.status = 'PROCESSING' and (i.claimedUntil is null or i.claimedUntil <= :now)))
            """)
    int claim(
            @Param("id") UUID id,
            @Param("statuses") Collection<String> statuses,
            @Param("now") Instant now,
            @Param("worker") String worker,
            @Param("token") UUID token,
            @Param("until") Instant until);

    /**
     * Marks a processing message complete only for the worker holding its current token.
     * @param id inbox row UUID to complete
     * @param token token issued to the active claim owner
     * @param now absolute instant at which processing completed
     * @return one when completion was accepted, otherwise zero for a stale or inactive claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeMessageInboxEntity i
            set i.status = 'PROCESSED', i.processedAt = :now,
                i.claimedBy = null, i.claimedUntil = null, i.claimToken = null
            where i.id = :id and i.status = 'PROCESSING' and i.claimToken = :token
            """)
    int complete(@Param("id") UUID id, @Param("token") UUID token, @Param("now") Instant now);

    /**
     * Returns a currently claimed message to pending with a retry time and retained reason.
     * @param id inbox row UUID to retry
     * @param token current claim token proving worker ownership
     * @param next next eligible processing instant
     * @param reason latest failure or deferral explanation
     * @return one when retry was scheduled, otherwise zero for a stale or inactive claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeMessageInboxEntity i
            set i.status = 'PENDING', i.nextAttemptAt = :next,
                i.claimedBy = null, i.claimedUntil = null, i.claimToken = null,
                i.quarantineReason = :reason
            where i.id = :id and i.status = 'PROCESSING' and i.claimToken = :token
            """)
    int retry(@Param("id") UUID id, @Param("token") UUID token,
              @Param("next") Instant next, @Param("reason") String reason);

    /**
     * Quarantines a currently claimed message and releases its worker lease.
     * @param id inbox row UUID to quarantine
     * @param token active claim token proving ownership
     * @param reason reason processing cannot safely continue
     * @return one when quarantined, otherwise zero for a stale or inactive claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeMessageInboxEntity i
            set i.status = 'QUARANTINED', i.quarantineReason = :reason,
                i.claimedBy = null, i.claimedUntil = null, i.claimToken = null
            where i.id = :id and i.status = 'PROCESSING' and i.claimToken = :token
            """)
    int quarantine(@Param("id") UUID id, @Param("token") UUID token, @Param("reason") String reason);

    /**
     * Requeues a quarantined message for replay and resets its attempt count and quarantine reason.
     * @param id inbox row UUID selected for operator-approved replay
     * @return one when the row was quarantined and requeued, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeMessageInboxEntity i
            set i.status = 'PENDING', i.nextAttemptAt = null, i.quarantineReason = null,
                i.attempts = 0
            where i.id = :id and i.status = 'QUARANTINED'
            """)
    int replay(@Param("id") UUID id);
}
