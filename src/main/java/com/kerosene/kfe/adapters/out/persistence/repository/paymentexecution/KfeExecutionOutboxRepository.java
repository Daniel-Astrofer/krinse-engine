package com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries and atomically claims asynchronous payment execution commands for workers.
 *
 * <p>The locking reads protect transaction state during reconciliation. Candidate queries include
 * due work, recoverable unknown outcomes, and expired worker leases. Claim operations are
 * compare-and-set updates; heartbeat succeeds only for an active matching token and unexpired
 * lease, so update counts signal whether worker ownership remains valid.</p>
 */
@Repository
public interface KfeExecutionOutboxRepository extends JpaRepository<KfeExecutionOutboxEntity, UUID> {

    /** Loads one execution row for mutation while holding a database write lock.
     * @param id outbox row UUID
     * @return locked row, or empty if the row does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from KfeExecutionOutboxEntity o where o.id = :id")
    Optional<KfeExecutionOutboxEntity> findByIdForUpdate(@Param("id") UUID id);

    /** @param transactionId owning financial transaction
     * @return all execution commands associated with that transaction
     */
    List<KfeExecutionOutboxEntity> findByTransactionId(UUID transactionId);

    /**
     * Loads execution rows for a batch of transactions under write locks in stable ID order.
     * @param ids transaction UUIDs whose outbox rows are needed
     * @return matching rows locked in outbox UUID order to reduce inconsistent lock ordering
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from KfeExecutionOutboxEntity o where o.transactionId in :ids order by o.id")
    List<KfeExecutionOutboxEntity> findByTransactionIdInForUpdate(@Param("ids") Collection<UUID> ids);

    /**
     * Finds unknown inbound operations for provider or ledger reconciliation.
     * @param operations operation names eligible for inbound reconciliation
     * @param pageable bounded scan window
     * @return oldest updated unknown operations first
     */
    @Query("""
            select o from KfeExecutionOutboxEntity o
            where o.status = 'UNKNOWN'
              and o.operation in :operations
            order by o.updatedAt asc
            """)
    List<KfeExecutionOutboxEntity> findInboundReconciliationCandidates(
            @Param("operations") Collection<String> operations,
            Pageable pageable);

    /**
     * Selects due commands, due recoverable unknown outcomes, and expired processing leases.
     * @param dueStatuses command states eligible for a retry or first attempt
     * @param recoverableOperations operation names for which unknown outcomes can be reconciled
     * @param now current UTC time used to evaluate attempt and lease deadlines
     * @param pageable worker batch window
     * @return candidates ordered oldest first with UUID as a stable tie-breaker
     */
    @Query("""
            select o from KfeExecutionOutboxEntity o
            where (
                o.status in :dueStatuses
                and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            ) or (
                o.status = 'UNKNOWN'
                and o.operation in :recoverableOperations
                and o.nextAttemptAt is not null and o.nextAttemptAt <= :now
            ) or (
                o.status = 'PROCESSING'
                and (
                    o.leaseExpiresAt is null
                    or o.leaseExpiresAt <= :now
                )
            )
            order by o.createdAt asc, o.id asc
            """)
    List<KfeExecutionOutboxEntity> findTop100ClaimCandidates(
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("recoverableOperations") Collection<String> recoverableOperations,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * Claims one eligible execution command, assigning worker, token, and lease atomically.
     * @param id command row UUID
     * @param dueStatuses retry/initial states eligible for processing
     * @param recoverableOperations operation names allowed to resume from unknown status
     * @param now current UTC instant used by eligibility checks and claim timestamp
     * @param workerId worker taking ownership
     * @param claimToken fresh fencing token required for subsequent heartbeat
     * @param leaseExpiresAt UTC expiry for the acquired lease
     * @return one when the compare-and-set claim succeeds, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeExecutionOutboxEntity o
            set o.status = 'PROCESSING',
                o.claimedBy = :workerId,
                o.claimedAt = :now,
                o.claimToken = :claimToken,
                o.leaseExpiresAt = :leaseExpiresAt,
                o.updatedAt = :now,
                o.rowVersion = o.rowVersion + 1
            where o.id = :id
              and (
                (
                    o.status in :dueStatuses
                    and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
                ) or (
                    o.status = 'UNKNOWN'
                    and o.operation in :recoverableOperations
                    and o.nextAttemptAt is not null and o.nextAttemptAt <= :now
                ) or (
                    o.status = 'PROCESSING'
                    and (
                        o.leaseExpiresAt is null
                        or o.leaseExpiresAt <= :now
                    )
                )
              )
            """)
    int claimDue(
            @Param("id") UUID id,
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("recoverableOperations") Collection<String> recoverableOperations,
            @Param("now") LocalDateTime now,
            @Param("workerId") String workerId,
            @Param("claimToken") UUID claimToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /**
     * Claims a due pending or retryable row for immediate synchronous-on-submit processing.
     *
     * <p>The restricted source states keep this fast path from taking over work already claimed by
     * an asynchronous worker.</p>
     *
     * @param id command row UUID
     * @param now UTC claim time and retry eligibility reference
     * @param workerId identity assigned to the synchronous claimant
     * @param claimToken fresh fencing token for later updates
     * @param leaseExpiresAt UTC lease deadline
     * @return one if immediate claim succeeded, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeExecutionOutboxEntity o
            set o.status = 'PROCESSING',
                o.claimedBy = :workerId,
                o.claimedAt = :now,
                o.claimToken = :claimToken,
                o.leaseExpiresAt = :leaseExpiresAt,
                o.updatedAt = :now,
                o.rowVersion = o.rowVersion + 1
            where o.id = :id
              and o.status in ('PENDING', 'FAILED_RETRYABLE')
              and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            """)
    int claimImmediate(
            @Param("id") UUID id,
            @Param("now") LocalDateTime now,
            @Param("workerId") String workerId,
            @Param("claimToken") UUID claimToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /**
     * Extends an active processing lease only for its current unexpired fencing token.
     * @param id command row UUID
     * @param claimToken token assigned to the current claimant
     * @param now current UTC instant; an expired claim cannot be renewed
     * @param leaseExpiresAt new UTC lease expiry
     * @return one when the matching live lease was renewed, otherwise zero
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeExecutionOutboxEntity o
            set o.leaseExpiresAt = :leaseExpiresAt,
                o.updatedAt = :now,
                o.rowVersion = o.rowVersion + 1
            where o.id = :id
              and o.status = 'PROCESSING'
              and o.claimToken = :claimToken
              and o.leaseExpiresAt > :now
            """)
    /**
     * Extends an active processing lease only for its current unexpired fencing token.
     * @param id command row UUID
     * @param claimToken token assigned to the current claimant
     * @param now current UTC instant; an expired claim cannot be renewed
     * @param leaseExpiresAt new UTC lease expiry
     * @return one when the matching live lease was renewed, otherwise zero
     */
    int heartbeat(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
}
