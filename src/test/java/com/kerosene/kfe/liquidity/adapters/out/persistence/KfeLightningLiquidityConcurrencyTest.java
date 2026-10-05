package com.kerosene.kfe.liquidity.adapters.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLiquidityReservationStatus;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningClient;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeLightningLiquidityReservationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Simulates concurrent LN liquidity reservations against a fixed node capacity
 * with an exclusive pool lock (mirrors pg_advisory_xact_lock serialization).
 */
class KfeLightningLiquidityConcurrencyTest {

    @Test
    void concurrentReservationsNeverExceedNodeCapacity() throws Exception {
        long nodeCapacity = 1_000L;
        long perTx = 100L;
        int workers = 30; // 30 * 100 > 1000 → at most 10 succeed

        AtomicLong held = new AtomicLong();
        Map<UUID, KfeLightningLiquidityReservationEntity> store = new ConcurrentHashMap<>();
        Object poolLock = new Object();

        @SuppressWarnings("unchecked")
        ObjectProvider<LightningClient> clientProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LightningPaymentGateway> paymentProvider = mock(ObjectProvider.class);
        KfeLightningLiquidityReservationRepository repository =
                mock(KfeLightningLiquidityReservationRepository.class);
        LightningClient client = mock(LightningClient.class);
        LightningPaymentGateway gateway = mock(LightningPaymentGateway.class);

        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(paymentProvider.getIfAvailable()).thenReturn(gateway);
        when(gateway.isLive()).thenReturn(true);
        when(client.getLocalBalance()).thenReturn(nodeCapacity);
        doAnswer(invocation -> {
            synchronized (poolLock) {
                // hold lock during reservation critical section via mock call order
            }
            return null;
        }).when(repository).acquirePoolLock(anyLong());
        when(repository.sumAmountByStatus(KfeLiquidityReservationStatus.HELD))
                .thenAnswer(invocation -> held.get());
        when(repository.findByTransactionId(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.ofNullable(store.get(id));
        });
        when(repository.saveAndFlush(any(KfeLightningLiquidityReservationEntity.class)))
                .thenAnswer(invocation -> {
                    KfeLightningLiquidityReservationEntity entity = invocation.getArgument(0);
                    store.put(entity.getTransactionId(), entity);
                    held.addAndGet(entity.getAmountSats());
                    return entity;
                });

        KfeLightningLiquidityService service = new KfeLightningLiquidityService(
                clientProvider, paymentProvider, repository, null, 0L, 0L, 10);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                UUID txId = UUID.randomUUID();
                // Serialize like advisory xact lock + free capacity check inside service.
                synchronized (poolLock) {
                    try {
                        service.reserveForTransaction(txId, perTx);
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
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(successes.get()).isEqualTo(10);
        assertThat(failures.get()).isEqualTo(20);
        assertThat(held.get()).isEqualTo(1_000L);
        assertThat(store).hasSize(10);
    }
}
