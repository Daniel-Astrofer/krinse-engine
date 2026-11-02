package com.kerosene.kfe.messaging.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.KfeFinancialNotificationOutboxRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KEROSENE_NOTIFICATION_TEST_JDBC",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/kerosene_notification_test")
class FinancialNotificationOutboxPostgresTest {
    static EntityManagerFactory emf;
    static EntityManager em;
    static TransactionTemplate transaction;
    static KfeFinancialNotificationOutboxService outbox;

    @BeforeAll static void setup() {
        emf = new Configuration()
                .addAnnotatedClass(KfeTransactionEntity.class)
                .addAnnotatedClass(KfeFinancialNotificationOutboxEntity.class)
                .setProperty("hibernate.connection.url", System.getenv("KEROSENE_NOTIFICATION_TEST_JDBC"))
                .setProperty("hibernate.connection.username", "notification_test")
                .setProperty("hibernate.connection.password", System.getenv("KEROSENE_NOTIFICATION_TEST_PASSWORD"))
                .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                .setProperty("hibernate.connection.isolation", "2")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.create_namespaces", "true")
                .buildSessionFactory();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        transaction = new TransactionTemplate(new JpaTransactionManager(emf));
        outbox = new KfeFinancialNotificationOutboxService(
                new JpaRepositoryFactory(em).getRepository(KfeFinancialNotificationOutboxRepository.class));
        transaction.executeWithoutResult(status -> em.createNativeQuery("""
                ALTER TABLE financial.transactions_master ADD COLUMN notification_sequence BIGINT NOT NULL DEFAULT 0
                """).executeUpdate());
    }

    @AfterAll static void close() { if (emf != null) emf.close(); }

    private UUID seed() {
        return transaction.execute(status -> {
            var payment = new KfeTransactionEntity();
            payment.setUserId(42L);
            payment.setIdempotencyKey("notification-test-" + payment.getId());
            payment.setRail(KfeRail.ONCHAIN);
            payment.setDirection(KfeDirection.OUTBOUND);
            payment.setStatus(KfeTransactionStatus.EXECUTING);
            payment.setGrossAmountSats(1500);
            payment.setReceiverAmountSats(1500);
            payment.setTotalDebitSats(1500);
            em.persist(payment);
            return payment.getId();
        });
    }

    private Map<String, Object> payload(UUID wallet) {
        return Map.of("walletId", wallet, "amountSats", 1500L, "rail", "ONCHAIN");
    }

    @Test void enqueueAndFinancialStateRollbackTogether() {
        UUID payment = seed(), id = UUID.randomUUID(), wallet = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            em.find(KfeTransactionEntity.class, payment).setStatus(KfeTransactionStatus.SETTLED);
            outbox.enqueue(id, 42L, payment, "PAYMENT_CONFIRMED", payload(wallet));
            throw new IllegalStateException("Injected failure before financial commit.");
        }));
        transaction.executeWithoutResult(status -> {
            assertEquals(KfeTransactionStatus.EXECUTING, em.find(KfeTransactionEntity.class, payment).getStatus());
            assertEquals(0L, ((Number) em.createNativeQuery("SELECT count(*) FROM financial.kfe_financial_notification_outbox WHERE event_id = ?")
                    .setParameter(1, id).getSingleResult()).longValue());
            assertEquals(0L, ((Number) em.createNativeQuery("SELECT notification_sequence FROM financial.transactions_master WHERE id = ?")
                    .setParameter(1, payment).getSingleResult()).longValue());
        });
    }

    @Test void concurrentReplayKeepsOneEventWithoutAbortingTheFinancialTransaction() throws Exception {
        UUID payment = seed(), id = UUID.randomUUID(), wallet = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return transaction.execute(status ->
                    outbox.enqueue(id, 42L, payment, "PAYMENT_CONFIRMED", payload(wallet)).getId()); });
            var second = pool.submit(() -> { start.await(); return transaction.execute(status ->
                    outbox.enqueue(id, 42L, payment, "PAYMENT_CONFIRMED", payload(wallet)).getId()); });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        transaction.executeWithoutResult(status -> {
            assertEquals(1L, ((Number) em.createNativeQuery("SELECT count(*) FROM financial.kfe_financial_notification_outbox WHERE event_id = ?")
                    .setParameter(1, id).getSingleResult()).longValue());
        });
    }

    @Test void distinctTransitionsHaveIncreasingSequencesAndReplayDoesNotAdvanceThem() {
        UUID payment = seed(), id = UUID.randomUUID(), wallet = UUID.randomUUID();
        long first = transaction.execute(status -> outbox.enqueue(id, 42L, payment, "PAYMENT_PROCESSING", payload(wallet)).getEventSequence());
        long replay = transaction.execute(status -> outbox.enqueue(id, 42L, payment, "PAYMENT_PROCESSING", payload(wallet)).getEventSequence());
        long terminal = transaction.execute(status -> outbox.enqueue(UUID.randomUUID(), 42L, payment, "PAYMENT_CONFIRMED", payload(wallet)).getEventSequence());
        assertEquals(first, replay);
        assertTrue(terminal > first);
    }
}
