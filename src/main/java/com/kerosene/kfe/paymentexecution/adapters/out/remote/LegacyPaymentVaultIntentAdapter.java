package com.kerosene.kfe.paymentexecution.adapters.out.remote;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentVaultIntentPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeVaultMeshIntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Optional legacy dual-path notification, still performed within the submit transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentVaultIntentAdapter implements PaymentVaultIntentPort {
    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentVaultIntentAdapter.class);

    private final KfeVaultMeshIntentService intents;

    public LegacyPaymentVaultIntentAdapter(KfeVaultMeshIntentService intents) {
        this.intents = intents;
    }

    @Override
    public void notifyOutbound(PaymentExecutionId executionId, PaymentRail rail, PaymentDirection direction,
                               String externalReference, long grossAmountSats) {
        if (!intents.isSubmitOnOutboundEnabled()) {
            return;
        }
        if (direction != PaymentDirection.OUTBOUND) {
            return;
        }
        // Mesh-only on-chain execution is intent-gated by its PSBT signing session.
        if (rail == PaymentRail.ONCHAIN && intents.isMeshOnly()) {
            return;
        }
        try {
            intents.submitOutboundIntent(executionId.value(), externalReference,
                    grossAmountSats, executionId.value().toString());
        } catch (RuntimeException exception) {
            log.warn("vault_mesh_intent_notify_failed txId={} exceptionType={}",
                    executionId.value(), exception.getClass().getSimpleName());
        }
    }
}
