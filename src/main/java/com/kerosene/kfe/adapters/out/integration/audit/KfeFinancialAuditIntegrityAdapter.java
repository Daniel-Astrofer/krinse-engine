package com.kerosene.kfe.adapters.out.integration.audit;

import org.springframework.stereotype.Component;
import com.kerosene.common.financial.operations.FinancialAuditIntegrityPort;
import com.kerosene.kfe.audit.adapters.in.reporting.KfeAuditAdminService;

/** Bridges the shared audit-integrity port to KFE's legacy audit administration boundary. */
@Component
public class KfeFinancialAuditIntegrityAdapter implements FinancialAuditIntegrityPort {

    /** Legacy audit service retained for integration wiring while signed-root storage is unavailable. */
    private final KfeAuditAdminService auditAdminService;

    /** @param auditAdminService KFE audit administration service available to this adapter */
    public KfeFinancialAuditIntegrityAdapter(KfeAuditAdminService auditAdminService) {
        this.auditAdminService = auditAdminService;
    }

    /**
     * Requests the signed audit root required by the shared integrity contract.
     * The legacy audit store has no signed-root representation, so this adapter fails explicitly.
     *
     * @return never returns with the current legacy audit-store implementation
     * @throws UnsupportedOperationException because signed audit roots are not available here
     */
    @Override
    public AuditRoot currentRoot() {
        throw new UnsupportedOperationException(
                "Signed audit roots are not available from the legacy audit store");
    }
}
