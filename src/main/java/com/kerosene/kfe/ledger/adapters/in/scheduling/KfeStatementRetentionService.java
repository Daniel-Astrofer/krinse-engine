package com.kerosene.kfe.ledger.adapters.in.scheduling;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Deletes expired user statements on a configurable fixed-delay schedule.
 * The delete runs in a transaction and compares expiry against the current UTC wall-clock time.
 */
@Service
public class KfeStatementRetentionService {

    /** Persistence adapter that performs the expiry-bounded bulk delete. */
    private final KfeUserStatementRepository statementRepository;

    /**
     * Creates the retention service with its statement persistence adapter.
     *
     * @param statementRepository repository used to delete expired statement records
     */
    public KfeStatementRetentionService(KfeUserStatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    /** Deletes rows whose expiration timestamp precedes the current UTC time. */
    @Scheduled(fixedDelayString = "${kfe.statement.cleanup-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredStatements() {
        statementRepository.deleteByExpiresAtBefore(LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
}
