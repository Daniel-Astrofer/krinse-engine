package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;

/**
 * Result of this submit's preparation, not a serializable approval or retry ticket.
 * @param transition confirmed lifecycle event after preparation/gate success
 * @param destinationWallet validated destination snapshot, if the flow has one
 */
public record PreparedPaymentSubmission(PaymentExecutionStatusChanged transition, PaymentWalletSnapshot destinationWallet) {}
