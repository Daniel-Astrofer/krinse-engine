package com.kerosene.kfe.audit.adapters.in.reporting;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditRootResponse;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditHashRow;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditLogRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeAuditAdminServiceTest {

    private final KfeAuditLogRepository repository = mock(KfeAuditLogRepository.class);
    private final KfeHashService hashService = new KfeHashService();
    private final KfeAuditAdminService service = new KfeAuditAdminService(repository, hashService);

    @Test
    void returnsGenesisRootWhenAuditLogIsEmpty() {
        when(repository.findHashRowsAfterSequence(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        KfeAuditRootResponse root = service.root();

        assertThat(root.eventCount()).isZero();
        assertThat(root.merkleRoot()).isEqualTo("0".repeat(64));
    }

    @Test
    void computesMerkleRootFromEventHashes() {
        KfeAuditLogEntity first = event(1L, "a".repeat(64));
        KfeAuditLogEntity second = event(2L, "b".repeat(64));
        String expected = hashService.sha256(first.getEventHash() + "|" + second.getEventHash());
        when(repository.findHashRowsAfterSequence(org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(hashRow(first), hashRow(second)));
        when(repository.findHashRowsAfterSequence(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(repository.findTopByOrderBySequenceNumberDesc()).thenReturn(Optional.of(second));

        KfeAuditRootResponse root = service.latest().root();

        assertThat(root.eventCount()).isEqualTo(2L);
        assertThat(root.fromSequence()).isEqualTo(1L);
        assertThat(root.toSequence()).isEqualTo(2L);
        assertThat(root.merkleRoot()).isEqualTo(expected);
    }

    private KfeAuditLogEntity event(Long sequence, String hash) {
        KfeAuditLogEntity event = new KfeAuditLogEntity();
        ReflectionTestUtils.setField(event, "sequenceNumber", sequence);
        event.setEventType("KFE_TEST");
        event.setPayloadHash(hash);
        event.setPreviousHash("0".repeat(64));
        event.setEventHash(hash);
        return event;
    }

    private KfeAuditHashRow hashRow(KfeAuditLogEntity event) {
        return new KfeAuditHashRow() {
            @Override
            public Long getSequenceNumber() {
                return event.getSequenceNumber();
            }

            @Override
            public String getEventHash() {
                return event.getEventHash();
            }
        };
    }
}
