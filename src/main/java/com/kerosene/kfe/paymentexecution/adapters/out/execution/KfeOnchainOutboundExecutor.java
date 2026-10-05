package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Executes prepared Bitcoin broadcasts outside database transactions with claim fencing. */
@Service
public class KfeOnchainOutboundExecutor implements KfeRailExecution, ExternalExecutionPort {

    /** Persists preparation and post-broadcast evidence in short transactions. */
    private final KfeExecutionTransactionHelper transactionHelper;
    /** Bitcoin Core gateway for preparing and broadcasting the immutable transaction. */
    private final KfeOnchainPaymentGateway onchainPaymentGateway;
    /** Durable encrypted storage that reuses the exact prepared transaction on retry. */
    private final KfePreparedExecutionService preparedExecutionService;
    /** Worker lease port used to renew and fence ownership before broadcast. */
    private final ExecutionClaimPort claims;

    /** Creates the executor with transaction, Bitcoin Core, prepared-payload, and claim ports. */
    public KfeOnchainOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("bitcoinCorePsbtKfeOnchainPaymentGateway")
            KfeOnchainPaymentGateway onchainPaymentGateway,
            KfePreparedExecutionService preparedExecutionService,
            ExecutionClaimPort claims) {
        this.transactionHelper = transactionHelper;
        this.onchainPaymentGateway = onchainPaymentGateway;
        this.preparedExecutionService = preparedExecutionService;
        this.claims = java.util.Objects.requireNonNull(claims);
    }

    /** Matches only the durable Bitcoin on-chain outbound operation name. */
    @Override
    public boolean supports(String operation) {
        return "ONCHAIN_OUTBOUND".equals(operation);
    }

    /** Adapts typed worker input and classifies uncertain RPC outcomes as requiring reconciliation. */
    @Override
    public ExternalExecutionResult execute(ExecutionClaim claim, ExecutionPreparation preparation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        java.util.Objects.requireNonNull(claim, "execution claim is required");
        java.util.Objects.requireNonNull(preparation, "execution preparation is required");
        var legacyPreparation = new KfeExecutionTransactionHelper.PreparationResult(
                true, preparation.operation(), preparation.transactionId(), preparation.userId(),
                preparation.sourceWalletLabel(), preparation.sourceWalletId(), preparation.externalReference(),
                preparation.amountSats(), preparation.networkFeeSats(), preparation.memo(),
                preparation.idempotencyKey(), preparation.quorumProposalHash(),
                preparation.feeRateSatsPerVbyte(), preparation.feeTargetBlocks(), claim.claimToken());
        try {
            execute(claim.outboxId(), legacyPreparation);
            return ExternalExecutionResult.completed();
        } catch (RuntimeException failure) {
            return classify(failure);
        }
    }

    /** Reuses or persists the prepared transaction, renews the claim, broadcasts, and records evidence. */
    @Override
    public void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        if (prep.externalReference() == null || prep.externalReference().isBlank()) {
            throw new IllegalArgumentException("externalReference must contain the destination address.");
        }

        KfeOnchainPaymentGateway.PreparedOnchainPayment prepared = preparedExecutionService.load(
                        outboxId,
                        prep.transactionId(),
                        prep.claimToken(),
                        "ONCHAIN_OUTBOUND",
                        KfePreparedExecutionService.PayloadType.ONCHAIN,
                        KfeOnchainPaymentGateway.PreparedOnchainPayment.class)
                .map(KfePreparedExecutionService.StoredPayload::payload)
                .orElseGet(() -> {
                    KfeOnchainPaymentGateway.PreparedOnchainPayment created =
                            onchainPaymentGateway.prepareOnchain(
                                    new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                                            prep.userId(),
                                            null,
                                            prep.sourceWalletLabel(),
                                            prep.externalReference(),
                                            prep.amountSats(),
                                            prep.networkFeeSats(),
                                            prep.memo() != null ? prep.memo() : "KFE on-chain outbound",
                                            prep.idempotencyKey(),
                                            prep.quorumProposalHash(),
                                            prep.feeRateSatsPerVbyte(),
                                            prep.feeTargetBlocks()));
                    try {
                        return preparedExecutionService.persistIfAbsent(
                                        outboxId,
                                        prep.transactionId(),
                                        prep.claimToken(),
                                        "ONCHAIN_OUTBOUND",
                                        KfePreparedExecutionService.PayloadType.ONCHAIN,
                                        created,
                                        created.expectedTxid(),
                                        KfeOnchainPaymentGateway.PreparedOnchainPayment.class)
                                .payload();
                    } catch (RuntimeException persistenceFailure) {
                        onchainPaymentGateway.releasePrepared(created);
                        throw persistenceFailure;
                    }
                });

        // Preparation may take longer than the original lease. Never dispatch using expired ownership.
        if (!claims.heartbeat(new ExecutionClaim(outboxId, prep.claimToken()))) {
            throw new KfeExecutionClaimLostException(outboxId);
        }
        final KfeOnchainPaymentGateway.PaymentResult result;
        try {
            result = onchainPaymentGateway.broadcastPrepared(prepared);
        } catch (RuntimeException uncertain) {
            // Once the RPC starts, exception type/message cannot prove that no broadcast happened.
            throw new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                    "Prepared on-chain broadcast outcome requires reconciliation.", prepared.expectedTxid(), null, null);
        }
        try {
            if (result == null || result.txid() == null || !prepared.expectedTxid().equalsIgnoreCase(result.txid())) {
                throw new IllegalStateException("Broadcast result differs from prepared transaction.");
            }
            String providerReference = firstNonBlank(result.txid(), result.providerReference());
            // Broadcast only: reserve stays locked until the confirmation monitor settles.
            transactionHelper.recordOutboundBroadcast(
                    outboxId,
                    prep.transactionId(),
                    prep.claimToken(),
                    onchainPaymentGateway.providerName(),
                    providerReference,
                    result.txid(),
                    result.feeSats(),
                    prep.sourceWalletId(),
                    result.rawPayload());
        } catch (RuntimeException persistenceFailure) {
            // Includes lost claim and local validation/commit failure AFTER the irreversible provider call.
            throw new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                    "On-chain broadcast acknowledgement requires reconciliation.", prepared.expectedTxid(),
                    result == null ? null : result.rawPayload(), null);
        }
    }

    /** Selects the first nonblank transaction reference for idempotent outbox correlation. */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Distinguishes ambiguous broadcast, claim loss, permanent rejection, and retryable failure. */
    private ExternalExecutionResult classify(RuntimeException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean claimLost = false;
        boolean permanent = false;
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous) {
                return ExternalExecutionResult.unknown(ambiguous.providerReference(), ambiguous.rawPayload());
            }
            claimLost |= current instanceof KfeExecutionClaimLostException;
            permanent |= current instanceof IllegalArgumentException || current instanceof UnsupportedOperationException;
        }
        if (claimLost) return ExternalExecutionResult.claimLost();
        if (permanent) return ExternalExecutionResult.finalFailure();
        return ExternalExecutionResult.retryableFailure();
    }
}
