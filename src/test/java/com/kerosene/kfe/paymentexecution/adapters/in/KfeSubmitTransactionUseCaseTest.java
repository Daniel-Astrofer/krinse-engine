package com.kerosene.kfe.paymentexecution.adapters.in;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentPreflightAdapter;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import com.kerosene.kfe.paymentexecution.application.usecase.PreflightPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ValidatePaymentRequestService;
import com.kerosene.kfe.paymentexecution.application.usecase.CreatePaymentIntentService;
import com.kerosene.kfe.paymentexecution.application.usecase.SettleInternalPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentFundsService;
import com.kerosene.kfe.paymentexecution.application.usecase.RouteLockedPaymentService;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandStore;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentRoutingStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyPaymentInitiatedNotificationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.remote.LegacyPaymentVaultIntentAdapter;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentFundsReservationStateAdapter;
import jakarta.persistence.EntityManager;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentIntentStoreAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyInternalPaymentNotificationAdapter;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentSettlementStatePort;
import com.kerosene.kfe.paymentexecution.domain.model.InternalPaymentSettlementSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;
import com.kerosene.common.financial.operations.FinancialTickerPort;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkSnapshot;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentPricingService;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentSubmissionService;
import com.kerosene.kfe.paymentexecution.application.usecase.CompletePaymentSubmissionService;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentSubmissionCompletionAdapter;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentSubmissionStateAdapter;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionTelemetryPort;
import com.kerosene.kfe.paymentexecution.adapters.out.pricing.FinancialPaymentDisplayRatesAdapter;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeVaultMeshIntentService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeSubmitTransactionUseCaseTest {
    private final java.util.Map<UUID, KfeTransactionEntity> created = new java.util.HashMap<>();
    private boolean submissionCommitted;

    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final PaymentPricingPort pricingService = mock(PaymentPricingPort.class);
    private final PaymentNetworkFeeFloorPort networkFeeFloor = mock(PaymentNetworkFeeFloorPort.class);
    private final FinancialTickerPort tickerPort = mock(FinancialTickerPort.class);
    private final PaymentLedgerPort ledgerPort = mock(PaymentLedgerPort.class);
    private final PaymentSettlementGateUseCase settlementGate = mock(PaymentSettlementGateUseCase.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final IdempotencyReservationStore completionIdempotency = mock(IdempotencyReservationStore.class);
    private final PaymentDestinationValidationPort destinationValidation = mock(PaymentDestinationValidationPort.class);
    private final ValidatePaymentRequestService validator = org.mockito.Mockito.spy(new ValidatePaymentRequestService(destinationValidation));
    private final AuthorizePaymentUseCase authorizationUseCase = mock(AuthorizePaymentUseCase.class);
    private final PaymentRequestFingerprintPort fingerprints = mock(PaymentRequestFingerprintPort.class);
    private final GetIdempotentPaymentUseCase getIdempotentPayment = mock(GetIdempotentPaymentUseCase.class);
    private final ReservePaymentIdempotencyUseCase reserveIdempotency = mock(ReservePaymentIdempotencyUseCase.class);
    private final PaymentWalletsUseCase walletResolver = mock(PaymentWalletsUseCase.class);
    private final PaymentCanonicalDestinationPort canonicalDestinations = mock(PaymentCanonicalDestinationPort.class);
    private final PaymentExecutionLifecycleUseCase executionLifecycle = mock(
            PaymentExecutionLifecycleUseCase.class,
            invocation -> {
                if (invocation.getMethod().getName().equals("transition")) {
                    PaymentExecutionId id = invocation.getArgument(0);
                    ExecutionStatus target = invocation.getArgument(1);
                    var tx = created.get(id.value());
                    var previous = ExecutionStatus.valueOf(tx.getStatus().name());
                    tx.setStatus(com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.valueOf(target.name()));
                    return new PaymentExecutionStatusChanged(id, previous, target);
                }
                return null;
            });
    private final ExecutionCommandStore outboxUseCase = mock(ExecutionCommandStore.class);
    private final PaymentStatementPort statementPort = mock(PaymentStatementPort.class);
    private final PaymentFeeSettlementPort feeSettlementPort = mock(PaymentFeeSettlementPort.class);
    private final PaymentRequestLinkUseCase paymentRequestSettlementUseCase = mock(PaymentRequestLinkUseCase.class);
    private final PaymentLiquidityPort liquidityPort = mock(PaymentLiquidityPort.class);
    private final PaymentWalletLookupPort reservationWallets = mock(PaymentWalletLookupPort.class);
    private final ReservePaymentFundsService reservationService = new ReservePaymentFundsService(
            new JpaPaymentFundsReservationStateAdapter(transactionRepository, mock(EntityManager.class)),
            reservationWallets, ledgerPort, liquidityPort, executionLifecycle);
    private final FinancialNotificationPort notificationPort = mock(FinancialNotificationPort.class);
    private final InternalPaymentSettlementStatePort settlementState = mock(InternalPaymentSettlementStatePort.class);
    private final SettleInternalPaymentService settlement = new SettleInternalPaymentService(
            settlementState, ledgerPort, executionLifecycle, feeSettlementPort, statementPort,
            new LegacyInternalPaymentNotificationAdapter(notificationPort));
    private final ExecutionCommandDispatcher commandDispatcher = mock(ExecutionCommandDispatcher.class);
    private final KfeVaultMeshIntentService vaultMeshIntentService = mock(KfeVaultMeshIntentService.class);
    private final RouteLockedPaymentService routing = new RouteLockedPaymentService(
            new JpaPaymentRoutingStateAdapter(transactionRepository, mock(EntityManager.class)), settlement::settle,
            outboxUseCase, executionLifecycle, statementPort, new LegacyPaymentInitiatedNotificationAdapter(notificationPort),
            new LegacyPaymentVaultIntentAdapter(vaultMeshIntentService));
    /** Tracks the transaction boundary only; database atomicity is verified in the PostgreSQL suite. */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    assertThat(definition.getIsolationLevel()).isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                    submissionCommitted = false;
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }

                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {
                    submissionCommitted = true;
                }

                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {
                }
            };

    private final KfeSubmitTransactionUseCase useCase = new KfeSubmitTransactionUseCase(
            transactionRepository,
            new PreparePaymentSubmissionService(new JpaPaymentSubmissionStateAdapter(transactionRepository, mock(EntityManager.class)),
                    walletResolver, new PreparePaymentPricingService(networkFeeFloor, pricingService,
                    new FinancialPaymentDisplayRatesAdapter(tickerPort)),
                    proposal -> hashService.sha256(proposal.canonicalContent()), settlementGate, executionLifecycle,
                    mock(PaymentSubmissionTelemetryPort.class))::prepare,
            reservationService::reserve,
            responseMapper,
            new CompletePaymentSubmissionService(
                    new JpaPaymentSubmissionCompletionAdapter(transactionRepository, mock(EntityManager.class), responseMapper),
                    completionIdempotency, reservationWallets, dashboardPublisher::publishAfterCommit)::complete,
            new PaymentPreflightAdapter(new PreflightPaymentService(walletResolver, canonicalDestinations, validator,
                    fingerprints, getIdempotentPayment, authorizationUseCase)),
            reserveIdempotency,
            executionLifecycle,
            routing::route,
            new CreatePaymentIntentService(new JpaPaymentIntentStoreAdapter(transactionRepository)),
            paymentRequestSettlementUseCase,
            commandDispatcher,
            true,
            true,
            transactionManager
    );

    @BeforeEach
    void retainCreatedIntentForScopedReload() {
        when(reserveIdempotency.reserve(any())).thenReturn(PaymentIdempotencyReservationResult.reservedNew());
        when(completionIdempotency.complete(org.mockito.ArgumentMatchers.anyLong(), any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            KfeTransactionEntity tx = invocation.getArgument(0);
            created.put(tx.getId(), tx);
            return tx;
        }).when(transactionRepository).save(any(KfeTransactionEntity.class));
        org.mockito.Mockito.lenient().when(transactionRepository.findByIdAndUserId(any(), any()))
                .thenAnswer(invocation -> {
                    KfeTransactionEntity tx = created.get(invocation.getArgument(0));
                    return tx != null && tx.getUserId().equals(invocation.getArgument(1))
                            ? Optional.of(tx) : Optional.empty();
                });
        org.mockito.Mockito.lenient().when(transactionRepository.findByIdAndUserIdForUpdate(any(), any()))
                .thenAnswer(invocation -> {
                    KfeTransactionEntity tx = created.get(invocation.getArgument(0));
                    return tx != null && tx.getUserId().equals(invocation.getArgument(1))
                            ? Optional.of(tx) : Optional.empty();
                });
        org.mockito.Mockito.lenient().when(reservationWallets.lockOwnedSource(org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenAnswer(invocation -> Optional.of(new PaymentWalletSnapshot(
                        invocation.getArgument(1), invocation.getArgument(0), true, false, true)));
    }

    private void stubPassingGate() {
        when(settlementGate.requirePass(any(PaymentSettlementGateCommand.class)))
                .thenReturn(new PaymentSettlementGateResult(3, 3));
    }

    @Test
    void submitRejectsAnAmbientTransactionBeforePreflightAuthorizationOrDispatch() throws Exception {
        var connection = mock(java.sql.Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                useCase.submit(123L, outboundRequest())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KFE transaction submission must start outside an existing transaction.");

        verify(connection).rollback();
        verify(connection, never()).commit();
        org.mockito.Mockito.verifyNoInteractions(walletResolver, authorizationUseCase, getIdempotentPayment,
                reserveIdempotency, transactionRepository, commandDispatcher, dashboardPublisher);
    }

    @Test
    void resolvesRecipientOnceBeforeHashAndAuthorizationUsingTheCanonicalWallet() {
        Long userId = 123L;
        UUID destination = UUID.randomUUID();
        KfeSubmitTransactionRequest original = new KfeSubmitTransactionRequest(
                "recipient-reference", KfeRail.INTERNAL, KfeDirection.INTERNAL, UUID.randomUUID(), null,
                5_000L, 0L, "@@ Recipient", null, null, "passkey", null);
        KfeSubmitTransactionRequest canonical = original.withDestinationWalletId(destination);
        var stopAtReservation = new IllegalStateException("reservation unavailable");
        when(walletResolver.resolveDestinationReference(walletCommand(userId, original))).thenReturn(destination);
        when(canonicalDestinations.resolve(submissionCommand(userId, canonical))).thenReturn(new CanonicalPaymentDestination(canonical.externalReference(), canonical.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, canonical))).thenReturn(new RequestFingerprint("canonical-hash"));
        when(reserveIdempotency.reserve(reserveCommand(userId, canonical, "canonical-hash"))).thenThrow(stopAtReservation);

        assertSame(stopAtReservation, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, original)));

        var order = org.mockito.Mockito.inOrder(walletResolver, canonicalDestinations, validator,
                fingerprints, getIdempotentPayment, reserveIdempotency, authorizationUseCase);
        order.verify(walletResolver).resolveDestinationReference(walletCommand(userId, original));
        order.verify(canonicalDestinations).resolve(submissionCommand(userId, canonical));
        order.verify(validator).validate(new ValidatePaymentRequestCommand(canonical.idempotencyKey(), PaymentRail.INTERNAL,
                PaymentDirection.INTERNAL, canonical.amountSats(), canonical.networkFeeSats(), canonical.externalReference()));
        order.verify(fingerprints).fingerprint(submissionCommand(userId, canonical));
        order.verify(getIdempotentPayment).find(replayQuery(userId, canonical, "canonical-hash"));
        order.verify(walletResolver).requireNotSelfPayment(walletCommand(userId, canonical));
        order.verify(authorizationUseCase).authorize(submissionCommand(userId, canonical));
        order.verify(reserveIdempotency).reserve(reserveCommand(userId, canonical, "canonical-hash"));
        verify(walletResolver, org.mockito.Mockito.times(1)).resolveDestinationReference(any());
        verify(walletResolver, never()).resolve(any());
        org.mockito.Mockito.verifyNoInteractions(ledgerPort, outboxUseCase, notificationPort);
    }

    @Test
    void canonicalAddressAndMemoReachAuthorizationWithOriginalFactorsBeforeFinancialReservation() {
        Long userId = 123L;
        var original = new KfeSubmitTransactionRequest("canonical-submit", KfeRail.ONCHAIN, KfeDirection.OUTBOUND,
                UUID.randomUUID(), null, 25_000L, 300L, "original-address", "original memo", " totp ",
                " assertion ", " passphrase ", " app-pin ", " public-id ", 17L, 3, " quote ");
        var canonical = original.withExternalReference(" canonical-address ").withMemo(" canonical memo ");
        var originalCommand = LegacyPaymentSubmissionMapper.toCommand(userId, original, " device-hash ");
        var canonicalCommand = LegacyPaymentSubmissionMapper.toCommand(userId, canonical, " device-hash ");
        when(canonicalDestinations.resolve(originalCommand)).thenReturn(
                new CanonicalPaymentDestination(canonical.externalReference(), canonical.memo()));
        when(fingerprints.fingerprint(canonicalCommand)).thenReturn(new RequestFingerprint("canonical-hash"));
        var stop = new IllegalStateException("reservation unavailable");
        when(reserveIdempotency.reserve(reserveCommand(userId, canonical, "canonical-hash"))).thenThrow(stop);

        assertSame(stop, assertThrows(IllegalStateException.class,
                () -> useCase.submit(userId, original, " device-hash ")));

        var order = org.mockito.Mockito.inOrder(canonicalDestinations, destinationValidation, fingerprints,
                getIdempotentPayment, authorizationUseCase, reserveIdempotency);
        order.verify(canonicalDestinations).resolve(originalCommand);
        order.verify(destinationValidation).validate(PaymentRail.ONCHAIN, canonical.externalReference());
        order.verify(fingerprints).fingerprint(canonicalCommand);
        order.verify(getIdempotentPayment).find(replayQuery(userId, canonical, "canonical-hash"));
        order.verify(authorizationUseCase).authorize(canonicalCommand);
        order.verify(reserveIdempotency).reserve(reserveCommand(userId, canonical, "canonical-hash"));
        org.mockito.Mockito.verifyNoInteractions(transactionRepository, paymentRequestSettlementUseCase, ledgerPort,
                liquidityPort, outboxUseCase, commandDispatcher, completionIdempotency, dashboardPublisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preflightValidationOrAuthorizationFailureCannotReserveIdempotencyOrCreateFinancialEffects(boolean authorizationFails) {
        Long userId = 123L;
        var request = outboundRequest();
        var command = submissionCommand(userId, request);
        when(canonicalDestinations.resolve(command)).thenReturn(new CanonicalPaymentDestination(
                request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(command)).thenReturn(new RequestFingerprint("request-hash"));
        var failure = new IllegalStateException("preflight denied");
        if (authorizationFails) {
            org.mockito.Mockito.doThrow(failure).when(authorizationUseCase).authorize(command);
        } else {
            org.mockito.Mockito.doThrow(failure).when(destinationValidation).validate(PaymentRail.ONCHAIN, request.externalReference());
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request)));

        org.mockito.Mockito.verifyNoInteractions(reserveIdempotency, transactionRepository, paymentRequestSettlementUseCase,
                ledgerPort, liquidityPort, outboxUseCase, commandDispatcher, completionIdempotency, dashboardPublisher);
        assertThat(submissionCommitted).isFalse();
        if (!authorizationFails) { org.mockito.Mockito.verifyNoInteractions(fingerprints, getIdempotentPayment, authorizationUseCase); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void walletOrGateRejectionCannotReserveOrDispatchFunds(boolean rejectWallet) {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        var rejection = new IllegalArgumentException("payment rejected");
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(null);
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("request-hash"));
        if (rejectWallet) {
            when(walletResolver.resolve(walletCommand(userId, request))).thenThrow(rejection);
        } else {
            when(walletResolver.resolve(walletCommand(userId, request))).thenReturn(new PaymentWalletSelection(
                    new PaymentWalletSnapshot(request.sourceWalletId(), userId, true, false, true), null));
            when(pricingService.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, 1_000L))
                    .thenReturn(new PaymentPricingQuote(100_000L, 100_000L, 1_000L, 900L, 101_900L, 3));
            when(hashService.sha256(anyString())).thenReturn("proposal");
            when(settlementGate.requirePass(any())).thenThrow(rejection);
        }

        assertSame(rejection, assertThrows(IllegalArgumentException.class, () -> useCase.submit(userId, request)));

        org.mockito.Mockito.verifyNoInteractions(ledgerPort, liquidityPort, outboxUseCase, statementPort,
                notificationPort, commandDispatcher, dashboardPublisher, settlementState);
        org.mockito.Mockito.verifyNoInteractions(completionIdempotency);
        verify(paymentRequestSettlementUseCase, never()).complete(any());
        if (rejectWallet) {
            org.mockito.Mockito.verifyNoInteractions(pricingService, settlementGate);
        }
    }

    @Test
    void requestAcceptanceFailureStopsBeforeIntentWalletSelectionOrFinancialEffects() {
        Long userId = 123L;
        var request = new KfeSubmitTransactionRequest("rejected-request", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                UUID.randomUUID(), UUID.randomUUID(), 10_000L, 0L, null, null, null, "passkey", null,
                null, "public-id");
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("hash"));
        var failure = new IllegalStateException("KFE payment request is no longer open.");
        when(paymentRequestSettlementUseCase.prepare(any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request)));

        verify(transactionRepository, never()).save(any());
        verify(walletResolver, never()).resolve(any());
        org.mockito.Mockito.verifyNoInteractions(completionIdempotency);
        org.mockito.Mockito.verifyNoInteractions(ledgerPort, liquidityPort, pricingService, settlementGate,
                outboxUseCase, commandDispatcher, notificationPort, dashboardPublisher, executionLifecycle, settlementState);
    }

    @Test
    void liquidityFailureAfterLedgerReservationStopsRoutingAndSubmitCompletion() {
        Long userId = 123L;
        UUID sourceId = UUID.randomUUID();
        var request = new KfeSubmitTransactionRequest("reserve-failure", KfeRail.LIGHTNING, KfeDirection.OUTBOUND,
                sourceId, null, 5_000L, 100L, "fixture-invoice", null, null, "passkey", null);
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(null);
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("hash"));
        when(walletResolver.resolve(walletCommand(userId, request))).thenReturn(new PaymentWalletSelection(
                new PaymentWalletSnapshot(sourceId, userId, true, false, true), null));
        when(pricingService.quote(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 5_000L, 100L))
                .thenReturn(new PaymentPricingQuote(5_000L, 5_000L, 100L, 0L, 5_100L, 0));
        when(hashService.sha256(anyString())).thenReturn("proposal");
        stubPassingGate();
        var failure = new IllegalStateException("liquidity unavailable");
        org.mockito.Mockito.doThrow(failure).when(liquidityPort).reserve(any(), eq(5_100L));

        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request)));

        var order = org.mockito.Mockito.inOrder(settlementGate, reservationWallets, ledgerPort, liquidityPort);
        order.verify(settlementGate).requirePass(any());
        order.verify(reservationWallets).lockOwnedSource(userId, sourceId);
        order.verify(ledgerPort).reserve(any(), eq(sourceId), eq(5_100L));
        order.verify(liquidityPort).reserve(any(), eq(5_100L));
        verify(executionLifecycle, never()).transition(any(), eq(ExecutionStatus.LOCKED), anyString(), any());
        org.mockito.Mockito.verifyNoInteractions(completionIdempotency);
        verify(paymentRequestSettlementUseCase, never()).complete(any());
        org.mockito.Mockito.verifyNoInteractions(outboxUseCase, commandDispatcher, settlementState,
                notificationPort, statementPort, dashboardPublisher);
    }

    @Test
    void existingIdempotencyResponseSkipsAuthorizationAndPaymentRequestLock() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        KfeTransactionResponse existingResponse = replayResponse();
        var existingResult = LegacyPaymentExecutionResultMapper.toResult(existingResponse);

        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint(requestHash));
        when(getIdempotentPayment.find(replayQuery(userId, request, requestHash)))
                .thenReturn(Optional.of(existingResult));

        KfeTransactionResponse response = useCase.submit(userId, request);

        assertThat(response).isEqualTo(LegacyPaymentExecutionResultMapper.toLegacyResponse(
                LegacyPaymentExecutionResultMapper.toResult(existingResponse)));
        verify(getIdempotentPayment).find(replayQuery(userId, request, requestHash));
        org.mockito.Mockito.verifyNoInteractions(reserveIdempotency, completionIdempotency, ledgerPort,
                liquidityPort, outboxUseCase, commandDispatcher, dashboardPublisher);
        verify(authorizationUseCase, never()).authorize(any());
        verify(walletResolver, never()).requireNotSelfPayment(any());
        verify(paymentRequestSettlementUseCase, never()).prepare(any());
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentIdempotencyReservationReturnsCommittedTransactionResponse() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        KfeTransactionResponse existingResponse = replayResponse();
        var existingResult = PaymentIdempotencyReservationResult.replay(LegacyPaymentExecutionResultMapper.toResult(existingResponse));
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint(requestHash));
        when(reserveIdempotency.reserve(reserveCommand(userId, request, requestHash)))
                .thenReturn(existingResult);

        KfeTransactionResponse response = useCase.submit(userId, request);

        assertThat(response).isEqualTo(LegacyPaymentExecutionResultMapper.toLegacyResponse(
                LegacyPaymentExecutionResultMapper.toResult(existingResponse)));
        verify(reserveIdempotency).reserve(reserveCommand(userId, request, requestHash));
        verify(authorizationUseCase).authorize(submissionCommand(userId, request));
        org.mockito.Mockito.verifyNoInteractions(completionIdempotency, ledgerPort, liquidityPort,
                outboxUseCase, commandDispatcher, dashboardPublisher);
        verify(paymentRequestSettlementUseCase, never()).prepare(any());
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedIdempotencyReservationDoesNotCreateTransactionIntent() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));

        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint(requestHash));
        when(reserveIdempotency.reserve(reserveCommand(userId, request, requestHash)))
                .thenThrow(new IllegalStateException("duplicate idempotency reservation"));

        assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request));

        verify(reserveIdempotency).reserve(reserveCommand(userId, request, requestHash));
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2_000L})
    void persistsIntentAndPreparedPricingBeforeTheGateWithTheExistingProposalFormat(long floor) {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        IdempotencyReservation idempotency = reservation(request.idempotencyKey(), "request-hash");
        var sourceWallet = new PaymentWalletSnapshot(request.sourceWalletId(), userId, true, false, true);
        KfeTransactionResponse response = transactionResponse();

        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("request-hash"));
        when(walletResolver.resolve(walletCommand(userId, request))).thenReturn(new PaymentWalletSelection(sourceWallet, null));
        long selectedFee = Math.max(1_000L, floor);
        long totalDebit = 100_900L + selectedFee;
        when(networkFeeFloor.minimumReserve(null, null)).thenReturn(floor);
        when(pricingService.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, selectedFee))
                .thenReturn(new PaymentPricingQuote(100_000L, 100_000L, selectedFee, 900L, totalDebit, 3));
        when(tickerPort.getPrice("usd")).thenReturn(new BigDecimal("61234.567"));
        when(tickerPort.getPrice("eur")).thenReturn(BigDecimal.ZERO);
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        stubPassingGate();
        when(outboxUseCase.enqueue(any())).thenAnswer(invocation -> {
            assertThat(submissionCommitted).isFalse();
            return UUID.randomUUID();
        });
        when(commandDispatcher.dispatchImmediately(any(), anyString())).thenAnswer(invocation -> {
            assertThat(submissionCommitted).isTrue();
            return ExecutionCommandDispatcher.DispatchResult.PROCESSED;
        });
        stubCompletionResponse(response);

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertSame(response, result);
        var transactionCaptor = org.mockito.ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).save(transactionCaptor.capture());
        KfeTransactionEntity transaction = transactionCaptor.getValue();
        assertThat(transaction.getExternalReference())
                .isEqualTo("bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
        assertThat(transaction.getMemo()).isEqualTo("memo");
        assertThat(transaction.getNetworkFeeSats()).isEqualTo(selectedFee);
        assertThat(transaction.getTotalDebitSats()).isEqualTo(totalDebit);
        assertThat(transaction.getPricingPolicyVersion()).isEqualTo(3);
        assertThat(transaction.getDisplayBtcUsd()).isEqualByComparingTo("61234.567");
        assertThat(transaction.getDisplayAmountUsd()).isEqualByComparingTo("61.23");
        assertThat(transaction.getDisplayBtcEur()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(transaction.getDisplayAmountEur()).isNull();
        assertThat(transaction.getDisplayBtcBrl()).isNull();
        assertThat(transaction.getDisplayAmountBrl()).isNull();
        var gateCaptor = org.mockito.ArgumentCaptor.forClass(PaymentSettlementGateCommand.class);
        verify(settlementGate).requirePass(gateCaptor.capture());
        assertThat(gateCaptor.getValue().networkFeeSats()).isEqualTo(selectedFee);
        assertThat(gateCaptor.getValue().totalDebitSats()).isEqualTo(totalDebit);
        verify(hashService).sha256("KFE_TX_PROPOSAL|" + transaction.getId() + "|123|ONCHAIN|OUTBOUND|"
                + request.sourceWalletId() + "|null|100000|100000|" + selectedFee + "|900|" + totalDebit
                + "|bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh|");
        verify(commandDispatcher).dispatchImmediately(any(UUID.class), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owned", "foreign-owner", "foreign-id", "missing"})
    void lightningOutboundDrainsOutboxSynchronouslyAfterCommitAndScopesReload(String reload) {
        Long userId = 123L;
        UUID outboxId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "ln-idemp",
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                sourceWalletId,
                null,
                5_000L,
                100L,
                "lntb50u1p...",
                "ln pay",
                "totp",
                "passkey",
                null);
        IdempotencyReservation idempotency = reservation(request.idempotencyKey(), "ln-hash");
        var sourceWallet = new PaymentWalletSnapshot(sourceWalletId, userId, true, false, true);
        KfeTransactionResponse pendingResponse = transactionResponse();
        KfeTransactionResponse settledResponse = transactionResponse();

        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("ln-hash"));
        when(walletResolver.resolve(walletCommand(userId, request))).thenReturn(new PaymentWalletSelection(sourceWallet, null));
        when(pricingService.quote(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, 5_000L, 100L))
                .thenReturn(new PaymentPricingQuote(5_000L, 5_000L, 100L, 0L, 5_100L, 0));
        when(hashService.sha256(anyString())).thenReturn("ln-proposal");
        stubPassingGate();
        when(outboxUseCase.enqueue(any())).thenReturn(outboxId);
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class)))
                .thenAnswer(invocation -> completionResponse(pendingResponse, invocation.getArgument(0)))
                .thenReturn(settledResponse);
        when(commandDispatcher.dispatchImmediately(eq(outboxId), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(submissionCommitted).isTrue();
                    return ExecutionCommandDispatcher.DispatchResult.PROCESSED;
                });
        when(transactionRepository.findByIdAndUserId(any(UUID.class), eq(userId))).thenAnswer(invocation -> {
            var tx = created.get(invocation.getArgument(0));
            if (submissionCommitted && tx != null) {
                if (reload.equals("missing")) { return Optional.empty(); }
                if (!reload.equals("owned")) {
                    var foreign = new KfeTransactionEntity();
                    foreign.setUserId(reload.equals("foreign-owner") ? 999L : userId);
                    if (reload.equals("foreign-owner")) {
                        org.springframework.test.util.ReflectionTestUtils.setField(foreign, "id", tx.getId());
                    }
                    return Optional.of(foreign);
                }
                tx.setStatus(com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.SETTLED);
            }
            return Optional.ofNullable(tx);
        });

        KfeTransactionResponse result = useCase.submit(userId, request);

        if (reload.equals("owned")) {
            assertSame(settledResponse, result);
        } else {
            assertThat(result).isEqualTo(LegacyPaymentExecutionResultMapper.toLegacyResponse(
                    LegacyPaymentExecutionResultMapper.toResult(pendingResponse)));
            verify(responseMapper).toTransactionResponse(any(KfeTransactionEntity.class));
        }
        verify(commandDispatcher).dispatchImmediately(eq(outboxId), anyString());
        verify(transactionRepository, never()).findById(any());
        verify(liquidityPort).reserve(any(PaymentExecutionId.class), eq(5_100L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settlesAndLinksInternalPaymentRequestInTheSameSubmission(boolean linkFails) {
        Long userId = 123L;
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        String publicId = "public-internal-id";
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "internal-idemp-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                sourceWalletId,
                destinationWalletId,
                10_000L,
                0L,
                null,
                "payment request",
                null,
                "passkey-json",
                null,
                "123456",
                publicId);
        IdempotencyReservation idempotency = reservation(request.idempotencyKey(), "internal-request-hash");
        var paymentRequest = new PreparedPaymentRequestLink(userId, new PaymentRequestLinkSnapshot(
                UUID.randomUUID(), publicId, 456L, destinationWalletId, PaymentRail.INTERNAL,
                true, 10_000L, null, null), 10_000L);
        var sourceWallet = new PaymentWalletSnapshot(sourceWalletId, userId, true, false, true);
        var destinationWallet = new PaymentWalletSnapshot(destinationWalletId, 456L, true, false, true);
        when(reservationWallets.findById(destinationWalletId)).thenReturn(Optional.of(destinationWallet));
        KfeTransactionResponse response = transactionResponse();

        when(walletResolver.resolveDestinationReference(walletCommand(userId, request))).thenReturn(request.destinationWalletId());
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("internal-request-hash"));
        when(paymentRequestSettlementUseCase.prepare(new PreparePaymentRequestLinkCommand(userId,
                PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destinationWalletId, 10_000L, publicId)))
                .thenReturn(Optional.of(paymentRequest));
        when(walletResolver.resolve(walletCommand(userId, request)))
                .thenReturn(new PaymentWalletSelection(sourceWallet, destinationWallet));
        when(settlementState.lockAndLoad(eq(userId), any())).thenAnswer(invocation ->
                new InternalPaymentSettlementSnapshot(invocation.getArgument(1), userId, ExecutionStatus.LOCKED,
                        PaymentRail.INTERNAL, PaymentDirection.INTERNAL, sourceWalletId, destinationWalletId,
                        456L, 10_000L, 9_910L));
        when(pricingService.quote(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, 10_000L, 0L))
                .thenReturn(new PaymentPricingQuote(10_000L, 9_910L, 0L, 90L, 10_000L, 0));
        when(hashService.sha256(anyString())).thenReturn("internal-proposal-hash");
        stubPassingGate();
        stubCompletionResponse(response);

        if (linkFails) {
            var failure = new IllegalStateException("request completion failed");
            org.mockito.Mockito.doAnswer(invocation -> {
                CompletePaymentRequestLinkCommand command = invocation.getArgument(0);
                var persisted = transactionRepository.findByIdAndUserId(command.executionId().value(), userId).orElseThrow();
                assertThat(persisted.getStatus()).isEqualTo(com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.SETTLED);
                throw failure;
            }).when(paymentRequestSettlementUseCase).complete(any());
            assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request)));
            org.mockito.Mockito.verifyNoInteractions(completionIdempotency);
            org.mockito.Mockito.verifyNoInteractions(commandDispatcher, dashboardPublisher, outboxUseCase);
            return;
        }

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertThat(result).isEqualTo(LegacyPaymentExecutionResultMapper.toLegacyResponse(
                LegacyPaymentExecutionResultMapper.toResult(response)));
        var transactionCaptor = org.mockito.ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).save(transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getExternalReference()).isEqualTo(publicId);
        PaymentExecutionId executionId = new PaymentExecutionId(transactionCaptor.getValue().getId());
        var accountingOrder = org.mockito.Mockito.inOrder(ledgerPort, feeSettlementPort, statementPort);
        accountingOrder.verify(ledgerPort).reserve(executionId, sourceWalletId, 10_000L);
        accountingOrder.verify(ledgerPort).settleReservedDebit(executionId, sourceWalletId, 10_000L);
        accountingOrder.verify(ledgerPort).creditAvailable(executionId, destinationWalletId, 9_910L);
        accountingOrder.verify(feeSettlementPort).settleFee(executionId);
        accountingOrder.verify(statementPort).record(new RecordPaymentStatementCommand(
                userId, executionId, sourceWalletId, null, false));
        accountingOrder.verify(statementPort).record(new RecordPaymentStatementCommand(
                456L, executionId, destinationWalletId, null, false));
        assertThat(transactionCaptor.getValue().getStatus()).isEqualTo(com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.SETTLED);
        var settlementOrder = org.mockito.Mockito.inOrder(settlementState, ledgerPort, executionLifecycle,
                feeSettlementPort, statementPort, notificationPort, paymentRequestSettlementUseCase, completionIdempotency);
        settlementOrder.verify(settlementState).lockAndLoad(userId, executionId);
        settlementOrder.verify(ledgerPort).settleReservedDebit(executionId, sourceWalletId, 10_000L);
        settlementOrder.verify(ledgerPort).creditAvailable(executionId, destinationWalletId, 9_910L);
        settlementOrder.verify(executionLifecycle).transition(executionId, ExecutionStatus.SETTLED,
                "KFE_TRANSACTION_SETTLED", java.util.Map.of("rail", "INTERNAL"));
        settlementOrder.verify(feeSettlementPort).settleFee(executionId);
        settlementOrder.verify(statementPort).record(new RecordPaymentStatementCommand(userId, executionId, sourceWalletId, null, false));
        settlementOrder.verify(notificationPort).notifyInternalTransferSent(userId, executionId.value(), sourceWalletId, 10_000L);
        settlementOrder.verify(statementPort).record(new RecordPaymentStatementCommand(456L, executionId, destinationWalletId, null, false));
        settlementOrder.verify(notificationPort).notifyInternalTransferReceived(456L, executionId.value(), destinationWalletId, 9_910L);
        settlementOrder.verify(paymentRequestSettlementUseCase).complete(
                new CompletePaymentRequestLinkCommand(userId, paymentRequest, executionId));
        var reservationCaptor = org.mockito.ArgumentCaptor.forClass(IdempotencyReservation.class);
        settlementOrder.verify(completionIdempotency).complete(eq(userId), reservationCaptor.capture(), eq(ExecutionStatus.SETTLED));
        assertThat(reservationCaptor.getValue().key()).isEqualTo(idempotency.key());
        assertThat(reservationCaptor.getValue().fingerprint()).isEqualTo(idempotency.fingerprint());
        assertThat(reservationCaptor.getValue().completedExecutionId()).isEqualTo(executionId);
        verify(dashboardPublisher).publishAfterCommit(userId);
        verify(dashboardPublisher).publishAfterCommit(456L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCompletionFailureCannotCommitOrStartImmediateProviderDispatch(boolean projectionFails) {
        Long userId = 123L;
        var request = outboundRequest();
        var source = new PaymentWalletSnapshot(request.sourceWalletId(), userId, true, false, true);
        var failure = new IllegalStateException("completion failed");
        when(canonicalDestinations.resolve(submissionCommand(userId, request))).thenReturn(new CanonicalPaymentDestination(request.externalReference(), request.memo()));
        when(fingerprints.fingerprint(submissionCommand(userId, request))).thenReturn(new RequestFingerprint("request-hash"));
        when(walletResolver.resolve(walletCommand(userId, request))).thenReturn(new PaymentWalletSelection(source, null));
        when(pricingService.quote(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 100_000L, 1_000L))
                .thenReturn(new PaymentPricingQuote(100_000L, 100_000L, 1_000L, 900L, 101_900L, 3));
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        when(outboxUseCase.enqueue(any())).thenReturn(UUID.randomUUID());
        stubPassingGate();
        if (projectionFails) {
            when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenThrow(failure);
        } else {
            when(completionIdempotency.complete(eq(userId), any(), eq(ExecutionStatus.EXECUTING))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request)));

        assertThat(submissionCommitted).isFalse();
        org.mockito.Mockito.verifyNoInteractions(commandDispatcher);
        if (!projectionFails) { org.mockito.Mockito.verifyNoInteractions(dashboardPublisher, responseMapper); }
    }

    private SubmitPaymentCommand submissionCommand(Long userId, KfeSubmitTransactionRequest request) {
        return LegacyPaymentSubmissionMapper.toCommand(userId, request, null);
    }

    private ReservePaymentIdempotencyCommand reserveCommand(Long userId, KfeSubmitTransactionRequest request, String hash) {
        return new ReservePaymentIdempotencyCommand(userId, new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(hash));
    }

    private GetIdempotentPaymentQuery replayQuery(Long userId, KfeSubmitTransactionRequest request, String hash) {
        return new GetIdempotentPaymentQuery(userId, new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(hash));
    }

    private ResolvePaymentWalletsCommand walletCommand(Long userId, KfeSubmitTransactionRequest request) {
        return new ResolvePaymentWalletsCommand(userId, PaymentRail.valueOf(request.rail().name()),
                PaymentDirection.valueOf(request.direction().name()), request.sourceWalletId(),
                request.destinationWalletId(), request.externalReference());
    }

    private KfeSubmitTransactionRequest outboundRequest() {
        return new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                100_000L,
                1000L,
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "memo",
                "totp-code-123",
                "passkey-json",
                "passphrase"
        );
    }

    private KfeTransactionResponse transactionResponse() {
        return mock(KfeTransactionResponse.class);
    }

    private KfeTransactionResponse replayResponse() {
        var response = transactionResponse();
        when(response.id()).thenReturn(UUID.randomUUID());
        when(response.status()).thenReturn(com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.SETTLED);
        when(response.rail()).thenReturn(KfeRail.ONCHAIN);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        return response;
    }

    private void stubCompletionResponse(KfeTransactionResponse response) {
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class)))
                .thenAnswer(invocation -> completionResponse(response, invocation.getArgument(0)));
    }

    private KfeTransactionResponse completionResponse(KfeTransactionResponse response, KfeTransactionEntity tx) {
        when(response.id()).thenReturn(tx.getId());
        when(response.status()).thenReturn(tx.getStatus());
        when(response.rail()).thenReturn(tx.getRail());
        when(response.direction()).thenReturn(tx.getDirection());
        return response;
    }

    private IdempotencyReservation reservation(String key, String requestHash) {
        return IdempotencyReservation.pending(
                new IdempotencyKey(key),
                new RequestFingerprint(requestHash));
    }
}
