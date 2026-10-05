package com.kerosene.kfe.wallet.adapters.out.approval;

import java.time.Instant;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;

/** Adapts the shared financial approval contract to cold-wallet PSBT step-up authorization. */
@Component
public final class KfeWalletApprovalAdapter implements WalletApprovalPort {
    /** Shared approval port that validates factors against the owner's account policy. */
    private final FinancialTransactionApprovalPort approval;

    /** Creates the adapter with the cross-service approval boundary. */
    public KfeWalletApprovalAdapter(FinancialTransactionApprovalPort approval) {
        this.approval = approval;
    }

    /** Submits nonblank factors as a time-stamped device proof; absence means no step-up factor. */
    @Override
    public void approveColdPsbt(long ownerId, String factor) {
        if (factor != null && !factor.isBlank()) {
            approval.approveColdWalletPsbt(
                    ownerId,
                    new DeviceProof("wallet-cold-signer", factor, "kfe-cold-wallet", Instant.now()));
        }
    }
}
