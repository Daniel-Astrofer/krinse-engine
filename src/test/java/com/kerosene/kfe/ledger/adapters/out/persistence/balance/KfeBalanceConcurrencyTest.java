package com.kerosene.kfe.ledger.adapters.out.persistence.balance;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial unit stress: many concurrent reserves against a single balance entity
 * (simulates serializable FOR UPDATE section with synchronized domain object).
 */
class KfeBalanceConcurrencyTest {

    @Test
    void atMostOneHundredConcurrentReservesOfOneUnitAgainstHundredAvailable() throws Exception {
        KfeBalanceEntity balance = emptyBalance(100L);
        Object lock = new Object();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        int workers = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                synchronized (lock) {
                    try {
                        balance.reserve(1L);
                        successes.incrementAndGet();
                    } catch (IllegalStateException ex) {
                        failures.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(successes.get()).isEqualTo(100);
        assertThat(failures.get()).isEqualTo(0);
        assertThat(balance.getAvailableSats()).isEqualTo(0L);
        assertThat(balance.getLockedSats()).isEqualTo(100L);
    }

    @Test
    void concurrentOversubscriptionRejectsExtraReserves() throws Exception {
        KfeBalanceEntity balance = emptyBalance(10L);
        Object lock = new Object();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        int workers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                synchronized (lock) {
                    try {
                        balance.reserve(1L);
                        successes.incrementAndGet();
                    } catch (IllegalStateException ex) {
                        failures.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(successes.get()).isEqualTo(10);
        assertThat(failures.get()).isEqualTo(40);
        assertThat(balance.getAvailableSats()).isEqualTo(0L);
        assertThat(balance.getLockedSats()).isEqualTo(10L);
    }

    private static KfeBalanceEntity emptyBalance(long available) {
        KfeBalanceEntity entity = new KfeBalanceEntity();
        entity.setId(new KfeBalanceId(UUID.randomUUID(), "BTC"));
        entity.setAvailableSats(available);
        entity.setLockedSats(0L);
        entity.setPendingSats(0L);
        entity.setAutoHoldSats(0L);
        entity.setObservedSats(0L);
        entity.setNonce(0L);
        entity.setLastHash("h");
        entity.setBalanceSignature("h");
        return entity;
    }
}
