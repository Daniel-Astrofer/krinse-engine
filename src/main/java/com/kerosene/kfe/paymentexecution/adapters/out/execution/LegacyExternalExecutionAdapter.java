package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentInFlightException;
import com.kerosene.kfe.adapters.out.rail.lightning.LndRestLightningClient;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeRailExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** One bridge per existing executor, composed explicitly rather than component-scanned. */
public final class LegacyExternalExecutionAdapter implements ExternalExecutionPort {
    /** Existing rail executor wrapped behind the typed external execution port. */
    private final KfeRailExecution executor;

    /** Creates the bridge for one non-null legacy rail executor. */
    public LegacyExternalExecutionAdapter(KfeRailExecution executor) {
        this.executor = Objects.requireNonNull(executor, "rail executor is required");
    }

    /** Delegates operation support to the wrapped executor. */
    @Override
    public boolean supports(String operation) {
        return executor.supports(operation);
    }

    /** Adapts typed claims and classifies uncertain provider acknowledgements as unknown outcomes. */
    @Override
    public ExternalExecutionResult execute(ExecutionClaim claim, ExecutionPreparation preparation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        Objects.requireNonNull(claim, "execution claim is required");
        Objects.requireNonNull(preparation, "execution preparation is required");
        var legacyPreparation = new KfeExecutionTransactionHelper.PreparationResult(
                true, preparation.operation(), preparation.transactionId(), preparation.userId(),
                preparation.sourceWalletLabel(), preparation.sourceWalletId(), preparation.externalReference(),
                preparation.amountSats(), preparation.networkFeeSats(), preparation.memo(),
                preparation.idempotencyKey(), preparation.quorumProposalHash(),
                preparation.feeRateSatsPerVbyte(), preparation.feeTargetBlocks(), claim.claimToken());
        try {
            // W1 executors turn every ambiguous RPC/ACK failure into a typed uncertainty signal.
            executor.execute(claim.outboxId(), legacyPreparation);
            return ExternalExecutionResult.completed();
        } catch (RuntimeException failure) {
            return classify(failure);
        }
    }

    /** Walks the exception cause chain once to preserve ambiguity and separate claim/failure classes. */
    private ExternalExecutionResult classify(RuntimeException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean claimLost = false;
        boolean permanent = false;
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            // Even a permanent-looking wrapper must not turn an uncertain send into a refund.
            if (current instanceof KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous) {
                return ExternalExecutionResult.unknown(ambiguous.providerReference(), ambiguous.rawPayload());
            }
            if (current instanceof LightningPaymentInFlightException inFlight) {
                return ExternalExecutionResult.unknown(inFlight.providerReference(), inFlight.rawPayload());
            }
            claimLost |= current instanceof KfeExecutionClaimLostException;
            permanent |= current instanceof IllegalArgumentException
                    || current instanceof UnsupportedOperationException
                    || LndRestLightningClient.isPermanentLightningClientError(current.getMessage());
        }
        if (claimLost) {
            return ExternalExecutionResult.claimLost();
        }
        return permanent ? ExternalExecutionResult.finalFailure() : ExternalExecutionResult.retryableFailure();
    }
}
