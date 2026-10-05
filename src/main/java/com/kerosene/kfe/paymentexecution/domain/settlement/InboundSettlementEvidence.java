package com.kerosene.kfe.paymentexecution.domain.settlement;

/** Trusted-monitor evidence. Raw provider payloads deliberately stay outside this domain type. */
public record InboundSettlementEvidence(
        String providerReference,
        String networkReference,
        long observedAmountSats,
        int confirmations,
        int requiredOnchainConfirmations) {

    @Override
    public String toString() {
        return "InboundSettlementEvidence[observedAmountSats=" + observedAmountSats
                + ", confirmations=" + confirmations
                + ", requiredOnchainConfirmations=" + requiredOnchainConfirmations
                + ", references=REDACTED]";
    }
}
