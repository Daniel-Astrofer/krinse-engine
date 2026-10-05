package com.kerosene.kfe.adapters.out.persistence.repository.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeUserStatementEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads and removes user-facing transaction statements by owner, transaction, and expiry. */
@Repository
public interface KfeUserStatementRepository extends JpaRepository<KfeUserStatementEntity, UUID> {

    /**
     * Returns the newest active statements for one user, capped at 25 rows.
     * @param userId owner whose statements are requested
     * @param now current UTC time used to exclude expired statements
     * @return up to 25 unexpired statements ordered newest first by their stable creation time
     */
    List<KfeUserStatementEntity> findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId,
            LocalDateTime now);

    /**
     * Checks whether a particular user already has a statement for a transaction.
     * @param userId statement owner; included because a transaction can be visible to multiple users
     * @param transactionId transaction summarized by the statement
     * @return {@code true} when that owner/transaction pair already exists
     */
    boolean existsByUserIdAndTransactionId(Long userId, UUID transactionId);

    /**
     * Finds one statement using its user-scoped transaction identity.
     * @param userId statement owner
     * @param transactionId summarized transaction
     * @return matching statement, or empty when it has not been created
     */
    Optional<KfeUserStatementEntity> findByUserIdAndTransactionId(Long userId, UUID transactionId);

    /** @deprecated User scope is required because one internal transfer may have statements for
     * multiple users; use {@link #existsByUserIdAndTransactionId(Long, UUID)} instead.
     * @param transactionId transaction identifier shared by any matching statement
     * @return whether any user's statement exists for the transaction
     */
    @Deprecated
    boolean existsByTransactionId(UUID transactionId);

    /** @deprecated This lookup can select the wrong user's view; use
     * {@link #findByUserIdAndTransactionId(Long, UUID)} instead.
     * @param transactionId transaction summarized by any matching statement
     * @return a matching statement for any owner, if present
     */
    @Deprecated
    Optional<KfeUserStatementEntity> findByTransactionId(UUID transactionId);

    /**
     * Deletes statements whose expiration precedes the supplied retention cutoff.
     * @param cutoff UTC instant before which statements are eligible for cleanup
     * @return number of rows removed by the derived delete query
     */
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
