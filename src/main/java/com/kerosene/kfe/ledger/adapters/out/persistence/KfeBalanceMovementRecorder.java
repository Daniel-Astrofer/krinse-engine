package com.kerosene.kfe.ledger.adapters.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;

import java.util.UUID;

/**
 * Persists legacy balance movement rows and handles duplicate races for idempotent credit types.
 * Other integrity violations propagate so callers cannot mistake unrelated failures for duplicates.
 */
@Component
public class KfeBalanceMovementRecorder {

    /** Logger for duplicate-credit skip outcomes, excluding balance payloads. */
    private static final Logger log = LoggerFactory.getLogger(KfeBalanceMovementRecorder.class);

    /** Movement repository enforcing transaction/type idempotency constraints. */
    private final KfeBalanceMovementRepository movementRepository;

    /**
     * Creates the recorder with the movement repository.
     *
     * @param movementRepository persistence adapter for movement rows
     */
    public KfeBalanceMovementRecorder(KfeBalanceMovementRepository movementRepository) {
        this.movementRepository = movementRepository;
    }

    /**
     * Persists one balance movement, prechecking idempotent types and handling their uniqueness race.
     * A duplicate credit returns false; violations for other movement types are rethrown.
     *
     * @param transactionId operation/transaction identity, nullable for uncorrelated movements
     * @param walletId wallet receiving the movement
     * @param movementType persisted movement type name
     * @param amountSats movement amount in satoshis
     * @param fromBucket source bucket name, nullable where the movement has no source
     * @param toBucket destination bucket name, nullable where the movement has no destination
     * @return true if a new row was written; false if skipped as duplicate credit
     * @throws DataIntegrityViolationException for non-idempotent constraint violations
     */
    public boolean record(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        if (transactionId != null
                && KfeLedgerMovementTypes.isIdempotentMovementType(movementType)
                && movementRepository.existsByTransactionIdAndMovementType(transactionId, movementType)) {
            log.debug(
                    "KFE movement already present transactionId={} type={} — skip",
                    transactionId,
                    movementType);
            return false;
        }

        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        try {
            movementRepository.save(movement);
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (transactionId != null && KfeLedgerMovementTypes.isIdempotentMovementType(movementType)) {
                log.info(
                        "KFE movement race lost transactionId={} type={} — treated as idempotent skip",
                        transactionId,
                        movementType);
                return false;
            }
            throw exception;
        }
    }
}
