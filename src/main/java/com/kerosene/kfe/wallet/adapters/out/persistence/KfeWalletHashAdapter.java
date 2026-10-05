package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;

/** Adapts the shared KFE hash service to wallet PSBT integrity hashing. */
@Component
public final class KfeWalletHashAdapter implements WalletHashPort {
    /** Shared hashing implementation used by audit and financial workflows. */
    private final KfeHashService hash;

    /** Creates the adapter with the shared hash service. */
    public KfeWalletHashAdapter(KfeHashService hash) {
        this.hash = hash;
    }

    /** Returns the shared service's SHA-256 digest for the supplied exact value. */
    @Override
    public String sha256(String value) {
        return hash.sha256(value);
    }
}
