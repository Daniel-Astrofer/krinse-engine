package com.kerosene.kfe.ledger.adapters.out.persistence;

import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.domain.LedgerPosting;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists ledger postings as append-only balance-movement rows within the caller's transaction.
 * The database uniqueness constraint remains the final race arbiter for concurrent operations.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaLedgerPostingAdapter implements LedgerPostingPort {
    /** Repository for immutable ledger movement rows and idempotency lookups. */
    private final KfeBalanceMovementRepository repository;

    /**
     * Creates the adapter with the append-only movement repository.
     *
     * @param repository repository that persists ledger postings
     */
    public JpaLedgerPostingAdapter(KfeBalanceMovementRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks whether an operation already has a posting of the requested movement type.
     *
     * @param operationId operation id used as the movement transaction reference
     * @param movementType domain movement type
     * @return true when the unique operation/type pair already exists
     */
    @Override
    public boolean exists(java.util.UUID operationId, LedgerMovementType movementType) {
        return repository.existsByTransactionIdAndMovementType(operationId, movementType.name());
    }

    /**
     * Inserts a posting only when its operation/type pair is absent at the pre-check.
     * Database uniqueness still arbitrates races; violations propagate to the application service.
     *
     * @param posting immutable posting to append
     * @return true when inserted, false when a duplicate was already present
     */
    @Override
    public boolean appendIfAbsent(LedgerPosting posting) {
        String type = posting.movementType().name();
        if (repository.existsByTransactionIdAndMovementType(posting.operationId(), type)) {
            return false;
        }
        KfeBalanceMovementEntity entity = new KfeBalanceMovementEntity();
        entity.setTransactionId(posting.operationId());
        entity.setWalletId(posting.walletId());
        entity.setMovementType(type);
        entity.setAmountSats(posting.amountSats());
        entity.setFromBucket(posting.fromBucket() == null ? null : posting.fromBucket().name());
        entity.setToBucket(posting.toBucket() == null ? null : posting.toBucket().name());
        entity.setAsset(posting.asset());
        entity.setReason(posting.reason());
        entity.setCorrelationId(posting.correlationId());
        entity.setCausationId(posting.causationId());
        repository.save(entity);
        return true;
    }
}
