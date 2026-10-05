package com.kerosene.kfe.ledger.adapters.out.statement;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

/** Payment Execution projection adapter backed by the extracted Statement boundary. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerPaymentStatementAdapter implements PaymentStatementPort {
    /** Resolves the persisted transaction that will be rendered into the statement payload. */
    private final KfeTransactionRepository transactions;
    /** Builds the stable participant-facing transaction fields from the current transaction state. */
    private final KfeResponseMapper mapper;
    /** Persists the user statement under the surrounding financial transaction. */
    private final KfeStatementService statements;

    /**
     * Creates the payment-statement adapter using existing transaction lookup, display mapping,
     * and statement-retention behavior.
     *
     * @param transactions repository for execution transactions
     * @param mapper participant display-payload mapper
     * @param statements transactional statement writer
     */
    public LedgerPaymentStatementAdapter(KfeTransactionRepository transactions,
            KfeResponseMapper mapper, KfeStatementService statements) {
        this.transactions = transactions;
        this.mapper = mapper;
        this.statements = statements;
    }

    /**
     * Builds and records a statement from the latest transaction, adding the optional memo and a
     * cancellation marker when those values are present in the command.
     *
     * @param command validated statement identity and user-visible memo/cancellation data
     * @throws IllegalArgumentException if the referenced KFE transaction no longer exists
     */
    @Override
    public void record(RecordPaymentStatementCommand command) {
        var transaction = transactions.findById(command.executionId().value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        var payload = new LinkedHashMap<String, Object>(mapper.buildDisplayPayload(transaction, command.userId()));
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        if (command.cancelled()) {
            payload.put("cancelled", true);
        }
        statements.recordUserStatement(command.userId(), command.walletId(), transaction, payload);
    }
}
