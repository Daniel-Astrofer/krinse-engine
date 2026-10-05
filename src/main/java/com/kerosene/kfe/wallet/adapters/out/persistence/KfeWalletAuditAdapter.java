package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;

import java.util.Map;
import java.util.UUID;

/** Adapts wallet audit events to the shared KFE audit log service. */
@Component
public final class KfeWalletAuditAdapter implements WalletAuditPort {
    /** Shared audit writer used by wallet workflows. */
    private final KfeAuditLogService audit;

    /** Creates the adapter with the audit log writer. */
    public KfeWalletAuditAdapter(KfeAuditLogService audit) {
        this.audit = audit;
    }

    /** Records the event as a wallet-scoped audit entry with no payment or transaction binding. */
    @Override
    public void record(String eventType, UUID walletId, Map<String, String> payload) {
        audit.record(eventType, null, walletId, null, null, payload);
    }
}
