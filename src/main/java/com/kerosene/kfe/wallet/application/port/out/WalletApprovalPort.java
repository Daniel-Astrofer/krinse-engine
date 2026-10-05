package com.kerosene.kfe.wallet.application.port.out;

public interface WalletApprovalPort {
    void approveColdPsbt(long ownerId, String factor);
}
