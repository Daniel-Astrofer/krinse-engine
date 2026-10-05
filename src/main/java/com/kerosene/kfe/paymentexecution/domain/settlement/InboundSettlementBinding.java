package com.kerosene.kfe.paymentexecution.domain.settlement;

import java.util.UUID;

/** Persisted identities and state that an inbound observation must match. */
public record InboundSettlementBinding(
        UUID proofTransactionId,
        UUID outboxTransactionId,
        String outboxOperation,
        String outboxStatus,
        String rail,
        String direction,
        String transactionStatus,
        long transactionUserId,
        UUID destinationWalletId,
        Long destinationWalletUserId,
        long grossAmountSats,
        long receiverAmountSats,
        String persistedOutboxProviderReference,
        String persistedProviderReference,
        String persistedNetworkReference,
        boolean providerReferenceOwnedByOtherSettledTransaction) {

    @Override
    public String toString() {
        return "InboundSettlementBinding[proofTransactionId=" + proofTransactionId
                + ", outboxTransactionId=" + outboxTransactionId
                + ", outboxStatus=" + outboxStatus
                + ", rail=" + rail
                + ", direction=" + direction
                + ", transactionStatus=" + transactionStatus
                + ", references=REDACTED]";
    }
}
