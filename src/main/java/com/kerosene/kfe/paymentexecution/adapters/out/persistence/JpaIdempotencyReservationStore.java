package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.sql.Connection;
import java.util.UUID;

@Component
public class JpaIdempotencyReservationStore implements IdempotencyReservationStore {

    private final KfeIdempotencyRepository repository;
    private final EntityManager entityManager;

    public JpaIdempotencyReservationStore(KfeIdempotencyRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<IdempotencyReservation> find(long userId, IdempotencyKey key) {
        if (userId <= 0L || key == null) {
            throw new IllegalArgumentException("Idempotency lookup identity is required.");
        }
        // Scalar projection executes a fresh statement instead of reusing a stale managed reservation.
        var rows = entityManager.createQuery("""
                select reservation.id.userId, reservation.id.idempotencyKey,
                       reservation.requestHash, reservation.transactionId, reservation.status
                from KfeIdempotencyEntity reservation
                where reservation.id.userId = :userId and reservation.id.idempotencyKey = :key
                """, Object[].class)
                .setParameter("userId", userId)
                .setParameter("key", key.value())
                .getResultList();
        if (rows.isEmpty()) { return Optional.empty(); }
        if (rows.size() != 1) { throw invalidState(); }
        return Optional.of(toDomain(userId, key, rows.getFirst()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(long userId, IdempotencyReservation reservation) {
        if (userId <= 0L || reservation == null || reservation.key() == null
                || reservation.fingerprint() == null || !reservation.isPending()) {
            throw new IllegalArgumentException("A pending client-scoped idempotency reservation is required.");
        }
        int isolation = entityManager.unwrap(Session.class).doReturningWork(Connection::getTransactionIsolation);
        if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Idempotency reservation requires READ_COMMITTED isolation.");
        }
        // Conflict waits for the competing transaction without aborting this financial transaction.
        // Replay then reads the winner in a separate READ_COMMITTED statement; never merge or overwrite it.
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO financial.transaction_idempotency
                    (user_id, idempotency_key, transaction_id, request_hash, status, created_at, expires_at)
                VALUES (:userId, :key, NULL, :fingerprint, 'PENDING', clock_timestamp() AT TIME ZONE 'UTC', NULL)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """)
                .setParameter("userId", userId)
                .setParameter("key", reservation.key().value())
                .setParameter("fingerprint", reservation.fingerprint().value())
                .executeUpdate();
        if (inserted != 0 && inserted != 1) { throw invalidState(); }
        return inserted == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(long userId, IdempotencyReservation reservation, ExecutionStatus status) {
        if (userId <= 0L || reservation == null || status == null) {
            throw new IllegalArgumentException("Idempotency completion identity and status are required.");
        }
        var executionId = reservation.completedExecutionId();
        var identity = new KfeIdempotencyId(userId, reservation.key().value());
        KfeIdempotencyEntity entity = repository.findByIdForUpdate(identity)
                .orElseThrow(() -> new IllegalStateException("Idempotency reservation is missing."));
        // Query auto-flush preserves this submit's pending reservation before discarding stale managed state.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        if (!identity.equals(entity.getId())
                || !reservation.fingerprint().value().equals(entity.getRequestHash())) {
            throw new IdempotencyKeyConflict();
        }
        if (entity.getTransactionId() != null) {
            if (!executionId.value().equals(entity.getTransactionId()) || !status.name().equals(entity.getStatus())) {
                throw new IdempotencyKeyConflict();
            }
            return false;
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new IdempotencyKeyConflict();
        }
        entity.setTransactionId(executionId.value());
        entity.setStatus(status.name());
        repository.save(entity);
        return true;
    }

    private IdempotencyReservation toDomain(long userId, IdempotencyKey key, Object[] row) {
        if (row == null || row.length != 5 || !Long.valueOf(userId).equals(row[0])
                || !key.value().equals(row[1]) || !(row[2] instanceof String fingerprint)
                || fingerprint.isBlank() || !(row[4] instanceof String status)) {
            throw invalidState();
        }
        PaymentExecutionId executionId;
        if (row[3] == null) {
            if (!"PENDING".equals(status)) { throw invalidState(); }
            executionId = null;
        } else {
            if (!(row[3] instanceof UUID id)) { throw invalidState(); }
            try { ExecutionStatus.valueOf(status); }
            catch (IllegalArgumentException invalidStatus) { throw invalidState(); }
            executionId = new PaymentExecutionId(id);
        }
        return IdempotencyReservation.reconstitute(
                key, new RequestFingerprint(fingerprint), executionId);
    }

    private static IllegalStateException invalidState() {
        return new IllegalStateException("Idempotency reservation state is invalid.");
    }
}
