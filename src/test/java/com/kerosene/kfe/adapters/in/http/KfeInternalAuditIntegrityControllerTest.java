package com.kerosene.kfe.adapters.in.http;

import org.junit.jupiter.api.Test;
import com.kerosene.common.financial.operations.FinancialAuditIntegrityPort;
import com.kerosene.kfe.adapters.out.integration.audit.KfeFinancialAuditIntegrityAdapter;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeInternalAuditIntegrityControllerTest {

    private final KfeFinancialAuditIntegrityAdapter adapter = mock(KfeFinancialAuditIntegrityAdapter.class);
    private final KfeInternalAuditIntegrityController controller =
            new KfeInternalAuditIntegrityController(adapter);

    @Test
    void returnsAuditRootWhenCredentialMatches() {
        when(adapter.currentRoot()).thenReturn(new FinancialAuditIntegrityPort.AuditRoot(
                1,
                "SHA-256",
                "abc123",
                7L,
                1L,
                7L,
                null,
                Instant.parse("2026-06-24T10:15:30Z"),
                "test-signer",
                "test-signature",
                "checkpoint-7"));

        FinancialAuditIntegrityPort.AuditRoot root = controller.root();

        assertEquals("abc123", root.merkleRoot());
        assertEquals(7L, root.eventCount());
        assertEquals(1L, root.fromSequence());
        assertEquals(7L, root.toSequence());
    }
}
