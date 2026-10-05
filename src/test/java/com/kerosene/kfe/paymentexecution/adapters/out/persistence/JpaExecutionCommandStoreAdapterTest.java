package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaExecutionCommandStoreAdapterTest {

    @Test
    void persistsTheExistingWirePayloadBehindTheApplicationPort() throws Exception {
        KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
        KfeHashService hashService = mock(KfeHashService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        var adapter = new JpaExecutionCommandStoreAdapter(repository, hashService, objectMapper);
        UUID executionId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        when(hashService.sha256(org.mockito.ArgumentMatchers.anyString())).thenReturn("payload-hash");

        UUID outboxId = adapter.enqueue(new ScheduleExternalExecutionCommand(
                new PaymentExecutionId(executionId),
                new IdempotencyKey("checkout-42"),
                42L,
                PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND,
                sourceWalletId,
                null,
                99_000L,
                1_000L,
                101_000L,
                "tb1-destination",
                "memo",
                "proposal-hash",
                12L,
                3));

        ArgumentCaptor<KfeExecutionOutboxEntity> entity = ArgumentCaptor.forClass(KfeExecutionOutboxEntity.class);
        verify(repository).save(entity.capture());
        assertThat(outboxId).isEqualTo(entity.getValue().getId());
        assertThat(entity.getValue().getTransactionId()).isEqualTo(executionId);
        assertThat(entity.getValue().getOperation()).isEqualTo("ONCHAIN_OUTBOUND");
        assertThat(entity.getValue().getPayloadHash()).isEqualTo("payload-hash");
        assertThat(entity.getValue().getNextAttemptAt()).isNotNull();

        JsonNode payload = objectMapper.readTree(entity.getValue().getPayloadJson());
        assertThat(payload.get("transactionId").asText()).isEqualTo(executionId.toString());
        assertThat(payload.get("idempotencyKey").asText()).isEqualTo("checkout-42");
        assertThat(payload.get("sourceWalletId").asText()).isEqualTo(sourceWalletId.toString());
        assertThat(payload.get("feeRateSatsPerVbyte").asLong()).isEqualTo(12L);
        assertThat(payload.get("feeTargetBlocks").asInt()).isEqualTo(3);
    }
}
