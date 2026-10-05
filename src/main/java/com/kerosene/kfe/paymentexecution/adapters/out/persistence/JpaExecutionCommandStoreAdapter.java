package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandStore;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL outbox adapter. The business transaction and enqueue share the caller transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaExecutionCommandStoreAdapter implements ExecutionCommandStore {

    private final KfeExecutionOutboxRepository repository;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;

    public JpaExecutionCommandStoreAdapter(
            KfeExecutionOutboxRepository repository,
            KfeHashService hashService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID enqueue(ScheduleExternalExecutionCommand command) {
        String payloadJson = serialize(command);
        String payloadHash = hashService.sha256(payloadJson);
        String operation = command.rail().name() + "_" + command.direction().name();
        for (KfeExecutionOutboxEntity existing : repository.findByTransactionId(command.executionId().value())) {
            if (operation.equals(existing.getOperation()) && payloadHash.equals(existing.getPayloadHash())
                    && !"FAILED_FINAL".equals(existing.getStatus())) {
                return existing.getId();
            }
        }
        KfeExecutionOutboxEntity entity = new KfeExecutionOutboxEntity();
        entity.setTransactionId(command.executionId().value());
        entity.setOperation(operation);
        entity.setPayloadJson(payloadJson);
        entity.setPayloadHash(payloadHash);
        entity.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        repository.save(entity);
        return entity.getId();
    }

    private String serialize(ScheduleExternalExecutionCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", command.executionId().value().toString());
        payload.put("idempotencyKey", command.idempotencyKey().value());
        payload.put("userId", command.userId());
        payload.put("rail", command.rail().name());
        payload.put("direction", command.direction().name());
        payload.put("sourceWalletId", command.sourceWalletId());
        payload.put("destinationWalletId", command.destinationWalletId());
        payload.put("amountSats", command.amountSats());
        payload.put("networkFeeSats", command.networkFeeSats());
        payload.put("totalDebitSats", command.totalDebitSats());
        payload.put("externalReference", command.externalReference());
        payload.put("memo", command.memo());
        payload.put("quorumProposalHash", command.quorumProposalHash());
        if (command.feeRateSatsPerVbyte() != null && command.feeRateSatsPerVbyte() > 0L) {
            payload.put("feeRateSatsPerVbyte", command.feeRateSatsPerVbyte());
        }
        if (command.feeTargetBlocks() != null && command.feeTargetBlocks() > 0) {
            payload.put("feeTargetBlocks", command.feeTargetBlocks());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize KFE outbox payload.", exception);
        }
    }
}
