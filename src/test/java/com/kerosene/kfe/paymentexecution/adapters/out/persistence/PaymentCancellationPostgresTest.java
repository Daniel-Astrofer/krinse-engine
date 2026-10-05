package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSubmissionCompletionAdapter;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionCompletionPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionDashboardPort;
import com.kerosene.kfe.paymentexecution.application.usecase.CompletePaymentSubmissionService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentIdempotencyAdapter;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.usecase.GetIdempotentPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentIdempotencyService;
import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentPricingUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionTelemetryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplaySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentSubmissionService;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentWalletsService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSubmissionPreparationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentWalletsAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.crypto.LegacyPaymentProposalHashAdapter;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.math.BigDecimal;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceEventPublisher;
import com.kerosene.kfe.paymentexecution.adapters.out.settlement.LegacyPaymentGateBalanceAdapter;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSettlementGateAdapter;
import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateBalancePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateSolvencyPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateQuorumPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateLightningPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateEnvironmentPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateTelemetryPort;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentSettlementGateService;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGatePolicy;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementQuorumEvidence;
import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentRoutingAdapter;
import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.RouteLockedPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRoutingStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInitiatedNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentVaultIntentPort;
import com.kerosene.kfe.paymentexecution.application.usecase.RouteLockedPaymentService;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentCancellationHintsService;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentEffectsService;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentRequestUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentCancellationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalInternalPaymentSettlementAdapter;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentExecutionLifecycleAdapter;
import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentRequestLinkService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentRequestLinkAdapter;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentFundsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentFundsService;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentFundsReservationAdapter;
import com.kerosene.kfe.paymentexecution.application.port.in.SettleInternalPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentSettlementStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.paymentexecution.application.usecase.CreatePaymentIntentService;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentExecutionLifecycleService;
import com.kerosene.kfe.paymentexecution.application.usecase.SettleInternalPaymentService;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Real PostgreSQL locks and JPQL, enabled only against a dedicated disposable database.
 * Cancellation and internal-settlement cases use real execution/request/outbox SQL and wallet
 * reads; ledger, fee, liquidity, statement, notification and audit ports are stubs, so these
 * cases do not validate the ledger's own SQL persistence or remote delivery.
 * Gate cases also use real balance SQL and the balance service; movement/audit/remote
 * implementations remain outside this fixture, and no full Flyway migration is executed.
 * Completion cases also use real idempotency SQL and transaction synchronizations; the
 * presentation mapper and dashboard transport are stubs, not end-to-end delivery tests.
 * Reservation/replay cases exercise a fixture submission branch with real reserve, intent,
 * routing/outbox and completion adapters. Preparation/gate are represented by a prepared
 * LOCKED intent, ledger calls are counted stubs, and the full submit facade is not booted.
 * Run with {@code bash scripts/test-payment-cancellation-postgres.sh}.
 */
@EnabledIfEnvironmentVariable(named = "KFE_TEST_POSTGRES_URL", matches = ".+")
@Timeout(30)
class PaymentCancellationPostgresTest {
    private static final String DATABASE_USER = "kfe_cancellation_test";
    private static EntityManagerFactory entityManagerFactory;
    private static EntityManager entityManager;
    private static JpaTransactionManager transactionManager;
    private static KfeExecutionOutboxRepository outboxes;
    private static PaymentCancellationFencePort fence;
    private static PaymentCancellationStatePort cancellationState;
    private static PaymentCancellationQueryPort cancellationQuery;
    private static RelatedPaymentLookupPort relatedPayments;
    private static KfePaymentRequestRepository requests;
    private static PaymentRequestCancellationLockPort requestLock;
    private static PaymentRequestCancellationStatePort requestState;
    private static InternalPaymentSettlementStatePort internalSettlementState;
    private static PaymentIntentStore intentStore;
    private static PaymentWalletLookupPort walletLookup;
    private static ExecutionSourceWalletPort executionSourceWallets;
    private static PaymentFundsReservationStatePort fundsReservationState;
    private static PaymentRequestLinkUseCase paymentRequestLinks;
    private static PaymentExecutionLifecycleUseCase lifecycle;
    private static PaymentRoutingStatePort routingState;
    private static ExecutionCommandStore routingCommands;
    private static PaymentGateBalancePort gateBalance;
    private static KfeBalanceService gateBalanceService;
    private static PaymentSubmissionStatePort submissionState;
    private static KfeTransactionRepository transactions;
    private static IdempotencyReservationStore idempotencyReservations;
    private static ExecutorService executor;
    private static String databaseUrl;

    @BeforeAll
    static void openDisposableDatabase() throws Exception {
        databaseUrl = System.getenv("KFE_TEST_POSTGRES_URL");
        // Hibernate DDL is allowed only on the isolated loopback fixture created by the script.
        assertThat(databaseUrl).matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/kfe_cancellation_test_[a-z0-9]+");
        try (var connection = DriverManager.getConnection(databaseUrl, DATABASE_USER, "");
             var statement = connection.createStatement();
             var marker = statement.executeQuery(
                     "select purpose from public.kfe_test_database_marker where id = 1")) {
            assertThat(marker.next()).isTrue();
            assertThat(marker.getString(1)).isEqualTo("ephemeral-payment-cancellation-test");
        }
        entityManagerFactory = new Configuration()
                .addAnnotatedClass(KfeExecutionOutboxEntity.class)
                .addAnnotatedClass(KfeTransactionEntity.class)
                .addAnnotatedClass(KfePaymentRequestEntity.class)
                .addAnnotatedClass(KfeBalanceEntity.class)
                .addAnnotatedClass(KfeIdempotencyEntity.class)
                // Real wallet rows are used by lookup, reservation and internal-settlement cases.
                .addAnnotatedClass(KfeWalletEntity.class)
                .setProperty("hibernate.connection.url", databaseUrl)
                .setProperty("hibernate.connection.username", DATABASE_USER)
                .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                .setProperty("hibernate.connection.pool_size", "8")
                .setProperty("hibernate.connection.isolation", "2")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.create_namespaces", "true")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        var repositories = new JpaRepositoryFactory(entityManager);
        outboxes = repositories.getRepository(KfeExecutionOutboxRepository.class);
        transactions = repositories.getRepository(KfeTransactionRepository.class);
        var proxy = new ProxyFactory(new JpaPaymentCancellationFenceAdapter(
                outboxes, transactions, entityManager));
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        fence = (PaymentCancellationFencePort) proxy.getProxy();
        var stateProxy = new ProxyFactory(new JpaPaymentCancellationStateAdapter(transactions));
        stateProxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        cancellationState = (PaymentCancellationStatePort) stateProxy.getProxy();
        requests = repositories.getRepository(KfePaymentRequestRepository.class);
        var queryProxy = new ProxyFactory(new JpaPaymentCancellationQueryAdapter(transactions, requests));
        queryProxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        Object queryAdapter = queryProxy.getProxy();
        cancellationQuery = (PaymentCancellationQueryPort) queryAdapter;
        relatedPayments = (RelatedPaymentLookupPort) queryAdapter;
        var requestProxy = new ProxyFactory(new JpaPaymentRequestCancellationLockAdapter(requests, entityManager));
        requestProxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        requestLock = (PaymentRequestCancellationLockPort) requestProxy.getProxy();
        var requestStateProxy = new ProxyFactory(new JpaPaymentRequestCancellationStateAdapter(requests));
        requestStateProxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        requestState = (PaymentRequestCancellationStatePort) requestStateProxy.getProxy();
        internalSettlementState = transactional(new JpaInternalPaymentSettlementStateAdapter(
                transactions, repositories.getRepository(KfeWalletRepository.class), entityManager), InternalPaymentSettlementStatePort.class);
        intentStore = transactional(new JpaPaymentIntentStoreAdapter(transactions), PaymentIntentStore.class);
        walletLookup = transactional(new JpaPaymentWalletLookupAdapter(
                repositories.getRepository(KfeWalletRepository.class), mock(KfeWalletAddressRepository.class), entityManager),
                PaymentWalletLookupPort.class);
        executionSourceWallets = transactional(new JpaExecutionSourceWalletAdapter(entityManager), ExecutionSourceWalletPort.class);
        fundsReservationState = transactional(new JpaPaymentFundsReservationStateAdapter(transactions, entityManager),
                PaymentFundsReservationStatePort.class);
        var linkState = transactional(new JpaPaymentRequestLinkStateAdapter(requests, transactions, entityManager),
                com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort.class);
        paymentRequestLinks = transactional(new TransactionalPaymentRequestLinkAdapter(new PaymentRequestLinkService(
                linkState, walletLookup, java.time.Clock.systemUTC())), PaymentRequestLinkUseCase.class);
        lifecycle = transactional(new TransactionalPaymentExecutionLifecycleAdapter(
                new PaymentExecutionLifecycleService(new JpaPaymentExecutionRepository(transactions),
                        mock(PaymentExecutionAuditPort.class))), PaymentExecutionLifecycleUseCase.class);
        routingState = transactional(new JpaPaymentRoutingStateAdapter(transactions, entityManager), PaymentRoutingStatePort.class);
        routingCommands = transactional(new JpaExecutionCommandStoreAdapter(outboxes, new KfeHashService(), new ObjectMapper()),
                ExecutionCommandStore.class);
        gateBalanceService = new KfeBalanceService(repositories.getRepository(KfeBalanceRepository.class),
                new KfeHashService(), repositories.getRepository(KfeWalletRepository.class), mock(BalanceEventPublisher.class));
        gateBalance = transactional(new LegacyPaymentGateBalanceAdapter(gateBalanceService), PaymentGateBalancePort.class);
        submissionState = transactional(new JpaPaymentSubmissionStateAdapter(transactions, entityManager), PaymentSubmissionStatePort.class);
        idempotencyReservations = transactional(new JpaIdempotencyReservationStore(
                repositories.getRepository(KfeIdempotencyRepository.class), entityManager), IdempotencyReservationStore.class);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void closeDisposableDatabase() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @Test
    void rejectsFenceWithoutAnOuterFinancialTransaction() {
        assertThatThrownBy(() -> fence.fence(List.of(new PaymentExecutionId(UUID.randomUUID()))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void competingWorkersCannotOwnTheSameUnexpiredCommand() throws Exception {
        Fixture fixture = createPayment();
        var contenderPid = new CompletableFuture<Integer>();
        Future<Integer> contender = inTransaction(status -> {
            assertThat(claimImmediate(fixture.commandId())).isEqualTo(1);
            Future<Integer> attempt = startClaim(fixture.commandId(), contenderPid);
            awaitBlocked(contenderPid);
            assertThat(attempt.isDone()).isFalse();
            return attempt;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(commandStatus(fixture.commandId())).isEqualTo("PROCESSING");
    }

    @Test
    void expiredHeartbeatCannotReviveOwnershipAndReclaimFencesTheOldToken() {
        Fixture fixture = createPayment();
        UUID oldToken = UUID.randomUUID(), newToken = UUID.randomUUID();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        inTransaction(status -> {
            assertThat(outboxes.claimImmediate(fixture.commandId(), now.minusMinutes(2), "old-worker", oldToken,
                    now.minusMinutes(1))).isEqualTo(1);
            assertThat(outboxes.heartbeat(fixture.commandId(), oldToken, now, now.plusMinutes(1))).isZero();
            assertThat(outboxes.claimDue(fixture.commandId(), List.of("PENDING", "FAILED_RETRYABLE"),
                    List.of("ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND"), now, "new-worker", newToken,
                    now.plusMinutes(1))).isEqualTo(1);
            assertThat(outboxes.heartbeat(fixture.commandId(), oldToken, now, now.plusMinutes(2))).isZero();
            assertThat(outboxes.heartbeat(fixture.commandId(), newToken, now, now.plusMinutes(2))).isEqualTo(1);
            return null;
        });
        inTransaction(status -> {
            var row = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(row.getClaimToken()).isEqualTo(newToken);
            assertThat(row.getClaimedBy()).isEqualTo("new-worker");
            return null;
        });
    }

    @Test
    void cancellationCannotBeResurrectedByHeartbeat() {
        Fixture fixture = createPayment();
        inTransaction(status -> { fence.fence(List.of(fixture.executionId())); return null; });
        inTransaction(status -> {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            assertThat(outboxes.heartbeat(fixture.commandId(), UUID.randomUUID(), now, now.plusMinutes(1))).isZero();
            return null;
        });
        assertThat(commandStatus(fixture.commandId())).isEqualTo("FAILED_FINAL");
    }

    @Test
    void claimAdapterCommitsOwnershipBeforeReturningAndRollbackDoesNotLeakClaim() {
        Fixture committed = createPayment(), rolledBack = createPayment();
        var port = transactional(new JpaExecutionClaimAdapter(outboxes, 600L),
                com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort.class);
        var claim = port.claimImmediate(committed.commandId(), " adapter-worker ").orElseThrow();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(commandStatus(committed.commandId())).isEqualTo("PROCESSING");
        assertThat(port.heartbeat(claim)).isTrue();
        inTransaction(status -> {
            assertThat(port.claimImmediate(rolledBack.commandId(), "rollback-worker")).isPresent();
            status.setRollbackOnly();
            return null;
        });
        assertThat(commandStatus(rolledBack.commandId())).isEqualTo("PENDING");
    }

    @Test
    void unknownWithoutRetryScheduleIsQuarantinedInBothSelectionAndClaimCompareAndSet() {
        var quarantined = createPayment();
        var due = createPayment();
        var future = createPayment();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        String scope = "UNKNOWN_TEST_" + UUID.randomUUID();
        inTransaction(status -> {
            for (var fixture : List.of(quarantined, due, future)) {
                var row = outboxes.findById(fixture.commandId()).orElseThrow();
                row.setStatus("UNKNOWN");
                row.setOperation(scope);
                row.setNextAttemptAt(fixture == quarantined ? null : fixture == due ? now.minusSeconds(1) : now.plusHours(1));
                row.setClaimToken(null);
            }
            return null;
        });
        inTransaction(status -> {
            var selected = outboxes.findTop100ClaimCandidates(List.of("NO_DUE_STATUS"), List.of(scope), now,
                    org.springframework.data.domain.PageRequest.of(0, 10_000));
            assertThat(selected.stream().filter(row -> scope.equals(row.getOperation())).map(KfeExecutionOutboxEntity::getId))
                    .containsExactly(due.commandId());
            for (var fixture : List.of(quarantined, due, future)) {
                assertThat(outboxes.claimImmediate(fixture.commandId(), now, "immediate", UUID.randomUUID(), now.plusMinutes(1))).isZero();
                assertThat(outboxes.claimDue(fixture.commandId(), List.of("PENDING", "FAILED_RETRYABLE"), List.of(scope), now,
                        "worker", UUID.randomUUID(), now.plusMinutes(1))).isEqualTo(fixture == due ? 1 : 0);
            }
            return null;
        });
        assertThat(commandStatus(quarantined.commandId())).isEqualTo("UNKNOWN");
        assertThat(commandStatus(due.commandId())).isEqualTo("PROCESSING");
        assertThat(commandStatus(future.commandId())).isEqualTo("UNKNOWN");
    }

    @Test
    void unscheduledPendingAndRetryableCommandsAndExpiredLeasesRemainClaimable() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        for (String state : List.of("PENDING", "FAILED_RETRYABLE", "PROCESSING")) {
            var fixture = createPayment();
            inTransaction(status -> {
                var row = outboxes.findById(fixture.commandId()).orElseThrow();
                row.setStatus(state);
                row.setNextAttemptAt(null);
                row.setLeaseExpiresAt(now.minusSeconds(1));
                return null;
            });
            inTransaction(status -> {
                assertThat(outboxes.claimDue(fixture.commandId(), List.of("PENDING", "FAILED_RETRYABLE"),
                        List.of("ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND"), now, "worker", UUID.randomUUID(),
                        now.plusMinutes(1))).isEqualTo(1);
                return null;
            });
        }
    }

    @Test
    void candidateQueryAppliesLimitInSqlAndStableOrderingAcrossPages() {
        String scope = "LIMIT_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        inTransaction(status -> {
            for (int i = 0; i < 103; i++) {
                Fixture fixture = createPayment();
                outboxes.findById(fixture.commandId()).orElseThrow().setStatus(scope);
            }
            return null;
        });
        inTransaction(status -> {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            var first = outboxes.findTop100ClaimCandidates(List.of(scope), List.of(), now,
                    org.springframework.data.domain.PageRequest.of(0, 100));
            var second = outboxes.findTop100ClaimCandidates(List.of(scope), List.of(), now,
                    org.springframework.data.domain.PageRequest.of(1, 100));
            // Other tests' PROCESSING/UNKNOWN rows may exist; filter this scope without mutating them.
            assertThat(first).hasSize(100);
            assertThat(second.stream().map(KfeExecutionOutboxEntity::getId).toList())
                    .doesNotContainAnyElementsOf(first.stream().map(KfeExecutionOutboxEntity::getId).toList());
            assertThat(first.stream().filter(row -> scope.equals(row.getStatus())).count()
                    + second.stream().filter(row -> scope.equals(row.getStatus())).count()).isEqualTo(103L);
            return null;
        });
    }

    @Test
    void relatedPaymentDiscoveryRequiresTheCallersFinancialTransaction() {
        assertThatThrownBy(() -> relatedPayments.findRelated(1L, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void relatedPaymentDiscoveryExcludesCopiedForeignLinksAndIncludesEveryOwnedExecutionOnlyOnce() {
        long ownerUserId = 101L;
        long otherUserId = 102L;
        UUID requestId = createRequest(ownerUserId);
        String publicId = requestId.toString();
        String prefix = "payment-request:" + requestId + ":";
        var expected = inTransaction(status -> {
            var owned = new ArrayList<PaymentExecutionId>();
            var paid = persistQueryPayment(ownerUserId, prefix + "paid", publicId);
            owned.add(new PaymentExecutionId(paid.getId()));
            requests.findById(requestId).orElseThrow().setPaidTransactionId(paid.getId());
            // Exercise both discovery branches beyond small collection limits, including more than 200 rows.
            for (int index = 0; index < 205; index++) {
                var payment = persistQueryPayment(ownerUserId, null, publicId);
                owned.add(new PaymentExecutionId(payment.getId()));
            }
            for (int index = 0; index < 7; index++) {
                var payment = persistQueryPayment(ownerUserId, prefix + "owned-" + index, null);
                owned.add(new PaymentExecutionId(payment.getId()));
            }
            persistQueryPayment(otherUserId, prefix + "foreign-prefix", null);
            persistQueryPayment(otherUserId, null, publicId);
            persistQueryPayment(otherUserId, prefix + "foreign-both", publicId);
            persistQueryPayment(ownerUserId, null, "unrelated-" + UUID.randomUUID());
            return List.copyOf(owned);
        });

        var actual = inTransaction(status -> {
            requestLock.lock(ownerUserId, requestId);
            return relatedPayments.findRelated(ownerUserId, requestId);
        });

        assertThat(actual).hasSize(213).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual.getFirst()).isEqualTo(expected.getFirst());
    }

    @Test
    void relatedPaymentDiscoveryRejectsAPaidTransactionOwnedByAnotherUser() {
        long ownerUserId = 201L;
        UUID requestId = createRequest(ownerUserId);
        inTransaction(status -> {
            var foreignPayment = persistQueryPayment(ownerUserId + 1L, null, requestId.toString());
            requests.findById(requestId).orElseThrow().setPaidTransactionId(foreignPayment.getId());
            return null;
        });

        assertThatThrownBy(() -> inTransaction(status -> {
            requestLock.lock(ownerUserId, requestId);
            return relatedPayments.findRelated(ownerUserId, requestId);
        })).isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void relatedPaymentDiscoveryRejectsAMissingPaidTransaction() {
        long ownerUserId = 301L;
        UUID requestId = createRequest(ownerUserId);
        inTransaction(status -> {
            requests.findById(requestId).orElseThrow().setPaidTransactionId(UUID.randomUUID());
            return null;
        });

        assertThatThrownBy(() -> inTransaction(status -> {
            requestLock.lock(ownerUserId, requestId);
            return relatedPayments.findRelated(ownerUserId, requestId);
        })).isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void relatedPaymentDiscoveryRejectsARequestOwnedByAnotherUser() {
        UUID requestId = createRequest(401L);

        assertThatThrownBy(() -> inTransaction(status -> relatedPayments.findRelated(402L, requestId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE payment request not found.");
    }

    @Test
    void participantVisibleQueryDoesNotRevealAnotherUsersRequestThroughCopiedReferences() {
        long ownerUserId = 501L;
        UUID foreignRequestId = createRequest(ownerUserId + 1L);
        var executionIds = inTransaction(status -> {
            var byPublicId = persistQueryPayment(ownerUserId, null, foreignRequestId.toString());
            var byIdempotency = persistQueryPayment(ownerUserId,
                    "payment-request:" + foreignRequestId + ":linked", null);
            var byPaidId = persistQueryPayment(ownerUserId, null, null);
            requests.findById(foreignRequestId).orElseThrow().setPaidTransactionId(byPaidId.getId());
            return List.of(byPublicId, byIdempotency, byPaidId).stream()
                    .map(payment -> new PaymentExecutionId(payment.getId())).toList();
        });

        for (var executionId : executionIds) {
            var snapshot = cancellationQuery.findParticipantVisible(ownerUserId, executionId).orElseThrow();
            assertThat(snapshot.executionId()).isEqualTo(executionId);
            assertThat(snapshot.ownerUserId()).isEqualTo(ownerUserId);
            assertThat(snapshot.paymentRequest()).isNull();
        }
    }

    @Test
    void internalRecipientCanReadButCannotCancelOrSeeTheSendersRequestMetadata() {
        long senderUserId = 701L;
        long recipientUserId = 702L;
        UUID requestId = createRequest(senderUserId);
        var executionId = inTransaction(status -> {
            var wallet = new KfeWalletEntity();
            wallet.setUserId(recipientUserId);
            wallet.setKind(KfeWalletKind.INTERNAL);
            wallet.setLabel("test-recipient");
            wallet.setQuorumPolicyHash("0".repeat(64));
            entityManager.persist(wallet);
            var payment = persistQueryPayment(senderUserId, null, requestId.toString());
            payment.setRail(KfeRail.INTERNAL);
            payment.setDirection(KfeDirection.INTERNAL);
            payment.setDestinationWalletId(wallet.getId());
            return new PaymentExecutionId(payment.getId());
        });

        var visible = cancellationQuery.findParticipantVisible(recipientUserId, executionId).orElseThrow();
        assertThat(visible.executionId()).isEqualTo(executionId);
        assertThat(visible.ownerUserId()).isEqualTo(senderUserId);
        assertThat(visible.paymentRequest()).isNull();
        var hints = new PaymentCancellationHintsService(cancellationQuery);
        assertThat(hints.hintsFor(recipientUserId, executionId).cancellable()).isFalse();
        assertThat(hints.hintsFor(recipientUserId, executionId).paymentRequestId()).isNull();
        assertThat(hints.hintsFor(senderUserId, executionId).paymentRequestId()).isEqualTo(requestId);
    }

    @Test
    void participantVisibleQueryReturnsOnlyTheOwnedRequestMetadata() {
        long ownerUserId = 601L;
        UUID requestId = createRequest(ownerUserId);
        var executionId = inTransaction(status -> {
            var payment = persistQueryPayment(ownerUserId, "payment-request:" + requestId + ":linked", null);
            payment.setBlockchainTxid("query-test-network-txid");
            return new PaymentExecutionId(payment.getId());
        });

        var snapshot = cancellationQuery.findParticipantVisible(ownerUserId, executionId).orElseThrow();

        assertThat(snapshot.executionId()).isEqualTo(executionId);
        assertThat(snapshot.ownerUserId()).isEqualTo(ownerUserId);
        assertThat(snapshot.status().name()).isEqualTo("INTENT");
        assertThat(snapshot.blockchainTransactionId()).isEqualTo("query-test-network-txid");
        assertThat(snapshot.paymentRequest().id()).isEqualTo(requestId);
        assertThat(snapshot.paymentRequest().userId()).isEqualTo(ownerUserId);
        assertThat(snapshot.paymentRequest().publicId()).isEqualTo(requestId.toString());
        assertThat(snapshot.paymentRequest().status().name()).isEqualTo("OPEN");
        assertThat(cancellationQuery.findParticipantVisible(ownerUserId + 1L, executionId)).isEmpty();
    }

    @Test
    void workerClaimWinsAndCancellationWaitsThenRejects() throws Exception {
        Fixture fixture = createPayment();
        var cancellationPid = new CompletableFuture<Integer>();

        Future<?> cancellation = inTransaction(status -> {
            assertThat(claimImmediate(fixture.commandId())).isEqualTo(1);
            Future<?> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                cancellationPid.complete(backendPid());
                fence.fence(List.of(fixture.executionId()));
                return null;
            }));
            awaitBlocked(cancellationPid);
            assertThat(attempt.isDone()).isFalse();
            return attempt;
        });

        assertThatThrownBy(() -> cancellation.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PaymentCancellationRejected.class);
        assertThat(commandStatus(fixture.commandId())).isEqualTo("PROCESSING");
    }

    @Test
    void cancellationWinsAndBlockedWorkerCannotClaimAfterCommit() throws Exception {
        Fixture fixture = createPayment();
        var workerPid = new CompletableFuture<Integer>();

        Future<Integer> worker = inTransaction(status -> {
            fence.fence(List.of(fixture.executionId()));
            Future<Integer> attempt = startClaim(fixture.commandId(), workerPid);
            awaitBlocked(workerPid);
            assertThat(attempt.isDone()).isFalse();
            return attempt;
        });

        assertThat(worker.get(10, TimeUnit.SECONDS)).isZero();
        inTransaction(status -> {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            assertThat(outboxes.claimDue(fixture.commandId(), List.of("PENDING", "FAILED_RETRYABLE"),
                    List.of("ONCHAIN_OUTBOUND"), now, "async-worker", UUID.randomUUID(), now.plusMinutes(1)))
                    .isZero();
            assertThat(outboxes.findTop100ClaimCandidates(
                    List.of("PENDING", "FAILED_RETRYABLE"), List.of("ONCHAIN_OUTBOUND"), now,
                    org.springframework.data.domain.PageRequest.of(0, 100)))
                    .extracting(KfeExecutionOutboxEntity::getId).doesNotContain(fixture.commandId());
            var command = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(command.getStatus()).isEqualTo("FAILED_FINAL");
            assertThat(command.getLastError()).isEqualTo("USER_CANCELLED");
            return null;
        });
    }

    @Test
    void rollbackAfterFencingRestoresClaimabilityForWaitingWorker() throws Exception {
        Fixture fixture = createPayment();
        var workerPid = new CompletableFuture<Integer>();

        Future<Integer> worker = inTransaction(status -> {
            fence.fence(List.of(fixture.executionId()));
            entityManager.flush();
            Future<Integer> attempt = startClaim(fixture.commandId(), workerPid);
            awaitBlocked(workerPid);
            // A later ledger/statement failure rolls back the same financial transaction.
            status.setRollbackOnly();
            return attempt;
        });

        assertThat(worker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        inTransaction(status -> {
            var command = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(command.getStatus()).isEqualTo("PROCESSING");
            assertThat(command.getLastError()).isNull();
            return null;
        });
    }

    @Test
    void financialEffectsAndFenceCommitTogetherBeforePublishingTheStatement() {
        UUID sourceWalletId = UUID.randomUUID();
        Fixture fixture = createPayment(sourceWalletId, 5000L);
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var statement = mock(PaymentStatementPort.class);
        var audit = mock(PaymentCancellationAuditPort.class);
        var published = new AtomicBoolean();
        doAnswer(invocation -> {
            publishAfterCommit(published);
            return null;
        }).when(statement).record(any());
        var effects = new CancelPaymentEffectsService(cancellationState, ledger, liquidity, statement, audit);

        inTransaction(status -> {
            fence.fence(List.of(fixture.executionId()));
            effects.cancel(fixture.executionId(), "Cancelado pelo usuário.");
            entityManager.flush();
            assertThat(published).isFalse();
            return null;
        });

        assertThat(published).isTrue();
        verify(ledger).releaseReserved(fixture.executionId(), sourceWalletId, 5000L);
        verifyNoInteractions(liquidity);
        verify(statement).record(new RecordPaymentStatementCommand(
                1L, fixture.executionId(), sourceWalletId, null, true));
        verify(audit).recordCancelled(any());
        inTransaction(status -> {
            var payment = entityManager.find(KfeTransactionEntity.class, fixture.executionId().value());
            var command = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("USER_CANCELLED");
            assertThat(payment.getFailureMessage()).isEqualTo("Cancelado pelo usuário.");
            assertThat(command.getStatus()).isEqualTo("FAILED_FINAL");
            assertThat(command.getLastError()).isEqualTo("USER_CANCELLED");
            assertThat(claimImmediate(fixture.commandId())).isZero();
            return null;
        });
    }

    @Test
    void auditFailureAfterFlushedFinancialEffectsRollsBackBothRowsAndSuppressesPublication() throws Exception {
        UUID sourceWalletId = UUID.randomUUID();
        Fixture fixture = createPayment(sourceWalletId, 5000L);
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var statement = mock(PaymentStatementPort.class);
        var audit = mock(PaymentCancellationAuditPort.class);
        var published = new AtomicBoolean();
        var workerPid = new CompletableFuture<Integer>();
        var waitingWorker = new AtomicReference<Future<Integer>>();
        var failure = new IllegalStateException("audit unavailable after flush");
        doAnswer(invocation -> {
            publishAfterCommit(published);
            return null;
        }).when(statement).record(any());
        doAnswer(invocation -> {
            entityManager.flush();
            // Read physical SQL rows, not only the managed entities, before inducing failure.
            assertThat(entityManager.createNativeQuery(
                            "select status from financial.transactions_master where id = :id")
                    .setParameter("id", fixture.executionId().value()).getSingleResult()).isEqualTo("FAILED");
            assertThat(entityManager.createNativeQuery(
                            "select status from financial.financial_execution_outbox where id = :id")
                    .setParameter("id", fixture.commandId()).getSingleResult()).isEqualTo("FAILED_FINAL");
            waitingWorker.set(startClaim(fixture.commandId(), workerPid));
            awaitBlocked(workerPid);
            assertThat(waitingWorker.get().isDone()).isFalse();
            throw failure;
        }).when(audit).recordCancelled(any());
        var effects = new CancelPaymentEffectsService(cancellationState, ledger, liquidity, statement, audit);

        assertThatThrownBy(() -> inTransaction(status -> {
            fence.fence(List.of(fixture.executionId()));
            effects.cancel(fixture.executionId(), "Cancelado pelo usuário.");
            return null;
        })).isSameAs(failure);

        assertThat(waitingWorker.get().get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(published).isFalse();
        verify(ledger).releaseReserved(fixture.executionId(), sourceWalletId, 5000L);
        inTransaction(status -> {
            var payment = entityManager.find(KfeTransactionEntity.class, fixture.executionId().value());
            var command = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            assertThat(payment.getFailureCode()).isNull();
            assertThat(payment.getFailureMessage()).isNull();
            assertThat(command.getStatus()).isEqualTo("PROCESSING");
            assertThat(command.getLastError()).isNull();
            return null;
        });
    }

    @Test
    void staleVersionedCommandRejectsCancellationAfterConcurrentClaim() {
        Fixture fixture = createPayment();
        inTransaction(status -> {
            var staleCommand = outboxes.findById(fixture.commandId()).orElseThrow();
            assertThat(staleCommand.getStatus()).isEqualTo("PENDING");
            await(executor.submit(() -> inTransaction(otherStatus -> claimImmediate(fixture.commandId()))));

            assertThat(staleCommand.getStatus()).isEqualTo("PENDING");
            // Hibernate rejects the stale @Version when upgrading the query lock, before
            // refresh can run. This must abort cancellation, never hide/retry the conflict.
            assertThatThrownBy(() -> fence.fence(List.of(fixture.executionId())))
                    .isInstanceOf(OptimisticLockException.class);
            status.setRollbackOnly();
            return null;
        });
        assertThat(commandStatus(fixture.commandId())).isEqualTo("PROCESSING");
    }

    @Test
    void fenceRefreshesManagedTransactionAfterConcurrentBroadcast() {
        Fixture fixture = createPayment();
        inTransaction(status -> {
            var stalePayment = entityManager.find(KfeTransactionEntity.class, fixture.executionId().value());
            assertThat(stalePayment.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            await(executor.submit(() -> inTransaction(otherStatus -> {
                var payment = entityManager.find(KfeTransactionEntity.class, fixture.executionId().value());
                payment.setStatus(KfeTransactionStatus.BROADCAST);
                payment.setBlockchainTxid("test-broadcast-txid");
                return null;
            })));

            assertThat(stalePayment.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            assertThatThrownBy(() -> fence.fence(List.of(fixture.executionId())))
                    .isInstanceOf(PaymentCancellationRejected.class);
            status.setRollbackOnly();
            return null;
        });
        assertThat(commandStatus(fixture.commandId())).isEqualTo("PENDING");
    }

    @Test
    void batchWithStartedPaymentLeavesEveryUnstartedCommandUntouched() {
        Fixture unstarted = createPayment();
        Fixture started = createPayment();
        inTransaction(status -> claimImmediate(started.commandId()));

        assertThatThrownBy(() -> inTransaction(status -> {
            fence.fence(List.of(unstarted.executionId(), started.executionId()));
            return null;
        })).isInstanceOf(PaymentCancellationRejected.class);

        assertThat(commandStatus(unstarted.commandId())).isEqualTo("PENDING");
        assertThat(commandStatus(started.commandId())).isEqualTo("PROCESSING");
    }

    @Test
    void requestOrchestrationCommitsRequestPaymentAndOutboxAndIsIdempotent() {
        var fixture = createLinkedRequestFixture();
        var audit = mock(PaymentRequestCancellationAuditPort.class);
        var statement = mock(PaymentStatementPort.class);
        var invoices = mock(PaymentInvoiceCancellationPort.class);
        when(invoices.cancel(any())).thenReturn(true);
        var published = new AtomicBoolean();
        PaymentCancellationNotificationPort notification = userId -> {
            assertThat(userId).isEqualTo(1L);
            assertThat(published).isFalse();
            publishAfterCommit(published);
        };
        var service = requestCancellationService(audit, statement, invoices, notification);

        assertThat(service.cancelPaymentRequest(new CancelPaymentRequestCommand(1L, fixture.requestId())))
                .isEqualTo(fixture.requestId());
        assertThat(published).isTrue();
        assertRequestState(fixture, KfePaymentRequestStatus.CANCELLED, KfeTransactionStatus.FAILED, "FAILED_FINAL");
        int cancelledClaim = inTransaction(status -> claimImmediate(fixture.payment().commandId()));
        assertThat(cancelledClaim).isZero();

        service.cancelPaymentRequest(new CancelPaymentRequestCommand(1L, fixture.requestId()));
        verify(invoices, times(1)).cancel(any());
        verify(audit, times(1)).recordCancelled(any());
        verify(statement, times(1)).record(any());
    }

    @Test
    void requestAuditFailureRollsBackRequestAndFencedOutboxEvenAfterRemoteSuccess() {
        var fixture = createLinkedRequestFixture();
        var audit = mock(PaymentRequestCancellationAuditPort.class);
        var statement = mock(PaymentStatementPort.class);
        var invoices = mock(PaymentInvoiceCancellationPort.class);
        var notification = mock(PaymentCancellationNotificationPort.class);
        var remotelyCancelled = new AtomicBoolean();
        when(invoices.cancel(any())).thenAnswer(invocation -> {
            remotelyCancelled.set(true);
            return true;
        });
        var failure = new IllegalStateException("request audit unavailable");
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(KfePaymentRequestStatus.CANCELLED);
            assertThat(outboxes.findById(fixture.payment().commandId()).orElseThrow().getStatus())
                    .isEqualTo("FAILED_FINAL");
            throw failure;
        }).when(audit).recordCancelled(any());
        var service = requestCancellationService(audit, statement, invoices, notification);

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(1L, fixture.requestId())))
                .isSameAs(failure);

        assertThat(remotelyCancelled).isTrue(); // A local rollback cannot undo the provider's RPC.
        assertRequestState(fixture, KfePaymentRequestStatus.OPEN, KfeTransactionStatus.EXECUTING, "PENDING");
        verifyNoInteractions(statement, notification);
    }

    @Test
    void requestStatementFailureRollsBackAllThreePersistedStates() {
        var fixture = createLinkedRequestFixture();
        var audit = mock(PaymentRequestCancellationAuditPort.class);
        var statement = mock(PaymentStatementPort.class);
        var invoices = mock(PaymentInvoiceCancellationPort.class);
        when(invoices.cancel(any())).thenReturn(true);
        var notification = mock(PaymentCancellationNotificationPort.class);
        var failure = new IllegalStateException("request statement unavailable");
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.payment().executionId().value())
                    .getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
            throw failure;
        }).when(statement).record(any());
        var service = requestCancellationService(audit, statement, invoices, notification);

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(1L, fixture.requestId())))
                .isSameAs(failure);

        assertRequestState(fixture, KfePaymentRequestStatus.OPEN, KfeTransactionStatus.EXECUTING, "PENDING");
        verify(audit).recordCancelled(any());
        verifyNoInteractions(notification);
    }

    @Test
    void requestOrchestrationRejectsClaimedPaymentBeforeRemoteCancellation() {
        var fixture = createLinkedRequestFixture();
        int claimed = inTransaction(status -> claimImmediate(fixture.payment().commandId()));
        assertThat(claimed).isEqualTo(1);
        var audit = mock(PaymentRequestCancellationAuditPort.class);
        var statement = mock(PaymentStatementPort.class);
        var invoices = mock(PaymentInvoiceCancellationPort.class);
        var notification = mock(PaymentCancellationNotificationPort.class);
        var service = requestCancellationService(audit, statement, invoices, notification);

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(1L, fixture.requestId())))
                .isInstanceOf(PaymentCancellationRejected.class);

        assertRequestState(fixture, KfePaymentRequestStatus.OPEN, KfeTransactionStatus.EXECUTING, "PROCESSING");
        verifyNoInteractions(audit, statement, invoices, notification);
    }

    @Test
    void requestOrchestrationRejectsAnotherUserBeforeDiscoveringPayments() {
        var fixture = createLinkedRequestFixture();
        var audit = mock(PaymentRequestCancellationAuditPort.class);
        var statement = mock(PaymentStatementPort.class);
        var invoices = mock(PaymentInvoiceCancellationPort.class);
        var notification = mock(PaymentCancellationNotificationPort.class);
        var service = requestCancellationService(audit, statement, invoices, notification);

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(2L, fixture.requestId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");

        assertRequestState(fixture, KfePaymentRequestStatus.OPEN, KfeTransactionStatus.EXECUTING, "PENDING");
        verifyNoInteractions(audit, statement, invoices, notification);
    }

    private static CancelPaymentRequestUseCase requestCancellationService(
            PaymentRequestCancellationAuditPort audit, PaymentStatementPort statement,
            PaymentInvoiceCancellationPort invoices, PaymentCancellationNotificationPort notification) {
        var effects = new CancelPaymentEffectsService(cancellationState, mock(PaymentLedgerPort.class),
                mock(PaymentLiquidityPort.class), statement, mock(PaymentCancellationAuditPort.class));
        var service = new CancelPaymentService(cancellationQuery,
                new PaymentCancellationHintsService(cancellationQuery), cancellationState, requestState,
                requestLock, relatedPayments, fence, invoices, audit, effects, notification,
                mock(PaymentExecutionQueryRepository.class));
        var proxy = new ProxyFactory(new TransactionalPaymentCancellationAdapter(service));
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (CancelPaymentRequestUseCase) proxy.getProxy();
    }

    private static RequestFixture createLinkedRequestFixture() {
        UUID requestId = createRequest();
        Fixture payment = createPayment(UUID.randomUUID(), 5_000L);
        inTransaction(status -> {
            var request = requests.findById(requestId).orElseThrow();
            request.setRail(KfeRail.LIGHTNING);
            request.setPaymentHash("test-invoice-" + requestId);
            var execution = entityManager.find(KfeTransactionEntity.class, payment.executionId().value());
            execution.setIdempotencyKey("payment-request:" + requestId + ":observed");
            execution.setRail(KfeRail.LIGHTNING);
            outboxes.findById(payment.commandId()).orElseThrow().setOperation("LIGHTNING_OUTBOUND");
            return null;
        });
        return new RequestFixture(requestId, payment);
    }

    private static void assertRequestState(RequestFixture fixture, KfePaymentRequestStatus requestStatus,
                                           KfeTransactionStatus executionStatus, String outboxStatus) {
        inTransaction(status -> {
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getStatus()).isEqualTo(requestStatus);
            var payment = entityManager.find(KfeTransactionEntity.class, fixture.payment().executionId().value());
            assertThat(payment.getStatus()).isEqualTo(executionStatus);
            assertThat(payment.getFailureCode()).isEqualTo(executionStatus == KfeTransactionStatus.FAILED
                    ? "USER_CANCELLED" : null);
            assertThat(outboxes.findById(fixture.payment().commandId()).orElseThrow().getStatus()).isEqualTo(outboxStatus);
            return null;
        });
    }

    private record RequestFixture(UUID requestId, Fixture payment) {}

    @Test
    void intentCreationSharesTheOwningCommitAndRollsBackWithIt() {
        var id = new AtomicReference<PaymentExecutionId>();
        var service = new CreatePaymentIntentService(intentStore);
        var command = new CreatePaymentIntentCommand(1L, new IdempotencyKey("intent-" + UUID.randomUUID()),
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, null, 1000L, " reference ", " memo ", null);
        assertThatThrownBy(() -> service.create(command)).isInstanceOf(IllegalTransactionStateException.class);
        inTransaction(status -> {
            id.set(service.create(command));
            entityManager.flush();
            var tx = entityManager.find(KfeTransactionEntity.class, id.get().value());
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
            assertThat(tx.getExternalReference()).isEqualTo("reference");
            assertThat(tx.getMemo()).isEqualTo("memo");
            status.setRollbackOnly();
            return null;
        });
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, id.get().value())).isNull();
            return null;
        });
    }

    @Test
    void internalSettlementCommitsWithPaymentRequestAndRejectsReplayBeforeSecondDebit() {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var service = internalService(ledger, mock(PaymentStatementPort.class), mock(InternalPaymentNotificationPort.class));
        assertThatThrownBy(() -> service.settle(new SettleInternalPaymentCommand(1L, fixture.id())))
                .isInstanceOf(IllegalTransactionStateException.class);
        inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            assertThat(service.settle(new SettleInternalPaymentCommand(1L, fixture.id())).currentStatus())
                    .isEqualTo(ExecutionStatus.SETTLED);
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            return null;
        });
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.PAID);
        assertThatThrownBy(() -> inTransaction(status -> service.settle(new SettleInternalPaymentCommand(1L, fixture.id()))))
                .isInstanceOf(IllegalStateException.class);
        verify(ledger, times(1)).settleReservedDebit(fixture.id(), fixture.source(), 10_000L);
        verify(ledger, times(1)).creditAvailable(fixture.id(), fixture.destination(), 9_910L);
    }

    @Test
    void outerFailureAfterLinkingRequestRollsBackInternalSettlementAndPaidLinkTogether() {
        var fixture = internalFixture();
        var service = internalService(mock(PaymentLedgerPort.class), mock(PaymentStatementPort.class),
                mock(InternalPaymentNotificationPort.class));
        var failure = new IllegalStateException("submit completion unavailable");
        assertThatThrownBy(() -> inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
            var request = requests.findById(fixture.requestId()).orElseThrow();
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            entityManager.flush();
            assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
            throw failure;
        })).isSameAs(failure);
        assertInternalState(fixture, KfeTransactionStatus.LOCKED, KfePaymentRequestStatus.OPEN);
        inTransaction(status -> {
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getPaidTransactionId()).isNull();
            return null;
        });
    }

    @Test
    void internalSettlementFlushesCurrentSubmitStateBeforeRefreshingIt() {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var service = internalService(ledger, mock(PaymentStatementPort.class), mock(InternalPaymentNotificationPort.class));
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setTotalDebitSats(20_000L);
            tx.setReceiverAmountSats(19_820L);
            // No explicit save/flush: the scoped locking query must not refresh away pending quote changes.
            service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
            return null;
        });
        verify(ledger).settleReservedDebit(fixture.id(), fixture.source(), 20_000L);
        verify(ledger).creditAvailable(fixture.id(), fixture.destination(), 19_820L);
    }

    @Test
    void internalStatementFailureRollsBackTransitionAndAllowsACleanRetry() {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(InternalPaymentNotificationPort.class);
        var failure = new IllegalStateException("internal statement unavailable");
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus())
                    .isEqualTo(KfeTransactionStatus.SETTLED);
            throw failure;
        }).when(statements).record(any());
        var service = internalService(ledger, statements, notifications);
        assertThatThrownBy(() -> inTransaction(status -> {
            requestLock.lock(2L, fixture.requestId());
            return service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
        })).isSameAs(failure);
        assertInternalState(fixture, KfeTransactionStatus.LOCKED, KfePaymentRequestStatus.OPEN);
        verifyNoInteractions(notifications);
        var retry = internalService(mock(PaymentLedgerPort.class), mock(PaymentStatementPort.class), notifications);
        inTransaction(status -> retry.settle(new SettleInternalPaymentCommand(1L, fixture.id())));
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.OPEN);
    }

    @Test
    void concurrentInternalSettlementRefreshesStaleStateAndDoesNotDebitTwice() throws Exception {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var service = internalService(ledger, mock(PaymentStatementPort.class), mock(InternalPaymentNotificationPort.class));
        var contenderPid = new CompletableFuture<Integer>();
        var contenderLoaded = new CompletableFuture<Void>();
        var startContender = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.LOCKED);
            contenderPid.complete(backendPid());
            contenderLoaded.complete(null);
            await(startContender);
            assertThatThrownBy(() -> service.settle(new SettleInternalPaymentCommand(1L, fixture.id())))
                    .isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
            return true;
        }));
        await(contenderLoaded);
        inTransaction(status -> {
            service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
            startContender.complete(null);
            awaitBlocked(contenderPid);
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(ledger, times(1)).settleReservedDebit(fixture.id(), fixture.source(), 10_000L);
        verify(ledger, times(1)).creditAvailable(fixture.id(), fixture.destination(), 9_910L);
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.OPEN);
    }

    @Test
    void wrongOwnerCannotSettleAnInternalExecution() {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(InternalPaymentNotificationPort.class);
        var service = internalService(ledger, statements, notifications);
        assertThatThrownBy(() -> inTransaction(status -> service.settle(new SettleInternalPaymentCommand(2L, fixture.id()))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ledger, statements, notifications);
        assertInternalState(fixture, KfeTransactionStatus.LOCKED, KfePaymentRequestStatus.OPEN);
    }

    private static SettleInternalPaymentUseCase internalService(PaymentLedgerPort ledger,
            PaymentStatementPort statements, InternalPaymentNotificationPort notifications) {
        return transactional(new TransactionalInternalPaymentSettlementAdapter(new SettleInternalPaymentService(
                internalSettlementState, ledger, lifecycle, mock(PaymentFeeSettlementPort.class), statements, notifications)),
                SettleInternalPaymentUseCase.class);
    }

    private static InternalFixture internalFixture() {
        return inTransaction(status -> {
            var source = internalWallet(1L);
            var destination = internalWallet(2L);
            var id = new CreatePaymentIntentService(intentStore).create(new CreatePaymentIntentCommand(
                    1L, new IdempotencyKey("internal-" + UUID.randomUUID()), PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                    source.getId(), destination.getId(), 10_000L, null, null, null));
            var tx = entityManager.find(KfeTransactionEntity.class, id.value());
            tx.setStatus(KfeTransactionStatus.LOCKED); tx.setTotalDebitSats(10_000L); tx.setReceiverAmountSats(9_910L);
            var request = new KfePaymentRequestEntity();
            request.setUserId(2L); request.setWalletId(destination.getId()); request.setRail(KfeRail.INTERNAL);
            request.setPublicId(request.getId().toString()); request.setAddress(request.getId().toString());
            entityManager.persist(request);
            tx.setExternalReference(request.getPublicId());
            return new InternalFixture(id, source.getId(), destination.getId(), request.getId());
        });
    }

    private static KfeWalletEntity internalWallet(long owner) {
        var wallet = new KfeWalletEntity();
        wallet.setUserId(owner); wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setLabel("internal-settlement-test"); wallet.setQuorumPolicyHash("0".repeat(64));
        entityManager.persist(wallet);
        return wallet;
    }

    private static void assertInternalState(InternalFixture fixture, KfeTransactionStatus execution, KfePaymentRequestStatus request) {
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus()).isEqualTo(execution);
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getStatus()).isEqualTo(request);
            return null;
        });
    }

    private static <T> T transactional(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private record InternalFixture(PaymentExecutionId id, UUID source, UUID destination, UUID requestId) {}

    private static PreparePaymentRequestLinkCommand linkCommand(InternalFixture fixture) {
        return new PreparePaymentRequestLinkCommand(1L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                fixture.destination(), 10_000L, fixture.requestId().toString());
    }

    @Test
    void requestLinkRefusesASecondPayerAfterWaitingForTheFirstPaymentsCommit() throws Exception {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var service = internalService(ledger, mock(PaymentStatementPort.class), mock(InternalPaymentNotificationPort.class));
        var contenderPid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = requests.findById(fixture.requestId()).orElseThrow();
            assertThat(stale.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
            contenderPid.complete(backendPid());
            loaded.complete(null);
            await(start);
            assertThatThrownBy(() -> paymentRequestLinks.prepare(new PreparePaymentRequestLinkCommand(3L,
                    PaymentRail.INTERNAL, PaymentDirection.INTERNAL, fixture.destination(), 10_000L, fixture.requestId().toString())))
                    .isInstanceOf(IllegalStateException.class).hasMessage("KFE payment request is no longer open.");
            assertThat(stale.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
            status.setRollbackOnly();
            return true;
        }));
        await(loaded);
        inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            entityManager.flush();
            start.complete(null);
            awaitBlocked(contenderPid);
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(ledger, times(1)).settleReservedDebit(fixture.id(), fixture.source(), 10_000L);
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.PAID);
    }

    @Test
    void requestLinkRefreshesCancellationCommittedWhileAcceptanceWaited() throws Exception {
        var fixture = internalFixture();
        var contenderPid = new CompletableFuture<Integer>();
        Future<Boolean> denied = inTransaction(status -> {
            requestLock.lock(2L, fixture.requestId());
            requests.findById(fixture.requestId()).orElseThrow().cancel();
            entityManager.flush();
            Future<Boolean> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                var stale = requests.findById(fixture.requestId()).orElseThrow();
                assertThat(stale.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
                contenderPid.complete(backendPid());
                assertThatThrownBy(() -> paymentRequestLinks.prepare(linkCommand(fixture)))
                        .isInstanceOf(IllegalStateException.class).hasMessage("KFE payment request is no longer open.");
                otherStatus.setRollbackOnly();
                return true;
            }));
            awaitBlocked(contenderPid);
            return attempt;
        });
        assertThat(denied.get(10, TimeUnit.SECONDS)).isTrue();
        assertInternalState(fixture, KfeTransactionStatus.LOCKED, KfePaymentRequestStatus.CANCELLED);
    }

    @Test
    void requestLinkRejectsForeignPayersExecutionWithMatchingDestinationAmountAndReference() {
        var fixture = internalFixture();
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setUserId(3L); tx.setStatus(KfeTransactionStatus.SETTLED);
            return null;
        });
        assertThatThrownBy(() -> inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            return null;
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.OPEN);
    }

    @Test
    void requestMutationAfterSettlementRollsBackBothExecutionAndRequestWrite() {
        var fixture = internalFixture();
        var service = internalService(mock(PaymentLedgerPort.class), mock(PaymentStatementPort.class), mock(InternalPaymentNotificationPort.class));
        assertThatThrownBy(() -> inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            service.settle(new SettleInternalPaymentCommand(1L, fixture.id()));
            requests.findById(fixture.requestId()).orElseThrow().setAmountSats(9_999L);
            // The re-lock query flushes these pending changes, then the accepted snapshot comparison rejects them.
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessage("Payment request changed after acceptance.");
        assertInternalState(fixture, KfeTransactionStatus.LOCKED, KfePaymentRequestStatus.OPEN);
        inTransaction(status -> {
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getAmountSats()).isNull();
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getPaidTransactionId()).isNull();
            return null;
        });
    }

    @Test
    void lightningLoopbackLinkUsesGrossAmountAndFlushesPendingSettledStateBeforeMarkingPaid() {
        var fixture = internalFixture();
        inTransaction(status -> {
            var request = requests.findById(fixture.requestId()).orElseThrow();
            request.setRail(KfeRail.LIGHTNING); request.setAmountSats(10_000L);
            return null;
        });
        inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setStatus(KfeTransactionStatus.SETTLED); tx.setReceiverAmountSats(9_910L);
            // No explicit flush: the settled execution and recipient request must commit together.
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            return null;
        });
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.PAID);
    }

    @Test
    void fundsReservationCommitsLockedStateAndRejectsReplayWithoutSecondFinancialEffect() {
        var fixture = reservationFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var service = fundsReservationService(ledger, liquidity);
        var command = new ReservePaymentFundsCommand(1L, fixture.id());
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(inTransaction(status -> service.reserve(command)).currentStatus()).isEqualTo(ExecutionStatus.LOCKED);
        assertThatThrownBy(() -> inTransaction(status -> service.reserve(command))).isInstanceOf(IllegalStateException.class);
        verify(ledger, times(1)).reserve(fixture.id(), fixture.source(), 10_100L);
        verify(liquidity, times(1)).reserve(fixture.id(), 10_100L);
        assertReservationStatus(fixture, KfeTransactionStatus.LOCKED);
    }

    @Test
    void fundsReservationPreservesPendingQuoteAndQuorumBeforeTheStateRefresh() {
        var fixture = reservationFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var service = fundsReservationService(ledger, liquidity);
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setTotalDebitSats(20_200L);
            tx.setQuorumProposalHash("b".repeat(64));
            tx.setQuorumAckCount(2);
            // Match submit: quote/quorum are dirty in this transaction, with no explicit flush.
            service.reserve(new ReservePaymentFundsCommand(1L, fixture.id()));
            assertThat(tx.getTotalDebitSats()).isEqualTo(20_200L);
            assertThat(tx.getQuorumProposalHash()).isEqualTo("b".repeat(64));
            assertThat(tx.getQuorumAckCount()).isEqualTo(2);
            return null;
        });
        verify(ledger).reserve(fixture.id(), fixture.source(), 20_200L);
        verify(liquidity).reserve(fixture.id(), 20_200L);
        assertReservationStatus(fixture, KfeTransactionStatus.LOCKED);
    }

    @Test
    void concurrentFundsReservationRevalidatesStaleQuorumStateWithoutSecondReserve() throws Exception {
        var fixture = reservationFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var service = fundsReservationService(ledger, liquidity);
        var contenderPid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.QUORUM_SYNC);
            contenderPid.complete(backendPid());
            loaded.complete(null);
            await(start);
            assertThatThrownBy(() -> service.reserve(new ReservePaymentFundsCommand(1L, fixture.id())))
                    .isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
            return true;
        }));
        await(loaded);
        inTransaction(status -> {
            service.reserve(new ReservePaymentFundsCommand(1L, fixture.id()));
            start.complete(null);
            awaitBlocked(contenderPid);
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(ledger, times(1)).reserve(fixture.id(), fixture.source(), 10_100L);
        verify(liquidity, times(1)).reserve(fixture.id(), 10_100L);
        assertReservationStatus(fixture, KfeTransactionStatus.LOCKED);
    }

    @Test
    void outerRoutingFailureRollsBackTheLockedTransitionAndAllowsReservationRetry() {
        var fixture = reservationFixture();
        var service = fundsReservationService(mock(PaymentLedgerPort.class), mock(PaymentLiquidityPort.class));
        var failure = new IllegalStateException("routing unavailable");
        assertThatThrownBy(() -> inTransaction(status -> {
            service.reserve(new ReservePaymentFundsCommand(1L, fixture.id()));
            entityManager.flush();
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus())
                    .isEqualTo(KfeTransactionStatus.LOCKED);
            throw failure;
        })).isSameAs(failure);
        assertReservationStatus(fixture, KfeTransactionStatus.QUORUM_SYNC);
        inTransaction(status -> service.reserve(new ReservePaymentFundsCommand(1L, fixture.id())));
        assertReservationStatus(fixture, KfeTransactionStatus.LOCKED);
    }

    @Test
    void foreignUserOrAnArchivedSourceCannotReserveFunds() {
        var fixture = reservationFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        var service = fundsReservationService(ledger, liquidity);
        assertThatThrownBy(() -> inTransaction(status -> service.reserve(new ReservePaymentFundsCommand(2L, fixture.id()))))
                .isInstanceOf(IllegalArgumentException.class);
        inTransaction(status -> {
            entityManager.find(KfeWalletEntity.class, fixture.source()).setStatus(KfeWalletStatus.ARCHIVED);
            return null;
        });
        assertThatThrownBy(() -> inTransaction(status -> service.reserve(new ReservePaymentFundsCommand(1L, fixture.id()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("source wallet is not active.");
        verifyNoInteractions(ledger, liquidity);
        assertReservationStatus(fixture, KfeTransactionStatus.QUORUM_SYNC);
    }

    @Test
    void inboundIgnoresOptionalSourceAndDoesNotReserveLedgerOrLiquidity() {
        var fixture = reservationFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var liquidity = mock(PaymentLiquidityPort.class);
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setDirection(KfeDirection.INBOUND);
            tx.setTotalDebitSats(0L);
            // Even an unusable optional source cannot turn inbound into a debit.
            entityManager.find(KfeWalletEntity.class, fixture.source()).setStatus(KfeWalletStatus.ARCHIVED);
            return null;
        });
        var service = fundsReservationService(ledger, liquidity);
        inTransaction(status -> service.reserve(new ReservePaymentFundsCommand(1L, fixture.id())));
        verifyNoInteractions(ledger, liquidity);
        assertReservationStatus(fixture, KfeTransactionStatus.LOCKED);
    }

    private static ReservePaymentFundsUseCase fundsReservationService(PaymentLedgerPort ledger, PaymentLiquidityPort liquidity) {
        return transactional(new TransactionalPaymentFundsReservationAdapter(new ReservePaymentFundsService(
                fundsReservationState, walletLookup, ledger, liquidity, lifecycle)), ReservePaymentFundsUseCase.class);
    }

    private static ReservationFixture reservationFixture() {
        return inTransaction(status -> {
            var source = internalWallet(1L);
            source.setStatus(KfeWalletStatus.ACTIVE);
            var id = new CreatePaymentIntentService(intentStore).create(new CreatePaymentIntentCommand(
                    1L, new IdempotencyKey("reserve-" + UUID.randomUUID()), PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                    source.getId(), null, 10_000L, "fixture-invoice", null, null));
            var tx = entityManager.find(KfeTransactionEntity.class, id.value());
            tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
            tx.setTotalDebitSats(10_100L); tx.setReceiverAmountSats(10_000L);
            tx.setQuorumProposalHash("a".repeat(64)); tx.setQuorumAckCount(3);
            return new ReservationFixture(id, source.getId());
        });
    }

    private static void assertReservationStatus(ReservationFixture fixture, KfeTransactionStatus expected) {
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus()).isEqualTo(expected);
            return null;
        });
    }

    private record ReservationFixture(PaymentExecutionId id, UUID source) {}

    @Test
    void externalRoutingCommitsOneCommandAndExecutingStateWithTheExistingPayloadContract() throws Exception {
        var fixture = routingFixture();
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(PaymentInitiatedNotificationPort.class);
        var vault = mock(PaymentVaultIntentPort.class);
        var service = routingService(statements, notifications, vault);
        var result = inTransaction(status -> service.route(routingCommand(fixture)));
        assertThat(result.transition().currentStatus()).isEqualTo(ExecutionStatus.EXECUTING);
        var row = inTransaction(status -> {
            var commands = outboxes.findByTransactionId(fixture.id().value());
            assertThat(commands).hasSize(1);
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus())
                    .isEqualTo(KfeTransactionStatus.EXECUTING);
            return commands.getFirst();
        });
        assertThat(row.getId()).isEqualTo(result.immediateDispatchOutboxId());
        assertThat(row.getOperation()).isEqualTo("ONCHAIN_OUTBOUND");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        var json = new ObjectMapper().readTree(row.getPayloadJson());
        assertThat(json.get("amountSats").asLong()).isEqualTo(9_910L);
        assertThat(json.get("networkFeeSats").asLong()).isEqualTo(100L);
        assertThat(json.get("totalDebitSats").asLong()).isEqualTo(10_100L);
        assertThat(json.get("externalReference").asText()).isEqualTo(" fixture-reference ");
        assertThat(json.get("memo").asText()).isEqualTo(" fixture memo ");
        assertThat(json.get("quorumProposalHash").asText()).isEqualTo("a".repeat(64));
        assertThat(json.get("feeRateSatsPerVbyte").asLong()).isEqualTo(12L);
        assertThat(json.get("feeTargetBlocks").asInt()).isEqualTo(3);
        assertThat(json.get("transactionId").asText()).isEqualTo(fixture.id().value().toString());
        assertThat(json.get("idempotencyKey").asText()).isEqualTo(inTransaction(status ->
                entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getIdempotencyKey()));
        assertThat(row.getPayloadHash()).isEqualTo(new KfeHashService().sha256(row.getPayloadJson()));
        assertThatThrownBy(() -> inTransaction(status -> service.route(routingCommand(fixture))))
                .isInstanceOf(IllegalStateException.class);
        verify(statements, times(1)).record(new RecordPaymentStatementCommand(1L, fixture.id(), fixture.source(), " fixture memo ", false));
        verify(notifications, times(1)).initiated(1L, fixture.id(), fixture.source(), PaymentRail.ONCHAIN, 10_000L);
        verify(vault, times(1)).notifyOutbound(fixture.id(), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, "fixture-reference", 10_000L);
        assertRoutingState(fixture, KfeTransactionStatus.EXECUTING, 1);
    }

    @Test
    void concurrentExternalRoutingRefreshesStaleLockedStateAndDoesNotEnqueueTwice() throws Exception {
        var fixture = routingFixture();
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(PaymentInitiatedNotificationPort.class);
        var vault = mock(PaymentVaultIntentPort.class);
        var service = routingService(statements, notifications, vault);
        var pid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.LOCKED);
            pid.complete(backendPid()); loaded.complete(null); await(start);
            assertThatThrownBy(() -> service.route(routingCommand(fixture))).isInstanceOf(IllegalStateException.class);
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            status.setRollbackOnly();
            return true;
        }));
        await(loaded);
        inTransaction(status -> {
            service.route(routingCommand(fixture)); start.complete(null); awaitBlocked(pid);
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(statements, times(1)).record(any());
        verify(notifications, times(1)).initiated(anyLong(), any(), any(), any(), anyLong());
        verify(vault, times(1)).notifyOutbound(any(), any(), any(), any(), anyLong());
        assertRoutingState(fixture, KfeTransactionStatus.EXECUTING, 1);
    }

    @Test
    void failureAfterRoutingFlushRollsBackBothSqlWritesAndAllowsRetry() {
        var fixture = routingFixture();
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(PaymentInitiatedNotificationPort.class);
        var vault = mock(PaymentVaultIntentPort.class);
        var failure = new IllegalStateException("statement unavailable");
        doAnswer(invocation -> {
            assertRoutingState(fixture, KfeTransactionStatus.EXECUTING, 1);
            throw failure;
        }).when(statements).record(any());
        var service = routingService(statements, notifications, vault);
        assertThatThrownBy(() -> inTransaction(status -> service.route(routingCommand(fixture)))).isSameAs(failure);
        assertRoutingState(fixture, KfeTransactionStatus.LOCKED, 0);
        verifyNoInteractions(notifications, vault);
        var retry = routingService(mock(PaymentStatementPort.class), notifications, vault);
        inTransaction(status -> retry.route(routingCommand(fixture)));
        assertRoutingState(fixture, KfeTransactionStatus.EXECUTING, 1);
    }

    @Test
    void routingRejectsForeignUserAndMismatchedReferencesBeforeAnyCommandOrEffect() {
        var fixture = routingFixture();
        var statements = mock(PaymentStatementPort.class);
        var notifications = mock(PaymentInitiatedNotificationPort.class);
        var vault = mock(PaymentVaultIntentPort.class);
        var service = routingService(statements, notifications, vault);
        assertThatThrownBy(() -> inTransaction(status -> service.route(new RouteLockedPaymentCommand(
                2L, fixture.id(), "fixture-reference", "fixture memo", null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        assertThatThrownBy(() -> inTransaction(status -> service.route(new RouteLockedPaymentCommand(
                1L, fixture.id(), "different-destination", "fixture memo", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(statements, notifications, vault);
        assertRoutingState(fixture, KfeTransactionStatus.LOCKED, 0);
    }

    @Test
    void inboundRoutingKeepsMonitorCommandAndDestinationStatementWithoutImmediateDispatch() {
        var fixture = routingFixture();
        var destination = activeLookupWallet(1L);
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setDirection(KfeDirection.INBOUND); tx.setSourceWalletId(null);
            tx.setDestinationWalletId(destination); tx.setTotalDebitSats(0L);
            return null;
        });
        var statements = mock(PaymentStatementPort.class);
        var result = inTransaction(status -> routingService(statements, mock(PaymentInitiatedNotificationPort.class),
                mock(PaymentVaultIntentPort.class)).route(routingCommand(fixture)));
        assertThat(result.immediateDispatchOutboxId()).isNull();
        var operation = inTransaction(status -> outboxes.findByTransactionId(fixture.id().value()).getFirst().getOperation());
        assertThat(operation).isEqualTo("ONCHAIN_INBOUND");
        verify(statements).record(new RecordPaymentStatementCommand(1L, fixture.id(), destination, " fixture memo ", false));
        assertRoutingState(fixture, KfeTransactionStatus.EXECUTING, 1);
    }

    @Test
    void internalRoutingSettlesAndCompletesTheAcceptedRequestWithoutAnExternalCommand() {
        var fixture = internalFixture();
        var ledger = mock(PaymentLedgerPort.class);
        var statements = mock(PaymentStatementPort.class);
        var externalNotifications = mock(PaymentInitiatedNotificationPort.class);
        var vault = mock(PaymentVaultIntentPort.class);
        var service = transactional(new TransactionalPaymentRoutingAdapter(new RouteLockedPaymentService(routingState,
                internalService(ledger, statements, mock(InternalPaymentNotificationPort.class)), routingCommands, lifecycle,
                statements, externalNotifications, vault)), RouteLockedPaymentUseCase.class);
        inTransaction(status -> {
            var accepted = paymentRequestLinks.prepare(linkCommand(fixture)).orElseThrow();
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setQuorumProposalHash("a".repeat(64));
            var result = service.route(new RouteLockedPaymentCommand(1L, fixture.id(), null, null, null, null));
            assertThat(result.transition().currentStatus()).isEqualTo(ExecutionStatus.SETTLED);
            assertThat(result.immediateDispatchOutboxId()).isNull();
            paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(1L, accepted, fixture.id()));
            return null;
        });
        assertInternalState(fixture, KfeTransactionStatus.SETTLED, KfePaymentRequestStatus.PAID);
        var commands = inTransaction(status -> outboxes.findByTransactionId(fixture.id().value()));
        assertThat(commands).isEmpty();
        verify(ledger).settleReservedDebit(fixture.id(), fixture.source(), 10_000L);
        verify(ledger).creditAvailable(fixture.id(), fixture.destination(), 9_910L);
        verifyNoInteractions(externalNotifications, vault);
    }

    @Test
    void settlementGateRetainsTheBtcBalanceLockUntilReservationCommitsAndTheContenderRechecks() throws Exception {
        var fixture = gateFixture();
        var telemetry = mock(PaymentGateTelemetryPort.class);
        var gate = settlementGate(telemetry);
        var pid = new CompletableFuture<Integer>();
        Future<Boolean> contender = inTransaction(status -> {
            gate.requirePass(gateCommand(fixture));
            // The gate reads but does not reserve; the lock must still protect the following step.
            assertThat(entityManager.find(KfeBalanceEntity.class, new KfeBalanceId(fixture.source(), "BTC")).getAvailableSats())
                    .isEqualTo(12_000L);
            Future<Boolean> attempt = executor.submit(() -> inTransaction(other -> {
                pid.complete(backendPid());
                assertThatThrownBy(() -> gate.requirePass(gateCommand(fixture)))
                        .isInstanceOf(SettlementGateRejectedException.class).hasMessageContaining("V_SALDO_DISP");
                other.setRollbackOnly();
                return true;
            }));
            awaitBlocked(pid);
            gateBalanceService.reserve(fixture.source(), "BTC", 10_100L);
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setStatus(KfeTransactionStatus.LOCKED);
            return attempt;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(telemetry).recordSettlementGate(true);
        verify(telemetry).recordSettlementGate(false);
        assertGateBalance(fixture, 1_900L, 10_100L, KfeTransactionStatus.LOCKED);
    }

    @Test
    void callerFailureAfterGateAndBalanceReservationRollsBackBalanceAndExecutionSql() {
        var fixture = gateFixture();
        var telemetry = mock(PaymentGateTelemetryPort.class);
        var gate = settlementGate(telemetry);
        var failure = new IllegalStateException("subsequent routing unavailable");
        assertThatThrownBy(() -> inTransaction(status -> {
            gate.requirePass(gateCommand(fixture));
            gateBalanceService.reserve(fixture.source(), "BTC", 10_100L);
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setStatus(KfeTransactionStatus.LOCKED);
            entityManager.flush();
            throw failure;
        })).isSameAs(failure);
        assertGateBalance(fixture, 12_000L, 0L, KfeTransactionStatus.QUORUM_SYNC);
        // Metrics, like remote observations, are not undone by the SQL rollback.
        verify(telemetry).recordSettlementGate(true);
    }

    @Test
    void capturedGateRejectionRollsBackPendingSqlInsteadOfCommittingTheCallersChanges() {
        var fixture = gateFixture();
        var gate = settlementGate(mock(PaymentGateTelemetryPort.class));
        assertThatThrownBy(() -> inTransaction(status -> {
            entityManager.find(KfeBalanceEntity.class, new KfeBalanceId(fixture.source(), "BTC")).setAvailableSats(10L);
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setStatus(KfeTransactionStatus.VALIDATING);
            assertThatThrownBy(() -> gate.requirePass(gateCommand(fixture)))
                    .isInstanceOf(SettlementGateRejectedException.class).hasMessageContaining("V_SALDO_DISP");
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertGateBalance(fixture, 12_000L, 0L, KfeTransactionStatus.QUORUM_SYNC);
    }

    private static PaymentSettlementGateUseCase settlementGate(PaymentGateTelemetryPort telemetry) {
        var quorum = mock(PaymentGateQuorumPort.class);
        when(quorum.requireConsensus("gate-proposal")).thenReturn(new SettlementQuorumEvidence(2, 3));
        return transactional(new TransactionalPaymentSettlementGateAdapter(new PaymentSettlementGateService(gateBalance,
                mock(PaymentGateSolvencyPort.class), quorum, mock(PaymentGateLightningPort.class),
                mock(PaymentGateEnvironmentPort.class), mock(PaymentGateAuditPort.class), telemetry,
                new SettlementGatePolicy("enforce", false, false, 3, 2))), PaymentSettlementGateUseCase.class);
    }

    private static ReservationFixture gateFixture() {
        var fixture = reservationFixture();
        inTransaction(status -> {
            var balance = KfeBalanceEntity.empty(fixture.source(), "BTC", "0".repeat(64));
            balance.setAvailableSats(12_000L); entityManager.persist(balance);
            return null;
        });
        return fixture;
    }

    private static PaymentSettlementGateCommand gateCommand(ReservationFixture fixture) {
        return new PaymentSettlementGateCommand(1L, fixture.id(), fixture.source(), new IdempotencyKey("gate-key"), true,
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, 10_000L, 100L, 10_100L, true, "gate-proposal");
    }

    private static void assertGateBalance(ReservationFixture fixture, long available, long locked, KfeTransactionStatus expected) {
        inTransaction(status -> {
            var row = entityManager.find(KfeBalanceEntity.class, new KfeBalanceId(fixture.source(), "BTC"));
            assertThat(row.getAvailableSats()).isEqualTo(available);
            assertThat(row.getLockedSats()).isEqualTo(locked);
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus()).isEqualTo(expected);
            assertThat(outboxes.findByTransactionId(fixture.id().value())).isEmpty();
            return null;
        });
    }

    @Test
    void submissionPreparationCommitsQuoteDisplayRawProposalAndAcknowledgementBeforeReservation() {
        var fixture = submissionFixture();
        var pricing = submissionPricing();
        var gate = passingSubmissionGate();
        when(gate.requirePass(any())).thenAnswer(invocation -> {
            PaymentSettlementGateCommand command = invocation.getArgument(0);
            var row = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(row.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
            assertThat(row.getNetworkFeeSats()).isEqualTo(90L);
            assertThat(row.getQuorumProposalHash()).isEqualTo(submissionProposalHash(fixture));
            assertThat(command.networkFeeSats()).isEqualTo(100L);
            assertThat(command.totalDebitSats()).isEqualTo(10_100L);
            return new PaymentSettlementGateResult(2, 3);
        });
        var preparation = submissionPreparation(pricing, gate);
        var result = inTransaction(status -> preparation.prepare(submissionCommand(fixture)));
        assertThat(result.transition().currentStatus()).isEqualTo(ExecutionStatus.QUORUM_SYNC);
        assertThat(result.destinationWallet()).isNull();
        inTransaction(status -> {
            var row = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(row.getStatus()).isEqualTo(KfeTransactionStatus.QUORUM_SYNC);
            assertThat(row.getGrossAmountSats()).isEqualTo(10_000L);
            assertThat(row.getReceiverAmountSats()).isEqualTo(9_910L);
            assertThat(row.getNetworkFeeSats()).isEqualTo(90L);
            assertThat(row.getKeroseneFeeSats()).isEqualTo(90L);
            assertThat(row.getTotalDebitSats()).isEqualTo(10_100L);
            assertThat(row.getPricingPolicyVersion()).isEqualTo(7);
            assertThat(row.getDisplayBtcUsd()).isEqualByComparingTo("61234.5678");
            assertThat(row.getDisplayAmountUsd()).isEqualByComparingTo("6.12");
            assertThat(row.getDisplayBtcEur()).isNull();
            assertThat(row.getQuorumProposalHash()).isEqualTo(submissionProposalHash(fixture));
            assertThat(row.getQuorumAckCount()).isEqualTo(2);
            assertThat(outboxes.findByTransactionId(fixture.id().value())).isEmpty();
            return null;
        });
        assertThatThrownBy(() -> inTransaction(status -> preparation.prepare(submissionCommand(fixture))))
                .isInstanceOf(IllegalStateException.class);
        verify(pricing, times(1)).prepare(any()); verify(gate, times(1)).requirePass(any());
    }

    @Test
    void concurrentPreparationRefreshesStaleIntentAndDoesNotRepeatPricingOrQuorum() throws Exception {
        var fixture = submissionFixture();
        var pricing = submissionPricing();
        var gate = passingSubmissionGate();
        var preparation = submissionPreparation(pricing, gate);
        var pid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
            pid.complete(backendPid()); loaded.complete(null); await(start);
            assertThatThrownBy(() -> preparation.prepare(submissionCommand(fixture))).isInstanceOf(IllegalStateException.class);
            assertThat(stale.getStatus()).isEqualTo(KfeTransactionStatus.QUORUM_SYNC);
            status.setRollbackOnly();
            return true;
        }));
        await(loaded);
        inTransaction(status -> {
            preparation.prepare(submissionCommand(fixture)); start.complete(null); awaitBlocked(pid);
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue();
        verify(pricing, times(1)).prepare(any()); verify(gate, times(1)).requirePass(any());
        assertReservationStatus(fixture, KfeTransactionStatus.QUORUM_SYNC);
    }

    @Test
    void failureAfterPreparedStateFlushRollsBackQuoteProposalAndAcknowledgementAndAllowsOwningFlowRetry() {
        var fixture = submissionFixture();
        var preparation = submissionPreparation(submissionPricing(), passingSubmissionGate());
        var failure = new IllegalStateException("subsequent reservation unavailable");
        assertThatThrownBy(() -> inTransaction(status -> {
            preparation.prepare(submissionCommand(fixture)); entityManager.flush(); throw failure;
        })).isSameAs(failure);
        assertUnpreparedSubmission(fixture);
        inTransaction(status -> preparation.prepare(submissionCommand(fixture)));
        assertReservationStatus(fixture, KfeTransactionStatus.QUORUM_SYNC);
    }

    @Test
    void preparationScopesExecutionToItsOwnerAndRejectsChangedExternalReferenceBeforePricing() {
        var fixture = submissionFixture();
        var pricing = submissionPricing();
        var gate = passingSubmissionGate();
        var preparation = submissionPreparation(pricing, gate);
        assertThatThrownBy(() -> inTransaction(status -> preparation.prepare(new PreparePaymentSubmissionCommand(2L,
                fixture.id(), new RequestFingerprint("request-hash"), 50L, null, null, "fixture-reference", null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        assertThatThrownBy(() -> inTransaction(status -> preparation.prepare(new PreparePaymentSubmissionCommand(1L,
                fixture.id(), new RequestFingerprint("request-hash"), 50L, null, null, "different-reference", null))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pricing, gate);
        assertUnpreparedSubmission(fixture);
    }

    @Test
    void mutationOfIntentDuringPricingIsDetectedBeforeSavingQuoteOrCallingGateAndRollsBack() {
        var fixture = submissionFixture();
        var pricing = submissionPricing();
        var gate = passingSubmissionGate();
        when(pricing.prepare(any())).thenAnswer(invocation -> {
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setSourceWalletId(UUID.randomUUID());
            return preparedSubmissionPricing();
        });
        var preparation = submissionPreparation(pricing, gate);
        assertThatThrownBy(() -> inTransaction(status -> preparation.prepare(submissionCommand(fixture))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Payment intent changed during submission preparation.");
        verifyNoInteractions(gate);
        assertUnpreparedSubmission(fixture);
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getSourceWalletId()).isEqualTo(fixture.source());
            return null;
        });
    }

    private static PreparePaymentSubmissionUseCase submissionPreparation(PreparePaymentPricingUseCase pricing, PaymentSettlementGateUseCase gate) {
        var wallets = transactional(new PaymentWalletsAdapter(new PaymentWalletsService(walletLookup,
                mock(PaymentRecipientDirectoryPort.class))), PaymentWalletsUseCase.class);
        return transactional(new TransactionalPaymentSubmissionPreparationAdapter(new PreparePaymentSubmissionService(
                submissionState, wallets, pricing, new LegacyPaymentProposalHashAdapter(new KfeHashService()), gate,
                lifecycle, mock(PaymentSubmissionTelemetryPort.class))), PreparePaymentSubmissionUseCase.class);
    }

    private static ReservationFixture submissionFixture() {
        return inTransaction(status -> {
            var source = internalWallet(1L); source.setStatus(KfeWalletStatus.ACTIVE);
            var id = new CreatePaymentIntentService(intentStore).create(new CreatePaymentIntentCommand(1L,
                    new IdempotencyKey("prepare-" + UUID.randomUUID()), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                    source.getId(), null, 10_000L, " fixture-reference ", "memo", null));
            return new ReservationFixture(id, source.getId());
        });
    }
    private static PreparePaymentSubmissionCommand submissionCommand(ReservationFixture fixture) {
        return new PreparePaymentSubmissionCommand(1L, fixture.id(), new RequestFingerprint("request-hash"),
                50L, 12L, 3, " fixture-reference ", null);
    }
    private static PreparePaymentPricingUseCase submissionPricing() {
        var pricing = mock(PreparePaymentPricingUseCase.class);
        when(pricing.prepare(any())).thenReturn(preparedSubmissionPricing());
        return pricing;
    }
    private static PaymentSubmissionPricing preparedSubmissionPricing() {
        return new PaymentSubmissionPricing(100L, new PaymentPricingQuote(10_000L, 9_910L, 90L, 90L, 10_100L, 7),
                new PaymentDisplaySnapshot(new BigDecimal("61234.5678"), null, null, new BigDecimal("6.12"), null, null));
    }
    private static PaymentSettlementGateUseCase passingSubmissionGate() {
        var gate = mock(PaymentSettlementGateUseCase.class);
        when(gate.requirePass(any())).thenReturn(new PaymentSettlementGateResult(2, 3));
        return gate;
    }
    private static String submissionProposalHash(ReservationFixture fixture) {
        return new KfeHashService().sha256("KFE_TX_PROPOSAL|" + fixture.id().value() + "|1|ONCHAIN|OUTBOUND|"
                + fixture.source() + "|null|10000|9910|90|90|10100| fixture-reference |");
    }
    private static void assertUnpreparedSubmission(ReservationFixture fixture) {
        inTransaction(status -> {
            var row = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(row.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
            assertThat(row.getTotalDebitSats()).isZero();
            assertThat(row.getNetworkFeeSats()).isZero();
            assertThat(row.getDisplayBtcUsd()).isNull();
            assertThat(row.getQuorumProposalHash()).isNull();
            assertThat(row.getQuorumAckCount()).isZero();
            assertThat(outboxes.findByTransactionId(fixture.id().value())).isEmpty();
            return null;
        });
    }

    private static RouteLockedPaymentUseCase routingService(PaymentStatementPort statements,
            PaymentInitiatedNotificationPort notifications, PaymentVaultIntentPort vault) {
        return transactional(new TransactionalPaymentRoutingAdapter(new RouteLockedPaymentService(routingState,
                mock(SettleInternalPaymentUseCase.class), routingCommands, lifecycle, statements, notifications, vault)),
                RouteLockedPaymentUseCase.class);
    }

    private static ReservationFixture routingFixture() {
        var fixture = reservationFixture();
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setStatus(KfeTransactionStatus.LOCKED); tx.setRail(KfeRail.ONCHAIN);
            tx.setReceiverAmountSats(9_910L); tx.setNetworkFeeSats(100L);
            tx.setExternalReference("fixture-reference"); tx.setMemo("fixture memo");
            return null;
        });
        return fixture;
    }

    private static RouteLockedPaymentCommand routingCommand(ReservationFixture fixture) {
        return new RouteLockedPaymentCommand(1L, fixture.id(), " fixture-reference ", " fixture memo ", 12L, 3);
    }

    private static void assertRoutingState(ReservationFixture fixture, KfeTransactionStatus expected, int commandCount) {
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getStatus()).isEqualTo(expected);
            assertThat(outboxes.findByTransactionId(fixture.id().value())).hasSize(commandCount);
            return null;
        });
    }

    @Test
    void submissionCompletionRequiresTheOuterTransactionAndPersistsBindingBeforeCommitDelivery() {
        var fixture = completionFixture();
        var published = new CopyOnWriteArrayList<Long>();
        var mapper = completionMapper();
        var service = submissionCompletion(mapper, published);
        assertThatThrownBy(() -> service.complete(fixture.command()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING))
                .isInstanceOf(IllegalTransactionStateException.class);

        var result = inTransaction(status -> {
            var completed = service.complete(fixture.command());
            assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
            assertThat(published).isEmpty();
            return completed;
        });

        assertThat(result.id()).isEqualTo(fixture.id().value());
        assertThat(result.status()).isEqualTo(ExecutionStatus.EXECUTING);
        assertThat(result.grossAmountSats()).isEqualTo(10_000L);
        assertThat(result.quorumProposalHash()).isNull();
        assertThat(result.quorumAckCount()).isZero();
        assertThat(published).containsExactly(1L);
        var replay = inTransaction(status -> service.complete(fixture.command()));
        assertThat(replay).isEqualTo(result);
        assertThat(published).containsExactly(1L);
        verify(mapper, times(2)).toTransactionResponse(any(KfeTransactionEntity.class));
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void internalSubmissionCompletionPublishesOwnerAndDistinctRecipientOnlyAfterCommit() {
        var fixture = completionFixture();
        UUID destination = activeLookupWallet(2L);
        inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setRail(KfeRail.INTERNAL); tx.setDirection(KfeDirection.INTERNAL);
            tx.setStatus(KfeTransactionStatus.SETTLED); tx.setDestinationWalletId(destination);
            return null;
        });
        var published = new CopyOnWriteArrayList<Long>();
        var service = submissionCompletion(completionMapper(), published);
        var result = inTransaction(status -> {
            var completed = service.complete(fixture.command());
            assertThat(published).isEmpty();
            return completed;
        });
        assertThat(result.status()).isEqualTo(ExecutionStatus.SETTLED);
        assertThat(result.destinationWalletId()).isEqualTo(destination);
        assertThat(published).containsExactly(1L, 2L);
        assertCompletionBinding(fixture, fixture.id().value(), "SETTLED");
        inTransaction(status -> service.complete(fixture.command()));
        assertThat(published).containsExactly(1L, 2L);
    }

    @Test
    void completionRejectsForeignOwnerWrongKeyAndFingerprintWithoutBindingOrProjection() {
        var fixture = completionFixture();
        var mapper = completionMapper();
        var published = new CopyOnWriteArrayList<Long>();
        var service = submissionCompletion(mapper, published);
        assertThatThrownBy(() -> inTransaction(status -> service.complete(new CompletePaymentSubmissionCommand(
                2L, fixture.id(), fixture.key(), fixture.fingerprint())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTransaction(status -> service.complete(new CompletePaymentSubmissionCommand(
                1L, fixture.id(), new IdempotencyKey("foreign-" + UUID.randomUUID()), fixture.fingerprint()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inTransaction(status -> service.complete(new CompletePaymentSubmissionCommand(
                1L, fixture.id(), fixture.key(), new RequestFingerprint("b".repeat(64))))))
                .isInstanceOf(IdempotencyKeyConflict.class);
        assertCompletionBinding(fixture, null, "PENDING");
        assertThat(published).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void idempotencySqlRejectsFingerprintReplacementExecutionRebindingAndStatusRewriting() {
        var fixture = completionFixture();
        var wrongFingerprint = IdempotencyReservation.pending(fixture.key(), new RequestFingerprint("b".repeat(64)));
        wrongFingerprint.complete(fixture.id());
        assertThatThrownBy(() -> inTransaction(status -> idempotencyReservations.complete(
                1L, wrongFingerprint, ExecutionStatus.EXECUTING))).isInstanceOf(IdempotencyKeyConflict.class);
        assertCompletionBinding(fixture, null, "PENDING");

        assertThat((boolean) inTransaction(status -> idempotencyReservations.complete(
                1L, completedReservation(fixture), ExecutionStatus.EXECUTING))).isTrue();
        var rebound = IdempotencyReservation.pending(fixture.key(), fixture.fingerprint());
        rebound.complete(new PaymentExecutionId(UUID.randomUUID()));
        assertThatThrownBy(() -> inTransaction(status -> idempotencyReservations.complete(
                1L, rebound, ExecutionStatus.EXECUTING))).isInstanceOf(IdempotencyKeyConflict.class);
        assertThatThrownBy(() -> inTransaction(status -> idempotencyReservations.complete(
                1L, completedReservation(fixture), ExecutionStatus.SETTLED))).isInstanceOf(IdempotencyKeyConflict.class);
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void completionCannotRepairAnInconsistentUnboundIdempotencyRow() {
        var fixture = completionFixture();
        inTransaction(status -> {
            entityManager.find(KfeIdempotencyEntity.class, fixture.rowId()).setStatus("EXECUTING");
            return null;
        });
        assertThatThrownBy(() -> inTransaction(status -> idempotencyReservations.complete(
                1L, completedReservation(fixture), ExecutionStatus.EXECUTING))).isInstanceOf(IdempotencyKeyConflict.class);
        assertCompletionBinding(fixture, null, "EXECUTING");
    }

    @Test
    void concurrentIdempotencyCompletionRefreshesStalePendingRowAndReportsOnlyOneFirstBinding() throws Exception {
        var fixture = completionFixture();
        var pid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<Boolean> contender = executor.submit(() -> inTransaction(status -> {
            var stale = entityManager.find(KfeIdempotencyEntity.class, fixture.rowId());
            assertThat(stale.getTransactionId()).isNull();
            assertThat(stale.getStatus()).isEqualTo("PENDING");
            pid.complete(backendPid()); loaded.complete(null); await(start);
            boolean firstBinding = idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING);
            assertThat(stale.getTransactionId()).isEqualTo(fixture.id().value());
            assertThat(stale.getStatus()).isEqualTo("EXECUTING");
            return firstBinding;
        }));
        await(loaded);
        boolean firstBinding = inTransaction(status -> {
            boolean completed = idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING);
            start.complete(null); awaitBlocked(pid);
            return completed;
        });
        assertThat(firstBinding).isTrue();
        assertThat(contender.get(10, TimeUnit.SECONDS)).isFalse();
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void concurrentSubmissionCompletionProjectsBothCallsButRegistersOnlyOneDashboardDelivery() throws Exception {
        var fixture = completionFixture();
        var published = new CopyOnWriteArrayList<Long>();
        var mapper = completionMapper();
        var service = submissionCompletion(mapper, published);
        var pid = new CompletableFuture<Integer>();
        var loaded = new CompletableFuture<Void>();
        var start = new CompletableFuture<Void>();
        Future<UUID> contender = executor.submit(() -> inTransaction(status -> {
            entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            var stale = entityManager.find(KfeIdempotencyEntity.class, fixture.rowId());
            assertThat(stale.getTransactionId()).isNull();
            pid.complete(backendPid()); loaded.complete(null); await(start);
            var response = service.complete(fixture.command());
            assertThat(stale.getTransactionId()).isEqualTo(fixture.id().value());
            return response.id();
        }));
        await(loaded);
        inTransaction(status -> {
            service.complete(fixture.command()); start.complete(null); awaitBlocked(pid);
            assertThat(published).isEmpty();
            return null;
        });
        assertThat(contender.get(10, TimeUnit.SECONDS)).isEqualTo(fixture.id().value());
        assertThat(published).containsExactly(1L);
        verify(mapper, times(2)).toTransactionResponse(any(KfeTransactionEntity.class));
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void projectionFailureRollsBackIdempotencyAndCallerSqlAndSuppressesDashboardCallback() {
        var fixture = completionFixture();
        var mapper = mock(KfeResponseMapper.class);
        var published = new CopyOnWriteArrayList<Long>();
        var failure = new IllegalStateException("completion projection unavailable");
        when(mapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenAnswer(invocation -> {
            assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
            throw failure;
        });
        var service = submissionCompletion(mapper, published);
        assertThatThrownBy(() -> inTransaction(status -> {
            entityManager.find(KfeTransactionEntity.class, fixture.id().value()).setMemo("must roll back");
            return service.complete(fixture.command());
        })).isSameAs(failure);
        assertCompletionBinding(fixture, null, "PENDING");
        assertThat(published).isEmpty();
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value()).getMemo()).isEqualTo("fixture memo");
            return null;
        });
        inTransaction(status -> submissionCompletion(completionMapper(), published).complete(fixture.command()));
        assertThat(published).containsExactly(1L);
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void rollbackAfterSuccessfulProjectionLeavesReservationPendingAndDiscardsDashboardDelivery() {
        var fixture = completionFixture();
        var published = new CopyOnWriteArrayList<Long>();
        var service = submissionCompletion(completionMapper(), published);
        inTransaction(status -> {
            assertThat(service.complete(fixture.command()).id()).isEqualTo(fixture.id().value());
            assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
            status.setRollbackOnly();
            return null;
        });
        assertCompletionBinding(fixture, null, "PENDING");
        assertThat(published).isEmpty();
        inTransaction(status -> service.complete(fixture.command()));
        assertThat(published).containsExactly(1L);
    }

    @Test
    void aNewReservationAndPendingExecutionChangesSurviveCompletionRefreshInTheSameTransaction() {
        var published = new CopyOnWriteArrayList<Long>();
        var service = submissionCompletion(completionMapper(), published);
        var fixture = inTransaction(status -> {
            var fresh = completionFixture();
            var tx = entityManager.find(KfeTransactionEntity.class, fresh.id().value());
            tx.setMemo("pending completion memo");
            var response = service.complete(fresh.command());
            assertThat(response.memo()).isEqualTo("pending completion memo");
            assertThat(published).isEmpty();
            entityManager.flush();
            return fresh;
        });
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
        assertThat(published).containsExactly(1L);
    }

    @Test
    void rollbackRemovesNewReservationAndExecutionEvenAfterCompletionFlush() {
        var published = new CopyOnWriteArrayList<Long>();
        var service = submissionCompletion(completionMapper(), published);
        var fixture = inTransaction(status -> {
            var fresh = completionFixture();
            service.complete(fresh.command());
            entityManager.flush();
            status.setRollbackOnly();
            return fresh;
        });
        inTransaction(status -> {
            assertThat(entityManager.find(KfeIdempotencyEntity.class, fixture.rowId())).isNull();
            assertThat(entityManager.find(KfeTransactionEntity.class, fixture.id().value())).isNull();
            return null;
        });
        assertThat(published).isEmpty();
    }

    @Test
    void atomicIdempotencyInsertCreatesPendingAndNeverOverwritesExistingBindingHashStatusOrDates() {
        var command = idempotencyCommand(1L);
        var rowId = new KfeIdempotencyId(command.userId(), command.idempotencyKey().value());
        var expected = inTransaction(status -> {
            assertThat(idempotencyReservations.reserve(1L, IdempotencyReservation.pending(command.idempotencyKey(), command.fingerprint()))).isTrue();
            var row = entityManager.find(KfeIdempotencyEntity.class, rowId);
            assertThat(row.getTransactionId()).isNull();
            assertThat(row.getStatus()).isEqualTo("PENDING");
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getExpiresAt()).isNull();
            assertThat(idempotencyReservations.reserve(1L, IdempotencyReservation.pending(command.idempotencyKey(), new RequestFingerprint("b".repeat(64))))).isFalse();
            var tx = persistQueryPayment(1L, command.idempotencyKey().value(), "idempotency-fixture");
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            var binding = IdempotencyReservation.reconstitute(command.idempotencyKey(), command.fingerprint(), new PaymentExecutionId(tx.getId()));
            assertThat(idempotencyReservations.complete(1L, binding, ExecutionStatus.EXECUTING)).isTrue();
            row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0));
            entityManager.flush();
            return new Object[] {tx.getId(), row.getCreatedAt(), row.getExpiresAt()};
        });
        inTransaction(status -> {
            assertThat(idempotencyReservations.reserve(1L, IdempotencyReservation.pending(command.idempotencyKey(), new RequestFingerprint("c".repeat(64))))).isFalse();
            var row = entityManager.find(KfeIdempotencyEntity.class, rowId);
            assertThat(row.getRequestHash()).isEqualTo(command.fingerprint().value());
            assertThat(row.getTransactionId()).isEqualTo(expected[0]);
            assertThat(row.getStatus()).isEqualTo("EXECUTING");
            assertThat(row.getCreatedAt()).isEqualTo(expected[1]);
            assertThat(row.getExpiresAt()).isEqualTo(expected[2]);
            assertThat(entityManager.createQuery("select count(r) from KfeIdempotencyEntity r where r.id = :id", Long.class)
                    .setParameter("id", rowId).getSingleResult()).isEqualTo(1L);
            return null;
        });
    }

    @Test
    void concurrentSameKeyWaitsForCommitThenReplaysWithoutAnotherIntentOutboxLedgerCallOrDashboard() throws Exception {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        var ledger = mock(PaymentLedgerPort.class);
        var published = new CopyOnWriteArrayList<Long>();
        var pid = new CompletableFuture<Integer>();
        var contender = new AtomicReference<Future<IdempotentBranch>>();

        var winner = inTransaction(status -> {
            var first = submitIdempotentFixture(access, command, ledger, published);
            contender.set(executor.submit(() -> inTransaction(other -> {
                pid.complete(backendPid());
                return submitIdempotentFixture(access, command, ledger, published);
            })));
            awaitBlocked(pid);
            assertThat(published).isEmpty();
            return first;
        });
        var replay = contender.get().get(10, TimeUnit.SECONDS);
        assertThat(winner.inserted()).isTrue();
        assertThat(replay.inserted()).isFalse();
        assertThat(replay.id()).isEqualTo(winner.id());
        assertThat(published).containsExactly(1L);
        verify(ledger, times(1)).reserve(eq(winner.id()), any(), eq(10_100L));
        assertIdempotentFixtureRows(command, winner.id(), 1L);
    }

    @Test
    void concurrentInsertProceedsAfterWinnerRollbackWithoutAnOrphanReservationOrPayment() throws Exception {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        var ledger = mock(PaymentLedgerPort.class);
        var published = new CopyOnWriteArrayList<Long>();
        var pid = new CompletableFuture<Integer>();
        var contender = new AtomicReference<Future<IdempotentBranch>>();
        var rolledBack = inTransaction(status -> {
            var first = submitIdempotentFixture(access, command, ledger, published);
            entityManager.flush();
            contender.set(executor.submit(() -> inTransaction(other -> {
                pid.complete(backendPid());
                return submitIdempotentFixture(access, command, ledger, published);
            })));
            awaitBlocked(pid);
            status.setRollbackOnly();
            return first;
        });
        var winner = contender.get().get(10, TimeUnit.SECONDS);
        assertThat(winner.inserted()).isTrue();
        assertThat(winner.id()).isNotEqualTo(rolledBack.id());
        assertThat(published).containsExactly(1L);
        // The failed transaction's mock invocation is not reversible SQL; only its persisted rows must disappear.
        verify(ledger, times(2)).reserve(any(), any(), eq(10_100L));
        inTransaction(status -> {
            assertThat(entityManager.find(KfeTransactionEntity.class, rolledBack.id().value())).isNull();
            assertThat(outboxes.findByTransactionId(rolledBack.id().value())).isEmpty();
            return null;
        });
        assertIdempotentFixtureRows(command, winner.id(), 1L);
    }

    @Test
    void concurrentDifferentFingerprintWaitsForWinnerAndFailsWithoutDuplicatingFinancialEffects() throws Exception {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        var conflict = new ReservePaymentIdempotencyCommand(1L, command.idempotencyKey(), new RequestFingerprint("b".repeat(64)));
        var ledger = mock(PaymentLedgerPort.class);
        var published = new CopyOnWriteArrayList<Long>();
        var pid = new CompletableFuture<Integer>();
        var contender = new AtomicReference<Future<Throwable>>();
        var winner = inTransaction(status -> {
            var first = submitIdempotentFixture(access, command, ledger, published);
            contender.set(executor.submit(() -> org.assertj.core.api.Assertions.catchThrowable(() -> inTransaction(other -> {
                pid.complete(backendPid());
                return submitIdempotentFixture(access, conflict, ledger, published);
            }))));
            awaitBlocked(pid);
            return first;
        });
        assertThat(contender.get().get(10, TimeUnit.SECONDS)).isInstanceOf(IdempotencyKeyConflict.class);
        assertThat(published).containsExactly(1L);
        verify(ledger, times(1)).reserve(any(), any(), eq(10_100L));
        assertIdempotentFixtureRows(command, winner.id(), 1L);
    }

    @Test
    void usersCanReserveTheSameRawKeyIndependentlyWhileTheOtherUsersTransactionRemainsOpen() throws Exception {
        var access = idempotencyAccess(completionMapper());
        var firstCommand = idempotencyCommand(1L);
        var secondCommand = new ReservePaymentIdempotencyCommand(2L, firstCommand.idempotencyKey(), firstCommand.fingerprint());
        var ledger = mock(PaymentLedgerPort.class);
        var published = new CopyOnWriteArrayList<Long>();
        var second = new AtomicReference<IdempotentBranch>();
        var first = inTransaction(status -> {
            var branch = submitIdempotentFixture(access, firstCommand, ledger, published);
            second.set(await(executor.submit(() -> inTransaction(other -> submitIdempotentFixture(access, secondCommand, ledger, published)))));
            assertThat(published).containsExactly(2L);
            return branch;
        });
        assertThat(first.id()).isNotEqualTo(second.get().id());
        assertThat(published).containsExactly(2L, 1L);
        assertIdempotentFixtureRows(firstCommand, first.id(), 1L);
        assertIdempotentFixtureRows(secondCommand, second.get().id(), 1L);
    }

    @Test
    void freshReservationProjectionAndUnlockedExecutionRefreshIgnoreStaleManagedPendingAndExecutingState() {
        var fixture = completionFixture();
        var access = idempotencyAccess(completionMapper());
        inTransaction(status -> {
            var staleReservation = entityManager.find(KfeIdempotencyEntity.class, fixture.rowId());
            var staleExecution = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            assertThat(staleReservation.getTransactionId()).isNull();
            assertThat(staleExecution.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            await(executor.submit(() -> inTransaction(other -> {
                idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING);
                var current = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
                current.setStatus(KfeTransactionStatus.SETTLED);
                current.setMemo("current committed memo");
                return null;
            })));
            var response = access.reads().find(new GetIdempotentPaymentQuery(1L, fixture.key(), fixture.fingerprint())).orElseThrow();
            assertThat(response.id()).isEqualTo(fixture.id().value());
            assertThat(response.status()).isEqualTo(ExecutionStatus.SETTLED);
            assertThat(response.memo()).isEqualTo("current committed memo");
            // Scalar lookup bypasses the stale reservation without clearing the persistence context.
            assertThat(staleReservation.getTransactionId()).isNull();
            assertThat(staleExecution.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
            return null;
        });
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void replayReadsCurrentCommittedStateWithoutWaitingForAnotherTransactionsExecutionWriteLock() {
        var fixture = completionFixture();
        inTransaction(status -> idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING));
        var access = idempotencyAccess(completionMapper());
        inTransaction(status -> {
            var locked = transactions.findByIdAndUserIdForUpdate(fixture.id().value(), 1L).orElseThrow();
            locked.setStatus(KfeTransactionStatus.SETTLED);
            entityManager.flush();
            var replay = await(executor.submit(() -> access.reads()
                    .find(new GetIdempotentPaymentQuery(1L, fixture.key(), fixture.fingerprint())).orElseThrow()));
            assertThat(replay.status()).isEqualTo(ExecutionStatus.EXECUTING);
            return null;
        });
        assertThat(access.reads().find(new GetIdempotentPaymentQuery(1L, fixture.key(), fixture.fingerprint())).orElseThrow().status())
                .isEqualTo(ExecutionStatus.SETTLED);
    }

    @Test
    void replayCannotCrossOwnerOrReservationKeyEvenWhenPersistedBindingPointsAtAnotherPayment() {
        var fixture = completionFixture();
        inTransaction(status -> idempotencyReservations.complete(1L, completedReservation(fixture), ExecutionStatus.EXECUTING));
        var mapper = completionMapper();
        var access = idempotencyAccess(mapper);
        assertThat(access.queries().findOwnedByIdAndKey(2L, fixture.id(), fixture.key())).isEmpty();
        assertThat(access.queries().findOwnedByIdAndKey(1L, fixture.id(), new IdempotencyKey("wrong-key"))).isEmpty();
        assertThat(access.reads().find(new GetIdempotentPaymentQuery(2L, fixture.key(), fixture.fingerprint()))).isEmpty();
        var differentKey = new IdempotencyKey("different-binding-" + UUID.randomUUID());
        inTransaction(status -> {
            assertThat(idempotencyReservations.reserve(2L, IdempotencyReservation.pending(fixture.key(), fixture.fingerprint()))).isTrue();
            entityManager.find(KfeIdempotencyEntity.class, new KfeIdempotencyId(2L, fixture.key().value())).setTransactionId(fixture.id().value());
            entityManager.find(KfeIdempotencyEntity.class, new KfeIdempotencyId(2L, fixture.key().value())).setStatus("EXECUTING");
            assertThat(idempotencyReservations.reserve(1L, IdempotencyReservation.pending(differentKey, fixture.fingerprint()))).isTrue();
            var wrongKeyRow = entityManager.find(KfeIdempotencyEntity.class, new KfeIdempotencyId(1L, differentKey.value()));
            wrongKeyRow.setTransactionId(fixture.id().value()); wrongKeyRow.setStatus("EXECUTING");
            return null;
        });
        assertThatThrownBy(() -> access.reads().find(new GetIdempotentPaymentQuery(2L, fixture.key(), fixture.fingerprint())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Idempotent transaction record is missing.");
        assertThatThrownBy(() -> access.reads().find(new GetIdempotentPaymentQuery(1L, differentKey, fixture.fingerprint())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Idempotent transaction record is missing.");
        verifyNoInteractions(mapper);
        assertCompletionBinding(fixture, fixture.id().value(), "EXECUTING");
    }

    @Test
    void outerRollbackRemovesAtomicReservationIntentOutboxAndCompletionWhileSuppressingAfterCommitDashboard() {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        var ledger = mock(PaymentLedgerPort.class);
        var published = new CopyOnWriteArrayList<Long>();
        var branch = inTransaction(status -> {
            var created = submitIdempotentFixture(access, command, ledger, published);
            entityManager.flush();
            assertThat(outboxes.findByTransactionId(created.id().value())).hasSize(1);
            assertThat(published).isEmpty();
            status.setRollbackOnly();
            return created;
        });
        assertThat(published).isEmpty();
        inTransaction(status -> {
            assertThat(idempotencyReservations.find(command.userId(), command.idempotencyKey())).isEmpty();
            assertThat(entityManager.find(KfeTransactionEntity.class, branch.id().value())).isNull();
            assertThat(outboxes.findByTransactionId(branch.id().value())).isEmpty();
            return null;
        });
        var retry = inTransaction(status -> submitIdempotentFixture(access, command, ledger, published));
        assertThat(retry.inserted()).isTrue();
        assertThat(published).containsExactly(1L);
        assertIdempotentFixtureRows(command, retry.id(), 1L);
    }

    @Test
    void retryAfterPostCommitCallbackFailureReplaysWithoutAnotherPaymentOrFinancialEffect() {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        var ledger = mock(PaymentLedgerPort.class);
        var failure = new IllegalStateException("dashboard unavailable after commit");
        var completedId = new AtomicReference<PaymentExecutionId>();
        var unavailable = new CopyOnWriteArrayList<Long>() {
            @Override public boolean add(Long userId) { throw failure; }
        };
        assertThatThrownBy(() -> inTransaction(status -> {
            var branch = submitIdempotentFixture(access, command, ledger, unavailable);
            completedId.set(branch.id());
            return branch;
        })).isSameAs(failure);

        assertIdempotentFixtureRows(command, completedId.get(), 1L);
        var published = new CopyOnWriteArrayList<Long>();
        var retry = inTransaction(status -> submitIdempotentFixture(access, command, ledger, published));
        assertThat(retry.inserted()).isFalse();
        assertThat(retry.id()).isEqualTo(completedId.get());
        assertThat(published).isEmpty();
        verify(ledger).reserve(eq(completedId.get()), any(), eq(10_100L));
        assertIdempotentFixtureRows(command, completedId.get(), 1L);
    }

    @Test
    void reserveRequiresOuterReadCommittedTransactionAndRejectsRepeatableReadBeforeInsert() {
        var access = idempotencyAccess(completionMapper());
        var command = idempotencyCommand(1L);
        assertThatThrownBy(() -> access.reserves().reserve(command)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> idempotencyReservations.reserve(command.userId(),
                IdempotencyReservation.pending(command.idempotencyKey(), command.fingerprint())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inTransaction(status -> {
            entityManager.createNativeQuery("set transaction isolation level repeatable read").executeUpdate();
            return access.reserves().reserve(command);
        })).isInstanceOf(IllegalStateException.class).hasMessage("Idempotency reservation requires READ_COMMITTED isolation.");
        assertThat(access.reads().find(new GetIdempotentPaymentQuery(command.userId(), command.idempotencyKey(), command.fingerprint())))
                .isEmpty();
    }

    private static IdempotencyAccess idempotencyAccess(KfeResponseMapper mapper) {
        var queryPort = transactional(new JpaPaymentIdempotencyQueryAdapter(transactions, entityManager, mapper), PaymentIdempotencyQueryPort.class);
        var get = new GetIdempotentPaymentService(idempotencyReservations, queryPort);
        var reserve = new ReservePaymentIdempotencyService(idempotencyReservations, get);
        var input = transactional(new TransactionalPaymentIdempotencyAdapter(get, reserve), GetIdempotentPaymentUseCase.class);
        return new IdempotencyAccess(input, (ReservePaymentIdempotencyUseCase) input, queryPort);
    }

    /** Reserve/replay branch only; preparation is a fixture LOCKED state, not the full submit or ledger implementation. */
    private static IdempotentBranch submitIdempotentFixture(IdempotencyAccess access, ReservePaymentIdempotencyCommand command,
            PaymentLedgerPort ledger, List<Long> published) {
        var reservation = access.reserves().reserve(command);
        if (!reservation.reserved()) { return new IdempotentBranch(false, new PaymentExecutionId(reservation.existingPayment().id())); }
        UUID source = activeLookupWallet(command.userId());
        var id = new CreatePaymentIntentService(intentStore).create(new CreatePaymentIntentCommand(command.userId(),
                command.idempotencyKey(), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, null, 10_000L,
                "fixture-reference", "fixture memo", null));
        var tx = entityManager.find(KfeTransactionEntity.class, id.value());
        tx.setStatus(KfeTransactionStatus.LOCKED); tx.setReceiverAmountSats(9_910L); tx.setNetworkFeeSats(100L);
        tx.setKeroseneFeeSats(90L); tx.setTotalDebitSats(10_100L); tx.setQuorumProposalHash("a".repeat(64));
        ledger.reserve(id, source, 10_100L);
        routingService(mock(PaymentStatementPort.class), mock(PaymentInitiatedNotificationPort.class), mock(PaymentVaultIntentPort.class))
                .route(new RouteLockedPaymentCommand(command.userId(), id, "fixture-reference", "fixture memo", 12L, 3));
        submissionCompletion(completionMapper(), published).complete(new CompletePaymentSubmissionCommand(command.userId(), id,
                command.idempotencyKey(), command.fingerprint()));
        return new IdempotentBranch(true, id);
    }

    private static ReservePaymentIdempotencyCommand idempotencyCommand(long userId) {
        return new ReservePaymentIdempotencyCommand(userId, new IdempotencyKey(" pg-idempotency-" + UUID.randomUUID() + " "),
                new RequestFingerprint("a".repeat(64)));
    }

    private static void assertIdempotentFixtureRows(ReservePaymentIdempotencyCommand command, PaymentExecutionId id, long expectedExecutions) {
        inTransaction(status -> {
            var binding = idempotencyReservations.find(command.userId(), command.idempotencyKey()).orElseThrow();
            assertThat(binding.fingerprint()).isEqualTo(command.fingerprint());
            assertThat(binding.completedExecutionId()).isEqualTo(id);
            assertThat(entityManager.createQuery("select count(p) from KfeTransactionEntity p where p.userId = :user and p.idempotencyKey = :key", Long.class)
                    .setParameter("user", command.userId()).setParameter("key", command.idempotencyKey().value()).getSingleResult()).isEqualTo(expectedExecutions);
            assertThat(outboxes.findByTransactionId(id.value())).hasSize(1);
            assertThat(entityManager.find(KfeTransactionEntity.class, id.value()).getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            return null;
        });
    }

    private record IdempotencyAccess(GetIdempotentPaymentUseCase reads, ReservePaymentIdempotencyUseCase reserves,
            PaymentIdempotencyQueryPort queries) {}
    private record IdempotentBranch(boolean inserted, PaymentExecutionId id) {}

    private static CompletePaymentSubmissionUseCase submissionCompletion(KfeResponseMapper mapper, List<Long> published) {
        var state = transactional(new JpaPaymentSubmissionCompletionAdapter(transactions, entityManager, mapper),
                PaymentSubmissionCompletionPort.class);
        PaymentSubmissionDashboardPort dashboards = userId -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    published.add(userId);
                }
            });
        };
        return transactional(new TransactionalPaymentSubmissionCompletionAdapter(new CompletePaymentSubmissionService(
                state, idempotencyReservations, walletLookup, dashboards)), CompletePaymentSubmissionUseCase.class);
    }

    private static CompletionFixture completionFixture() {
        var fixture = routingFixture();
        return inTransaction(status -> {
            var tx = entityManager.find(KfeTransactionEntity.class, fixture.id().value());
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            var key = new IdempotencyKey(tx.getIdempotencyKey());
            var fingerprint = new RequestFingerprint("a".repeat(64));
            idempotencyReservations.reserve(1L, IdempotencyReservation.pending(key, fingerprint));
            return new CompletionFixture(fixture.id(), key, fingerprint);
        });
    }

    private static IdempotencyReservation completedReservation(CompletionFixture fixture) {
        var reservation = IdempotencyReservation.pending(fixture.key(), fixture.fingerprint());
        reservation.complete(fixture.id());
        return reservation;
    }

    private static void assertCompletionBinding(CompletionFixture fixture, UUID executionId, String expectedStatus) {
        inTransaction(status -> {
            var row = entityManager.find(KfeIdempotencyEntity.class, fixture.rowId());
            assertThat(row).isNotNull();
            assertThat(row.getUserId()).isEqualTo(1L);
            assertThat(row.getRequestHash()).isEqualTo(fixture.fingerprint().value());
            assertThat(row.getTransactionId()).isEqualTo(executionId);
            assertThat(row.getStatus()).isEqualTo(expectedStatus);
            return null;
        });
    }

    private static KfeResponseMapper completionMapper() {
        var mapper = mock(KfeResponseMapper.class);
        when(mapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenAnswer(invocation -> {
            var tx = invocation.getArgument(0, KfeTransactionEntity.class);
            var response = mock(KfeTransactionResponse.class);
            when(response.id()).thenReturn(tx.getId());
            when(response.status()).thenReturn(tx.getStatus());
            when(response.rail()).thenReturn(tx.getRail());
            when(response.direction()).thenReturn(tx.getDirection());
            when(response.sourceWalletId()).thenReturn(tx.getSourceWalletId());
            when(response.destinationWalletId()).thenReturn(tx.getDestinationWalletId());
            when(response.grossAmountSats()).thenReturn(tx.getGrossAmountSats());
            when(response.memo()).thenReturn(tx.getMemo());
            return response;
        });
        return mapper;
    }

    private record CompletionFixture(PaymentExecutionId id, IdempotencyKey key, RequestFingerprint fingerprint) {
        CompletePaymentSubmissionCommand command() {
            return new CompletePaymentSubmissionCommand(1L, id, key, fingerprint);
        }
        KfeIdempotencyId rowId() {
            return new KfeIdempotencyId(1L, key.value());
        }
    }

    @Test
    void walletSourceLookupScopesTheRowToItsOwnerAndRequiresTheSubmissionTransaction() {
        UUID walletId = activeLookupWallet(1L);

        assertThatThrownBy(() -> walletLookup.lockOwnedSource(1L, walletId))
                .isInstanceOf(IllegalTransactionStateException.class);

        inTransaction(status -> {
            assertThat(walletLookup.findOwnedDestination(2L, walletId)).isEmpty();
            assertThat(walletLookup.findOwnedDestination(1L, UUID.randomUUID())).isEmpty();
            assertThat(walletLookup.findOwnedDestination(1L, walletId).orElseThrow().userId()).isEqualTo(1L);
            assertThat(walletLookup.lockOwnedSource(2L, walletId)).isEmpty();
            assertThat(walletLookup.lockOwnedSource(1L, UUID.randomUUID())).isEmpty();
            var snapshot = walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
            assertThat(snapshot.id()).isEqualTo(walletId);
            assertThat(snapshot.userId()).isEqualTo(1L);
            assertThat(snapshot.usable()).isTrue();
            return null;
        });
    }

    @Test
    void walletSourceLookupRefreshesPreviouslyManagedActiveWalletAfterWaitingForArchival() throws Exception {
        UUID walletId = activeLookupWallet(1L);
        var lookupPid = new CompletableFuture<Integer>();

        Future<Boolean> refreshed = inTransaction(status -> {
            walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
            var wallet = entityManager.find(KfeWalletEntity.class, walletId);
            wallet.setStatus(KfeWalletStatus.ARCHIVED);
            entityManager.flush();
            Future<Boolean> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                var stale = entityManager.find(KfeWalletEntity.class, walletId);
                assertThat(stale.getStatus()).isEqualTo(KfeWalletStatus.ACTIVE);
                lookupPid.complete(backendPid());

                var snapshot = walletLookup.lockOwnedSource(1L, walletId).orElseThrow();

                assertThat(entityManager.find(KfeWalletEntity.class, walletId)).isSameAs(stale);
                assertThat(stale.getStatus()).isEqualTo(KfeWalletStatus.ARCHIVED);
                assertThat(snapshot.active()).isFalse();
                assertThat(snapshot.usable()).isFalse();
                assertThatThrownBy(() -> snapshot.requireSpendable("source"))
                        .isInstanceOf(IllegalStateException.class).hasMessage("source wallet is not active.");
                return true;
            }));
            awaitBlocked(lookupPid);
            return attempt;
        });

        assertThat(refreshed.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void walletSourceLookupRechecksSqlOwnerAfterWaitingDespiteAPreviouslyManagedOwnedWallet() throws Exception {
        UUID walletId = activeLookupWallet(1L);
        var lookupPid = new CompletableFuture<Integer>();

        Future<Boolean> rejected = inTransaction(status -> {
            walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
            entityManager.find(KfeWalletEntity.class, walletId).setUserId(2L);
            entityManager.flush();
            Future<Boolean> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                var stale = entityManager.find(KfeWalletEntity.class, walletId);
                assertThat(stale.getUserId()).isEqualTo(1L);
                lookupPid.complete(backendPid());

                return walletLookup.lockOwnedSource(1L, walletId).isEmpty();
            }));
            awaitBlocked(lookupPid);
            return attempt;
        });

        assertThat(rejected.get(10, TimeUnit.SECONDS)).isTrue();
        inTransaction(status -> {
            assertThat(walletLookup.lockOwnedSource(1L, walletId)).isEmpty();
            assertThat(walletLookup.lockOwnedSource(2L, walletId).orElseThrow().userId()).isEqualTo(2L);
            return null;
        });
    }

    @Test
    void executionSourceProjectionIsOwnerScopedAndRequiresThePreparationTransaction() {
        UUID walletId = activeLookupWallet(1L);
        assertThatThrownBy(() -> executionSourceWallets.findOwned(1L, walletId))
                .isInstanceOf(IllegalTransactionStateException.class);
        inTransaction(status -> {
            assertThat(executionSourceWallets.findOwned(2L, walletId)).isEmpty();
            assertThat(executionSourceWallets.findOwned(1L, UUID.randomUUID())).isEmpty();
            assertThat(executionSourceWallets.findOwned(0L, walletId)).isEmpty();
            assertThat(executionSourceWallets.findOwned(1L, null)).isEmpty();
            var snapshot = executionSourceWallets.findOwned(1L, walletId).orElseThrow();
            assertThat(snapshot.usableFor(1L, walletId)).isTrue();
            assertThat(snapshot.usableFor(2L, walletId)).isFalse();
            assertThat(snapshot.usableFor(1L, UUID.randomUUID())).isFalse();
            assertThat(snapshot.asset()).isEqualTo("BTC");
            assertThat(snapshot.label()).isNotBlank();
            return null;
        });
    }

    @Test
    void executionSourceProjectionSeesCommittedArchivalDespitePreviouslyManagedActiveEntity() {
        UUID walletId = activeLookupWallet(1L);
        inTransaction(status -> {
            var stale = entityManager.find(KfeWalletEntity.class, walletId);
            assertThat(stale.getStatus()).isEqualTo(KfeWalletStatus.ACTIVE);
            Future<?> archive = executor.submit(() -> inTransaction(otherStatus -> {
                walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
                var current = entityManager.find(KfeWalletEntity.class, walletId);
                current.setStatus(KfeWalletStatus.ARCHIVED);
                current.setSpendable(false);
                return null;
            }));
            awaitTask(archive);
            var snapshot = executionSourceWallets.findOwned(1L, walletId).orElseThrow();
            assertThat(snapshot.active()).isFalse();
            assertThat(snapshot.spendable()).isFalse();
            assertThat(snapshot.usableFor(1L, walletId)).isFalse();
            assertThat(entityManager.find(KfeWalletEntity.class, walletId)).isSameAs(stale);
            assertThat(stale.getStatus()).isEqualTo(KfeWalletStatus.ACTIVE);
            return null;
        });
    }

    @Test
    void executionSourceProjectionSeesCommittedOwnershipChangeDespiteManagedOldOwner() {
        UUID walletId = activeLookupWallet(1L);
        inTransaction(status -> {
            var stale = entityManager.find(KfeWalletEntity.class, walletId);
            assertThat(stale.getUserId()).isEqualTo(1L);
            Future<?> transfer = executor.submit(() -> inTransaction(otherStatus -> {
                walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
                entityManager.find(KfeWalletEntity.class, walletId).setUserId(2L);
                return null;
            }));
            awaitTask(transfer);
            assertThat(executionSourceWallets.findOwned(1L, walletId)).isEmpty();
            assertThat(executionSourceWallets.findOwned(2L, walletId).orElseThrow().usableFor(2L, walletId)).isTrue();
            assertThat(stale.getUserId()).isEqualTo(1L);
            return null;
        });
    }

    @Test
    void executionSourceProjectionAddsNoWalletLockAndDoesNotClaimToRevokeUncommittedArchival() {
        UUID walletId = activeLookupWallet(1L);
        inTransaction(status -> {
            walletLookup.lockOwnedSource(1L, walletId).orElseThrow();
            entityManager.find(KfeWalletEntity.class, walletId).setStatus(KfeWalletStatus.ARCHIVED);
            entityManager.flush();
            Future<Boolean> read = executor.submit(() -> inTransaction(otherStatus ->
                    executionSourceWallets.findOwned(1L, walletId).orElseThrow().usableFor(1L, walletId)));
            // Must finish while this transaction still holds the wallet write lock.
            assertThat(awaitTask(read)).isEqualTo(true);
            return null;
        });
        inTransaction(status -> {
            assertThat(executionSourceWallets.findOwned(1L, walletId).orElseThrow().active()).isFalse();
            return null;
        });
    }

    private static <T> T awaitTask(Future<T> task) {
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static UUID activeLookupWallet(long owner) {
        return inTransaction(status -> {
            var wallet = internalWallet(owner);
            wallet.setStatus(KfeWalletStatus.ACTIVE);
            return wallet.getId();
        });
    }

    private static Fixture createPayment() {
        return createPayment(null, 0L);
    }

    private static Fixture createPayment(UUID sourceWalletId, long totalDebitSats) {
        return inTransaction(status -> {
            var payment = new KfeTransactionEntity();
            payment.setIdempotencyKey("cancel-test-" + UUID.randomUUID());
            payment.setUserId(1L);
            payment.setRail(KfeRail.ONCHAIN);
            payment.setDirection(KfeDirection.OUTBOUND);
            payment.setStatus(KfeTransactionStatus.EXECUTING);
            payment.setSourceWalletId(sourceWalletId);
            payment.setTotalDebitSats(totalDebitSats);
            entityManager.persist(payment);
            var command = new KfeExecutionOutboxEntity();
            command.setTransactionId(payment.getId());
            command.setOperation("ONCHAIN_OUTBOUND");
            command.setPayloadHash("0".repeat(64));
            command.setPayloadJson("{}");
            entityManager.persist(command);
            return new Fixture(new PaymentExecutionId(payment.getId()), command.getId());
        });
    }

    private static void publishAfterCommit(AtomicBoolean published) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                published.set(true);
            }
        });
    }

    @Test
    void peerQueryWaitsForCancellationAndDoesNotReopenCancelledRequest() throws Exception {
        UUID requestId = createRequest();
        var observerPid = new CompletableFuture<Integer>();
        Future<Integer> observed = inTransaction(status -> {
            requestLock.lock(1L, requestId);
            requests.findById(requestId).orElseThrow().cancel();
            entityManager.flush();
            Future<Integer> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                observerPid.complete(backendPid());
                return requests.findOpenByAddressAndRailForUpdate(
                        requestId.toString(), KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 1L).size();
            }));
            awaitBlocked(observerPid);
            return attempt;
        });

        assertThat(observed.get(10, TimeUnit.SECONDS)).isZero();
        inTransaction(status -> {
            assertThat(requests.findById(requestId).orElseThrow().getStatus())
                    .isEqualTo(KfePaymentRequestStatus.CANCELLED);
            return null;
        });
    }

    @Test
    void cancellationRefreshesRequestAfterPeerWinsTheLock() throws Exception {
        UUID requestId = createRequest();
        var cancellationPid = new CompletableFuture<Integer>();
        Future<KfePaymentRequestStatus> observed = inTransaction(status -> {
            var request = requests.findOpenByAddressAndRailForUpdate(
                    requestId.toString(), KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 1L).getFirst();
            request.markPaid(UUID.randomUUID());
            Future<KfePaymentRequestStatus> attempt = executor.submit(() -> inTransaction(otherStatus -> {
                var stale = requests.findById(requestId).orElseThrow();
                assertThat(stale.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
                cancellationPid.complete(backendPid());
                requestLock.lock(1L, requestId);
                return stale.getStatus();
            }));
            awaitBlocked(cancellationPid);
            return attempt;
        });

        assertThat(observed.get(10, TimeUnit.SECONDS)).isEqualTo(KfePaymentRequestStatus.PAID);
    }

    private static UUID createRequest() {
        return createRequest(1L);
    }

    private static UUID createRequest(long userId) {
        return inTransaction(status -> {
            var request = new KfePaymentRequestEntity();
            request.setUserId(userId);
            request.setWalletId(UUID.randomUUID());
            request.setPublicId(request.getId().toString());
            request.setAddress(request.getId().toString());
            entityManager.persist(request);
            return request.getId();
        });
    }

    private static KfeTransactionEntity persistQueryPayment(long userId, String idempotencyKey,
                                                           String externalReference) {
        var payment = new KfeTransactionEntity();
        payment.setUserId(userId);
        payment.setIdempotencyKey(idempotencyKey == null ? "query-test-" + UUID.randomUUID() : idempotencyKey);
        payment.setExternalReference(externalReference);
        payment.setRail(KfeRail.ONCHAIN);
        payment.setDirection(KfeDirection.OUTBOUND);
        entityManager.persist(payment);
        return payment;
    }

    private static Future<Integer> startClaim(UUID commandId, CompletableFuture<Integer> started) {
        return executor.submit(() -> inTransaction(status -> {
            started.complete(backendPid());
            return claimImmediate(commandId);
        }));
    }

    private static int claimImmediate(UUID commandId) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        return outboxes.claimImmediate(commandId, now, "sync-worker", UUID.randomUUID(), now.plusMinutes(1));
    }

    private static String commandStatus(UUID commandId) {
        return inTransaction(status -> outboxes.findById(commandId).orElseThrow().getStatus());
    }

    private static int backendPid() {
        return ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
    }

    private static <T> T inTransaction(Function<TransactionStatus, T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.createNativeQuery("set local lock_timeout = '10s'").executeUpdate();
            return work.apply(status);
        });
    }

    private static void awaitBlocked(CompletableFuture<Integer> started) {
        try {
            int pid = started.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try (var connection = DriverManager.getConnection(databaseUrl, DATABASE_USER, "");
                 var query = connection.prepareStatement(
                         "select wait_event_type = 'Lock' from pg_stat_activity where pid = ?")) {
                query.setInt(1, pid);
                do {
                    try (var result = query.executeQuery()) {
                        if (result.next() && result.getBoolean(1)) {
                            return;
                        }
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                } while (System.nanoTime() < deadline);
            }
            throw new AssertionError("Expected PostgreSQL backend " + pid + " to wait on a real row lock");
        } catch (Exception exception) {
            throw new AssertionError("Could not observe the concurrent PostgreSQL transaction", exception);
        }
    }

    private static <T> T await(Future<T> task) {
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent PostgreSQL operation failed", exception);
        }
    }

    private record Fixture(PaymentExecutionId executionId, UUID commandId) {}
}
