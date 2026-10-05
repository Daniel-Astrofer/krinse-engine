package com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads, locks, and scans persisted financial transactions for execution and user-facing history.
 *
 * <p>Owner-scoped methods restrict results to a transaction's user. Participant-visible methods
 * additionally expose inbound internal transfers to the owner of the destination wallet. Pessimistic
 * queries serialize settlement and state transitions, while background scans select confirmation
 * and reorganization work in deterministic or oldest-first order.</p>
 */
@Repository
public interface KfeTransactionRepository extends JpaRepository<KfeTransactionEntity, UUID> {

    /**
     * Serializes inbound settlement attempts sharing one provider reference.
     *
     * <p>Acquire this transaction-scoped advisory lock before locking candidate transaction rows.
     * Consistent ordering prevents replayed settlement checks from deadlocking while checking for
     * duplicate external references across multiple rows.</p>
     * @param providerReference external reference used by the competing inbound settlement attempts
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:providerReference))", nativeQuery = true)
    void acquireInboundProviderReferenceLock(@Param("providerReference") String providerReference);

    /** @param idempotencyKey caller-supplied key identifying a transaction creation retry
     * @return transaction associated with that key, if one exists
     */
    Optional<KfeTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    /** Resolves a lost response only within the authenticated submitter's account. */
    Optional<KfeTransactionEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /** @param destinationWalletId receiving wallet
     * @param provider execution provider identifier
     * @return transactions directed to that wallet through the specified provider
     */
    List<KfeTransactionEntity> findByDestinationWalletIdAndProvider(
            UUID destinationWalletId, String provider);

    /** @param id internal transaction UUID
     * @param userId transaction owner
     * @return transaction only when it belongs to the specified user
     */
    Optional<KfeTransactionEntity> findByIdAndUserId(UUID id, Long userId);

    /**
     * Resolves a transaction visible to its owner or to the recipient of an internal transfer.
     *
     * <p>Recipient visibility applies only when both rail and direction match the supplied internal
     * values and the destination wallet belongs to the requesting user.</p>
     * @param id transaction UUID
     * @param userId requesting user
     * @param internalRail enum value identifying internal transfers
     * @param internalDirection enum value identifying the inbound participant view
     * @return transaction when the requester is a permitted participant
     */
    @Query("""
            select t from KfeTransactionEntity t
            where t.id = :id
              and (
                    t.userId = :userId
                    or (
                        t.rail = :internalRail
                        and t.direction = :internalDirection
                        and t.destinationWalletId in (
                            select destinationWallet.id from KfeWalletEntity destinationWallet
                            where destinationWallet.userId = :userId
                        )
                    )
              )
            """)
    Optional<KfeTransactionEntity> findParticipantVisibleById(
            @Param("id") UUID id,
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection);

    /**
     * Loads a transaction under a write lock for a state-changing operation.
     * @param id transaction UUID to lock
     * @return locked transaction, or empty when absent
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from KfeTransactionEntity t where t.id = :id")
    Optional<KfeTransactionEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Loads and locks a transaction only when it belongs to the specified owner.
     * @param id transaction UUID to lock
     * @param userId required owner scope
     * @return locked owned transaction, or empty when not found or not owned
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from KfeTransactionEntity t where t.id = :id and t.userId = :userId")
    Optional<KfeTransactionEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    /**
     * Locks transactions matching both provider reference and lifecycle state.
     * @param providerReference external provider lookup key
     * @param status transaction state to match
     * @return all matching rows under write locks for settlement reconciliation
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from KfeTransactionEntity t
            where t.providerReference = :providerReference
              and t.status = :status
            """)
    List<KfeTransactionEntity> findByProviderReferenceAndStatusForUpdate(
            @Param("providerReference") String providerReference,
            @Param("status") KfeTransactionStatus status);

    /**
     * Locks transactions matching a provider reference, newest creation first.
     * @param providerReference external reference returned by the provider
     * @return matching transactions under write locks, newest first
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /**
     * Selects outbound transactions with a chain transaction ID that have not reached a confirmation cap.
     * @param rail payment rail used by the transaction
     * @param direction outbound direction value
     * @param statuses lifecycle states still eligible for confirmation monitoring
     * @param maxConfirmations exclusive upper confirmation threshold
     * @param pageable bounded monitoring batch
     * @return oldest-updated transactions awaiting further confirmations
     */
    @Query("""
            select t from KfeTransactionEntity t
            where t.providerReference = :providerReference
            order by t.createdAt desc
            """)
    List<KfeTransactionEntity> findByProviderReferenceForUpdate(
            @Param("providerReference") String providerReference);

    /**
     * Selects chain transactions still below the supplied confirmation threshold.
     * @param rail payment rail used by the transaction
     * @param direction outbound direction value
     * @param statuses lifecycle states still eligible for confirmation monitoring
     * @param maxConfirmations exclusive upper confirmation threshold
     * @param pageable bounded monitoring batch
     * @return oldest-updated transactions awaiting further confirmations
     */
    @Query("""
            select t from KfeTransactionEntity t
            where t.rail = :rail
              and t.direction = :direction
              and t.status in :statuses
              and t.blockchainTxid is not null
              and t.blockchainTxid <> ''
              and t.confirmations < :maxConfirmations
            order by t.updatedAt asc
            """)
    List<KfeTransactionEntity> findOutboundAwaitingConfirmation(
            @Param("rail") KfeRail rail,
            @Param("direction") KfeDirection direction,
            @Param("statuses") Collection<KfeTransactionStatus> statuses,
            @Param("maxConfirmations") int maxConfirmations,
            Pageable pageable);

    /**
     * Selects inbound chain transactions with monitoring enabled for possible reorganization.
     * @param rail payment rail to scan
     * @param direction inbound direction value
     * @param statuses lifecycle states monitored for reorgs
     * @param pageable bounded observer batch
     * @return oldest-updated transactions with a transaction ID still under monitoring
     */
    @Query("""
            select t from KfeTransactionEntity t
            where t.rail = :rail
              and t.direction = :direction
              and t.status in :statuses
              and t.confirmationMonitoringActive = true
              and t.blockchainTxid is not null
              and t.blockchainTxid <> ''
            order by t.updatedAt asc
            """)
    List<KfeTransactionEntity> findInboundUnderReorgMonitoring(
            @Param("rail") KfeRail rail,
            @Param("direction") KfeDirection direction,
            @Param("statuses") Collection<KfeTransactionStatus> statuses,
            Pageable pageable);

    /** @param userId transaction owner
     * @return up to the 25 most recently created transactions for the user
     */
    List<KfeTransactionEntity> findTop25ByUserIdOrderByCreatedAtDesc(Long userId);

    /** @param userId transaction owner
     * @return up to the 200 most recently created transactions for the user
     */
    List<KfeTransactionEntity> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    /** @param userId transaction owner
     * @param pageable caller-selected page and sort constraints
     * @return that user's transactions within the requested page, newest first
     */
    List<KfeTransactionEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Lists transactions visible to an owner or recipient of an internal inbound transfer.
     * @param userId requesting participant
     * @param internalRail rail identifier for internal transfers
     * @param internalDirection inbound direction identifier for recipient visibility
     * @param pageable bounded page of transaction history
     * @return visible transactions ordered newest first with UUID tie-breaking
     */
    @Query("""
            select t from KfeTransactionEntity t
            where t.userId = :userId
               or (
                    t.rail = :internalRail
                    and t.direction = :internalDirection
                    and t.destinationWalletId in (
                        select destinationWallet.id from KfeWalletEntity destinationWallet
                        where destinationWallet.userId = :userId
                    )
               )
            order by t.createdAt desc, t.id desc
            """)
    List<KfeTransactionEntity> findParticipantVisibleByUserId(
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection,
            Pageable pageable);

    /**
     * Lists participant-visible transactions updated after a supplied instant.
     * @param userId requesting participant
     * @param internalRail rail identifier for internal transfers
     * @param internalDirection inbound direction identifier for recipient visibility
     * @param since exclusive lower bound on the transaction's update time
     * @param pageable bounded page of results
     * @return visible rows updated after the cutoff, ordered by creation time and UUID descending
     */
    @Query("""
            select t from KfeTransactionEntity t
            where (
                    t.userId = :userId
                    or (
                        t.rail = :internalRail
                        and t.direction = :internalDirection
                        and t.destinationWalletId in (
                            select destinationWallet.id from KfeWalletEntity destinationWallet
                            where destinationWallet.userId = :userId
                        )
                    )
               )
              and t.updatedAt > :since
            order by t.createdAt desc, t.id desc
            """)
    List<KfeTransactionEntity> findParticipantVisibleByUserIdSince(
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection,
            @Param("since") java.time.LocalDateTime since,
            Pageable pageable);

    /** @param idempotencyKeyPrefix prefix identifying a family of generated/retried request keys
     * @return newest transaction with a matching key prefix, if present
     */
    Optional<KfeTransactionEntity> findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(String idempotencyKeyPrefix);

    /** @param userId transaction owner
     * @param idempotencyKeyPrefix prefix used to group the user's related idempotency keys
     * @return that user's transactions whose key starts with the prefix
     */
    List<KfeTransactionEntity> findByUserIdAndIdempotencyKeyStartingWith(
            Long userId, String idempotencyKeyPrefix);

    /** @param userId transaction owner
     * @param externalReference external payment or reconciliation reference
     * @return user's transactions carrying that exact external reference
     */
    List<KfeTransactionEntity> findByUserIdAndExternalReference(Long userId, String externalReference);

    /** @param blockchainTxid on-chain transaction identifier
     * @param userId transaction owner
     * @return user's transactions associated with the specified chain transaction
     */
    List<KfeTransactionEntity> findByBlockchainTxidAndUserId(String blockchainTxid, Long userId);

    /**
     * Finds transactions involving a wallet and whose state is in the supplied set.
     * @param walletId wallet appearing as source or destination
     * @param statuses lifecycle states included in the result
     * @return matching transactions involving the wallet
     */
    @Query("""
            select t from KfeTransactionEntity t
            where (t.sourceWalletId = :walletId or t.destinationWalletId = :walletId)
              and t.status in :statuses
            """)
    List<KfeTransactionEntity> findByWalletIdAndStatusIn(
            @Param("walletId") UUID walletId,
            @Param("statuses") Collection<KfeTransactionStatus> statuses);
}
