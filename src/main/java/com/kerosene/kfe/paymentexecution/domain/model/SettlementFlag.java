package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Binary settlement flags (doc: Motor KFE — liquidação binária).
 * Each flag is 0 (fail) or 1 (pass). Final result is logical AND of all flags.
 */
public enum SettlementFlag {
    /** Idempotency reservation exists and is bound to the submitted request. */
    V_IDEMPOTENCIA,
    /** Required source-wallet row lock was acquired before balance-sensitive effects. */
    V_LOCK_BANDO,
    /** Amount, fee, total debit, and integer-satoshi arithmetic satisfy atomicity bounds. */
    V_ATOMICIDADE,
    /** Available source balance covers the complete debit. */
    V_SALDO_DISP,
    /** Production execution uses real balances and forbids simulated funds. */
    V_DINHEIRO_REAL,
    /** Outbound rail has enough currently available liquidity for this debit. */
    V_LIQUIDEZ,
    /** Peer-to-peer policy gate for the settlement route. */
    V_P2P,
    /** Proposal hash has the required healthy MPC quorum acknowledgements. */
    V_ASSINATURA_MPC,
    /** Ledger-derived assets cover liabilities and the required reserve buffer. */
    V_RESERVA_MAT,
    /** Lightning jamming/risk evidence permits outbound execution. */
    V_NO_JAMMING,
    /** Lightning outbound circuit breaker is closed and gateway is live. */
    V_CIRCUIT_BREAKER
}
