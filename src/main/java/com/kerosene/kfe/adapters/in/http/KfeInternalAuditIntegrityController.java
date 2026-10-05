package com.kerosene.kfe.adapters.in.http;

import com.kerosene.common.financial.operations.FinancialAuditIntegrityPort;
import com.kerosene.kfe.adapters.out.integration.audit.KfeFinancialAuditIntegrityAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated internal endpoint exposing the current financial audit chain root. */
@RestController
@RequestMapping("/internal/kfe/audit-integrity")
public class KfeInternalAuditIntegrityController {

    /** Adapter that reads the current root from the shared financial audit subsystem. */
    private final KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter;

    public KfeInternalAuditIntegrityController(KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter) {
        this.auditIntegrityAdapter = auditIntegrityAdapter;
    }

    /**
     * Returns the current audit-chain root.
     *
     * @return current audit root and its integrity metadata
     */
    @GetMapping("/root")
    public FinancialAuditIntegrityPort.AuditRoot root() {
        return auditIntegrityAdapter.currentRoot();
    }
}
