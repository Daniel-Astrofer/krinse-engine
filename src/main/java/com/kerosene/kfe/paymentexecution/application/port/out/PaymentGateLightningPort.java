package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementJammingCheck;

/** Read-only Lightning health, outbound liquidity, circuit-breaker, and jamming probes. */
public interface PaymentGateLightningPort {
    /** Reports whether the configured Lightning gateway is live for outbound execution. */
    /** @return true only when the gateway passes its live-health check */
    boolean isLive();
    /** Reads unreserved outbound channel capacity in integer satoshis. */
    /** @return free outbound capacity, or a negative sentinel when unavailable */
    long freeOutboundCapacitySats();
    /** Checks whether outbound capacity can cover the complete debit. */
    /** @param totalDebitSats principal plus applicable fees @return true when current capacity can cover the debit */
    boolean canCoverOutbound(long totalDebitSats);
    /** Reports whether the outbound circuit breaker is open. */
    /** @return true when policy currently blocks new outbound work */
    boolean circuitBreakerOpen();
    /** Evaluates current jamming-risk evidence without returning provider internals. */
    /** @return allowed, hard-block, and diagnostic decision for the settlement gate */
    SettlementJammingCheck evaluateJamming();
}
