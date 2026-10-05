package com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads and locks payment requests by owner, public locator, settlement, and lifecycle state.
 *
 * <p>User-scoped queries preserve ownership boundaries; pessimistic variants serialize operations
 * that consume, hide, or cancel a request. Address lookups support matching on-chain receipts to
 * open requests, while invoice hash/string lookups support Lightning settlement observation.</p>
 */
@Repository
public interface KfePaymentRequestRepository extends JpaRepository<KfePaymentRequestEntity, UUID> {

    /** @param id internal payment-request UUID
     * @param userId owning user used to enforce row ownership
     * @return request when both identifier and owner match
     */
    Optional<KfePaymentRequestEntity> findByIdAndUserId(UUID id, Long userId);

    /**
     * Loads an owned request under a pessimistic write lock for a state-changing operation.
     * @param id request UUID
     * @param userId owner required for authorization-scoped mutation
     * @return locked request when it belongs to the user
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from KfePaymentRequestEntity p where p.id = :id and p.userId = :userId")
    Optional<KfePaymentRequestEntity> findByIdAndUserIdForUpdate(
            @Param("id") UUID id, @Param("userId") Long userId);

    /**
     * Loads a request by internal UUID under a write lock when ownership was already checked.
     * @param id request UUID
     * @return locked request if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from KfePaymentRequestEntity p where p.id = :id")
    Optional<KfePaymentRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    /** @param publicId opaque shareable locator exposed outside the service
     * @return request resolved by its public identifier
     */
    Optional<KfePaymentRequestEntity> findByPublicId(String publicId);

    /** @param publicId opaque shareable request identifier
     * @param userId expected owner
     * @return request only when public identifier and owner match
     */
    Optional<KfePaymentRequestEntity> findByPublicIdAndUserId(String publicId, Long userId);

    /** @param paidTransactionId transaction recorded as fulfilling the request
     * @param userId owner of the request
     * @return request fulfilled by that transaction for the specified user
     */
    Optional<KfePaymentRequestEntity> findByPaidTransactionIdAndUserId(UUID paidTransactionId, Long userId);

    /**
     * Locks requests associated with one paid transaction and owner.
     * @param transactionId settled transaction identifier
     * @param userId request owner
     * @return matching requests under write lock for settlement-side updates
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from KfePaymentRequestEntity p where p.paidTransactionId = :transactionId and p.userId = :userId")
    List<KfePaymentRequestEntity> findByPaidTransactionIdAndUserIdForUpdate(
            @Param("transactionId") UUID transactionId, @Param("userId") Long userId);

    /**
     * Locks the request found by public identifier for a state-changing public-link operation.
     * @param publicId opaque public request identifier
     * @return locked request when the public identifier exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from KfePaymentRequestEntity p where p.publicId = :publicId")
    Optional<KfePaymentRequestEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** @param userId owner whose receiving requests are listed
     * @return that user's requests newest first
     */
    List<KfePaymentRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** @param walletId receiving wallet
     * @param status lifecycle status to select
     * @return wallet requests in the requested status, newest first
     */
    List<KfePaymentRequestEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfePaymentRequestStatus status);

    /**
     * Scans one status and rail in oldest-first order, typically for a background worker.
     * @param status lifecycle state to scan
     * @param rail payment rail to filter
     * @param pageable bounded scan window
     * @return matching requests oldest first
     */
    List<KfePaymentRequestEntity> findByStatusAndRailOrderByCreatedAtAsc(
            KfePaymentRequestStatus status,
            KfeRail rail,
            Pageable pageable);

    /**
     * Scans a set of states on one rail in oldest-first order.
     * @param statuses lifecycle states eligible for processing
     * @param rail payment rail to filter
     * @param pageable bounded worker scan window
     * @return matching requests ordered by creation time ascending
     */
    List<KfePaymentRequestEntity> findByStatusInAndRailOrderByCreatedAtAsc(
            List<KfePaymentRequestStatus> statuses,
            KfeRail rail,
            Pageable pageable);

    /** @param paymentHash Lightning invoice payment hash from the settlement observation
     * @return first request whose invoice hash matches case-insensitively
     */
    Optional<KfePaymentRequestEntity> findFirstByPaymentHashIgnoreCase(String paymentHash);

    /** @param paymentRequest BOLT11 invoice string observed on the Lightning rail
     * @return first request whose invoice value matches case-insensitively
     */
    Optional<KfePaymentRequestEntity> findFirstByPaymentRequestIgnoreCase(String paymentRequest);

    /**
     * Finds requests matching an address and rail without locking them.
     *
     * <p>Address comparison is case-insensitive and results are oldest first. Use the locking
     * variant when a matching request will be consumed or transitioned.</p>
     * @param address observed receiving address
     * @param status request state to match, typically {@code OPEN}
     * @param rail receiving rail associated with the observed payment
     * @return requests matching the address, state, and rail
     */
    @Query("""
            select p from KfePaymentRequestEntity p
            where p.status = :status
              and p.rail = :rail
              and lower(p.address) = lower(:address)
            order by p.createdAt asc
            """)
    List<KfePaymentRequestEntity> findOpenByAddressAndRail(
            @Param("address") String address,
            @Param("status") KfePaymentRequestStatus status,
            @Param("rail") KfeRail rail);

    /**
     * Finds and locks requests matching an address, state, rail, and owner.
     *
     * <p>Ordering by UUID provides deterministic lock acquisition when more than one row matches.</p>
     * @param address observed receiving address, matched case-insensitively
     * @param status lifecycle state to match
     * @param rail receiving rail
     * @param userId owner whose matching request rows may be updated
     * @return matching locked requests in UUID order
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from KfePaymentRequestEntity p
            where p.status = :status
              and p.rail = :rail
              and p.userId = :userId
              and lower(p.address) = lower(:address)
            order by p.id
            """)
    List<KfePaymentRequestEntity> findOpenByAddressAndRailForUpdate(
            @Param("address") String address,
            @Param("status") KfePaymentRequestStatus status,
            @Param("rail") KfeRail rail,
            @Param("userId") Long userId);
}
