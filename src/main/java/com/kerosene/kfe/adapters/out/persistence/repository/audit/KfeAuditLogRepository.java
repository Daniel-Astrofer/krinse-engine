package com.kerosene.kfe.adapters.out.persistence.repository.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence queries for appending and verifying the ordered financial audit chain. */
@Repository
public interface KfeAuditLogRepository extends JpaRepository<KfeAuditLogEntity, Long> {

    /**
     * Acquires a transaction-scoped PostgreSQL advisory lock shared by all audit appenders.
     *
     * <p>Call within the transaction that reads the chain head and inserts the next event so
     * concurrent writers cannot derive the same predecessor hash.</p>
     */
    @Query(value = "SELECT pg_advisory_xact_lock( hashtext('GLOBAL_AUDIT_APPENDER') )", nativeQuery = true)
    void lockAuditAppender();

    /** @return latest inserted audit event, or empty when the chain has no entries */
    Optional<KfeAuditLogEntity> findTopByOrderBySequenceNumberDesc();

    /** @return all audit events in ascending append sequence order */
    List<KfeAuditLogEntity> findAllByOrderBySequenceNumberAsc();

    /**
     * Fetches the newest audit events in descending sequence order using a page window.
     * @param pageable offset/size constraints limiting the returned history window
     * @return newest matching events first
     */
    List<KfeAuditLogEntity> findAllByOrderBySequenceNumberDesc(Pageable pageable);

    /**
     * Selects only chain sequence and hash values after a checkpoint for efficient verification.
     * @param afterSequence exclusive sequence checkpoint; rows with greater values are returned
     * @param pageable page window bounding the verification batch
     * @return hash projections ordered from oldest to newest after the checkpoint
     */
    @Query("""
            select e.sequenceNumber as sequenceNumber, e.eventHash as eventHash
            from KfeAuditLogEntity e
            where e.sequenceNumber > :afterSequence
            order by e.sequenceNumber asc
            """)
    List<KfeAuditHashRow> findHashRowsAfterSequence(
            @Param("afterSequence") Long afterSequence,
            Pageable pageable);

    /**
     * Loads the audit history associated with one transaction in original append order.
     * @param transactionId transaction whose audit trail is requested
     * @return matching audit entries from earliest to latest
     */
    List<KfeAuditLogEntity> findByTransactionIdOrderBySequenceNumberAsc(UUID transactionId);
}
