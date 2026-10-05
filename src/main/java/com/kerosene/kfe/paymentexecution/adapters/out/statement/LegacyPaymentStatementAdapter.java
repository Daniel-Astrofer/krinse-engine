package com.kerosene.kfe.paymentexecution.adapters.out.statement;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves the existing statement upsert, display projection and after-commit publication. */
@Transactional(propagation = Propagation.MANDATORY)
@Deprecated(forRemoval = false)
public class LegacyPaymentStatementAdapter implements PaymentStatementPort {

    /** Transaction lookup used to build the canonical participant display projection. */
    private final KfeTransactionRepository repository;
    /** Mapper that constructs response fields suitable for statement display. */
    private final KfeResponseMapper responseMapper;
    /** Statement upsert and after-commit publication service. */
    private final KfeStatementService statementService;

    /** Creates the adapter with transaction, display-mapping, and statement services. */
    public LegacyPaymentStatementAdapter(
            KfeTransactionRepository repository,
            KfeResponseMapper responseMapper,
            KfeStatementService statementService) {
        this.repository = repository;
        this.responseMapper = responseMapper;
        this.statementService = statementService;
    }

    /** Upserts the user statement with display data, optional memo, and cancellation marker. */
    public void record(RecordPaymentStatementCommand command) {
        var tx = repository.findById(command.executionId().value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        Map<String, Object> payload = new LinkedHashMap<>(
                responseMapper.buildDisplayPayload(tx, command.userId()));
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        if (command.cancelled()) {
            payload.put("cancelled", true);
        }
        statementService.recordUserStatement(command.userId(), command.walletId(), tx, payload);
    }
}
