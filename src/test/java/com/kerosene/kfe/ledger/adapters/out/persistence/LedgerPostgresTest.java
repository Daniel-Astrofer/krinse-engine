package com.kerosene.kfe.ledger.adapters.out.persistence;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.application.service.LedgerService;
import com.kerosene.kfe.ledger.application.service.LedgerSettlementService;
import com.kerosene.kfe.ledger.domain.LedgerInvariantViolation;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeUserStatementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeUserStatementRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL proof for the extracted ledger adapters. */
@EnabledIfEnvironmentVariable(named = "KFE_LEDGER_POSTGRES_URL", matches = ".+")
class LedgerPostgresTest {
    private static EntityManagerFactory emf;
    private static EntityManager entityManager;
    private static PlatformTransactionManager txManager;
    private static KfeBalanceRepository balances;
    private static KfeBalanceMovementRepository movements;
    private static KfeTransactionRepository transactions;
    private static KfeUserStatementRepository statements;
    private static KfeStatementService statementService;
    private static LedgerAccountPort accounts;
    private static LedgerPostingPort postings;
    private static LedgerService ledger;
    private static LedgerSettlementService settlement;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void openDatabase() {
        String url = System.getenv("KFE_LEDGER_POSTGRES_URL");
        emf = new Configuration()
                .addAnnotatedClass(KfeBalanceEntity.class)
                .addAnnotatedClass(KfeBalanceMovementEntity.class)
                .addAnnotatedClass(KfeTransactionEntity.class)
                .addAnnotatedClass(KfeUserStatementEntity.class)
                .addAnnotatedClass(KfeWalletEntity.class)
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "kfe_ledger_test")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                .setProperty("hibernate.connection.isolation", "2")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.create_namespaces", "true")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        txManager = new JpaTransactionManager(emf);
        JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);
        balances = factory.getRepository(KfeBalanceRepository.class);
        movements = factory.getRepository(KfeBalanceMovementRepository.class);
        transactions = factory.getRepository(KfeTransactionRepository.class);
        statements = factory.getRepository(KfeUserStatementRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.kerosene.kfe.messaging.adapters.out.websocket.TransactionEventPublisher> publisher =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(publisher.getIfAvailable()).thenReturn(null);
        statementService = new KfeStatementService(statements, new ObjectMapper(), entityManager, publisher, null);
        accounts = transactional(new JpaLedgerAccountAdapter(balances), LedgerAccountPort.class);
        postings = transactional(new JpaLedgerPostingAdapter(movements), LedgerPostingPort.class);
        ledger = new LedgerService(accounts, postings, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        settlement = new LedgerSettlementService(accounts, postings, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        transaction = new TransactionTemplate(txManager);
    }

    @AfterAll
    static void closeDatabase() {
        if (emf != null) {
            emf.close();
        }
    }

    @Test
    void reserveReplayAndRollbackKeepBalanceAndPostingAtomic() {
        UUID wallet = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        seed(wallet, 100L, 0L);
        LedgerService.Command command = new LedgerService.Command(operation, wallet, "BTC", 40L,
                "reserve", operation, null);

        transaction.executeWithoutResult(status -> ledger.reserve(command));
        transaction.executeWithoutResult(status -> ledger.reserve(command));

        KfeBalanceEntity afterReplay = balance(wallet);
        assertThat(afterReplay.getAvailableSats()).isEqualTo(60L);
        assertThat(afterReplay.getLockedSats()).isEqualTo(40L);
        assertThat(movementCount(operation)).isEqualTo(1L);

        UUID rollbackOperation = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            ledger.reserve(new LedgerService.Command(rollbackOperation, wallet, "BTC", 10L,
                    "rollback", rollbackOperation, null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(balance(wallet).getAvailableSats()).isEqualTo(60L);
        assertThat(movementCount(rollbackOperation)).isZero();
    }

    @Test
    void settlementDebitsAndCreditsBothWalletsInOneCommit() {
        UUID source = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        seed(source, 0L, 75L);
        seed(destination, 0L, 0L);

        transaction.executeWithoutResult(status -> settlement.settle(new LedgerSettlementService.Command(
                operation, source, destination, "BTC", 50L, "internal settlement", operation, null)));

        assertThat(balance(source).getLockedSats()).isEqualTo(25L);
        assertThat(balance(destination).getAvailableSats()).isEqualTo(50L);
        assertThat(movementCount(operation)).isEqualTo(2L);
    }

    @Test
    void concurrentReservationsCannotDoubleSpend() throws Exception {
        UUID wallet = UUID.randomUUID();
        seed(wallet, 100L, 0L);
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            UUID operation = UUID.randomUUID();
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    transaction.executeWithoutResult(status -> ledger.reserve(new LedgerService.Command(
                            operation, wallet, "BTC", 30L, "concurrent reserve", operation, null)));
                    return true;
                } catch (LedgerInvariantViolation failure) {
                    return false;
                }
            }));
        }
        start.countDown();
        long successes = 0L;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        pool.shutdownNow();

        assertThat(successes).isEqualTo(3L);
        assertThat(balance(wallet).getAvailableSats()).isEqualTo(10L);
        assertThat(balance(wallet).getLockedSats()).isEqualTo(90L);
    }

    @Test
    void statementUpsertKeepsOneCohesiveParticipantRow() {
        AtomicReference<UUID> transactionId = new AtomicReference<>();
        transaction.executeWithoutResult(status -> {
            KfeTransactionEntity tx = transaction();
            transactionId.set(tx.getId());
            transactions.save(tx);
            statementService.recordUserStatement(42L, null, tx, java.util.Map.of("status", "INTENT"));
            statementService.recordUserStatement(42L, null, tx, java.util.Map.of("status", "SETTLED"));
        });

        KfeUserStatementEntity row = transaction.execute(status ->
                statements.findByUserIdAndTransactionId(42L, transactionId.get()).orElseThrow());
        assertThat(row.getDisplayPayloadJson()).contains("SETTLED");
        assertThat(statements.findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
                42L, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))).hasSize(1);
    }

    private static void seed(UUID wallet, long available, long locked) {
        transaction.executeWithoutResult(status -> {
            KfeBalanceEntity balance = KfeBalanceEntity.empty(wallet, "BTC", "genesis");
            balance.setAvailableSats(available);
            balance.setLockedSats(locked);
            balance.setLastHash("genesis");
            balance.setBalanceSignature("genesis");
            balances.save(balance);
        });
    }

    private static KfeTransactionEntity transaction() {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(42L);
        tx.setIdempotencyKey("statement-" + tx.getId());
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);
        tx.setStatus(KfeTransactionStatus.INTENT);
        tx.setGrossAmountSats(1L);
        tx.setReceiverAmountSats(1L);
        tx.setTotalDebitSats(1L);
        return tx;
    }

    private static KfeBalanceEntity balance(UUID wallet) {
        return transaction.execute(status -> balances.findById(new KfeBalanceId(wallet, "BTC")).orElseThrow());
    }

    private static long movementCount(UUID operation) {
        return transaction.execute(status -> movements.findAll().stream()
                .filter(row -> operation.equals(row.getTransactionId())).count());
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(Object target, Class<T> contract) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
