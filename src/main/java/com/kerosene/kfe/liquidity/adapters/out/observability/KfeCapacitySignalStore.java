package com.kerosene.kfe.liquidity.adapters.out.observability;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process sliding window of capacity stress signals for dead-man channel control.
 * Not a durable SoT — durable intents live in {@code channel_capacity_jobs}.
 */
@Component
public class KfeCapacitySignalStore {

    private final LongAdder liquidityRejects = new LongAdder();
    private final LongAdder gateFails = new LongAdder();
    private final AtomicLong windowStartedAtMs = new AtomicLong(System.currentTimeMillis());

    public void recordLiquidityReject() {
        maybeRotate();
        liquidityRejects.increment();
    }

    public void recordGateFail() {
        maybeRotate();
        gateFails.increment();
    }

    public long liquidityRejectsInWindow() {
        maybeRotate();
        return liquidityRejects.sum();
    }

    public long gateFailsInWindow() {
        maybeRotate();
        return gateFails.sum();
    }

    public CapacitySignals snapshot(long windowMs) {
        maybeRotate(windowMs);
        return new CapacitySignals(
                liquidityRejects.sum(),
                gateFails.sum(),
                System.currentTimeMillis() - windowStartedAtMs.get());
    }

    private void maybeRotate() {
        maybeRotate(15 * 60_000L);
    }

    private void maybeRotate(long windowMs) {
        long now = System.currentTimeMillis();
        long started = windowStartedAtMs.get();
        if (now - started < Math.max(60_000L, windowMs)) {
            return;
        }
        if (windowStartedAtMs.compareAndSet(started, now)) {
            liquidityRejects.reset();
            gateFails.reset();
        }
    }

    public record CapacitySignals(long liquidityRejects, long gateFails, long windowAgeMs) {
    }
}
