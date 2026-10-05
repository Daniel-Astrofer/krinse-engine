package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Acquire and retain the BTC balance row lock in the owning submit transaction. */
public interface PaymentGateBalancePort {
    /** Locks and reads the available BTC balance row until the submit transaction completes. */
    /** @param walletId owned source wallet identity @return available balance in integer satoshis observed under lock */
    long lockAvailable(UUID walletId);
}
