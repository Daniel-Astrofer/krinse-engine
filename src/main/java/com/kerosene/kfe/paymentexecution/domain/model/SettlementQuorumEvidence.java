package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Aggregate quorum counts returned by the consensus evidence port.
 * @param acceptedNodes members whose acknowledgements matched the proposal
 * @param totalHealthyNodes healthy members considered for the decision
 */
public record SettlementQuorumEvidence(int acceptedNodes, int totalHealthyNodes) {}
