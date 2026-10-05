package com.kerosene.kfe.ledger.adapters.out.statement;

import com.kerosene.kfe.ledger.application.port.out.StatementPort;
import com.kerosene.kfe.ledger.domain.StatementRecord;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility adapter for the existing 24-hour participant projection. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaStatementAdapter implements StatementPort {
    /** Resolves the source transaction whose participant projection is being written. */
    private final KfeTransactionRepository transactions;
    /** Existing statement service that applies retention and uniqueness behavior. */
    private final KfeStatementService statements;

    /**
     * Creates the legacy-compatible adapter. Calls are transaction-bound by the mandatory
     * propagation rule on this component.
     *
     * @param transactions source transaction repository
     * @param statements statement projection writer
     */
    public JpaStatementAdapter(KfeTransactionRepository transactions, KfeStatementService statements) {
        this.transactions = transactions;
        this.statements = statements;
    }

    /**
     * Resolves the ledger record's transaction and forwards its participant identity, wallet, and
     * payload to the established statement workflow.
     *
     * @param record validated ledger statement projection input
     * @throws IllegalArgumentException if the referenced transaction does not exist
     */
    @Override
    public void upsert(StatementRecord record) {
        var transaction = transactions.findById(record.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found"));
        statements.recordUserStatement(record.userId(), record.walletId(), transaction, record.payload());
    }
}
