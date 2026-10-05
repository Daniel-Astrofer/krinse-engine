package com.kerosene.kfe.audit.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.kerosene.common.audit.AuditEventType;
import com.kerosene.common.audit.AuditEventPayloadSanitizer;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditLogRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeAuditLogServiceTest {

    private final KfeAuditLogRepository repository = mock(KfeAuditLogRepository.class);
    private final KfeHashService hashService = new KfeHashService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredAuditLogger auditLogger = mock(StructuredAuditLogger.class);
    private final KfeAuditLogService service = new KfeAuditLogService(
            repository,
            hashService,
            objectMapper,
            auditLogger);

    @Test
    void recordsKnownEventWithSanitizedPayloadHashAndStructuredAuditLog() throws Exception {
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        String previousHash = "a".repeat(64);
        KfeAuditLogEntity previous = new KfeAuditLogEntity();
        previous.setEventHash(previousHash);

        when(repository.findTopByOrderBySequenceNumberDesc()).thenReturn(Optional.of(previous));
        when(repository.save(any(KfeAuditLogEntity.class))).thenAnswer(invocation -> {
            KfeAuditLogEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "sequenceNumber", 7L);
            return saved;
        });

        KfeAuditLogEntity saved = service.record(
                "KFE_SETTLEMENT_COMPLETED",
                transactionId,
                walletId,
                null,
                null,
                payload());

        Map<String, Object> expectedPayload = AuditEventPayloadSanitizer.sanitize(payload());
        String expectedPayloadHash = hashService.sha256(objectMapper.writeValueAsString(expectedPayload));
        String expectedEventHash = hashService.sha256(previousHash + "|" + expectedPayloadHash
                + "|KFE_SETTLEMENT_COMPLETED|" + transactionId + "|" + walletId + "|null");

        assertThat(saved.getEventType()).isEqualTo("KFE_SETTLEMENT_COMPLETED");
        assertThat(saved.getPayloadHash()).isEqualTo(expectedPayloadHash);
        assertThat(saved.getPreviousHash()).isEqualTo(previousHash);
        assertThat(saved.getEventHash()).isEqualTo(expectedEventHash);

        verify(repository).lockAuditAppender();
        verify(auditLogger).persisted(
                eq(AuditEventType.KFE_SETTLEMENT_COMPLETED),
                eq(7L),
                eq(saved.getId()),
                eq(transactionId),
                eq(walletId),
                isNull(),
                isNull(),
                eq(expectedPayloadHash),
                eq(expectedEventHash),
                eq(expectedPayload));
    }

    @Test
    void rejectsUnknownEventsBeforePersistingOrLogging() {
        assertThatThrownBy(() -> service.record(
                "KFE_UNKNOWN",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown audit event type");

        verify(repository, never()).lockAuditAppender();
        verify(repository, never()).save(any());
        verify(auditLogger, never()).persisted(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", "secret-token");
        payload.put("reason", "settled for user@example.com");
        return payload;
    }
}
