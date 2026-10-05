package com.kerosene.kfe.wallet.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface WalletAuditPort {
    void record(String eventType, UUID walletId, Map<String, String> payload);
}
