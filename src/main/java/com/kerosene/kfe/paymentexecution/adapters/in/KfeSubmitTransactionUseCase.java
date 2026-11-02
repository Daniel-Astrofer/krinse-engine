package com.kerosene.kfe.paymentexecution.adapters.in;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.paymentexecution.application.port.in.PreflightPaymentUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentFundsUseCase;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.RouteLockedPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CreatePaymentIntentUseCase;
import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;

/** Coordinates the legacy KFE submit API with the payment execution application and outbox. */
@Service
public class KfeSubmitTransactionUseCase {

    /** Logger for operational events in the synchronous submit and outbox-dispatch path. */
    private static final Logger log = LoggerFactory.getLogger(KfeSubmitTransactionUseCase.class);
    /** Stable worker identity used when the request thread claims an outbox command immediately. */
    private static final String SYNC_WORKER_ID = "kfe-submit-sync-lightning";

    /** Owner-scoped legacy transaction repository used to reload the committed response. */
    private final KfeTransactionRepository transactionRepository;
    /** Validates and prepares the authorized submission after preflight. */
    private final PreparePaymentSubmissionUseCase prepareSubmission;
    /** Reserves the requested payment amount and related fees atomically. */
    private final ReservePaymentFundsUseCase reservePaymentFunds;
    /** Maps the persisted legacy transaction into its HTTP response shape. */
    private final KfeResponseMapper responseMapper;
    /** Persists submission completion and its participant-facing projection. */
    private final CompletePaymentSubmissionUseCase completeSubmission;
    /** Performs preflight validation and resolves idempotent replays before the write transaction. */
    private final PreflightPaymentUseCase preflight;
    /** Atomically reserves the user's idempotency key for this request fingerprint. */
    private final ReservePaymentIdempotencyUseCase reserveIdempotency;
    /** Records execution lifecycle transitions alongside submission state. */
    private final PaymentExecutionLifecycleUseCase executionLifecycle;
    /** Selects and persists the payment route for the locked execution. */
    private final RouteLockedPaymentUseCase routePayment;
    /** Creates the durable payment intent represented by this legacy transaction. */
    private final CreatePaymentIntentUseCase createPaymentIntent;
    /** Prepares and completes optional links to participant payment requests. */
    private final PaymentRequestLinkUseCase paymentRequestLinks;
    /** Attempts immediate processing of a committed outbox command. */
    private final ExecutionCommandDispatcher commandDispatcher;
    /** Enables post-commit immediate Lightning dispatch in the submit request thread. */
    private final boolean lightningSyncOnSubmit;
    /** Enables post-commit immediate on-chain dispatch in the submit request thread. */
    private final boolean onchainSyncOnSubmit;
    /** Owns the short READ COMMITTED transaction for authorized ledger mutations. */
    private final TransactionTemplate transactionTemplate;

    /** Wires the payment collaborators and creates the submission transaction policy. */
    public KfeSubmitTransactionUseCase(
            KfeTransactionRepository transactionRepository,
            PreparePaymentSubmissionUseCase prepareSubmission,
            ReservePaymentFundsUseCase reservePaymentFunds,
            KfeResponseMapper responseMapper,
            CompletePaymentSubmissionUseCase completeSubmission,
            PreflightPaymentUseCase preflight,
            ReservePaymentIdempotencyUseCase reserveIdempotency,
            PaymentExecutionLifecycleUseCase executionLifecycle,
            RouteLockedPaymentUseCase routePayment,
            CreatePaymentIntentUseCase createPaymentIntent,
            PaymentRequestLinkUseCase paymentRequestLinks,
            ExecutionCommandDispatcher commandDispatcher,
            @Value("${kfe.execution.lightning.sync-on-submit:true}") boolean lightningSyncOnSubmit,
            @Value("${kfe.execution.onchain.sync-on-submit:true}") boolean onchainSyncOnSubmit,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.prepareSubmission = prepareSubmission;
        this.reservePaymentFunds = reservePaymentFunds;
        this.responseMapper = responseMapper;
        this.completeSubmission = completeSubmission;
        this.preflight = preflight;
        this.reserveIdempotency = reserveIdempotency;
        this.executionLifecycle = executionLifecycle;
        this.routePayment = routePayment;
        this.createPaymentIntent = createPaymentIntent;
        this.paymentRequestLinks = paymentRequestLinks;
        this.commandDispatcher = commandDispatcher;
        this.lightningSyncOnSubmit = lightningSyncOnSubmit;
        this.onchainSyncOnSubmit = onchainSyncOnSubmit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Submits a KFE-only transaction.
     *
     * <p>Transactional authorization (remote HTTP to auth server for Device Key / passkey step-up)
     * runs <strong>outside</strong> the DB transaction so Hikari connections are not held during
     * network I/O — holding them was a root cause of multi-minute hangs and pool exhaustion under load.
     *
     * <p>Idempotency reservation and ledger mutation stay in one DB transaction: if reservation fails,
     * no transaction row, balance movement, outbox item, statement, or dashboard side effect should be emitted.
     *
     * <p>Lightning outbound: after commit, the outbox item is claimed and processed <strong>in the
     * request path</strong> (sync-on-submit) so the API returns SETTLED/FAILED instead of leaving
     * the client spinning on EXECUTING. Async worker remains the safety net if sync is disabled
     * or claim races. On-chain also supports optional post-commit immediate dispatch.
     */
    /** Submits a request without a device hash, preserving the legacy two-argument API. */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    /** Runs preflight outside the financial transaction, commits the payment, then optionally drains its outbox. */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        // This boundary owns the commit: joining an outer transaction would dispatch before its real commit.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("KFE transaction submission must start outside an existing transaction.");
        }
        var prepared = preflight.preflight(LegacyPaymentSubmissionMapper.toCommand(userId, request, deviceHash));
        if (prepared.existingPayment().isPresent()) {
            return LegacyPaymentExecutionResultMapper.toLegacyResponse(prepared.existingPayment().orElseThrow());
        }

        final KfeSubmitTransactionRequest authorizedRequest = LegacyPaymentSubmissionMapper.toLegacyRequest(prepared.command());
        final String requestHash = prepared.fingerprint().value();
        SubmissionOutcome outcome = transactionTemplate.execute(
                status -> submitAuthorized(userId, authorizedRequest, requestHash));
        if (outcome == null || outcome.response() == null) {
            throw new IllegalStateException("KFE transaction submission returned no response.");
        }

        if (outcome.lightningOutboxId() != null && lightningSyncOnSubmit) {
            return drainOutboxSync(userId, outcome, "Lightning");
        }
        // Broadcast on-chain in the request path so platform peer inbound can be exposed
        // immediately (recipient history + push) instead of waiting for the async worker.
        if (outcome.onchainOutboxId() != null && onchainSyncOnSubmit) {
            return drainOutboxSync(userId, outcome, "Onchain");
        }
        return outcome.response();
    }

    /** Performs idempotency reservation and every authorized payment mutation in one transaction. */
    private SubmissionOutcome submitAuthorized(
            Long userId,
            KfeSubmitTransactionRequest request,
            String requestHash) {
        var reservation = reserveIdempotency.reserve(new ReservePaymentIdempotencyCommand(userId,
                new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(requestHash)));
        if (!reservation.reserved()) {
            return new SubmissionOutcome(LegacyPaymentExecutionResultMapper.toLegacyResponse(reservation.existingPayment()), null, null, null);
        }

        var paymentRequest = paymentRequestLinks.prepare(new PreparePaymentRequestLinkCommand(userId,
                PaymentRail.valueOf(request.rail().name()), PaymentDirection.valueOf(request.direction().name()),
                request.destinationWalletId(), request.amountSats(), request.paymentRequestPublicId()));

        PaymentExecutionId intentId = createPaymentIntent.create(new CreatePaymentIntentCommand(
                userId, new IdempotencyKey(request.idempotencyKey()), PaymentRail.valueOf(request.rail().name()),
                PaymentDirection.valueOf(request.direction().name()), request.sourceWalletId(),
                request.destinationWalletId(), request.amountSats(), request.externalReference(),
                request.memo(), request.paymentRequestPublicId()));
        KfeTransactionEntity tx = transactionRepository.findByIdAndUserId(intentId.value(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        executionLifecycle.recordCurrentState(
                new PaymentExecutionId(tx.getId()),
                ExecutionStatus.valueOf(tx.getStatus().name()),
                "KFE_TRANSACTION_INTENT",
                null);

        prepareSubmission.prepare(new PreparePaymentSubmissionCommand(userId, intentId,
                new RequestFingerprint(requestHash), request.networkFeeSats(), request.feeRateSatPerVbyte(),
                request.feeTargetBlocks(), request.externalReference(), request.paymentRequestPublicId()));
        var reserved = reservePaymentFunds.reserve(new ReservePaymentFundsCommand(userId, intentId));
        tx.setStatus(KfeTransactionStatus.valueOf(reserved.currentStatus().name()));
        var routed = routePayment.route(new RouteLockedPaymentCommand(userId, intentId,
                request.externalReference(), request.memo(), request.feeRateSatPerVbyte(), request.feeTargetBlocks()));
        tx.setStatus(KfeTransactionStatus.valueOf(routed.transition().currentStatus().name()));
        paymentRequest.ifPresent(link -> paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(userId, link, intentId)));

        KfeTransactionResponse response = LegacyPaymentExecutionResultMapper.toLegacyResponse(
                completeSubmission.complete(new CompletePaymentSubmissionCommand(userId, intentId,
                        new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(requestHash))));
        UUID lightningOutboxId =
                routed.rail() == PaymentRail.LIGHTNING ? routed.immediateDispatchOutboxId() : null;
        UUID onchainOutboxId =
                routed.rail() == PaymentRail.ONCHAIN ? routed.immediateDispatchOutboxId() : null;
        return new SubmissionOutcome(response, tx.getId(), lightningOutboxId, onchainOutboxId);
    }

    /**
     * After the ledger TX commits: claim the outbox item and run provider execution now
     * (Lightning pay or on-chain broadcast). Reloads the transaction for the HTTP response.
     */
    /** Claims committed provider work immediately and reloads the owner-scoped transaction response. */
    private KfeTransactionResponse drainOutboxSync(Long userId, SubmissionOutcome outcome, String railLabel) {
        UUID outboxId = outcome.lightningOutboxId() != null
                ? outcome.lightningOutboxId()
                : outcome.onchainOutboxId();
        UUID transactionId = outcome.transactionId();
        try {
            ExecutionCommandDispatcher.DispatchResult dispatch =
                    commandDispatcher.dispatchImmediately(outboxId, SYNC_WORKER_ID);
            if (dispatch == ExecutionCommandDispatcher.DispatchResult.ALREADY_CLAIMED) {
                log.info(
                        "[KFE Submit] {} outbox already claimed (async worker) outboxId={} txId={}",
                        railLabel,
                        outboxId,
                        transactionId);
            } else {
                log.info(
                        "[KFE Submit] {} sync-on-submit drain starting outboxId={} txId={}",
                        railLabel,
                        outboxId,
                        transactionId);
            }
        } catch (RuntimeException exception) {
            // Processor already marks retryable/final failure in most paths; never fail the HTTP
            // envelope if the intent was recorded — client can poll history.
            log.warn(
                    "[KFE Submit] {} sync drain error outboxId={} txId={}: {}",
                    railLabel,
                    outboxId,
                    transactionId,
                    exception.getMessage());
        }

        if (transactionId == null) {
            return outcome.response();
        }
        return transactionRepository.findByIdAndUserId(transactionId, userId)
                .filter(tx -> transactionId.equals(tx.getId()) && userId.equals(tx.getUserId()))
                .map(responseMapper::toTransactionResponse)
                .orElse(outcome.response());
    }

    /**
     * @param lightningOutboxId non-null when LIGHTNING OUTBOUND should drain sync
     * @param onchainOutboxId non-null when ONCHAIN OUTBOUND should drain sync
     */
    /** Carries the committed response and optional outbox identifiers to post-commit dispatch.
     * @param response response used if a post-dispatch reload cannot be completed
     * @param transactionId committed transaction identity for the owner-scoped reload
     * @param lightningOutboxId optional Lightning command to process immediately
     * @param onchainOutboxId optional on-chain command to process immediately
     */
    private record SubmissionOutcome(
            /** Response to use when no newer committed transaction state can be reloaded. */
            KfeTransactionResponse response,
            /** Persisted transaction identifier used for a safe post-dispatch reload. */
            UUID transactionId,
            /** Outbox command for immediate Lightning processing, when applicable. */
            UUID lightningOutboxId,
            /** Outbox command for immediate on-chain processing, when applicable. */
            UUID onchainOutboxId) {
    }

}
