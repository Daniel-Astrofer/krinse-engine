package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;

/** Internal submit stage, not an independently authorized payment or retry endpoint. */
public interface PaymentSettlementGateUseCase {
    /** Evaluates all configured settlement flags and returns evidence only after every required gate passes. */
    /** @param command immutable settlement facts from the authorized submission @return quorum evidence required by later reservation/routing @throws SettlementGateRejectedException when any required gate fails */
    PaymentSettlementGateResult requirePass(PaymentSettlementGateCommand command);
}
