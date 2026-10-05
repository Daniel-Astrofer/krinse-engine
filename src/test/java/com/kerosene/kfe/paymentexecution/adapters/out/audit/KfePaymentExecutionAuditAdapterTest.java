package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePaymentExecutionAuditAdapterTest {

    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfePaymentExecutionAuditAdapter adapter =
            new KfePaymentExecutionAuditAdapter(repository, auditLogService, hashService);

    @Test
    void hashesIdempotencyKeyAndAddsOnlyForensicMetadata() {
        PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity entity = new KfeTransactionEntity();
        entity.setIdempotencyKey("raw-secret-idempotency-key");
        entity.setSourceWalletId(walletId);
        when(repository.findById(id.value())).thenReturn(Optional.of(entity));
        when(hashService.sha256("raw-secret-idempotency-key")).thenReturn("safe-hash");

        adapter.record(
                id,
                "KFE_TRANSACTION_LOCKED",
                ExecutionStatus.QUORUM_SYNC,
                ExecutionStatus.LOCKED,
                Map.of("quorumAckCount", 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(
                eq("KFE_TRANSACTION_LOCKED"),
                eq(id.value()),
                eq(walletId),
                eq(KfeTransactionStatus.QUORUM_SYNC),
                eq(KfeTransactionStatus.LOCKED),
                payload.capture());
        assertThat(payload.getValue().get("transactionId")).isEqualTo(id.value().toString());
        assertThat(payload.getValue().get("idempotencyHash")).isEqualTo("safe-hash");
        assertThat(payload.getValue().get("quorumAckCount")).isEqualTo(3);
        assertThat(payload.getValue().containsValue("raw-secret-idempotency-key")).isFalse();
    }
}
