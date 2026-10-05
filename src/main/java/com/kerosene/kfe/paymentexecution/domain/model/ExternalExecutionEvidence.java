package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Presence-only evidence; raw prepared operations and provider identifiers stay outside the domain.
 * @param preparedCiphertextPresent whether protected prepared operation data exists
 * @param preparedHashPresent whether a digest of the prepared operation exists
 * @param executionReferencePresent whether the internal execution reference exists
 * @param outboxProviderReferencePresent whether the outbox stores a provider reference
 * @param transactionProviderReferencePresent whether the execution stores a provider reference
 * @param blockchainTxidPresent whether a chain transaction identifier exists
 * @param paymentHashPresent whether a Lightning payment hash exists
 */
public record ExternalExecutionEvidence(
        boolean preparedCiphertextPresent,
        boolean preparedHashPresent,
        boolean executionReferencePresent,
        boolean outboxProviderReferencePresent,
        boolean transactionProviderReferencePresent,
        boolean blockchainTxidPresent,
        boolean paymentHashPresent) {
}
