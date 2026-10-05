package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeDerivationCursorEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeDerivationCursorRepository;

/** Issues monotonically increasing external derivation indexes under a database row lock. */
@Service
public class KfeDerivationCursorService {

    /** Cursor key shared by KFE BIP84 external-address issuance. */
    public static final String KFE_BIP84_EXTERNAL = "KFE_BIP84_EXTERNAL";

    /** Persistence boundary supporting a pessimistic lock on an existing cursor. */
    private final KfeDerivationCursorRepository repository;

    /** Creates the cursor service with its locking repository. */
    public KfeDerivationCursorService(KfeDerivationCursorRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically allocates and persists the next index for a cursor key.
     *
     * <p>The first allocated index is zero; concurrent callers serialize through the repository's
     * row lock. The caller must use a stable cursor key for one derivation sequence.
     */
    @Transactional
    public int nextIndex(String cursorKey) {
        KfeDerivationCursorEntity cursor = repository.findByCursorKeyForUpdate(cursorKey)
                .orElseGet(() -> newCursor(cursorKey));
        int current = cursor.getLastIssuedIndex() != null ? cursor.getLastIssuedIndex() : -1;
        int next = current + 1;
        cursor.setLastIssuedIndex(next);
        repository.save(cursor);
        return next;
    }

    /** Creates an unsaved cursor whose initial last-issued value makes the next index zero. */
    private KfeDerivationCursorEntity newCursor(String cursorKey) {
        KfeDerivationCursorEntity cursor = new KfeDerivationCursorEntity();
        cursor.setCursorKey(cursorKey);
        cursor.setLastIssuedIndex(-1);
        return cursor;
    }
}
