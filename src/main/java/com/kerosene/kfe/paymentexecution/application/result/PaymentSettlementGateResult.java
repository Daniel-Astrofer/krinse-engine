package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Quorum evidence returned after the settlement gate required all configured checks to pass.
 * @param quorumAckCount accepted acknowledgements for the proposal
 * @param quorumHealthyNodes healthy quorum members observed during the check
 */
public record PaymentSettlementGateResult(int quorumAckCount, int quorumHealthyNodes) {
}
