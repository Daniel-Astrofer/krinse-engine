package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowEntity;
import com.kerosene.kfe.wallet.adapters.in.KfePsbtWorkflowService;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

/** Bridges the application workflow port to the existing persisted PSBT workflow service. */
@Component
public final class JpaWalletWorkflowAdapter implements WalletWorkflowPort {
    /** Compatibility service that owns workflow persistence and audit behavior. */
    private final KfePsbtWorkflowService workflows;

    /** Creates the adapter using the legacy workflow lifecycle implementation. */
    public JpaWalletWorkflowAdapter(KfePsbtWorkflowService workflows) {
        this.workflows = workflows;
    }

    /** Maps domain outpoints to the compatibility DTO and returns the persisted workflow identity. */
    @Override
    public UUID create(
            long ownerId,
            UUID walletId,
            String psbt,
            String psbtHash,
            long feeSats,
            long amountSats,
            String destinationAddress,
            List<Outpoint> inputs) {
        List<KfeColdWalletPsbtRequest.Input> legacyInputs = inputs.stream()
                .map(input -> new KfeColdWalletPsbtRequest.Input(input.txid(), input.vout()))
                .toList();
        KfePsbtWorkflowEntity workflow = workflows.create(
                ownerId,
                walletId,
                psbt,
                psbtHash,
                feeSats,
                amountSats,
                destinationAddress,
                legacyInputs);
        return workflow.getId();
    }
}
