package com.kerosene.kfe.adapters.out.persistence.repository.messaging;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * Coordinates notification outbox lookup, worker claims, retry scheduling, and delivery state.
 *
 * <p>Due-query methods select eligible or abandoned leases; fenced update methods compare the
 * current status and claim token so an old worker cannot overwrite the result of a newer claim.
 * The unfenced variants support legacy delivery paths and should be used only when caller
 * serialization guarantees exclusive ownership.</p>
 */
@Repository
public interface KfeFinancialNotificationOutboxRepository
        extends JpaRepository<KfeFinancialNotificationOutboxEntity, UUID> {

    /** Increments the aggregate's sequence under the same row lock as its financial mutation. */
    @Query(value = """
            UPDATE financial.transactions_master SET notification_sequence = notification_sequence + 1
            WHERE id = :transactionId RETURNING notification_sequence
            """, nativeQuery = true)
    long nextEventSequence(@Param("transactionId") UUID transactionId);

    /** Atomic enqueue: a duplicate must not abort the surrounding financial transaction. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO financial.kfe_financial_notification_outbox
              (id, event_id, user_id, transaction_id, event_type, payload_json,
               schema_version, correlation_id, causation_id, status, attempts, created_at, next_attempt_at, event_sequence, row_version)
            VALUES (:id, :eventId, :userId, :transactionId, :type, :payload,
                    :schemaVersion, :correlationId, :causationId, 'PENDING', 0, :now, :now, :sequence, 0)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertEventIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
            @Param("userId") long userId, @Param("transactionId") UUID transactionId,
            @Param("type") String type, @Param("payload") String payload,
            @Param("schemaVersion") int schemaVersion, @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId, @Param("now") Instant now, @Param("sequence") long sequence);

    /** @param eventId unique logical notification identifier
     * @return persisted event, or empty when the event has not been enqueued
     */
    Optional<KfeFinancialNotificationOutboxEntity> findByEventId(UUID eventId);

    /**
     * Loads one notification row under a pessimistic write lock for transactional processing.
     * @param id persistence UUID of the outbox row
     * @return locked row, or empty when it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from KfeFinancialNotificationOutboxEntity o where o.id = :id")
    Optional<KfeFinancialNotificationOutboxEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Selects up to the first 100 due rows or expired legacy {@code CLAIMED} rows.
     * @param dueStatuses states eligible for a new delivery attempt
     * @param now current absolute instant used for retry and lease deadlines
     * @return oldest eligible events first
     */
    @Query("""
            select o from KfeFinancialNotificationOutboxEntity o
            where (
                o.status in :dueStatuses
                and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            ) or (
                o.status = 'CLAIMED'
                and o.claimedUntil is not null
                and o.claimedUntil <= :now
            )
            order by o.createdAt asc
            """)
    List<KfeFinancialNotificationOutboxEntity> findTop100ClaimCandidates(
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now);

    /**
     * Selects a pageable batch of due events and expired {@code PROCESSING} leases.
     * @param dueStatuses states eligible for dispatch
     * @param now current instant used to evaluate retry and lease deadlines
     * @param pageable batch window for the worker poll
     * @return candidates ordered by creation time and UUID for deterministic processing
     */
    @Query("""
            select o from KfeFinancialNotificationOutboxEntity o
            where (o.status in :dueStatuses and (o.nextAttemptAt is null or o.nextAttemptAt <= :now))
               or (o.status = 'PROCESSING' and (o.claimedUntil is null or o.claimedUntil <= :now))
            order by o.createdAt asc, o.id asc
            """)
    List<KfeFinancialNotificationOutboxEntity> findClaimCandidatesFenced(
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Claims a due or expired row using a fencing token and increments its attempt count.
     * @param id outbox row to claim
     * @param dueStatuses states eligible for a new attempt
     * @param now current instant for the due/expired checks
     * @param workerId identity of the worker acquiring the claim
     * @param claimToken fresh token required for subsequent fenced updates
     * @param claimedUntil lease expiration instant assigned to the worker
     * @return one if this worker atomically acquired the claim, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'PROCESSING', o.claimedBy = :workerId,
                o.claimedUntil = :claimedUntil, o.claimToken = :claimToken,
                o.attempts = o.attempts + 1
            where o.id = :id
              and ((o.status in :dueStatuses and (o.nextAttemptAt is null or o.nextAttemptAt <= :now))
                or (o.status = 'PROCESSING' and (o.claimedUntil is null or o.claimedUntil <= :now)))
            """)
    int claimFenced(
            @Param("id") UUID id,
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now,
            @Param("workerId") String workerId,
            @Param("claimToken") UUID claimToken,
            @Param("claimedUntil") Instant claimedUntil);

    /**
     * Claims a due row using the legacy {@code CLAIMED} state without issuing a token.
     * @param id outbox row to claim
     * @param dueStatuses states eligible for delivery
     * @param now current instant used to test retry and expired legacy claims
     * @param workerId worker identity recorded as claim owner
     * @param claimedUntil lease expiration instant
     * @return one if claim succeeded, otherwise zero when the row is no longer due
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'CLAIMED',
                o.claimedBy = :workerId,
                o.claimedUntil = :claimedUntil
            where o.id = :id
              and (
                  (o.status in :dueStatuses and (o.nextAttemptAt is null or o.nextAttemptAt <= :now))
                  or (o.status = 'CLAIMED' and o.claimedUntil is not null and o.claimedUntil <= :now)
              )
            """)
    int claimDue(
            @Param("id") UUID id,
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now,
            @Param("workerId") String workerId,
            @Param("claimedUntil") Instant claimedUntil);

    /**
     * Records a retryable failure using a caller-selected retry state and schedule.
     *
     * <p>This compatibility path is not fenced by a claim token; callers must ensure exclusive
     * ownership before using it.</p>
     * @param id outbox row that failed
     * @param status retryable state to persist
     * @param nextAttemptAt absolute instant when another attempt becomes due
     * @param lastError failure detail retained for operations
     * @return number of rows updated
     */
    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = :status,
                o.attempts = o.attempts + 1,
                o.nextAttemptAt = :nextAttemptAt,
                o.lastError = :lastError,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markRetryableFailure(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError);

    /**
     * Records a retryable failure only when the row remains in processing under this token.
     * @param id outbox row that failed
     * @param claimToken current worker token proving ownership
     * @param nextAttemptAt next eligible retry instant
     * @param lastError failure detail retained for operations
     * @return one when the current claim was updated, otherwise zero for a stale claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'FAILED_RETRYABLE', o.nextAttemptAt = :nextAttemptAt,
                o.lastError = :lastError, o.claimedBy = null, o.claimedUntil = null,
                o.claimToken = null
            where o.id = :id and o.status = 'PROCESSING' and o.claimToken = :claimToken
            """)
    int markRetryableFailureFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError);

    /**
     * Marks a row as finally failed and releases its legacy claim fields.
     *
     * <p>This compatibility operation has no fencing token and therefore relies on caller-side
     * exclusive ownership.</p>
     * @param id outbox row with terminal failure
     * @param status terminal state to persist
     * @param lastError final failure detail
     * @return number of rows updated
     */
    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = :status,
                o.lastError = :lastError,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markFinalFailure(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("lastError") String lastError);

    /**
     * Moves the current token owner's processing row to the dead-letter state.
     * @param id outbox row to dead-letter
     * @param claimToken current worker token proving ownership
     * @param lastError terminal delivery failure detail
     * @return one when the current claim was dead-lettered, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'DEAD_LETTER', o.lastError = :lastError,
                o.claimedBy = null, o.claimedUntil = null, o.claimToken = null
            where o.id = :id and o.status = 'PROCESSING' and o.claimToken = :claimToken
            """)
    int markFinalFailureFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lastError") String lastError);

    /**
     * Marks a notification delivered and clears its legacy claim owner and deadline.
     * @param id successfully delivered outbox row
     * @param now absolute delivery acknowledgement instant
     * @return number of rows marked delivered
     */
    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'DELIVERED',
                o.deliveredAt = :now,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markDelivered(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Marks a notification delivered only if the row is still processing under the supplied token.
     * @param id successfully delivered outbox row
     * @param claimToken current worker token proving ownership
     * @param now absolute delivery acknowledgement instant
     * @return one if the current claim was acknowledged, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'DELIVERED', o.deliveredAt = :now,
                o.claimedBy = null, o.claimedUntil = null, o.claimToken = null
            where o.id = :id and o.status = 'PROCESSING' and o.claimToken = :claimToken
            """)
    int markDeliveredFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);
}
