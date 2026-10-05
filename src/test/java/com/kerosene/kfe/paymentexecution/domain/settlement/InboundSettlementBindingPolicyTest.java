package com.kerosene.kfe.paymentexecution.domain.settlement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InboundSettlementBindingPolicyTest {

    private static final UUID TX_ID = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final String TXID = "a".repeat(64);

    private final InboundSettlementBindingPolicy policy = new InboundSettlementBindingPolicy();

    @Test
    void rejectsProofThatTargetsAnotherOutboxTransaction() {
        InboundSettlementBinding binding = binding(UUID.randomUUID(), "EXECUTING", false, 1000L, 1000L);

        InboundSettlementDecision decision = policy.decide(binding, evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.REJECT, decision.outcome());
        assertEquals("INBOUND_TRANSACTION_BINDING_MISMATCH", decision.code());
    }

    @Test
    void rejectsWrongOperationAndDirectionBeforeAnyEffect() {
        InboundSettlementBinding binding = new InboundSettlementBinding(
                TX_ID, TX_ID, "LIGHTNING_INBOUND", "UNKNOWN", "ONCHAIN", "OUTBOUND",
                "EXECUTING", 42L, WALLET_ID, 42L, 1000L, 1000L,
                null, null, null, false);

        InboundSettlementDecision decision = policy.decide(binding, evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.REJECT, decision.outcome());
        assertEquals("INBOUND_OPERATION_MISMATCH", decision.code());
    }

    @Test
    void allowsOnlyAConclusiveOnchainObservation() {
        InboundSettlementDecision decision = policy.decide(
                binding(TX_ID, "REQUIRES_RECONCILIATION", false, 1000L, 1000L),
                evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.SETTLE, decision.outcome());
    }

    @Test
    void rejectsOnchainObservationBelowConfiguredFinality() {
        InboundSettlementDecision decision = policy.decide(
                binding(TX_ID, "REQUIRES_RECONCILIATION", false, 1000L, 1000L),
                evidence(1000L, 2));

        assertEquals(InboundSettlementDecision.Outcome.REJECT, decision.outcome());
        assertEquals("INBOUND_FINALITY_NOT_REACHED", decision.code());
    }

    @Test
    void conflictIsReconciliationAndNeverSuccess() {
        InboundSettlementDecision decision = policy.decide(
                binding(TX_ID, "REQUIRES_RECONCILIATION", true, 1000L, 1000L),
                evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.RECONCILE, decision.outcome());
        assertEquals("INBOUND_PROVIDER_REFERENCE_CONFLICT", decision.code());
    }

    @Test
    void rejectsReceiverAmountThatWouldCreditMoreThanObserved() {
        InboundSettlementDecision decision = policy.decide(
                binding(TX_ID, "EXECUTING", false, 1000L, 1500L),
                evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.REJECT, decision.outcome());
        assertEquals("INBOUND_CREDIT_AMOUNT_INVALID", decision.code());
    }

    @Test
    void rejectsPersistedReferenceMismatchBeforeNonTerminalSettlement() {
        InboundSettlementBinding binding = new InboundSettlementBinding(
                TX_ID, TX_ID, "ONCHAIN_INBOUND", "UNKNOWN", "ONCHAIN", "INBOUND",
                "EXECUTING", 42L, WALLET_ID, 42L, 1000L, 1000L,
                "outbox-old", "transaction-old", "different-network", false);

        InboundSettlementDecision decision = policy.decide(binding, evidence(1000L, 3));

        assertEquals(InboundSettlementDecision.Outcome.REJECT, decision.outcome());
        assertEquals("INBOUND_REFERENCE_MISMATCH", decision.code());
    }

    @Test
    void settledWithSameReferencesIsIdempotentOnlyForKnownOutbox() {
        InboundSettlementBinding binding = new InboundSettlementBinding(
                TX_ID, TX_ID, "ONCHAIN_INBOUND", "UNKNOWN", "ONCHAIN", "INBOUND",
                "SETTLED", 42L, WALLET_ID, 42L, 1000L, 1000L,
                null, "provider-ref", TXID, false);

        InboundSettlementDecision decision = policy.decide(
                binding,
                new InboundSettlementEvidence("provider-ref", TXID, 1000L, 3, 3));

        assertEquals(InboundSettlementDecision.Outcome.IDEMPOTENT, decision.outcome());
        assertEquals("INBOUND_ALREADY_SETTLED", decision.code());
    }

    private static InboundSettlementBinding binding(
            UUID proofTransactionId,
            String status,
            boolean providerConflict,
            long gross,
            long receiver) {
        return new InboundSettlementBinding(
                proofTransactionId, TX_ID, "ONCHAIN_INBOUND", "UNKNOWN", "ONCHAIN", "INBOUND",
                status, 42L, WALLET_ID, 42L, gross, receiver,
                null, null, null, providerConflict);
    }

    private static InboundSettlementEvidence evidence(long amount, int confirmations) {
        return new InboundSettlementEvidence("provider-ref", TXID, amount, confirmations, 3);
    }
}
