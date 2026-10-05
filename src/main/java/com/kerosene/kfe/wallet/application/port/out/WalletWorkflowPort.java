package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

/** Outbound boundary for persisting and tracking a cold-signing workflow. */
public interface WalletWorkflowPort {
    /**
     * Creates a durable workflow bound to the owner, wallet, PSBT hash, payment, and exact inputs.
     *
     * @return identifier used to retrieve and advance the signing workflow
     */
    UUID create(
            long ownerId,
            UUID walletId,
            String psbt,
            String psbtHash,
            long feeSats,
            long amountSats,
            String destinationAddress,
            List<Outpoint> inputs);
}
