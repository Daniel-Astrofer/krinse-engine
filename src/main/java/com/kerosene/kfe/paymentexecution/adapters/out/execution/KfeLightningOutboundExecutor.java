package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentInFlightException;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentOutcome;
import com.kerosene.kfe.adapters.out.rail.lightning.LndRestLightningClient;
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

/** Executes prepared outbound Lightning payments outside database transactions with claim fencing. */
@Service
public class KfeLightningOutboundExecutor implements KfeRailExecution, ExternalExecutionPort {

    /** Transactional preparation/result recorder; provider calls are kept outside its transaction. */
    private final KfeExecutionTransactionHelper transactionHelper;
    /** Lightning provider boundary that dispatches the persisted payment operation. */
    private final LightningPaymentGateway lightningPaymentGateway;
    /** Encrypted payload store that prevents provider operations from being recreated on retry. */
    private final KfePreparedExecutionService preparedExecutionService;
    /** Lease heartbeat and fencing port checked immediately before external dispatch. */
    private final ExecutionClaimPort claims;

    /** Creates the executor with transaction, provider, payload, and claim collaborators. */
    public KfeLightningOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("kfeExternalLightningPaymentGateway")
            LightningPaymentGateway lightningPaymentGateway,
            KfePreparedExecutionService preparedExecutionService,
            ExecutionClaimPort claims) {
        this.transactionHelper = transactionHelper;
        this.lightningPaymentGateway = lightningPaymentGateway;
        this.preparedExecutionService = preparedExecutionService;
        this.claims = java.util.Objects.requireNonNull(claims);
    }

    /** Matches only the durable Lightning outbound operation name. */
    @Override
    public boolean supports(String operation) {
        return "LIGHTNING_OUTBOUND".equals(operation);
    }

    /** Adapts the typed worker claim/result API and classifies provider ambiguity conservatively. */
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

    /** Executes or recovers one prepared provider request and records its terminal evidence. */
    @Override
    public void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        if (prep.externalReference() == null || prep.externalReference().isBlank()) {
            throw new IllegalArgumentException(
                    "externalReference must contain a Lightning destination (invoice / LNURL / address / pubkey).");
        }
        if (!lightningPaymentGateway.isLive()) {
            throw new IllegalStateException(
                    "Lightning payment gateway is not live (" + lightningPaymentGateway.providerName() + ").");
        }

        LightningPaymentGateway.PreparedLightningPayment prepared = preparedExecutionService.load(
                        outboxId,
                        prep.transactionId(),
                        prep.claimToken(),
                        "LIGHTNING_OUTBOUND",
                        KfePreparedExecutionService.PayloadType.LIGHTNING,
                        LightningPaymentGateway.PreparedLightningPayment.class)
                .map(KfePreparedExecutionService.StoredPayload::payload)
                .orElseGet(() -> {
                    LightningPaymentGateway.PreparedLightningPayment created =
                            lightningPaymentGateway.prepareLightning(
                                    new CustodyGateway.LightningPaymentCommand(
                                            prep.userId(),
                                            null,
                                            prep.sourceWalletLabel(),
                                            prep.externalReference(),
                                            prep.amountSats(),
                                            prep.networkFeeSats(),
                                            prep.memo() != null ? prep.memo() : "KFE lightning outbound",
                                            prep.idempotencyKey(),
                                            prep.quorumProposalHash()));
                    return preparedExecutionService.persistIfAbsent(
                                    outboxId,
                                    prep.transactionId(),
                                    prep.claimToken(),
                                    "LIGHTNING_OUTBOUND",
                                    KfePreparedExecutionService.PayloadType.LIGHTNING,
                                    created,
                                    created.executionReference(),
                                    LightningPaymentGateway.PreparedLightningPayment.class)
                            .payload();
                });

        if (!claims.heartbeat(new ExecutionClaim(outboxId, prep.claimToken()))) {
            throw new KfeExecutionClaimLostException(outboxId);
        }
        final CustodyGateway.PaymentResult result;
        try {
            result = lightningPaymentGateway.payPreparedLightning(prepared);
        } catch (RuntimeException uncertain) {
            throw new LightningPaymentInFlightException(
                    "Prepared Lightning payment outcome requires reconciliation.", prepared.executionReference(), null);
        }
        try {
            if (result == null || LightningPaymentOutcome.fromProviderStatus(result.status()) != LightningPaymentOutcome.SUCCEEDED) {
                throw new IllegalStateException("Lightning payment has no terminal success acknowledgement.");
            }
            if (prepared.paymentHash() != null
                    && (result.paymentHash() == null || !prepared.paymentHash().equalsIgnoreCase(result.paymentHash()))) {
                throw new LightningPaymentInFlightException(
                        "Lightning provider returned a different payment hash than the persisted operation.",
                        prepared.paymentHash(),
                        result.rawPayload());
            }

            // Only terminal SUCCEEDED reaches here (gateway throws on fail / in-flight).
            String paymentReference = firstNonBlank(result.paymentHash(), result.providerReference(), result.txid());
            transactionHelper.settleOutboundLightning(
                    outboxId,
                    prep.transactionId(),
                    prep.claimToken(),
                    lightningPaymentGateway.providerName(),
                    result.providerReference(),
                    result.txid(),
                    paymentReference,
                    result.feeSats(),
                    prep.sourceWalletId(),
                    result.rawPayload());
        } catch (RuntimeException persistenceFailure) {
            throw new LightningPaymentInFlightException(
                    "Lightning payment acknowledgement requires reconciliation.", prepared.executionReference(),
                    result == null ? null : result.rawPayload());
        }
    }

    /** Selects the first nonblank payment reference from provider result identifiers. */
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

    /** Preserves unknown outcomes for in-flight acknowledgements and separates claim/permanent failures. */
    private ExternalExecutionResult classify(RuntimeException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean claimLost = false;
        boolean permanent = false;
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof LightningPaymentInFlightException inFlight) {
                return ExternalExecutionResult.unknown(inFlight.providerReference(), inFlight.rawPayload());
            }
            claimLost |= current instanceof KfeExecutionClaimLostException;
            permanent |= current instanceof IllegalArgumentException || current instanceof UnsupportedOperationException
                    || LndRestLightningClient.isPermanentLightningClientError(current.getMessage());
        }
        if (claimLost) return ExternalExecutionResult.claimLost();
        if (permanent) return ExternalExecutionResult.finalFailure();
        return ExternalExecutionResult.retryableFailure();
    }
}
